//! Member identities and signatures.
//!
//! Members sign with their libsignal identity key: the same key the endpoint
//! uses for pairing (`endpoint/`), so a member is the person whose safety
//! number their contacts already checked. Signing uses libsignal's own API
//! (XEdDSA over Curve25519); this crate adds no signature scheme of its own.
//! Every signed message starts with a label unique to its purpose (see
//! [`context`]), so a group signature can never pass as a pairing signature
//! or the other way round.

use libsignal_protocol::{IdentityKeyPair, PrivateKey, PublicKey};
use rand::{rngs::OsRng, TryRngCore};
use std::fmt;

/// A member's public identity key (the 32-byte Curve25519 key, without
/// libsignal's one-byte type prefix). Members are identified by it.
#[derive(Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct MemberKey(pub [u8; 32]);

impl MemberKey {
    pub fn verify(&self, message: &[u8], signature: &[u8; 64]) -> bool {
        PublicKey::from_djb_public_key_bytes(&self.0)
            .map(|key| key.verify_signature(message, signature))
            .unwrap_or(false)
    }
}

impl fmt::Debug for MemberKey {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            f,
            "Member({:02x}{:02x}{:02x}{:02x})",
            self.0[0], self.0[1], self.0[2], self.0[3]
        )
    }
}

/// Identifies one of a member's devices. Stage one only records devices; it
/// does not yet sign with per-device keys.
#[derive(Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash, Debug)]
pub struct DeviceKey(pub [u8; 32]);

/// A member's private identity key pair. Never log it.
pub struct Identity(IdentityKeyPair);

impl Identity {
    pub fn generate() -> Self {
        Self(IdentityKeyPair::generate(&mut OsRng.unwrap_err()))
    }

    /// Uses an existing libsignal identity, such as the endpoint's own.
    pub fn from_key_pair(pair: IdentityKeyPair) -> Self {
        Self(pair)
    }

    /// Deterministic identity, for tests and fixtures only.
    pub fn from_seed(seed: [u8; 32]) -> Self {
        let private = PrivateKey::deserialize(&seed).expect("32-byte key");
        let public = private.public_key().expect("valid key");
        Self(IdentityKeyPair::new(public.into(), private))
    }

    pub fn key(&self) -> MemberKey {
        let bytes = self.0.public_key().public_key_bytes();
        MemberKey(bytes.try_into().expect("Curve25519 keys are 32 bytes"))
    }

    pub(crate) fn sign(&self, message: &[u8]) -> [u8; 64] {
        let signature = self
            .0
            .private_key()
            .calculate_signature(message, &mut OsRng.unwrap_err())
            .expect("signing with a valid key");
        (*signature)
            .try_into()
            .expect("XEdDSA signatures are 64 bytes")
    }
}

impl fmt::Debug for Identity {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "Identity({:?})", self.key())
    }
}

/// Domain-separated message, so a signature for one purpose cannot be reused
/// for another.
pub(crate) fn context(label: &str, body: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(label.len() + 1 + body.len());
    out.extend_from_slice(label.as_bytes());
    out.push(0);
    out.extend_from_slice(body);
    out
}
