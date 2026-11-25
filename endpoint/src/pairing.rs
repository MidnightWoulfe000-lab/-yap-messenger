//! Canonical, reviewed debug pairing records. Public bundles contain NO secrets.
use crate::{
    bytes::{self},
    store::CryptoStore,
};
use anyhow::{Result, bail, ensure};
use libsignal_protocol::*;
use rand::{Rng, TryRngCore, rngs::OsRng};
use sha2::{Digest, Sha256};
use yap_core::envelope::Token;

pub const OFFER_MS: u64 = 600_000;
pub const BUNDLE_BYTES: usize = 1883;
pub struct PublicBundle {
    pub identity: Vec<u8>,
    pub nonce: Token,
    pub route: Token,
    pub hint: Token,
    pub created: u64,
    pub expires: u64,
    pub registration: u32,
    pub prekey_id: u32,
    pub prekey: Vec<u8>,
    pub signed_id: u32,
    pub signed: Vec<u8>,
    pub signature: Vec<u8>,
    pub pq_id: u32,
    pub pq: Vec<u8>,
    pub pq_signature: Vec<u8>,
}
pub fn random_token() -> Token {
    OsRng.unwrap_err().random()
}
pub fn contact_id(identity: &[u8]) -> String {
    bytes::hex(&Sha256::digest(identity))
}
pub fn address(id: &str) -> ProtocolAddress {
    ProtocolAddress::new(id.to_owned(), DeviceId::new(1).unwrap())
}

impl PublicBundle {
    pub async fn create(store: &mut CryptoStore, id: u32, route: Token, now: u64) -> Result<Self> {
        let mut rng = OsRng.unwrap_err();
        let one = KeyPair::generate(&mut rng);
        let signed = KeyPair::generate(&mut rng);
        let pq = kem::KeyPair::generate(kem::KeyType::Kyber1024, &mut rng);
        let identity = store.identity.pair()?;
        let signature = identity
            .private_key()
            .calculate_signature(&signed.public_key.serialize(), &mut rng)?
            .to_vec();
        let pq_signature = identity
            .private_key()
            .calculate_signature(&pq.public_key.serialize(), &mut rng)?
            .to_vec();
        store
            .prekeys
            .save_pre_key(id.into(), &PreKeyRecord::new(id.into(), &one))
            .await?;
        store
            .signed
            .save_signed_pre_key(
                id.into(),
                &SignedPreKeyRecord::new(
                    id.into(),
                    Timestamp::from_epoch_millis(now),
                    &signed,
                    &signature,
                ),
            )
            .await?;
        store
            .pq
            .save_kyber_pre_key(
                id.into(),
                &KyberPreKeyRecord::new(
                    id.into(),
                    Timestamp::from_epoch_millis(now),
                    &pq,
                    &pq_signature,
                ),
            )
            .await?;
        Ok(Self {
            identity: identity.identity_key().serialize().to_vec(),
            nonce: random_token(),
            route,
            hint: random_token(),
            created: now,
            expires: now
                .checked_add(OFFER_MS)
                .ok_or_else(|| anyhow::anyhow!("clock overflow"))?,
            registration: store.identity.registration(),
            prekey_id: id,
            prekey: one.public_key.serialize().to_vec(),
            signed_id: id,
            signed: signed.public_key.serialize().to_vec(),
            signature,
            pq_id: id,
            pq: pq.public_key.serialize().to_vec(),
            pq_signature,
        })
    }
    pub fn encode(&self) -> Result<Vec<u8>> {
        ensure!(
            self.identity.len() == 33
                && self.prekey.len() == 33
                && self.signed.len() == 33
                && self.signature.len() == 64
                && self.pq.len() == 1569
                && self.pq_signature.len() == 64,
            "invalid bundle field lengths"
        );
        let mut out = Vec::with_capacity(BUNDLE_BYTES);
        out.extend_from_slice(b"YPB1");
        out.extend_from_slice(&self.identity);
        out.extend_from_slice(&self.nonce);
        out.extend_from_slice(&self.route);
        out.extend_from_slice(&self.hint);
        out.extend_from_slice(&self.created.to_be_bytes());
        out.extend_from_slice(&self.expires.to_be_bytes());
        out.extend_from_slice(&self.registration.to_be_bytes());
        out.push(1);
        out.extend_from_slice(&self.prekey_id.to_be_bytes());
        out.extend_from_slice(&self.prekey);
        out.extend_from_slice(&self.signed_id.to_be_bytes());
        out.extend_from_slice(&self.signed);
        out.extend_from_slice(&self.signature);
        out.extend_from_slice(&self.pq_id.to_be_bytes());
        out.extend_from_slice(&(self.pq.len() as u16).to_be_bytes());
        out.extend_from_slice(&self.pq);
        out.extend_from_slice(&self.pq_signature);
        ensure!(out.len() == BUNDLE_BYTES, "bundle size mismatch");
        Ok(out)
    }
    pub fn decode(data: &[u8]) -> Result<Self> {
        ensure!(data.len() == BUNDLE_BYTES, "invalid bundle length");
        let mut r = Reader(data);
        ensure!(r.take(4)? == b"YPB1", "unknown pairing version");
        let identity = r.take(33)?.to_vec();
        let nonce = r.take(16)?.try_into()?;
        let route = r.take(16)?.try_into()?;
        let hint = r.take(16)?.try_into()?;
        let created = r.u64()?;
        let expires = r.u64()?;
        let registration = r.u32()?;
        ensure!(r.take(1)? == [1], "invalid device ID");
        let prekey_id = r.u32()?;
        let prekey = r.take(33)?.to_vec();
        let signed_id = r.u32()?;
        let signed = r.take(33)?.to_vec();
        let signature = r.take(64)?.to_vec();
        let pq_id = r.u32()?;
        let len = u16::from_be_bytes(r.take(2)?.try_into()?) as usize;
        ensure!(len == 1569, "wrong PQ key size");
        let pq = r.take(len)?.to_vec();
        let pq_signature = r.take(64)?.to_vec();
        ensure!(r.0.is_empty(), "trailing bundle data");
        ensure!(
            registration > 0
                && registration < 16384
                && expires.checked_sub(created) == Some(OFFER_MS),
            "invalid pairing lifetime/registration"
        );
        ensure!(
            nonce != [0; 16] && route != [0; 16] && hint != [0; 16],
            "invalid pairing tokens"
        );
        Ok(Self {
            identity,
            nonce,
            route,
            hint,
            created,
            expires,
            registration,
            prekey_id,
            prekey,
            signed_id,
            signed,
            signature,
            pq_id,
            pq,
            pq_signature,
        })
    }
    pub fn validate(&self) -> Result<()> {
        let identity = IdentityKey::decode(&self.identity)?;
        ensure!(
            identity
                .public_key()
                .verify_signature(&self.signed, &self.signature),
            "invalid signed EC prekey"
        );
        ensure!(
            identity
                .public_key()
                .verify_signature(&self.pq, &self.pq_signature),
            "invalid signed PQ prekey"
        );
        self.prekey_bundle()?;
        Ok(())
    }
    pub fn prekey_bundle(&self) -> Result<PreKeyBundle> {
        Ok(PreKeyBundle::new(
            self.registration,
            DeviceId::new(1)?,
            Some((self.prekey_id.into(), PublicKey::deserialize(&self.prekey)?)),
            self.signed_id.into(),
            PublicKey::deserialize(&self.signed)?,
            self.signature.clone(),
            self.pq_id.into(),
            kem::PublicKey::deserialize(&self.pq)?,
            self.pq_signature.clone(),
            IdentityKey::decode(&self.identity)?,
        )?)
    }
    pub fn qr(&self) -> Result<String> {
        let mut qr = b"YPR1".to_vec();
        qr.extend_from_slice(&self.identity);
        qr.extend_from_slice(&self.nonce);
        qr.extend_from_slice(&commitment(&self.encode()?));
        qr.extend_from_slice(&self.route);
        Ok(format!("yap:v1:{}", bytes::encode(&qr)))
    }
    pub fn check_qr(&self, qr: &str) -> Result<()> {
        ensure!(qr.len() <= 256, "QR exceeds limit");
        let raw = bytes::decode(
            qr.strip_prefix("yap:v1:")
                .ok_or_else(|| anyhow::anyhow!("not a Yap pairing QR"))?,
            101,
        )?;
        ensure!(
            raw.len() == 101 && &raw[..4] == b"YPR1",
            "unknown QR format"
        );
        ensure!(
            raw[4..37] == self.identity
                && raw[37..53] == self.nonce
                && raw[53..85] == commitment(&self.encode()?)
                && raw[85..] == self.route,
            "QR does not match this public bundle"
        );
        Ok(())
    }
    pub fn id(&self) -> String {
        contact_id(&self.identity)
    }
}
pub fn commitment(bundle: &[u8]) -> [u8; 32] {
    let mut h = Sha256::new();
    h.update(b"Yap public bundle v1\0");
    h.update(bundle);
    h.finalize().into()
}
pub fn transcript(first: &[u8], second: &[u8]) -> Result<Vec<u8>> {
    let a = PublicBundle::decode(first)?;
    let b = PublicBundle::decode(second)?;
    ensure!(
        a.identity != b.identity,
        "cannot pair this identity with itself"
    );
    let (small, large) = if a.identity < b.identity {
        (first, second)
    } else {
        (second, first)
    };
    let mut out = b"Yap pairing confirmation v1\0".to_vec();
    for record in [small, large] {
        out.extend_from_slice(&(record.len() as u32).to_be_bytes());
        out.extend_from_slice(record);
    }
    Ok(out)
}
pub fn confirmation(store: &CryptoStore, local: &[u8], remote: &[u8]) -> Result<Vec<u8>> {
    let key = store.identity.pair()?;
    let signature = key
        .private_key()
        .calculate_signature(&transcript(local, remote)?, &mut OsRng.unwrap_err())?;
    let mut out = b"YPC1".to_vec();
    out.extend_from_slice(&key.identity_key().serialize());
    out.extend_from_slice(&signature);
    Ok(out)
}
pub fn verify_confirmation(message: &[u8], local: &[u8], remote: &[u8]) -> Result<()> {
    ensure!(
        message.len() == 101 && &message[..4] == b"YPC1",
        "invalid confirmation"
    );
    let peer = PublicBundle::decode(remote)?;
    ensure!(message[4..37] == peer.identity, "wrong confirming identity");
    ensure!(
        IdentityKey::decode(&peer.identity)?
            .public_key()
            .verify_signature(&transcript(local, remote)?, &message[37..]),
        "invalid pairing confirmation signature"
    );
    Ok(())
}
pub fn retire_offer_keys(store: &mut CryptoStore, public: &[u8]) -> Result<()> {
    let offer = PublicBundle::decode(public)?;
    store.prekeys.0.remove(&offer.prekey_id);
    store.signed.0.remove(&offer.signed_id);
    store.pq.0.remove(&offer.pq_id);
    Ok(())
}
struct Reader<'a>(&'a [u8]);
impl<'a> Reader<'a> {
    fn take(&mut self, len: usize) -> Result<&'a [u8]> {
        if self.0.len() < len {
            bail!("truncated record");
        }
        let (a, b) = self.0.split_at(len);
        self.0 = b;
        Ok(a)
    }
    fn u32(&mut self) -> Result<u32> {
        Ok(u32::from_be_bytes(self.take(4)?.try_into()?))
    }
    fn u64(&mut self) -> Result<u64> {
        Ok(u64::from_be_bytes(self.take(8)?.try_into()?))
    }
}
