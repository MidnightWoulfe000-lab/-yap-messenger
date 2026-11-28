//! Transactional endpoint state machine. The host must commit the returned state
//! before publishing any of its results. Errors discard the entire staged state.
use crate::{
    bytes::{self, Bytes},
    pairing::{self, PublicBundle},
    store::CryptoStore,
};
use anyhow::{Result, bail, ensure};
use libsignal_protocol::*;
use rand::{TryRngCore, rngs::OsRng};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::{
    collections::BTreeMap,
    time::{Duration, UNIX_EPOCH},
};
use yap_core::envelope::{Envelope, Header, MAX_LIFETIME_MS, Token};

pub const SNAPSHOT_MAX: usize = 8 * 1024 * 1024 - 64 * 1024; // reserve host settings/format overhead
#[derive(Clone, Serialize, Deserialize)]
pub struct State {
    version: u8,
    pub crypto: CryptoStore,
    route: Token,
    next_key: u32,
    next_ui: u64,
    last_time: u64,
    offers: BTreeMap<String, Offer>,
    current_offer: Option<String>,
    contacts: BTreeMap<String, Contact>,
    messages: Vec<StoredMessage>,
    outbox: BTreeMap<String, Pending>,
    read: BTreeMap<String, u64>,
    texts: BTreeMap<String, Accepted>,
    bootstraps: BTreeMap<String, Accepted>,
    controls: BTreeMap<String, Seen>,
    retry_windows: BTreeMap<String, RetryWindow>,
}
#[derive(Clone, Serialize, Deserialize)]
struct Offer {
    public: Bytes,
    bound: Option<String>,
}
#[derive(Clone, Serialize, Deserialize)]
struct Contact {
    name: String,
    local: Bytes,
    remote: Bytes,
    confirmation: Bytes,
    remote_confirmation: Option<Bytes>,
    ready: bool,
    phase: String,
    last_confirm: u64,
    /// When our confirmation was last handed to the host to send, newest last
    /// (at most four within a minute). Older snapshots start empty.
    #[serde(default)]
    confirm_sends: Vec<u64>,
    setup_id: Option<Token>,
    created: u64,
}
/// The contract allows four confirmation sends a minute. Retransmissions use at
/// most three, so one is always left to answer the peer's first valid
/// confirmation straight away. Without that reserve, a phone that scanned first
/// had spent its allowance on copies the other phone could not yet accept, and
/// answered the other phone's confirmation only at its next retransmission.
const CONFIRM_SENDS_PER_MINUTE: usize = 4;
const CONFIRM_RETRIES_PER_MINUTE: usize = 3;
const CONFIRM_RETRY_MS: u64 = 15_000;
#[derive(PartialEq)]
enum ConfirmSend {
    /// A periodic retransmission from `pending`.
    Retry,
    /// Answering a repeated confirmation: the peer still lacks ours.
    Answer,
    /// Answering the first valid confirmation, which completed mutual proof here.
    FirstAnswer,
}
impl Contact {
    /// Takes one send from the confirmation budget, if there is one left.
    fn take_confirm_send(&mut self, kind: ConfirmSend, now: u64) -> bool {
        self.confirm_sends
            .retain(|sent| *sent <= now && now - sent < 60_000);
        let limit = if kind == ConfirmSend::Retry {
            CONFIRM_RETRIES_PER_MINUTE
        } else {
            CONFIRM_SENDS_PER_MINUTE
        };
        let spaced = kind == ConfirmSend::FirstAnswer
            || now.saturating_sub(self.last_confirm) >= CONFIRM_RETRY_MS;
        if self.confirm_sends.len() >= limit || !spaced {
            return false;
        }
        self.confirm_sends.push(now);
        self.last_confirm = now;
        true
    }
}
#[derive(Clone, Serialize, Deserialize)]
struct Pending {
    contact: String,
    bytes: Bytes,
    kind: u8,
    expires: u64,
    created: u64,
    last_sent: u64,
    attempts: u32,
}
#[derive(Clone, Serialize, Deserialize)]
struct Accepted {
    digest: Bytes,
    response: Bytes,
    expires: u64,
    last_scheduled: u64,
}
#[derive(Clone, Serialize, Deserialize)]
struct Seen {
    digest: Bytes,
    expires: u64,
    at: u64,
}
#[derive(Clone, Serialize, Deserialize)]
struct RetryWindow {
    start: u64,
    count: u32,
}
#[derive(Clone, Serialize, Deserialize)]
pub struct StoredMessage {
    pub id: String,
    pub ui_id: u64,
    pub contact: String,
    pub mine: bool,
    pub body: String,
    pub created: u64,
    pub receipt_at: Option<u64>,
    pub status: String,
    pub generation: String,
    pub acknowledged_at: Option<u64>,
}

impl State {
    pub async fn new(now: u64) -> Result<Self> {
        let mut result = Self {
            version: 1,
            crypto: CryptoStore::default(),
            route: pairing::random_token(),
            next_key: 1,
            next_ui: 1,
            last_time: now,
            offers: BTreeMap::new(),
            current_offer: None,
            contacts: BTreeMap::new(),
            messages: Vec::new(),
            outbox: BTreeMap::new(),
            read: BTreeMap::new(),
            texts: BTreeMap::new(),
            bootstraps: BTreeMap::new(),
            controls: BTreeMap::new(),
            retry_windows: BTreeMap::new(),
        };
        result.make_offer(now).await?;
        Ok(result)
    }
    fn local_id(&self) -> Result<String> {
        Ok(pairing::contact_id(
            &self.crypto.identity.pair()?.identity_key().serialize(),
        ))
    }
    fn control_count(&self) -> usize {
        self.outbox.values().filter(|v| v.kind != 1).count()
    }
    fn text_count(&self) -> usize {
        self.outbox.values().filter(|v| v.kind == 1).count()
    }
    fn generation(contact: &Contact) -> Result<String> {
        Ok(bytes::hex(&PublicBundle::decode(&contact.local.0)?.hint))
    }
    fn dedup_key(id: &str, contact: &Contact, message: Token) -> Result<String> {
        Ok(format!(
            "{id}:{}:{}",
            Self::generation(contact)?,
            bytes::hex(&message)
        ))
    }
    fn check(&self, now: u64) -> Result<()> {
        ensure!(self.version == 1, "unsupported endpoint state");
        ensure!(
            now >= self.last_time,
            "clock moved backwards; correct the clock to reopen this identity"
        );
        ensure!(
            self.contacts.len() <= 64
                && self.offers.len() <= 68
                && self.messages.len() <= 512
                && self.outbox.len() <= 512
                && self.texts.len() <= 4096
                && self.controls.len() <= 512
                && self.bootstraps.len() <= 64,
            "endpoint exceeds state limits"
        );
        Ok(())
    }
    fn prune(&mut self, now: u64) -> Result<()> {
        let expired: Vec<String> = self
            .offers
            .iter()
            .filter_map(|(nonce, offer)| {
                let bundle = PublicBundle::decode(&offer.public.0).ok()?;
                let mutual = offer
                    .bound
                    .as_ref()
                    .and_then(|id| self.contacts.get(id))
                    .is_some_and(|c| c.remote_confirmation.is_some());
                let deadline = if mutual {
                    bundle.created.saturating_add(MAX_LIFETIME_MS)
                } else {
                    bundle.expires
                };
                (deadline <= now).then(|| nonce.clone())
            })
            .collect();
        for nonce in expired {
            if let Some(offer) = self.offers.remove(&nonce) {
                pairing::retire_offer_keys(&mut self.crypto, &offer.public.0)?;
                if let Some(id) = offer.bound {
                    if let Some(contact) = self.contacts.get_mut(&id)
                        && !contact.ready
                    {
                        contact.phase = "Pairing expired; retire and pair again".into();
                    }
                    self.crypto.sessions.0.remove(&id);
                    self.crypto.identity.retire(&pairing::address(&id));
                    self.outbox.retain(|_, p| p.contact != id);
                }
                if self.current_offer.as_ref() == Some(&nonce) {
                    self.current_offer = None;
                }
            }
        }
        let expired: Vec<String> = self
            .outbox
            .iter()
            .filter(|(_, pending)| pending.expires <= now)
            .map(|(id, _)| id.clone())
            .collect();
        for id in expired {
            self.outbox.remove(&id);
            if let Some(m) = self
                .messages
                .iter_mut()
                .find(|m| m.mine && m.id == id && m.receipt_at.is_none())
            {
                m.status = "Expired without recipient confirmation".into();
            }
        }
        self.texts.retain(|_, v| v.expires > now);
        self.controls.retain(|_, v| v.expires > now);
        self.bootstraps.retain(|_, v| v.expires > now);
        self.retry_windows
            .retain(|_, v| now.saturating_sub(v.start) < 60_000);
        Ok(())
    }
    async fn make_offer(&mut self, now: u64) -> Result<()> {
        if let Some(nonce) = self.current_offer.clone()
            && self.offers.get(&nonce).is_some_and(|o| o.bound.is_none())
        {
            let old = self.offers.remove(&nonce).unwrap();
            pairing::retire_offer_keys(&mut self.crypto, &old.public.0)?;
            self.current_offer = None;
        }
        ensure!(
            self.offers.values().filter(|o| o.bound.is_none()).count() < 4,
            "too many unbound pairing attempts"
        );
        let id = self.next_key;
        self.next_key = self
            .next_key
            .checked_add(1)
            .ok_or_else(|| anyhow::anyhow!("prekey identifiers exhausted"))?;
        let bundle = PublicBundle::create(&mut self.crypto, id, self.route, now).await?;
        let nonce = bytes::hex(&bundle.nonce);
        self.offers.insert(
            nonce.clone(),
            Offer {
                public: Bytes(bundle.encode()?),
                bound: None,
            },
        );
        self.current_offer = Some(nonce);
        Ok(())
    }
    async fn pair(&mut self, qr: &str, raw: &[u8], name: &str, now: u64) -> Result<Value> {
        ensure!(name.chars().count() <= 80, "contact name is too long");
        let remote = PublicBundle::decode(raw)?;
        remote.check_qr(qr)?;
        ensure!(
            remote.created <= now.saturating_add(300_000) && remote.expires > now,
            "pairing offer expired or clock differs"
        );
        remote.validate()?;
        let id = remote.id();
        ensure!(id != self.local_id()?, "cannot pair with yourself");
        if let Some(existing) = self.contacts.get(&id) {
            ensure!(
                existing.remote.0 == raw
                    && !existing.ready
                    && !existing.phase.starts_with("Pairing expired"),
                "retire the existing session explicitly before re-pairing"
            );
            return Ok(
                json!({"contact":id,"confirmation":bytes::encode(&existing.confirmation.0)}),
            );
        }
        ensure!(self.contacts.len() < 64, "contact storage is full");
        let nonce = self
            .current_offer
            .clone()
            .ok_or_else(|| anyhow::anyhow!("create a fresh pairing code first"))?;
        let offer = self
            .offers
            .get_mut(&nonce)
            .ok_or_else(|| anyhow::anyhow!("pairing offer missing"))?;
        ensure!(
            offer.bound.is_none(),
            "this pairing attempt is already reserved; create a fresh code"
        );
        let local = PublicBundle::decode(&offer.public.0)?;
        ensure!(
            local.expires > now,
            "your pairing offer expired; create a fresh code"
        );
        ensure!(
            self.contacts
                .values()
                .all(|c| PublicBundle::decode(&c.local.0).is_ok_and(|b| b.hint != local.hint)),
            "duplicate receiver hint"
        );
        let confirmation = pairing::confirmation(&self.crypto, &offer.public.0, raw)?;
        offer.bound = Some(id.clone());
        let contact = Contact {
            name: if name.trim().is_empty() {
                format!("Contact {}", &id[..8])
            } else {
                name.trim().to_owned()
            },
            local: offer.public.clone(),
            remote: Bytes(raw.to_vec()),
            confirmation: Bytes(confirmation.clone()),
            remote_confirmation: None,
            ready: false,
            phase: "Waiting for mutual confirmation".into(),
            // The host sends this confirmation as soon as pairing returns.
            last_confirm: now,
            confirm_sends: vec![now],
            setup_id: None,
            created: now,
        };
        self.contacts.insert(id.clone(), contact);
        Ok(json!({"contact":id,"confirmation":bytes::encode(&confirmation)}))
    }
    async fn confirm(&mut self, raw: &[u8], now: u64) -> Result<Value> {
        ensure!(
            raw.len() == 101 && &raw[..4] == b"YPC1",
            "invalid confirmation"
        );
        let id = pairing::contact_id(&raw[4..37]);
        let mut contact = self
            .contacts
            .get(&id)
            .cloned()
            .ok_or_else(|| anyhow::anyhow!("scan and confirm this peer first"))?;
        ensure!(
            !contact.phase.starts_with("Pairing expired"),
            "retire the expired pairing before retrying"
        );
        if !contact.ready {
            ensure!(
                contact.created.saturating_add(pairing::OFFER_MS) > now
                    || contact.remote_confirmation.is_some(),
                "pairing attempt expired"
            );
        }
        pairing::verify_confirmation(raw, &contact.local.0, &contact.remote.0)?;
        let first = contact.remote_confirmation.is_none();
        if first {
            ensure!(
                PublicBundle::decode(&contact.local.0)?.expires > now
                    && PublicBundle::decode(&contact.remote.0)?.expires > now,
                "pairing proof expired; retire and pair again"
            );
        }
        contact.remote_confirmation = Some(Bytes(raw.to_vec()));
        self.crypto.identity.pin(
            &pairing::address(&id),
            &IdentityKey::decode(&PublicBundle::decode(&contact.remote.0)?.identity)?,
        )?;
        if first {
            contact.phase = "Establishing encrypted session".into();
            let local = PublicBundle::decode(&contact.local.0)?;
            let remote = PublicBundle::decode(&contact.remote.0)?;
            if local.identity < remote.identity {
                let local_address = pairing::address(&self.local_id()?);
                process_prekey_bundle(
                    &pairing::address(&id),
                    &local_address,
                    &mut self.crypto.sessions,
                    &mut self.crypto.identity,
                    &remote.prekey_bundle()?,
                    UNIX_EPOCH + Duration::from_millis(now),
                    &mut OsRng.unwrap_err(),
                )
                .await?;
                let envelope = self.encrypt(&id, &contact, 3, &[], now).await?;
                contact.setup_id = Some(envelope.header().id);
                self.queue(&id, envelope, 3, now)?;
            }
        }
        // Answer their proof with ours, stored exact bytes only. The first valid
        // confirmation is always answered at once: until then the peer may have
        // dropped every copy of ours, because it had not scanned us yet.
        let kind = if first {
            ConfirmSend::FirstAnswer
        } else {
            ConfirmSend::Answer
        };
        let confirmation = contact
            .take_confirm_send(kind, now)
            .then(|| bytes::encode(&contact.confirmation.0));
        self.contacts.insert(id, contact);
        Ok(json!({"confirmation":confirmation}))
    }
    async fn encrypt(
        &mut self,
        id: &str,
        contact: &Contact,
        kind: u8,
        payload: &[u8],
        now: u64,
    ) -> Result<Envelope> {
        let remote = PublicBundle::decode(&contact.remote.0)?;
        let header = Header {
            id: pairing::random_token(),
            destination: remote.route,
            sender_hint: remote.hint,
            created_ms: now,
            expires_ms: now
                .checked_add(MAX_LIFETIME_MS)
                .ok_or_else(|| anyhow::anyhow!("clock overflow"))?,
            hops: 8,
        };
        let mut plain = b"YEP1".to_vec();
        plain.extend_from_slice(&header.binding());
        plain.push(kind);
        if kind == 1 {
            plain.extend_from_slice(&(payload.len() as u32).to_be_bytes());
        }
        plain.extend_from_slice(payload);
        let local_address = pairing::address(&self.local_id()?);
        let message = message_encrypt(
            &plain,
            &pairing::address(id),
            &local_address,
            &mut self.crypto.sessions,
            &mut self.crypto.identity,
            UNIX_EPOCH + Duration::from_millis(now),
            &mut OsRng.unwrap_err(),
        )
        .await?;
        let mut cipher = vec![message.message_type() as u8];
        cipher.extend_from_slice(message.serialize());
        Envelope::new(header, cipher)
            .map_err(|_| anyhow::anyhow!("encrypted message exceeds envelope bound"))
    }
    fn queue(&mut self, id: &str, envelope: Envelope, kind: u8, now: u64) -> Result<()> {
        let key = bytes::hex(&envelope.header().id);
        if self.outbox.contains_key(&key) {
            return Ok(());
        }
        ensure!(
            self.outbox.len() < 512
                && (if kind == 1 {
                    self.text_count() < 448
                } else {
                    self.control_count() < 64
                }),
            "outgoing storage full; retry later"
        );
        self.outbox.insert(
            key,
            Pending {
                contact: id.to_owned(),
                bytes: Bytes(envelope.encode()),
                kind,
                expires: envelope.header().expires_ms,
                created: now,
                last_sent: 0,
                attempts: 0,
            },
        );
        Ok(())
    }
    async fn send(&mut self, id: &str, text: &str, now: u64) -> Result<Value> {
        ensure!(
            !text.is_empty() && text.len() <= 4096,
            "messages must be 1–4096 UTF-8 bytes"
        );
        ensure!(
            self.messages.len() < 512 && self.text_count() < 448,
            "message storage is full; clear visible history or wait for receipts"
        );
        let contact = self
            .contacts
            .get(id)
            .cloned()
            .ok_or_else(|| anyhow::anyhow!("contact not found"))?;
        ensure!(contact.ready, "mutual pairing is not complete");
        let envelope = self.encrypt(id, &contact, 1, text.as_bytes(), now).await?;
        let message_id = bytes::hex(&envelope.header().id);
        self.messages.push(StoredMessage {
            id: message_id.clone(),
            ui_id: self.next_ui,
            contact: id.to_owned(),
            mine: true,
            body: text.to_owned(),
            created: now,
            receipt_at: None,
            status: "Queued".into(),
            generation: Self::generation(&contact)?,
            acknowledged_at: None,
        });
        self.next_ui += 1;
        self.queue(id, envelope, 1, now)?;
        Ok(json!({"message":message_id}))
    }
    fn ready(&mut self, id: &str) -> Result<()> {
        let contact = self
            .contacts
            .get_mut(id)
            .ok_or_else(|| anyhow::anyhow!("contact missing"))?;
        if !contact.ready {
            contact.ready = true;
            contact.phase = "Paired and verified".into();
            let local = PublicBundle::decode(&contact.local.0)?;
            pairing::retire_offer_keys(&mut self.crypto, &contact.local.0)?;
            let nonce = bytes::hex(&local.nonce);
            self.offers.remove(&nonce);
            if self.current_offer.as_ref() == Some(&nonce) {
                self.current_offer = None;
            }
        }
        Ok(())
    }
    fn cached_response(
        &mut self,
        id: &str,
        cache_key: &str,
        bootstrap: bool,
        now: u64,
    ) -> Result<()> {
        let record = if bootstrap {
            self.bootstraps.get(cache_key)
        } else {
            self.texts.get(cache_key)
        }
        .cloned()
        .ok_or_else(|| anyhow::anyhow!("missing acceptance"))?;
        if now.saturating_sub(record.last_scheduled) < 10_000 {
            return Ok(());
        }
        if self.retry_windows.get(id).is_some_and(|w| w.count >= 6)
            || self
                .retry_windows
                .get("global")
                .is_some_and(|w| w.count >= 64)
            || self.control_count() >= 64
        {
            return Ok(());
        }
        let envelope = Envelope::decode(&record.response.0)
            .map_err(|_| anyhow::anyhow!("corrupt stored receipt"))?;
        let kind = if bootstrap { 4 } else { 2 };
        self.queue(id, envelope, kind, now)?;
        for name in [id, "global"] {
            self.retry_windows
                .entry(name.to_owned())
                .or_insert(RetryWindow {
                    start: now,
                    count: 0,
                })
                .count += 1;
        }
        if bootstrap {
            self.bootstraps.get_mut(cache_key).unwrap().last_scheduled = now;
        } else {
            self.texts.get_mut(cache_key).unwrap().last_scheduled = now;
        }
        Ok(())
    }
    async fn receive(&mut self, raw: &[u8], now: u64) -> Result<Value> {
        let envelope = Envelope::decode(raw).map_err(|_| anyhow::anyhow!("malformed envelope"))?;
        let header = envelope.header();
        ensure!(
            header.destination == self.route,
            "envelope is for another device"
        );
        ensure!(
            header.expires_ms > now && header.created_ms <= now.saturating_add(300_000),
            "envelope expired or future-dated"
        );
        let (id, contact) = self
            .contacts
            .iter()
            .find(|(_, c)| {
                PublicBundle::decode(&c.local.0).is_ok_and(|b| b.hint == header.sender_hint)
            })
            .map(|(id, c)| (id.clone(), c.clone()))
            .ok_or_else(|| anyhow::anyhow!("unknown sender hint"))?;
        ensure!(
            contact.remote_confirmation.is_some(),
            "mutual verification is not complete"
        );
        ensure!(
            !contact.phase.starts_with("Pairing expired"),
            "pairing expired; retire and pair again"
        );
        ensure!(
            contact.ready || contact.created.saturating_add(MAX_LIFETIME_MS) > now,
            "pairing expired; retire and pair again"
        );
        let cache_key = Self::dedup_key(&id, &contact, header.id)?;
        let key = envelope.relay_key();
        if let Some(accepted) = self.texts.get(&cache_key)
            && accepted.digest.0 == key
        {
            self.cached_response(&id, &cache_key, false, now)?;
            return Ok(json!({"custody":bytes::encode(&key),"duplicate":true}));
        }
        if let Some(accepted) = self.bootstraps.get(&cache_key)
            && accepted.digest.0 == key
        {
            self.cached_response(&id, &cache_key, true, now)?;
            return Ok(json!({"custody":bytes::encode(&key),"duplicate":true}));
        }
        if self
            .controls
            .get(&cache_key)
            .is_some_and(|accepted| accepted.digest.0 == key)
        {
            return Ok(json!({"custody":bytes::encode(&key),"duplicate":true}));
        }
        let cipher = envelope.ciphertext();
        let message = match cipher.first() {
            Some(2) => CiphertextMessage::SignalMessage(SignalMessage::try_from(&cipher[1..])?),
            Some(3) => {
                CiphertextMessage::PreKeySignalMessage(PreKeySignalMessage::try_from(&cipher[1..])?)
            }
            _ => bail!("unsupported ciphertext type"),
        };
        let local_address = pairing::address(&self.local_id()?);
        let plain = message_decrypt(
            &message,
            &pairing::address(&id),
            &local_address,
            &mut self.crypto.sessions,
            &mut self.crypto.identity,
            &mut self.crypto.prekeys,
            &self.crypto.signed,
            &mut self.crypto.pq,
            &mut OsRng.unwrap_err(),
        )
        .await?;
        ensure!(
            plain.len() >= 73 && &plain[..4] == b"YEP1" && plain[4..72] == header.binding(),
            "authenticated metadata mismatch"
        );
        ensure!(
            !self.texts.contains_key(&cache_key)
                && !self.bootstraps.contains_key(&cache_key)
                && !self.controls.contains_key(&cache_key),
            "authenticated message ID collision"
        );
        let kind = plain[72];
        let payload = &plain[73..];
        match kind {
            1 => {
                ensure!(
                    contact.ready || contact.setup_id.is_some(),
                    "session bootstrap is required first"
                );
                ensure!(payload.len() >= 4, "truncated text");
                let length = u32::from_be_bytes(payload[..4].try_into()?) as usize;
                ensure!(
                    length > 0 && length <= 4096 && payload.len() == 4 + length,
                    "invalid text length"
                );
                let text = std::str::from_utf8(&payload[4..])?;
                ensure!(
                    self.messages.len() < 512
                        && self.texts.len() < 4096
                        && self.control_count() < 64,
                    "incoming text storage is full"
                );
                let receipt = self.encrypt(&id, &contact, 2, &header.id, now).await?;
                self.texts.insert(
                    cache_key.clone(),
                    Accepted {
                        digest: Bytes(key.to_vec()),
                        response: Bytes(receipt.encode()),
                        expires: header.expires_ms,
                        last_scheduled: now,
                    },
                );
                self.queue(&id, receipt, 2, now)?;
                self.messages.push(StoredMessage {
                    id: bytes::hex(&header.id),
                    ui_id: self.next_ui,
                    contact: id.clone(),
                    mine: false,
                    body: text.to_owned(),
                    created: header.created_ms,
                    receipt_at: None,
                    status: "Received · verified contact".into(),
                    generation: Self::generation(&contact)?,
                    acknowledged_at: None,
                });
                self.next_ui += 1;
                self.ready(&id)?;
            }
            2 => {
                ensure!(payload.len() == 16, "invalid receipt");
                let original = bytes::hex(payload);
                let generation = Self::generation(&contact)?;
                let queued = self
                    .outbox
                    .get(&original)
                    .is_some_and(|p| p.contact == id && p.kind == 1);
                let message = self.messages.iter_mut().find(|m| {
                    m.mine && m.contact == id && m.id == original && m.generation == generation
                });
                ensure!(
                    queued || message.is_some(),
                    "receipt does not match a sent message"
                );
                if let Some(message) = message
                    && message.receipt_at.is_none()
                {
                    message.receipt_at = Some(now);
                    message.status = "Delivered".into();
                }
                self.outbox.remove(&original);
                self.remember_control(cache_key.clone(), key, header.expires_ms, now);
                self.ready(&id)?;
            }
            3 => {
                ensure!(payload.is_empty(), "invalid bootstrap");
                let remote = PublicBundle::decode(&contact.remote.0)?;
                let local = PublicBundle::decode(&contact.local.0)?;
                ensure!(
                    remote.identity < local.identity
                        && !contact.ready
                        && !self
                            .bootstraps
                            .keys()
                            .any(|k| k.starts_with(&format!("{id}:"))),
                    "unexpected session bootstrap"
                );
                let ack = self.encrypt(&id, &contact, 4, &header.id, now).await?;
                self.bootstraps.insert(
                    cache_key.clone(),
                    Accepted {
                        digest: Bytes(key.to_vec()),
                        response: Bytes(ack.encode()),
                        expires: header.expires_ms,
                        last_scheduled: now,
                    },
                );
                self.queue(&id, ack, 4, now)?;
                self.ready(&id)?;
            }
            4 => {
                ensure!(
                    payload.len() == 16 && contact.setup_id.is_some_and(|v| v == payload),
                    "invalid bootstrap acknowledgement"
                );
                self.outbox.remove(&bytes::hex(payload));
                self.remember_control(cache_key.clone(), key, header.expires_ms, now);
                self.ready(&id)?;
            }
            _ => bail!("unsupported authenticated message kind"),
        }
        Ok(json!({"custody":bytes::encode(&key),"duplicate":false}))
    }
    fn remember_control(&mut self, key: String, digest: [u8; 48], expires: u64, now: u64) {
        while self.controls.len() >= 512 {
            if let Some(oldest) = self
                .controls
                .iter()
                .min_by_key(|(_, v)| v.at)
                .map(|(k, _)| k.clone())
            {
                self.controls.remove(&oldest);
            } else {
                break;
            }
        }
        self.controls.insert(
            key,
            Seen {
                digest: Bytes(digest.to_vec()),
                expires,
                at: now,
            },
        );
    }
    fn pending(&mut self, now: u64, force: bool) -> Result<Value> {
        let mut packets = Vec::new();
        for contact in self.contacts.values_mut() {
            if packets.len() >= 32 {
                break;
            }
            if !contact.ready
                && !contact.phase.starts_with("Pairing expired")
                && contact.take_confirm_send(ConfirmSend::Retry, now)
            {
                let remote = PublicBundle::decode(&contact.remote.0)?;
                packets.push(json!({"kind":"confirmation","destination":bytes::encode(&remote.route),"bytes":bytes::encode(&contact.confirmation.0)}));
            }
        }
        let mut order: Vec<_> = self
            .outbox
            .iter()
            .map(|(id, p)| (id.clone(), p.kind == 1, p.created))
            .collect();
        order.sort_by_key(|(_, data, time)| (*data, *time));
        for (id, _, _) in order {
            if packets.len() >= 32 {
                break;
            }
            let pending = self.outbox.get_mut(&id).unwrap();
            let seconds = (1u64 << pending.attempts.min(6)).min(60);
            if force
                || pending.last_sent == 0
                || now.saturating_sub(pending.last_sent) >= seconds * 1000
            {
                let envelope = Envelope::decode(&pending.bytes.0)
                    .map_err(|_| anyhow::anyhow!("corrupt outbox envelope"))?;
                packets.push(json!({"kind":"envelope","destination":bytes::encode(&envelope.header().destination),"key":bytes::encode(&envelope.relay_key()),"bytes":bytes::encode(&pending.bytes.0)}));
                pending.last_sent = now;
                pending.attempts = pending.attempts.saturating_add(1);
            }
        }
        Ok(json!({"packets":packets}))
    }
    fn ack(&mut self, key: &[u8], now: u64) -> Result<Value> {
        ensure!(key.len() == 48, "invalid custody key");
        let found = self.outbox.iter().find_map(|(id, p)| {
            Envelope::decode(&p.bytes.0)
                .ok()
                .filter(|e| e.relay_key() == key)
                .map(|_| (id.clone(), p.kind))
        });
        if let Some((id, kind)) = found {
            if kind == 1 {
                if let Some(m) = self
                    .messages
                    .iter_mut()
                    .find(|m| m.id == id && m.mine && m.receipt_at.is_none())
                {
                    m.status = "Relayed · awaiting recipient".into();
                    m.acknowledged_at = Some(now);
                }
            } else if kind != 3 {
                self.outbox.remove(&id);
            }
        }
        Ok(json!({}))
    }
    fn retire(&mut self, id: &str) -> Result<()> {
        let contact = self
            .contacts
            .remove(id)
            .ok_or_else(|| anyhow::anyhow!("contact not found"))?;
        self.crypto.sessions.0.remove(id);
        self.crypto.identity.retire(&pairing::address(id));
        let local = PublicBundle::decode(&contact.local.0)?;
        pairing::retire_offer_keys(&mut self.crypto, &contact.local.0)?;
        let nonce = bytes::hex(&local.nonce);
        self.offers.remove(&nonce);
        if self.current_offer.as_ref() == Some(&nonce) {
            self.current_offer = None;
        }
        self.outbox.retain(|_, p| p.contact != id);
        self.bootstraps
            .retain(|k, _| !k.starts_with(&format!("{id}:")));
        self.retry_windows.remove(id);
        self.read.remove(id);
        for m in &mut self.messages {
            if m.contact == id && m.mine && m.receipt_at.is_none() {
                m.status = "Session retired · unconfirmed".into();
            }
        }
        Ok(())
    }
    pub fn view(&self) -> Result<Value> {
        let identity = self.crypto.identity.pair()?;
        let fingerprint = pairing::contact_id(&identity.identity_key().serialize());
        let offer = self
            .current_offer
            .as_ref()
            .and_then(|id| self.offers.get(id));
        let offer=offer.map(|offer|->Result<Value>{let b=PublicBundle::decode(&offer.public.0)?;
            Ok(json!({"qr":b.qr()?,"bundle":bytes::encode(&offer.public.0),"expires":b.expires,"reserved":offer.bound.is_some(),
                "package":json!({"qr":b.qr()?,"bundle":bytes::encode(&offer.public.0)}).to_string()}))}).transpose()?;
        let contacts=self.contacts.iter().map(|(id,c)|->Result<Value>{let remote=PublicBundle::decode(&c.remote.0)?;
            let unread=self.messages.iter().filter(|m|m.contact==*id && !m.mine && m.ui_id>*self.read.get(id).unwrap_or(&0)).count();
            let local=PublicBundle::decode(&c.local.0)?;Ok(json!({"id":id,"name":c.name,"ready":c.ready,"phase":c.phase,"unread":unread,
                "fingerprint":id,"route":bytes::encode(&remote.route),"hint":bytes::encode(&local.hint),
                "package":json!({"qr":local.qr()?,"bundle":bytes::encode(&c.local.0),"confirmation":bytes::encode(&c.confirmation.0)}).to_string()}))}).collect::<Result<Vec<_>>>()?;
        Ok(
            json!({"identity":fingerprint,"route":bytes::encode(&self.route),"route_bytes":self.route,"offer":offer,
            "contacts":contacts,"messages":self.messages,"queued":self.outbox.len(),"version":1}),
        )
    }
}

pub async fn transact(
    state: Option<State>,
    op: &str,
    args: &Value,
    now: u64,
) -> Result<(State, Value)> {
    let mut state = match state {
        Some(value) => value,
        None => {
            ensure!(
                op == "init",
                "endpoint state missing; identity reset is not automatic"
            );
            State::new(now).await?
        }
    };
    state.check(now)?;
    state.prune(now)?;
    let text = |key: &str| -> Result<&str> {
        args.get(key)
            .and_then(Value::as_str)
            .ok_or_else(|| anyhow::anyhow!("missing {key}"))
    };
    let data = match op {
        "init" | "view" => json!({}),
        "offer" => {
            state.make_offer(now).await?;
            json!({})
        }
        "pair" => {
            let raw = bytes::decode(text("bundle")?, pairing::BUNDLE_BYTES)?;
            state
                .pair(
                    text("qr")?,
                    &raw,
                    args.get("name").and_then(Value::as_str).unwrap_or(""),
                    now,
                )
                .await?
        }
        "confirm" => {
            let raw = bytes::decode(text("bytes")?, 101)?;
            state.confirm(&raw, now).await?
        }
        "send" => state.send(text("contact")?, text("text")?, now).await?,
        "receive" => {
            let raw = bytes::decode(text("bytes")?, 65536)?;
            state.receive(&raw, now).await?
        }
        "pending" => state.pending(
            now,
            args.get("force").and_then(Value::as_bool).unwrap_or(false),
        )?,
        "ack" => {
            let key = bytes::decode(text("key")?, 48)?;
            state.ack(&key, now)?
        }
        "retire" => {
            state.retire(text("contact")?)?;
            json!({})
        }
        "clear_history" => {
            state.messages.clear();
            json!({})
        }
        "mark_read" => {
            let id = text("contact")?;
            ensure!(state.contacts.contains_key(id), "contact missing");
            let latest = state
                .messages
                .iter()
                .filter(|m| m.contact == id)
                .map(|m| m.ui_id)
                .max()
                .unwrap_or(0);
            state.read.insert(id.to_owned(), latest);
            json!({})
        }
        _ => bail!("unknown endpoint operation"),
    };
    state.last_time = now;
    state.check(now)?;
    ensure!(
        serde_json::to_vec(&state)?.len() <= SNAPSHOT_MAX,
        "encrypted snapshot storage is full"
    );
    Ok((state, data))
}

pub fn execute(input: &str, now: u64) -> String {
    #[derive(Deserialize)]
    struct Request {
        state: Option<State>,
        op: String,
        #[serde(default)]
        args: Value,
    }
    let result = (|| -> Result<Value> {
        ensure!(
            input.len() <= 12 * 1024 * 1024,
            "native request exceeds limit"
        );
        let request: Request = serde_json::from_str(input)?;
        let (state, data) =
            futures::executor::block_on(transact(request.state, &request.op, &request.args, now))?;
        Ok(json!({"ok":true,"view":state.view()?,"state":state,"data":data}))
    })();
    match result {
        Ok(value) => value.to_string(),
        Err(error) => json!({"ok":false,"error":error.to_string()}).to_string(),
    }
}
