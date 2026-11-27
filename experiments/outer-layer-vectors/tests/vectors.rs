use yap_outer_layer_vectors::*;

#[test]
fn committed_vectors_match_the_rules() {
    let committed = include_str!("../vectors.txt").replace("\r\n", "\n");
    assert_eq!(
        render(),
        committed,
        "regenerate with `cargo run > vectors.txt`"
    );
}

#[test]
fn hkdf_matches_rfc_5869_test_case_1() {
    // Checks the HKDF library against the published answer before trusting it.
    let ikm = [0x0b; 22];
    let salt: Vec<u8> = (0x00..=0x0c).collect();
    let info: Vec<u8> = (0xf0..=0xf9).collect();
    let mut okm = [0u8; 42];
    hkdf::Hkdf::<sha2::Sha256>::new(Some(&salt), &ikm)
        .expand(&info, &mut okm)
        .unwrap();
    assert_eq!(
        hex(&okm),
        "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"
    );
}

#[test]
fn every_vector_opens_and_rejects_tampering() {
    for v in vectors() {
        assert_eq!(
            open(&v.key, &v.ad, &v.sealed),
            Some(v.plaintext.clone()),
            "{}",
            v.name
        );
        let mut bad = v.sealed.clone();
        *bad.last_mut().unwrap() ^= 1;
        assert_eq!(open(&v.key, &v.ad, &bad), None, "tampered tag: {}", v.name);
        let mut bad_ad = v.ad.clone();
        bad_ad[0] ^= 1;
        assert_eq!(
            open(&v.key, &bad_ad, &v.sealed),
            None,
            "tampered AD: {}",
            v.name
        );
        let mut bad_format = v.sealed.clone();
        bad_format[0] = b'X';
        assert_eq!(
            open(&v.key, &v.ad, &bad_format),
            None,
            "wrong format tag: {}",
            v.name
        );
    }
}

#[test]
fn the_two_directions_of_a_pair_use_different_keys() {
    let v = vectors();
    let (ab, ba) = (&v[2], &v[3]);
    assert_ne!(ab.key, ba.key);
    assert_eq!(open(&ba.key, &ab.ad, &ab.sealed), None);
}

#[test]
fn an_hour_boundary_changes_the_key() {
    let v = vectors();
    assert_ne!(v[0].key, v[1].key);
    assert_eq!(hour_of(NOON_MS) - hour_of(NOON_MS - 1), 1);
    assert_eq!(open(&v[1].key, &v[0].ad, &v[0].sealed), None);
}

#[test]
fn the_hop_count_is_not_bound() {
    // Relays lower the hop count, so it must not break the envelope.
    let v = &vectors()[0];
    let mut h = header(NOON_MS);
    h.hops = 3;
    assert_eq!(
        open(&v.key, &envelope_ad(&h), &v.sealed),
        Some(v.plaintext.clone())
    );
}

#[test]
fn a_changed_creation_time_is_refused() {
    let v = &vectors()[0];
    assert_eq!(
        open(&v.key, &envelope_ad(&header(NOON_MS + 1)), &v.sealed),
        None
    );
}

#[test]
fn group_and_pair_kinds_give_different_keys() {
    let hour = hour_of(NOON_MS);
    let group = derive_key(&LABEL_KEY, &info(KIND_GROUP, &GROUP, &ALICE, &BOB, hour));
    let pair = derive_key(&LABEL_KEY, &info(KIND_PAIR, &GROUP, &ALICE, &BOB, hour));
    assert_ne!(group, pair);
}

#[test]
fn xchacha20poly1305_matches_the_published_example() {
    // draft-irtf-cfrg-xchacha-03, appendix A.3.1.
    use chacha20poly1305::aead::{Aead, KeyInit, Payload};
    use chacha20poly1305::{XChaCha20Poly1305, XNonce};
    let key: Vec<u8> = (0x80..=0x9f).collect();
    let nonce: Vec<u8> = (0x40..=0x57).collect();
    let ad = [
        0x50, 0x51, 0x52, 0x53, 0xc0, 0xc1, 0xc2, 0xc3, 0xc4, 0xc5, 0xc6, 0xc7,
    ];
    let msg = b"Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it.";
    let out = XChaCha20Poly1305::new(key.as_slice().into())
        .encrypt(XNonce::from_slice(&nonce), Payload { msg, aad: &ad })
        .unwrap();
    let out = hex(&out);
    assert!(out.starts_with("bd6d179d3e83d43b9576579493c0e939"), "{out}");
    assert!(out.ends_with("c0875924c1c7987947deafd8780acf49"), "{out}");
}
