use yap_noise_pairing::*;

fn qr(seed: u8) -> Qr {
    let mut identity = [seed; 33];
    identity[0] = if seed % 2 == 0 { 0x02 } else { 0x03 };
    Qr {
        identity,
        nonce: [seed.wrapping_add(1); 16],
        bundle_hash: [seed.wrapping_add(2); 32],
        token: [seed.wrapping_add(3); 16],
        pairing_key: [seed.wrapping_add(4); 32],
    }
}

fn pair() -> (Qr, Qr, [u8; 32], Vec<u8>) {
    let (a, b) = (qr(10), qr(21));
    let psk = derive_psk(&a, &b);
    let pro = original_prologue(&a, &b);
    (a, b, psk, pro)
}

#[test]
fn psk_is_order_independent_and_32_bytes() {
    let (a, b) = (qr(10), qr(21));
    assert_eq!(derive_psk(&a, &b), derive_psk(&b, &a));
    // A different pairing key or nonce on either side gives a different psk.
    let mut a2 = a.clone();
    a2.pairing_key[0] ^= 1;
    assert_ne!(derive_psk(&a, &b), derive_psk(&a2, &b));
    let mut b2 = b.clone();
    b2.nonce[0] ^= 1;
    assert_ne!(derive_psk(&a, &b), derive_psk(&a, &b2));
    assert_eq!(qr(10).payload().len(), 133);
}

/// Prints the fixed vector recorded in the README (psk, prologue hash, handshake hash).
#[test]
fn full_original_exchange() {
    let (a, b, psk, pro) = pair();
    let (small, _) = ordered(&a, &b);
    assert!(small.identity <= a.identity.max(b.identity));
    let mut d = run(&psk, &pro, &psk, &pro).expect("handshake");
    // Both messages exactly 48 bytes, empty payload.
    assert_eq!(d.msg1_len, 48);
    assert_eq!(d.msg2_len, 48);
    // Same 32-byte handshake hash on both sides (the channel binding of section 3).
    assert_eq!(d.hash_initiator.len(), 32);
    assert_eq!(d.hash_initiator, d.hash_responder);
    // Transport works both ways and in order.
    let mut buf = vec![0u8; 70_000];
    let mut out = vec![0u8; 70_000];
    for (n, msg) in [b"bundle".as_slice(), b"pair secret", b""].iter().enumerate() {
        let w = d.initiator.write_message(msg, &mut buf).unwrap();
        let r = d.responder.read_message(&buf[..w], &mut out).unwrap();
        assert_eq!(&out[..r], *msg, "initiator -> responder {n}");
        let w = d.responder.write_message(msg, &mut buf).unwrap();
        let r = d.initiator.read_message(&buf[..w], &mut out).unwrap();
        assert_eq!(&out[..r], *msg, "responder -> initiator {n}");
    }
    // The psk is deterministic and is the vector recorded in the README. The handshake hash
    // is not (fresh ephemeral keys each run), so it is not recorded.
    assert_eq!(hex(&psk), PSK_VECTOR);
}

#[test]
fn full_resume_exchange() {
    let (a, b, psk, pro) = pair();
    let first = run(&psk, &pro, &psk, &pro).unwrap();
    let hash = first.hash_initiator.clone();
    let rp = resume_prologue(&a, &b, &hash);
    let mut second = run(&psk, &rp, &psk, &rp).expect("resume handshake");
    assert_eq!((second.msg1_len, second.msg2_len), (48, 48));
    // A resume is a fresh handshake: new ephemeral keys, so a new handshake hash.
    assert_eq!(second.hash_initiator, second.hash_responder);
    assert_ne!(second.hash_initiator, hash);
    let mut buf = vec![0u8; 1000];
    let mut out = vec![0u8; 1000];
    let w = second.initiator.write_message(b"kind 03", &mut buf).unwrap();
    let r = second.responder.read_message(&buf[..w], &mut out).unwrap();
    assert_eq!(&out[..r], b"kind 03");
}

/// Resume prologue must be tied to the original hash and the identity order.
#[test]
fn resume_with_wrong_original_hash_or_original_prologue_fails() {
    let (a, b, psk, pro) = pair();
    let first = run(&psk, &pro, &psk, &pro).unwrap();
    let good = resume_prologue(&a, &b, &first.hash_initiator);
    let mut bad_hash = first.hash_initiator.clone();
    bad_hash[0] ^= 1;
    assert!(run(&psk, &good, &psk, &resume_prologue(&a, &b, &bad_hash)).is_err());
    // One side resumes while the other still runs the original prologue.
    assert!(run(&psk, &good, &psk, &pro).is_err());
    // Identity order swapped inside the prologue.
    let mut swapped = b"Yap pairing v2 resume".to_vec();
    swapped.extend_from_slice(&first.hash_initiator);
    let (s, l) = ordered(&a, &b);
    swapped.extend_from_slice(&l.identity);
    swapped.extend_from_slice(&s.identity);
    assert!(run(&psk, &good, &psk, &swapped).is_err());
}

#[test]
fn wrong_psk_fails_at_the_first_message() {
    let (_, _, psk, pro) = pair();
    let mut wrong = psk;
    wrong[31] ^= 1;
    assert!(run(&psk, &pro, &wrong, &pro).is_err(), "responder with wrong psk");
    assert!(run(&wrong, &pro, &psk, &pro).is_err(), "initiator with wrong psk");
    // Check where it fails: the responder cannot even read message 1.
    let mut i = build(true, &psk, &pro);
    let mut r = build(false, &wrong, &pro);
    let mut m = [0u8; 65_535];
    let n = i.write_message(&[], &mut m).unwrap();
    let mut out = [0u8; 65_535];
    assert!(r.read_message(&m[..n], &mut out).is_err());
}

#[test]
fn wrong_prologue_fails_at_the_first_message() {
    let (a, b, psk, pro) = pair();
    // Prologue that differs by one byte, by order, by one QR, and by a missing QR.
    let mut one_byte = pro.clone();
    *one_byte.last_mut().unwrap() ^= 1;
    let (s, l) = ordered(&a, &b);
    let mut reversed = b"Yap pairing v2".to_vec();
    reversed.extend(l.payload());
    reversed.extend(s.payload());
    let mut only_one = b"Yap pairing v2".to_vec();
    only_one.extend(s.payload());
    for (name, bad) in [("one byte", one_byte), ("reversed", reversed), ("one QR", only_one), ("empty", vec![])] {
        assert!(run(&psk, &pro, &psk, &bad).is_err(), "responder prologue: {name}");
        assert!(run(&psk, &bad, &psk, &pro).is_err(), "initiator prologue: {name}");
    }
    // A third phone with a different QR cannot complete either.
    let c = qr(33);
    let pro_c = original_prologue(&a, &c);
    let psk_c = derive_psk(&a, &c);
    assert!(run(&psk, &pro, &psk_c, &pro_c).is_err());
}

#[test]
fn message_one_is_refused_if_changed_truncated_or_extended() {
    let (_, _, psk, pro) = pair();
    let mut i = build(true, &psk, &pro);
    let mut m1 = [0u8; 65_535];
    let n = i.write_message(&[], &mut m1).unwrap();
    assert_eq!(n, 48);
    let good = m1[..n].to_vec();

    let try_read = |msg: &[u8]| {
        let mut r = build(false, &psk, &pro);
        let mut out = [0u8; 65_535];
        r.read_message(msg, &mut out)
    };
    assert!(try_read(&good).is_ok());

    // Every length shorter than 48 and a few longer: refused by snow too.
    for len in [0usize, 1, 31, 32, 33, 47] {
        assert!(try_read(&good[..len]).is_err(), "truncated to {len}");
    }
    let mut longer = good.clone();
    longer.push(0);
    assert!(try_read(&longer).is_err(), "49 bytes (one extra zero)");
    longer.extend_from_slice(&[0u8; 100]);
    assert!(try_read(&longer).is_err(), "149 bytes");

    // Flip every bit of every byte: none may be accepted.
    for pos in 0..48 {
        for bit in 0..8 {
            let mut bad = good.clone();
            bad[pos] ^= 1 << bit;
            assert!(try_read(&bad).is_err(), "bit {bit} of byte {pos} accepted");
        }
    }
}

#[test]
fn message_two_is_refused_if_changed_or_truncated() {
    let (_, _, psk, pro) = pair();
    let mut i = build(true, &psk, &pro);
    let mut r = build(false, &psk, &pro);
    let mut m = [0u8; 65_535];
    let mut out = [0u8; 65_535];
    let n = i.write_message(&[], &mut m).unwrap();
    r.read_message(&m[..n], &mut out).unwrap();
    let n2 = r.write_message(&[], &mut m).unwrap();
    assert_eq!(n2, 48);
    let good = m[..n2].to_vec();
    let rebuild = || {
        // Replays message 1 to get a fresh initiator waiting for message 2.
        let mut i = build(true, &psk, &pro);
        let mut tmp = [0u8; 65_535];
        i.write_message(&[], &mut tmp).unwrap();
        i
    };
    // The ephemeral keys differ per run, so this checks refusal of damaged input only.
    for len in [0usize, 16, 32, 47] {
        assert!(rebuild().read_message(&good[..len], &mut out).is_err(), "truncated to {len}");
    }
    for pos in 0..48 {
        let mut bad = good.clone();
        bad[pos] ^= 0x80;
        assert!(rebuild().read_message(&bad, &mut out).is_err(), "byte {pos}");
    }
}

/// `snow` accepts a non-empty payload on message 1 (49+ bytes) from a party that holds the
/// psk. The proposal's "exactly 48 bytes, any other length is dropped" is therefore an
/// app-level rule that must run before `read_message`; `snow` will not enforce it.
#[test]
fn snow_does_not_enforce_the_48_byte_rule_itself() {
    let (_, _, psk, pro) = pair();
    let mut i = build(true, &psk, &pro);
    let mut m = [0u8; 65_535];
    let n = i.write_message(b"x", &mut m).unwrap();
    assert_eq!(n, 49);
    assert!(!length_gate(&m[..n]));
    let mut r = build(false, &psk, &pro);
    let mut out = [0u8; 65_535];
    let got = r.read_message(&m[..n], &mut out).expect("snow accepts it");
    assert_eq!(&out[..got], b"x");
    // The gate itself.
    assert!(length_gate(&[0u8; 48]));
    for len in [0usize, 47, 49, 65_535] {
        assert!(!length_gate(&vec![0u8; len]));
    }
}

#[test]
fn replayed_or_out_of_order_handshake_and_transport_messages_fail() {
    let (_, _, psk, pro) = pair();
    // Responder gets message 1 twice: the second read on the same state is refused.
    let mut i = build(true, &psk, &pro);
    let mut r = build(false, &psk, &pro);
    let mut m = [0u8; 65_535];
    let mut out = [0u8; 65_535];
    let n = i.write_message(&[], &mut m).unwrap();
    r.read_message(&m[..n], &mut out).unwrap();
    assert!(r.read_message(&m[..n], &mut out).is_err(), "second read of message 1");
    // The initiator has written message 1 and is now waiting for message 2.
    assert!(!i.is_my_turn());

    // Transport: reordered or replayed messages are refused (snow keeps strict counters).
    let mut d = run(&psk, &pro, &psk, &pro).unwrap();
    let mut a = vec![0u8; 100];
    let mut b = vec![0u8; 100];
    let wa = d.initiator.write_message(b"first", &mut a).unwrap();
    let wb = d.initiator.write_message(b"second", &mut b).unwrap();
    assert!(d.responder.read_message(&b[..wb], &mut out).is_err(), "second before first");
    // That failed read must not have moved the counter: the in-order message still works.
    assert!(d.responder.read_message(&a[..wa], &mut out).is_ok());
    assert!(d.responder.read_message(&a[..wa], &mut out).is_err(), "replay of first");
    assert!(d.responder.read_message(&b[..wb], &mut out).is_ok());
}

#[test]
fn transport_maximum_is_65535_bytes_with_tag() {
    let (_, _, psk, pro) = pair();
    let mut d = run(&psk, &pro, &psk, &pro).unwrap();
    let payload = vec![0xAB; MAX_TRANSPORT_PLAINTEXT]; // 65,519
    let mut buf = vec![0u8; 70_000];
    let mut out = vec![0u8; 70_000];
    let w = d.initiator.write_message(&payload, &mut buf).unwrap();
    assert_eq!(w, 65_535, "65,519 bytes plus the 16-byte tag");
    let r = d.responder.read_message(&buf[..w], &mut out).unwrap();
    assert_eq!(&out[..r], &payload[..]);

    // One byte more is refused on write...
    let too_big = vec![0xAB; MAX_TRANSPORT_PLAINTEXT + 1];
    assert!(d.initiator.write_message(&too_big, &mut buf).is_err());
    // ...and a 65,536-byte message is refused on read.
    assert!(d.responder.read_message(&buf[..65_536], &mut out).is_err());
    // A failed write must not have burned a send counter: the next message still reads.
    let w = d.initiator.write_message(b"after", &mut buf).unwrap();
    assert!(d.responder.read_message(&buf[..w], &mut out).is_ok());
}

#[test]
fn bundle_of_1883_bytes_fits_in_one_message() {
    // Section 3: "a public bundle is 1,883 bytes".
    let (_, _, psk, pro) = pair();
    let mut d = run(&psk, &pro, &psk, &pro).unwrap();
    let bundle = vec![0x5A; 1883];
    let mut buf = vec![0u8; 4000];
    let mut out = vec![0u8; 4000];
    let w = d.initiator.write_message(&bundle, &mut buf).unwrap();
    assert_eq!(w, 1883 + 16);
    assert_eq!(d.responder.read_message(&buf[..w], &mut out).unwrap(), 1883);
}

const PSK_VECTOR: &str = "fb574fe0abfae561de6fb3e1359eaa3431cff45bceef6cb0232579893b335984";

fn hex(b: &[u8]) -> String {
    b.iter().map(|x| format!("{x:02x}")).collect()
}
