use yap_core::framing::{FrameError, FrameLimits};

#[test]
fn long_utf8_and_binary_payloads_survive_small_bluetooth_writes() {
    for payload in [
        "Hello 🌍 — a message longer than twenty bytes"
            .as_bytes()
            .to_vec(),
        (0..=255).collect::<Vec<u8>>(),
        vec![],
        vec![7; 1024],
    ] {
        for mtu in [1, 2, 3, 4, 5, 20, 64, 512] {
            let limits = FrameLimits::new(1024, mtu).unwrap();
            let fragments = limits.fragment(&payload).unwrap();
            let mut decoder = limits.decoder();
            for (index, fragment) in fragments.iter().enumerate() {
                assert!(fragment.len() <= mtu);
                let result = decoder.push(fragment).unwrap();
                if index + 1 == fragments.len() {
                    assert_eq!(result.as_ref(), Some(&payload));
                } else {
                    assert_eq!(result, None);
                }
            }
        }
    }
}

#[test]
fn partial_record_is_discarded_when_a_connection_ends() {
    let limits = FrameLimits::new(100, 20).unwrap();
    let fragments = limits.fragment(&[42; 40]).unwrap();
    let mut decoder = limits.decoder();
    assert_eq!(decoder.push(&fragments[0]), Ok(None));
    decoder.reset();
    let fresh = limits.fragment(b"new session").unwrap();
    assert_eq!(decoder.push(&fresh[0]), Ok(Some(b"new session".to_vec())));
}

#[test]
fn rejects_hostile_lengths_and_oversized_writes_then_clears_state() {
    let limits = FrameLimits::new(32, 20).unwrap();
    let mut decoder = limits.decoder();
    assert_eq!(
        decoder.push(&u32::MAX.to_be_bytes()),
        Err(FrameError::PayloadTooLarge)
    );
    assert_eq!(decoder.push(&[0; 21]), Err(FrameError::FragmentTooLarge));
    assert_eq!(limits.fragment(&[0; 33]), Err(FrameError::PayloadTooLarge));
    // A caller may reuse the decoder only after replacing the failed session.
    let fresh = limits.fragment(b"fresh").unwrap();
    assert_eq!(decoder.push(&fresh[0]), Ok(Some(b"fresh".to_vec())));
}

#[test]
fn rejects_trailing_bytes_instead_of_losing_the_next_record() {
    let limits = FrameLimits::new(100, 20).unwrap();
    let mut decoder = limits.decoder();
    assert_eq!(
        decoder.push(&[0, 0, 0, 1, 42, 43]),
        Err(FrameError::TrailingBytes)
    );
}

#[test]
fn supports_consecutive_records_and_empty_input_without_false_completion() {
    let limits = FrameLimits::new(100, 20).unwrap();
    let mut decoder = limits.decoder();
    assert_eq!(decoder.push(&[]), Ok(None));
    for payload in [b"first".as_slice(), b"second", b""] {
        for fragment in limits.fragment(payload).unwrap() {
            assert_eq!(decoder.push(&fragment), Ok(Some(payload.to_vec())));
        }
    }
    assert_eq!(decoder.push(&[]), Ok(None));
}

#[test]
fn accepts_different_fragment_boundaries_from_the_sender() {
    let limits = FrameLimits::new(100, 20).unwrap();
    let payload = b"boundaries can split the header or any payload byte";
    let record: Vec<u8> = limits
        .fragment(payload)
        .unwrap()
        .into_iter()
        .flatten()
        .collect();
    for split in 1..=20 {
        let mut decoder = limits.decoder();
        let mut completed = None;
        for fragment in record.chunks(split) {
            if let Some(value) = decoder.push(fragment).unwrap() {
                assert!(completed.replace(value).is_none());
            }
        }
        assert_eq!(completed.as_deref(), Some(payload.as_slice()));
    }
}

#[test]
fn rejects_zero_limits() {
    assert!(matches!(
        FrameLimits::new(0, 20),
        Err(FrameError::InvalidLimits)
    ));
    assert!(matches!(
        FrameLimits::new(100, 0),
        Err(FrameError::InvalidLimits)
    ));
}
