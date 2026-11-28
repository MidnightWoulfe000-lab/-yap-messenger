//! Gate 4 feasibility check for `docs/proposals/encrypted-pairing.md` sections 1-3.
//!
//! Uses `snow` 0.10.0 for `Noise_NNpsk0_25519_ChaChaPoly_SHA256`. The initiator is the
//! phone holding the smaller identity key. Not linked into the app. Not reviewed.
//! The proposal's wording is followed literally; where it can be read two ways the
//! choice made here is marked `READING:` and listed in the README.

use hkdf::Hkdf;
use sha2::Sha256;
use snow::{Builder, HandshakeState, TransportState};

pub const PATTERN: &str = "Noise_NNpsk0_25519_ChaChaPoly_SHA256";
pub const HANDSHAKE_MESSAGE_LEN: usize = 48; // 32-byte ephemeral key + 16-byte tag, empty payload
pub const MAX_NOISE_MESSAGE: usize = 65_535;
pub const TAG: usize = 16;
pub const MAX_TRANSPORT_PLAINTEXT: usize = MAX_NOISE_MESSAGE - TAG; // 65,519

/// What each phone puts in its QR for the pairing link (section 1). Field sizes as written.
#[derive(Clone)]
pub struct Qr {
    pub identity: [u8; 33],
    pub nonce: [u8; 16],
    pub bundle_hash: [u8; 32],
    pub token: [u8; 16],
    pub pairing_key: [u8; 32],
}

impl Qr {
    /// READING: "QR payload" is the decoded bytes `YPR2 || identity || nonce || bundle hash
    /// || token || pairing key` (133 bytes), not the `yap:v2:` text or its base64url form.
    pub fn payload(&self) -> Vec<u8> {
        let mut v = b"YPR2".to_vec();
        v.extend_from_slice(&self.identity);
        v.extend_from_slice(&self.nonce);
        v.extend_from_slice(&self.bundle_hash);
        v.extend_from_slice(&self.token);
        v.extend_from_slice(&self.pairing_key);
        v
    }
}

/// Orders two QRs by unsigned-byte comparison of the 33-byte identity keys; returns
/// (smaller, larger). The smaller is the Noise initiator.
pub fn ordered<'a>(a: &'a Qr, b: &'a Qr) -> (&'a Qr, &'a Qr) {
    if a.identity <= b.identity { (a, b) } else { (b, a) }
}

/// Section 1: psk = HKDF-SHA256(ikm = key_small || key_large, salt = "Yap pairing psk v1",
/// info = nonce_small || nonce_large) -> 32 bytes.
pub fn derive_psk(a: &Qr, b: &Qr) -> [u8; 32] {
    let (s, l) = ordered(a, b);
    let mut ikm = Vec::new();
    ikm.extend_from_slice(&s.pairing_key);
    ikm.extend_from_slice(&l.pairing_key);
    let mut info = Vec::new();
    info.extend_from_slice(&s.nonce);
    info.extend_from_slice(&l.nonce);
    let hk = Hkdf::<Sha256>::new(Some(b"Yap pairing psk v1"), &ikm);
    let mut out = [0u8; 32];
    hk.expand(&info, &mut out).expect("32 bytes is a valid HKDF-SHA256 length");
    out
}

/// Original prologue: "Yap pairing v2" followed by both QR payloads in identity order.
pub fn original_prologue(a: &Qr, b: &Qr) -> Vec<u8> {
    let (s, l) = ordered(a, b);
    let mut p = b"Yap pairing v2".to_vec();
    p.extend(s.payload());
    p.extend(l.payload());
    p
}

/// Resume prologue: "Yap pairing v2 resume" followed by the original handshake hash and
/// both identity keys in order. READING: the identity keys are the raw 33-byte keys,
/// smaller first.
pub fn resume_prologue(a: &Qr, b: &Qr, original_hash: &[u8]) -> Vec<u8> {
    let (s, l) = ordered(a, b);
    let mut p = b"Yap pairing v2 resume".to_vec();
    p.extend_from_slice(original_hash);
    p.extend_from_slice(&s.identity);
    p.extend_from_slice(&l.identity);
    p
}

pub fn build(initiator: bool, psk: &[u8; 32], prologue: &[u8]) -> HandshakeState {
    let b = Builder::new(PATTERN.parse().expect("pattern parses"))
        .psk(0, psk)
        .expect("psk")
        .prologue(prologue)
        .expect("prologue");
    if initiator { b.build_initiator() } else { b.build_responder() }.expect("build")
}

/// What a finished handshake gives back.
pub struct Done {
    pub initiator: TransportState,
    pub responder: TransportState,
    pub hash_initiator: Vec<u8>,
    pub hash_responder: Vec<u8>,
    pub msg1_len: usize,
    pub msg2_len: usize,
}

/// App-side length gate from the proposal: "each is exactly 48 bytes ... any other
/// length is dropped" before any cryptographic work. `snow` does not do this itself.
pub fn length_gate(message: &[u8]) -> bool {
    message.len() == HANDSHAKE_MESSAGE_LEN
}

/// Runs both sides in-process: `-> psk, e` then `<- e, ee`, empty payloads.
pub fn run(psk_i: &[u8; 32], pro_i: &[u8], psk_r: &[u8; 32], pro_r: &[u8]) -> Result<Done, snow::Error> {
    let mut i = build(true, psk_i, pro_i);
    let mut r = build(false, psk_r, pro_r);
    let mut buf = [0u8; MAX_NOISE_MESSAGE];
    let mut out = [0u8; MAX_NOISE_MESSAGE];
    let n1 = i.write_message(&[], &mut buf)?;
    r.read_message(&buf[..n1], &mut out)?;
    let mut buf2 = [0u8; MAX_NOISE_MESSAGE];
    let n2 = r.write_message(&[], &mut buf2)?;
    i.read_message(&buf2[..n2], &mut out)?;
    assert!(i.is_handshake_finished() && r.is_handshake_finished());
    let hash_initiator = i.get_handshake_hash().to_vec();
    let hash_responder = r.get_handshake_hash().to_vec();
    Ok(Done {
        initiator: i.into_transport_mode()?,
        responder: r.into_transport_mode()?,
        hash_initiator,
        hash_responder,
        msg1_len: n1,
        msg2_len: n2,
    })
}
