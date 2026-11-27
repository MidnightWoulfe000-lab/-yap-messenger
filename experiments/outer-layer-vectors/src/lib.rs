//! Reference implementation of the outer encryption layer proposed in
//! `docs/proposals/group-encryption-v1.md` §3.4, written only to produce test
//! vectors. **Not reviewed and not for use in the app.**
//!
//! Byte rules, all integers big-endian:
//! - key = HKDF-SHA256(salt = `Yap outer layer v1`, ikm = label key,
//!   info = kind ‖ group ID (16) ‖ sender member key (32) ‖ recipient member
//!   key (32, zeros for a group message) ‖ hour (8)), 32 bytes out;
//! - kind: `0x01` group message, `0x02` one-to-one piece;
//! - hour = floor(milliseconds since the Unix epoch / 3,600,000);
//! - associated data: for an envelope, `Header::binding()`, which already
//!   holds the label as `destination` and leaves out the hop count; for a
//!   piece, its label (16) ‖ hour (8);
//! - output = `YGO1` ‖ nonce (24) ‖ XChaCha20-Poly1305 ciphertext and tag.
//!   In use the nonce is random; vectors fix it.

pub mod policy;

use chacha20poly1305::aead::{Aead, KeyInit, Payload};
use chacha20poly1305::{XChaCha20Poly1305, XNonce};
use hkdf::Hkdf;
use sha2::Sha256;
use yap_core::envelope::Header;

pub const SALT: &[u8] = b"Yap outer layer v1";
pub const TAG: &[u8; 4] = b"YGO1";
pub const HOUR_MS: u64 = 3_600_000;
pub const KIND_GROUP: u8 = 0x01;
pub const KIND_PAIR: u8 = 0x02;

pub fn hour_of(ms: u64) -> u64 {
    ms / HOUR_MS
}

pub fn info(
    kind: u8,
    group: &[u8; 16],
    sender: &[u8; 32],
    recipient: &[u8; 32],
    hour: u64,
) -> Vec<u8> {
    let mut v = vec![kind];
    v.extend_from_slice(group);
    v.extend_from_slice(sender);
    v.extend_from_slice(recipient);
    v.extend_from_slice(&hour.to_be_bytes());
    v
}

pub fn derive_key(label_key: &[u8; 32], info: &[u8]) -> [u8; 32] {
    let mut out = [0u8; 32];
    Hkdf::<Sha256>::new(Some(SALT), label_key)
        .expand(info, &mut out)
        .expect("32 bytes is a valid HKDF-SHA256 length");
    out
}

pub fn envelope_ad(header: &Header) -> Vec<u8> {
    header.binding().to_vec()
}

pub fn piece_ad(label: &[u8; 16], hour: u64) -> Vec<u8> {
    let mut v = label.to_vec();
    v.extend_from_slice(&hour.to_be_bytes());
    v
}

pub fn seal(key: &[u8; 32], nonce: &[u8; 24], ad: &[u8], plaintext: &[u8]) -> Vec<u8> {
    let cipher = XChaCha20Poly1305::new(key.into());
    let body = cipher
        .encrypt(
            XNonce::from_slice(nonce),
            Payload {
                msg: plaintext,
                aad: ad,
            },
        )
        .expect("in-memory encryption does not fail");
    let mut out = TAG.to_vec();
    out.extend_from_slice(nonce);
    out.extend_from_slice(&body);
    out
}

pub fn open(key: &[u8; 32], ad: &[u8], sealed: &[u8]) -> Option<Vec<u8>> {
    if sealed.len() < 4 + 24 + 16 || &sealed[..4] != TAG {
        return None;
    }
    let cipher = XChaCha20Poly1305::new(key.into());
    cipher
        .decrypt(
            XNonce::from_slice(&sealed[4..28]),
            Payload {
                msg: &sealed[28..],
                aad: ad,
            },
        )
        .ok()
}

pub fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

/// Fixed inputs shared by every vector.
pub const LABEL_KEY: [u8; 32] = [0x11; 32];
pub const GROUP: [u8; 16] = [0x22; 16];
pub const ALICE: [u8; 32] = [0xaa; 32];
pub const BOB: [u8; 32] = [0xbb; 32];
pub const NOBODY: [u8; 32] = [0; 32];
/// 2026-11-27 12:00:00 UTC.
pub const NOON_MS: u64 = 1_795_780_800_000;

pub fn header(created_ms: u64) -> Header {
    Header {
        id: [0x01; 16],
        destination: [0x33; 16],
        sender_hint: [0x44; 16],
        created_ms,
        expires_ms: created_ms + 86_400_000,
        hops: 8,
    }
}

pub struct Vector {
    pub name: &'static str,
    pub info: Vec<u8>,
    pub key: [u8; 32],
    pub nonce: [u8; 24],
    pub ad: Vec<u8>,
    pub plaintext: Vec<u8>,
    pub sealed: Vec<u8>,
}

fn vector(
    name: &'static str,
    info: Vec<u8>,
    nonce: [u8; 24],
    ad: Vec<u8>,
    plaintext: &[u8],
) -> Vector {
    let key = derive_key(&LABEL_KEY, &info);
    let sealed = seal(&key, &nonce, &ad, plaintext);
    Vector {
        name,
        info,
        key,
        nonce,
        ad,
        plaintext: plaintext.to_vec(),
        sealed,
    }
}

pub fn vectors() -> Vec<Vector> {
    let piece_label = [0x55; 16];
    let piece_hour = hour_of(NOON_MS);
    let noon = header(NOON_MS);
    let before = header(NOON_MS - 1);
    vec![
        vector(
            "group envelope",
            info(
                KIND_GROUP,
                &GROUP,
                &ALICE,
                &NOBODY,
                hour_of(noon.created_ms),
            ),
            [0x61; 24],
            envelope_ad(&noon),
            b"Yap group message",
        ),
        vector(
            "group envelope, one millisecond before the hour",
            info(
                KIND_GROUP,
                &GROUP,
                &ALICE,
                &NOBODY,
                hour_of(before.created_ms),
            ),
            [0x62; 24],
            envelope_ad(&before),
            b"Yap group message",
        ),
        vector(
            "piece, Alice to Bob",
            info(KIND_PAIR, &GROUP, &ALICE, &BOB, piece_hour),
            [0x63; 24],
            piece_ad(&piece_label, piece_hour),
            b"inner libsignal message",
        ),
        vector(
            "piece, Bob to Alice, same label key and hour",
            info(KIND_PAIR, &GROUP, &BOB, &ALICE, piece_hour),
            [0x63; 24],
            piece_ad(&piece_label, piece_hour),
            b"inner libsignal message",
        ),
        vector(
            "piece, empty plaintext",
            info(KIND_PAIR, &GROUP, &ALICE, &BOB, piece_hour),
            [0x64; 24],
            piece_ad(&piece_label, piece_hour),
            b"",
        ),
    ]
}

/// The vectors as committed in `vectors.txt`.
pub fn render() -> String {
    let mut s = String::from(
        "# Outer layer test vectors, group encryption v1 (proposal section 3.4).\n\
         # Generated by experiments/outer-layer-vectors; do not edit by hand.\n\
         # label key = 11 x 32, group ID = 22 x 16, Alice = aa x 32, Bob = bb x 32.\n",
    );
    for v in vectors() {
        s.push_str(&format!(
            "\n[{}]\ninfo      = {}\nkey       = {}\nnonce     = {}\nad        = {}\nplaintext = {}\nsealed    = {}\n",
            v.name,
            hex(&v.info),
            hex(&v.key),
            hex(&v.nonce),
            hex(&v.ad),
            hex(&v.plaintext),
            hex(&v.sealed)
        ));
    }
    s
}
