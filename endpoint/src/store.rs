//! Serializable libsignal store callbacks. The owner commits the WHOLE snapshot
//! after a successful operation, never individual callbacks or partial records.
use crate::bytes::Bytes;
use async_trait::async_trait;
use libsignal_protocol::*;
use rand::{Rng, TryRngCore, rngs::OsRng};
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;

type Result<T> = std::result::Result<T, SignalProtocolError>;

#[derive(Clone, Serialize, Deserialize)]
pub struct IdentityStore {
    private: Bytes,
    registration: u32,
    known: BTreeMap<String, Bytes>,
}
impl IdentityStore {
    pub fn new() -> Self {
        let mut rng = OsRng.unwrap_err();
        let key = IdentityKeyPair::generate(&mut rng);
        Self {
            private: Bytes(key.serialize().to_vec()),
            registration: rng.random::<u32>() % 16383 + 1,
            known: BTreeMap::new(),
        }
    }
    pub fn pair(&self) -> Result<IdentityKeyPair> {
        IdentityKeyPair::try_from(self.private.0.as_slice())
    }
    pub fn registration(&self) -> u32 {
        self.registration
    }
    pub fn pin(&mut self, address: &ProtocolAddress, key: &IdentityKey) -> Result<()> {
        if let Some(existing) = self.known.get(address.name())
            && existing.0 != key.serialize().as_ref()
        {
            return Err(SignalProtocolError::UntrustedIdentity(address.clone()));
        }
        self.known
            .insert(address.name().to_owned(), Bytes(key.serialize().to_vec()));
        Ok(())
    }
    pub fn retire(&mut self, address: &ProtocolAddress) {
        self.known.remove(address.name());
    }
}
impl Default for IdentityStore {
    fn default() -> Self {
        Self::new()
    }
}

#[async_trait(?Send)]
impl IdentityKeyStore for IdentityStore {
    async fn get_identity_key_pair(&self) -> Result<IdentityKeyPair> {
        self.pair()
    }
    async fn get_local_registration_id(&self) -> Result<u32> {
        Ok(self.registration)
    }
    async fn save_identity(
        &mut self,
        address: &ProtocolAddress,
        key: &IdentityKey,
    ) -> Result<IdentityChange> {
        // The pairing layer must already have pinned it. No implicit TOFU.
        if !self
            .is_trusted_identity(address, key, Direction::Sending)
            .await?
        {
            return Err(SignalProtocolError::UntrustedIdentity(address.clone()));
        }
        Ok(IdentityChange::NewOrUnchanged)
    }
    async fn is_trusted_identity(
        &self,
        address: &ProtocolAddress,
        key: &IdentityKey,
        _: Direction,
    ) -> Result<bool> {
        Ok(self
            .known
            .get(address.name())
            .is_some_and(|bytes| bytes.0 == key.serialize().as_ref()))
    }
    async fn get_identity(&self, address: &ProtocolAddress) -> Result<Option<IdentityKey>> {
        self.known
            .get(address.name())
            .map(|value| IdentityKey::decode(&value.0))
            .transpose()
    }
}

#[derive(Clone, Default, Serialize, Deserialize)]
pub struct Sessions(pub BTreeMap<String, Bytes>);
#[async_trait(?Send)]
impl SessionStore for Sessions {
    async fn load_session(&self, address: &ProtocolAddress) -> Result<Option<SessionRecord>> {
        self.0
            .get(address.name())
            .map(|value| SessionRecord::deserialize(&value.0))
            .transpose()
    }
    async fn store_session(
        &mut self,
        address: &ProtocolAddress,
        record: &SessionRecord,
    ) -> Result<()> {
        let bytes = record.serialize()?;
        if bytes.len() > 512 * 1024 {
            return Err(SignalProtocolError::InvalidState(
                "store_session",
                "session exceeds debug storage bound".into(),
            ));
        }
        self.0.insert(address.name().to_owned(), Bytes(bytes));
        Ok(())
    }
}

#[derive(Clone, Default, Serialize, Deserialize)]
pub struct Records(pub BTreeMap<u32, Bytes>);
#[async_trait(?Send)]
impl PreKeyStore for Records {
    async fn get_pre_key(&self, id: PreKeyId) -> Result<PreKeyRecord> {
        PreKeyRecord::deserialize(
            &self
                .0
                .get(&u32::from(id))
                .ok_or(SignalProtocolError::InvalidPreKeyId)?
                .0,
        )
    }
    async fn save_pre_key(&mut self, id: PreKeyId, record: &PreKeyRecord) -> Result<()> {
        self.0.insert(id.into(), Bytes(record.serialize()?));
        Ok(())
    }
    async fn remove_pre_key(&mut self, id: PreKeyId) -> Result<()> {
        self.0.remove(&id.into());
        Ok(())
    }
}
#[async_trait(?Send)]
impl SignedPreKeyStore for Records {
    async fn get_signed_pre_key(&self, id: SignedPreKeyId) -> Result<SignedPreKeyRecord> {
        SignedPreKeyRecord::deserialize(
            &self
                .0
                .get(&u32::from(id))
                .ok_or(SignalProtocolError::InvalidSignedPreKeyId)?
                .0,
        )
    }
    async fn save_signed_pre_key(
        &mut self,
        id: SignedPreKeyId,
        record: &SignedPreKeyRecord,
    ) -> Result<()> {
        self.0.insert(id.into(), Bytes(record.serialize()?));
        Ok(())
    }
}
#[async_trait(?Send)]
impl KyberPreKeyStore for Records {
    async fn get_kyber_pre_key(&self, id: KyberPreKeyId) -> Result<KyberPreKeyRecord> {
        KyberPreKeyRecord::deserialize(
            &self
                .0
                .get(&u32::from(id))
                .ok_or(SignalProtocolError::InvalidKyberPreKeyId)?
                .0,
        )
    }
    async fn save_kyber_pre_key(
        &mut self,
        id: KyberPreKeyId,
        record: &KyberPreKeyRecord,
    ) -> Result<()> {
        self.0.insert(id.into(), Bytes(record.serialize()?));
        Ok(())
    }
    async fn mark_kyber_pre_key_used(
        &mut self,
        id: KyberPreKeyId,
        _: SignedPreKeyId,
        _: &PublicKey,
    ) -> Result<()> {
        self.0
            .remove(&id.into())
            .ok_or(SignalProtocolError::InvalidKyberPreKeyId)?;
        Ok(())
    }
}

#[derive(Clone, Default, Serialize, Deserialize)]
pub struct CryptoStore {
    pub identity: IdentityStore,
    pub sessions: Sessions,
    pub prekeys: Records,
    pub signed: Records,
    pub pq: Records,
}
