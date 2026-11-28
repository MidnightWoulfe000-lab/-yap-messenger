use sha2::{Digest, Sha256};
use yap_stream_attachments::*;

const KEY: [u8; 32] = [7; 32];
const NONCE: [u8; 19] = [9; 19];

fn data(n: usize) -> Vec<u8> {
    (0..n).map(|i| (i * 31 + 7) as u8).collect()
}

/// The size table from the fifth, sixth and eighth reviews.
#[test]
fn size_table() {
    // (plaintext, segments, encrypted, fits the 262,144-byte small limit)
    let rows = [
        (0usize, 1usize, 16usize, true),
        (1, 1, 17, true),
        (65_535, 1, 65_551, true),
        (65_536, 1, 65_552, true),
        (65_537, 2, 65_569, true),
        (262_080, 4, 262_144, true),
        (262_081, 4, 262_145, false),
    ];
    for (n, count, enc, small) in rows {
        assert_eq!(segment_count(n), count, "count for {n}");
        assert_eq!(encrypted_size(n), enc, "size for {n}");
        assert_eq!(enc <= SMALL_MAX_ENCRYPTED, small, "small for {n}");
        // The real encryption produces exactly the predicted length.
        assert_eq!(encrypt(&KEY, &NONCE, &data(n)).len(), enc, "real length for {n}");
    }
}

#[test]
fn round_trip_every_size() {
    for n in [0usize, 1, 65_535, 65_536, 65_537, 131_072, 262_080, 262_081] {
        let p = data(n);
        let e = encrypt(&KEY, &NONCE, &p);
        let m = Manifest::new(n, &e);
        assert!(m.equations_hold());
        assert_eq!(decrypt(&KEY, &NONCE, &m, &e).unwrap(), p, "n = {n}");
    }
}

/// Exactly 65,536 x k bytes: k full segments and no extra empty one.
#[test]
fn exact_multiples_have_no_extra_segment() {
    for k in 1..=4usize {
        let n = SEGMENT * k;
        assert_eq!(segment_count(n), k);
        assert_eq!(encrypt(&KEY, &NONCE, &data(n)).len(), n + 16 * k);
    }
}

#[test]
fn empty_file_is_one_empty_last_segment() {
    let e = encrypt(&KEY, &NONCE, &[]);
    assert_eq!(e.len(), 16);
    let m = Manifest::new(0, &e);
    assert_eq!(decrypt(&KEY, &NONCE, &m, &e).unwrap(), Vec::<u8>::new());
}

/// Cut on a segment boundary: the manifest's size check catches it, and so does the
/// final flag when that check is bypassed.
#[test]
fn truncated_on_a_segment_boundary_fails() {
    let p = data(3 * SEGMENT);
    let e = encrypt(&KEY, &NONCE, &p);
    let m = Manifest::new(p.len(), &e);
    let cut = &e[..2 * (SEGMENT + 16)]; // first two segments, both non-final
    assert_eq!(decrypt(&KEY, &NONCE, &m, cut), Err(Refused::WrongSize));
    // Size and hash bypassed, true count of 3: it runs out of segments.
    assert!(decrypt_segments(&KEY, &NONCE, 3, cut).is_err());
    // Everything bypassed, count wrongly 2: segment index 1 was encrypted non-final, so
    // decrypting it as final fails. This is the final flag working alone.
    assert_eq!(decrypt_segments(&KEY, &NONCE, 2, cut), Err(Refused::Segment(1)));
}

#[test]
fn truncated_mid_segment_fails() {
    let p = data(2 * SEGMENT + 100);
    let e = encrypt(&KEY, &NONCE, &p);
    let cut = &e[..e.len() - 1];
    assert!(decrypt_segments(&KEY, &NONCE, 3, cut).is_err());
    let m = Manifest::new(p.len(), &e);
    assert_eq!(decrypt(&KEY, &NONCE, &m, cut), Err(Refused::WrongSize));
}

#[test]
fn reordered_segments_fail() {
    let p = data(3 * SEGMENT + 5); // 4 segments, last short
    let e = encrypt(&KEY, &NONCE, &p);
    let full = SEGMENT + 16;
    let mut swapped = Vec::new();
    swapped.extend_from_slice(&e[full..2 * full]);
    swapped.extend_from_slice(&e[..full]);
    swapped.extend_from_slice(&e[2 * full..]);
    assert_eq!(swapped.len(), e.len());
    // Same size, so the segment checks (or the hash) must be what notices.
    assert_eq!(decrypt_segments(&KEY, &NONCE, 4, &swapped), Err(Refused::Segment(0)));
    let m = Manifest::new(p.len(), &e);
    assert_eq!(decrypt(&KEY, &NONCE, &m, &swapped), Err(Refused::WrongHash));
}

/// Swap the last segment with the first (equal length because every segment is full).
#[test]
fn final_segment_swapped_with_an_earlier_one_fails() {
    let p = data(3 * SEGMENT);
    let e = encrypt(&KEY, &NONCE, &p);
    let full = SEGMENT + 16;
    let mut swapped = e.clone();
    swapped[..full].copy_from_slice(&e[2 * full..]);
    swapped[2 * full..].copy_from_slice(&e[..full]);
    assert!(decrypt_segments(&KEY, &NONCE, 3, &swapped).is_err());
}

/// A non-final segment presented as the last one fails, and an appended segment fails.
#[test]
fn non_final_presented_as_last_and_extension_fail() {
    let p = data(2 * SEGMENT + 10);
    let e = encrypt(&KEY, &NONCE, &p);
    let full = SEGMENT + 16;
    assert_eq!(decrypt_segments(&KEY, &NONCE, 1, &e[..full]), Err(Refused::Segment(0)));
    assert!(decrypt_segments(&KEY, &NONCE, 2, &e).is_err());
    let mut longer = e.clone();
    longer.extend_from_slice(&encrypt(&KEY, &NONCE, &data(5)));
    let m = Manifest::new(p.len(), &e);
    assert_eq!(decrypt(&KEY, &NONCE, &m, &longer), Err(Refused::WrongSize));
    assert!(decrypt_segments(&KEY, &NONCE, 3, &longer).is_err());
}

#[test]
fn flipped_bit_in_any_segment_fails() {
    let p = data(2 * SEGMENT + 10);
    let e = encrypt(&KEY, &NONCE, &p);
    for at in [0usize, SEGMENT + 16 + 3, e.len() - 1] {
        let mut bad = e.clone();
        bad[at] ^= 1;
        assert!(decrypt_segments(&KEY, &NONCE, 3, &bad).is_err());
    }
}

#[test]
fn wrong_key_or_nonce_fails() {
    let p = data(100);
    let e = encrypt(&KEY, &NONCE, &p);
    assert!(decrypt_segments(&[8; 32], &NONCE, 1, &e).is_err());
    assert!(decrypt_segments(&KEY, &[10; 19], 1, &e).is_err());
}

#[test]
fn manifest_equations() {
    let p = data(70_000);
    let e = encrypt(&KEY, &NONCE, &p);
    let good = Manifest::new(p.len(), &e);
    assert!(good.equations_hold());
    assert_eq!(good.segment_count, 2);
    assert_eq!(good.encrypted_size, 70_000 + 32);

    let mut m = good.clone();
    m.segment_count = 3;
    assert!(!m.equations_hold());
    assert_eq!(decrypt(&KEY, &NONCE, &m, &e), Err(Refused::ManifestEquations));

    let mut m = good.clone();
    m.encrypted_size += 1;
    assert!(!m.equations_hold());

    let mut m = good.clone();
    m.plaintext_size += 1; // still 2 segments, but the encrypted size is no longer n + 32
    assert!(!m.equations_hold());

    let mut m = good.clone();
    m.encrypted_sha256[0] ^= 1;
    assert_eq!(decrypt(&KEY, &NONCE, &m, &e), Err(Refused::WrongHash));
}

/// The two crate families must give identical bytes, so a later move from one pair to
/// the other would not change files already stored.
#[test]
fn both_crate_families_agree() {
    for n in [0usize, 1, 65_536, 65_537, 262_080] {
        let p = data(n);
        assert_eq!(encrypt(&KEY, &NONCE, &p), encrypt_old_family(&KEY, &NONCE, &p), "n = {n}");
    }
}

/// Fixed vector, recorded in the README.
#[test]
fn fixed_vector() {
    let p = data(65_537);
    let e = encrypt(&KEY, &NONCE, &p);
    let h = hex(&Sha256::digest(&e));
    println!("VECTOR enc_len={} sha256(enc)={h}", e.len());
    assert_eq!(e.len(), 65_569);
    assert_eq!(h, FIXED_VECTOR_SHA256);
}

const FIXED_VECTOR_SHA256: &str = "0af344ba2afd5c6339c4b883736c889217dfeb0a24dd4068ddb557958ffba491";

fn hex(b: &[u8]) -> String {
    b.iter().map(|x| format!("{x:02x}")).collect()
}

/// Independent check of the nonce layout the proposal relies on ("the final flag is part
/// of the nonce"): rebuild each segment's XChaCha20-Poly1305 nonce by hand as
/// stream nonce (19) || big-endian counter (4) || flag (1) and open it with the plain AEAD.
#[test]
fn hand_built_nonce_layout_matches_stream() {
    use chacha20poly1305::aead::{Aead, KeyInit};
    use chacha20poly1305::{XChaCha20Poly1305, XNonce};
    let p = data(2 * SEGMENT + 10);
    let e = encrypt(&KEY, &NONCE, &p);
    let aead = XChaCha20Poly1305::new((&KEY).into());
    let full = SEGMENT + 16;
    for i in 0..3usize {
        let last = i == 2;
        let mut n = [0u8; 24];
        n[..19].copy_from_slice(&NONCE);
        n[19..23].copy_from_slice(&(i as u32).to_be_bytes());
        n[23] = last as u8;
        let seg = if last { &e[2 * full..] } else { &e[i * full..(i + 1) * full] };
        let plain = aead.decrypt(XNonce::from_slice(&n), seg).expect("hand-built nonce opens segment");
        assert_eq!(plain, &p[i * SEGMENT..(i * SEGMENT + plain.len())]);
        // And with the flag flipped it must not open.
        n[23] = !last as u8;
        assert!(aead.decrypt(XNonce::from_slice(&n), seg).is_err());
    }
}
