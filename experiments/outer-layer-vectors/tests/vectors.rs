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
        assert_eq!(open(&v.key, &v.ad, &bad), None, "last tag byte: {}", v.name);
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
fn group_and_pair_kinds_give_different_keys() {
    let hour = hour_of(NOON_MS);
    let group = derive_key(&LABEL_KEY, &info(KIND_GROUP, &GROUP, &ALICE, &NOBODY, hour));
    let pair = derive_key(&LABEL_KEY, &info(KIND_PAIR, &GROUP, &ALICE, &BOB, hour));
    assert_ne!(group, pair);
}

#[test]
fn xchacha20poly1305_matches_the_published_example() {
    // draft-irtf-cfrg-xchacha-03, appendix A.3.1: the whole ciphertext and tag.
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
    let published = concat!(
        "bd6d179d3e83d43b9576579493c0e939572a1700252bfaccbed2902c21396cbb",
        "731c7f1b0b4aa6440bf3a82f4eda7e39ae64c6708c54c216cb96b72e1213b452",
        "2f8c9ba40db5d945b11b69b982c1bb9e3f3fac2bc369488f76b2383565d3fff9",
        "21f9664c97637da9768812f615c68b13b52e",
        "c0875924c1c7987947deafd8780acf49"
    );
    assert_eq!(out.len(), 130);
    assert_eq!(hex(&out), published);
}

#[test]
fn every_part_of_the_sealed_output_is_checked() {
    for v in vectors() {
        let flips = [
            ("format tag", 0),
            ("nonce", 4),
            ("ciphertext or tag start", 28),
            ("tag end", v.sealed.len() - 1),
        ];
        for (what, at) in flips {
            let mut bad = v.sealed.clone();
            bad[at] ^= 1;
            assert_eq!(open(&v.key, &v.ad, &bad), None, "{what}: {}", v.name);
        }
        if v.sealed.len() > 44 {
            let mut bad = v.sealed.clone();
            bad[29] ^= 1;
            assert_eq!(open(&v.key, &v.ad, &bad), None, "ciphertext: {}", v.name);
        }
        let truncated = &v.sealed[..v.sealed.len() - 1];
        assert_eq!(
            open(&v.key, &v.ad, truncated),
            None,
            "truncated: {}",
            v.name
        );
        let mut extended = v.sealed.clone();
        extended.push(0);
        assert_eq!(open(&v.key, &v.ad, &extended), None, "extended: {}", v.name);
    }
}

#[test]
fn each_header_field_is_bound_on_its_own() {
    let v = &vectors()[0];
    let base = header(NOON_MS);
    let mut changed = Vec::new();
    let mut h = base;
    h.id[0] ^= 1;
    changed.push(("ID", h));
    let mut h = base;
    h.destination[0] ^= 1;
    changed.push(("label", h));
    let mut h = base;
    h.sender_hint[0] ^= 1;
    changed.push(("sender hint", h));
    let mut h = base;
    h.created_ms += 1;
    changed.push(("creation time alone", h));
    let mut h = base;
    h.expires_ms -= 1;
    changed.push(("expiry alone", h));
    for (what, h) in changed {
        assert_eq!(open(&v.key, &envelope_ad(&h), &v.sealed), None, "{what}");
    }
}

#[test]
fn a_piece_binds_its_label_and_its_hour() {
    let v = &vectors()[2];
    let mut label = v.ad.clone();
    label[0] ^= 1;
    assert_eq!(open(&v.key, &label, &v.sealed), None, "label");
    let mut hour = v.ad.clone();
    *hour.last_mut().unwrap() ^= 1;
    assert_eq!(open(&v.key, &hour, &v.sealed), None, "hour");
}
