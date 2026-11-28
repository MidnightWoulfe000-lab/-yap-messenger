//! Gate 5 feasibility check for `docs/proposals/attachments-v1.md` section 1.
//!
//! Implements the segmenting exactly as the proposal words it: XChaCha20-Poly1305
//! under `StreamBE32` (19-byte stream nonce), 65,536-byte plaintext segments, one
//! empty last segment for an empty file, final flag carried in the nonce.
//! Not linked into the app. Not reviewed.

use aead_stream::{DecryptorBE32, EncryptorBE32};
use chacha20poly1305::{KeyInit, XChaCha20Poly1305};
use sha2::{Digest, Sha256};

pub const SEGMENT: usize = 65_536;
pub const TAG: usize = 16;
pub const SMALL_MAX_ENCRYPTED: usize = 262_144;
pub const KEY_LEN: usize = 32;
pub const NONCE_LEN: usize = 19;

/// `max(1, ceil(n / 65,536))`.
pub fn segment_count(n: usize) -> usize {
    n.div_ceil(SEGMENT).max(1)
}

/// `n + 16 * count`.
pub fn encrypted_size(n: usize) -> usize {
    n + TAG * segment_count(n)
}

/// The manifest fields that the segmenting rules constrain (attachments-v1 section 1).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Manifest {
    pub plaintext_size: usize,
    pub encrypted_size: usize,
    pub segment_count: usize,
    pub encrypted_sha256: [u8; 32],
}

#[derive(Debug, PartialEq, Eq)]
pub enum Refused {
    /// The manifest's own numbers do not satisfy the segmenting equations.
    ManifestEquations,
    /// The bytes are not the announced size.
    WrongSize,
    /// The bytes do not hash to the announced SHA-256.
    WrongHash,
    /// A segment failed authentication (including a wrong final flag).
    Segment(usize),
}

impl Manifest {
    pub fn new(plaintext_len: usize, encrypted: &[u8]) -> Manifest {
        Manifest {
            plaintext_size: plaintext_len,
            encrypted_size: encrypted.len(),
            segment_count: segment_count(plaintext_len),
            encrypted_sha256: Sha256::digest(encrypted).into(),
        }
    }

    /// The equations alone, before any bytes are looked at.
    pub fn equations_hold(&self) -> bool {
        self.segment_count == segment_count(self.plaintext_size)
            && self.encrypted_size == self.plaintext_size + TAG * self.segment_count
    }
}

fn cipher(key: &[u8; KEY_LEN]) -> XChaCha20Poly1305 {
    XChaCha20Poly1305::new(key.into())
}

pub fn encrypt(key: &[u8; KEY_LEN], nonce: &[u8; NONCE_LEN], plaintext: &[u8]) -> Vec<u8> {
    let mut enc = EncryptorBE32::from_aead(cipher(key), nonce.into());
    let count = segment_count(plaintext.len());
    let mut out = Vec::with_capacity(encrypted_size(plaintext.len()));
    for i in 0..count {
        let start = i * SEGMENT;
        let end = (start + SEGMENT).min(plaintext.len());
        let chunk = &plaintext[start..end];
        if i + 1 == count {
            out.extend(enc.encrypt_last(chunk).expect("encrypt last"));
            return out;
        }
        out.extend(enc.encrypt_next(chunk).expect("encrypt next"));
    }
    unreachable!()
}

/// Receiver side: check the manifest, then decrypt using only the manifest's count to
/// decide which segment is last, as the proposal says.
pub fn decrypt(
    key: &[u8; KEY_LEN],
    nonce: &[u8; NONCE_LEN],
    manifest: &Manifest,
    encrypted: &[u8],
) -> Result<Vec<u8>, Refused> {
    if !manifest.equations_hold() {
        return Err(Refused::ManifestEquations);
    }
    if encrypted.len() != manifest.encrypted_size {
        return Err(Refused::WrongSize);
    }
    if Sha256::digest(encrypted).as_slice() != manifest.encrypted_sha256 {
        return Err(Refused::WrongHash);
    }
    decrypt_segments(key, nonce, manifest.segment_count, encrypted)
}

/// Segment decryption alone (no size or hash gate), so tests can show the final flag
/// does its own work when the other checks are bypassed.
pub fn decrypt_segments(
    key: &[u8; KEY_LEN],
    nonce: &[u8; NONCE_LEN],
    count: usize,
    encrypted: &[u8],
) -> Result<Vec<u8>, Refused> {
    let mut dec = DecryptorBE32::from_aead(cipher(key), nonce.into());
    let full = SEGMENT + TAG;
    let mut out = Vec::new();
    for i in 0..count {
        let start = i * full;
        if start > encrypted.len() {
            return Err(Refused::Segment(i));
        }
        let last = i + 1 == count;
        let end = if last { encrypted.len() } else { start + full };
        if end > encrypted.len() || (last && end - start > full) {
            return Err(Refused::Segment(i));
        }
        let seg = &encrypted[start..end];
        if last {
            return match dec.decrypt_last(seg) {
                Ok(p) => {
                    out.extend(p);
                    Ok(out)
                }
                Err(_) => Err(Refused::Segment(i)),
            };
        }
        match dec.decrypt_next(seg) {
            Ok(p) => out.extend(p),
            Err(_) => return Err(Refused::Segment(i)),
        }
    }
    Err(Refused::Segment(count))
}

/// Same encryption through the older family (`aead` 0.5's built-in `stream` module
/// from chacha20poly1305 0.10.1, which the outer layer pins today).
pub fn encrypt_old_family(key: &[u8; KEY_LEN], nonce: &[u8; NONCE_LEN], plaintext: &[u8]) -> Vec<u8> {
    use chacha20poly1305_old::aead::stream::EncryptorBE32;
    use chacha20poly1305_old::{KeyInit, XChaCha20Poly1305};
    let mut enc = EncryptorBE32::from_aead(XChaCha20Poly1305::new(key.into()), nonce.into());
    let count = segment_count(plaintext.len());
    let mut out = Vec::new();
    for i in 0..count {
        let start = i * SEGMENT;
        let end = (start + SEGMENT).min(plaintext.len());
        let chunk = &plaintext[start..end];
        if i + 1 == count {
            out.extend(enc.encrypt_last(chunk).expect("encrypt last"));
            return out;
        }
        out.extend(enc.encrypt_next(chunk).expect("encrypt next"));
    }
    unreachable!()
}
