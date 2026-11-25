//! Experimental v1 relay envelope. Ciphertext is opaque and NOT authenticated here.
use sha2::{Digest, Sha256};

pub const MAX_ENVELOPE_BYTES: usize = 65_536;
pub const HEADER_BYTES: usize = 73;
pub const MAX_HOPS: u8 = 8;
pub const MAX_LIFETIME_MS: u64 = 86_400_000;
pub type Token = [u8; 16];
pub type RelayKey = [u8; 48];

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum EnvelopeError {
    Size,
    Version,
    Length,
    Lifetime,
    Hops,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Header {
    pub id: Token,
    pub destination: Token,
    pub sender_hint: Token,
    pub created_ms: u64,
    pub expires_ms: u64,
    pub hops: u8,
}

impl Header {
    /// Canonical immutable metadata to copy INSIDE authenticated plaintext.
    /// The crypto adapter must compare it before committing any staged state.
    pub fn binding(&self) -> [u8; 68] {
        let mut bytes = [0; 68];
        bytes[..4].copy_from_slice(b"YAP\x01");
        bytes[4..20].copy_from_slice(&self.id);
        bytes[20..36].copy_from_slice(&self.destination);
        bytes[36..52].copy_from_slice(&self.sender_hint);
        bytes[52..60].copy_from_slice(&self.created_ms.to_be_bytes());
        bytes[60..68].copy_from_slice(&self.expires_ms.to_be_bytes());
        bytes
    }
}

/// Construction validates bounds, not identity, encryption or authenticity.
/// The caller must generate random IDs/tokens and supply real ciphertext.
#[derive(Clone, PartialEq, Eq)]
pub struct Envelope {
    header: Header,
    ciphertext: Vec<u8>,
}

impl Envelope {
    pub fn new(header: Header, ciphertext: Vec<u8>) -> Result<Self, EnvelopeError> {
        if ciphertext.is_empty() || ciphertext.len() > MAX_ENVELOPE_BYTES - HEADER_BYTES {
            return Err(EnvelopeError::Size);
        }
        if header.hops > MAX_HOPS {
            return Err(EnvelopeError::Hops);
        }
        if header.expires_ms <= header.created_ms
            || header.expires_ms - header.created_ms > MAX_LIFETIME_MS
        {
            return Err(EnvelopeError::Lifetime);
        }
        Ok(Self { header, ciphertext })
    }

    pub fn header(&self) -> Header {
        self.header
    }
    pub fn ciphertext(&self) -> &[u8] {
        &self.ciphertext
    }

    pub fn encode(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(HEADER_BYTES + self.ciphertext.len());
        out.extend_from_slice(&self.header.binding());
        out.push(self.header.hops);
        out.extend_from_slice(&(self.ciphertext.len() as u32).to_be_bytes());
        out.extend_from_slice(&self.ciphertext);
        out
    }

    pub fn decode(bytes: &[u8]) -> Result<Self, EnvelopeError> {
        if bytes.len() <= HEADER_BYTES || bytes.len() > MAX_ENVELOPE_BYTES {
            return Err(EnvelopeError::Size);
        }
        if &bytes[..4] != b"YAP\x01" {
            return Err(EnvelopeError::Version);
        }
        let length = u32::from_be_bytes(bytes[69..73].try_into().unwrap()) as usize;
        if length != bytes.len() - HEADER_BYTES {
            return Err(EnvelopeError::Length);
        }
        Self::new(
            Header {
                id: bytes[4..20].try_into().unwrap(),
                destination: bytes[20..36].try_into().unwrap(),
                sender_hint: bytes[36..52].try_into().unwrap(),
                created_ms: u64::from_be_bytes(bytes[52..60].try_into().unwrap()),
                expires_ms: u64::from_be_bytes(bytes[60..68].try_into().unwrap()),
                hops: bytes[68],
            },
            bytes[HEADER_BYTES..].to_vec(),
        )
    }

    /// Digest ALL immutable bytes. Mutating any outer metadata cannot poison the
    /// genuine copy's cache entry. The mutable hop byte is the only exclusion.
    pub fn relay_key(&self) -> RelayKey {
        let mut canonical = self.encode();
        canonical[68] = 0;
        let mut hash = Sha256::new();
        hash.update(b"Yap relay envelope v1\0");
        hash.update(canonical);
        let mut key = [0; 48];
        key[..16].copy_from_slice(&self.header.id);
        key[16..].copy_from_slice(&hash.finalize());
        key
    }

    pub fn forwarded(&self) -> Option<Self> {
        let hops = self.header.hops.checked_sub(1)?;
        let mut next = self.clone();
        next.header.hops = hops;
        Some(next)
    }
}
