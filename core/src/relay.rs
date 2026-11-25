//! Durable, bounded custody of opaque envelopes. This never confirms delivery.
use crate::envelope::{Envelope, RelayKey, Token, MAX_ENVELOPE_BYTES};
use redb::{Database, Durability, ReadableTable, ReadableTableMetadata, TableDefinition};
use std::collections::{HashMap, VecDeque};
use std::error::Error;
use std::io;
use std::path::Path;
use std::time::{Duration, Instant};

const QUEUE: TableDefinition<&[u8], &[u8]> = TableDefinition::new("relay_queue_v1");
const META: TableDefinition<&str, u64> = TableDefinition::new("relay_meta_v1");
const STORED_PREFIX: usize = 24;
pub type StoreResult<T> = Result<T, Box<dyn Error + Send + Sync>>;

#[derive(Clone, Copy)]
pub struct Budget {
    pub envelopes: u32,
    pub bytes: usize,
}

/// Explicit operational limits; no implicit global or Sybil policy.
#[derive(Clone)]
pub struct RelayConfig {
    pub queue_envelopes: usize,
    /// Encoded bytes, excluding database/index/allocation overhead.
    pub queue_bytes: usize,
    pub recent_entries: usize,
    pub recent_lifetime: Duration,
    pub max_residence: Duration,
    pub max_connections: usize,
    pub rate_window: Duration,
    pub per_connection: Budget,
    pub global: Budget,
    pub max_future_skew_ms: u64,
    /// When full, delete the oldest carried envelopes to make room instead of
    /// rejecting new ones. For phones acting as carriers: their custody is
    /// best-effort and the original sender keeps retrying until a receipt. Keep
    /// this false for relays that promise custody (the internet relay server).
    pub evict_oldest_when_full: bool,
}

#[derive(Clone, Copy)]
pub struct Clock {
    pub unix_ms: u64,
    pub monotonic: Instant,
}

#[derive(Debug, PartialEq, Eq)]
pub enum Reject {
    RateLimited,
    Malformed,
    Expired,
    FutureDated,
    HopLimit,
    QueueFull,
}

#[derive(Debug, PartialEq, Eq)]
pub enum Admission {
    /// The commit completed. Only this and AlreadyStored confirm custody.
    Stored(RelayKey),
    AlreadyStored(RelayKey),
    /// NOT proof of current custody or recipient delivery.
    RecentlyForwarded,
    Rejected(Reject),
}

pub struct Forward {
    pub key: RelayKey,
    pub envelope: Envelope,
}

struct Window {
    start: Instant,
    envelopes: u32,
    bytes: usize,
}

impl Window {
    fn new(start: Instant) -> Self {
        Self {
            start,
            envelopes: 0,
            bytes: 0,
        }
    }
    fn expired(&self, now: Instant, duration: Duration) -> bool {
        now.saturating_duration_since(self.start) >= duration
    }
    fn charge(&mut self, now: Instant, duration: Duration, budget: Budget, bytes: usize) -> bool {
        if self.expired(now, duration) {
            *self = Self::new(now);
        }
        if self.envelopes >= budget.envelopes || bytes > budget.bytes.saturating_sub(self.bytes) {
            return false;
        }
        self.envelopes += 1;
        self.bytes += bytes;
        true
    }
}

pub struct Relay {
    database: Database,
    config: RelayConfig,
    recent: VecDeque<(RelayKey, Instant)>,
    connections: HashMap<Token, Window>,
    global: Window,
    clock_anchor: Clock,
}

fn invalid(message: &'static str) -> Box<dyn Error + Send + Sync> {
    io::Error::new(io::ErrorKind::InvalidData, message).into()
}

/// Cheap view of a stored record without copying or decoding the envelope:
/// (accepted_ms, source, expires_ms, encoded envelope length). Used by scans
/// that run on every operation, so a 10,000-envelope carrier store stays fast.
fn peek_stored(value: &[u8]) -> StoreResult<(u64, Token, u64, usize)> {
    // Envelope header: expires_ms is at bytes 60..68 of the encoded envelope.
    if value.len() <= STORED_PREFIX + 73 || value.len() > STORED_PREFIX + MAX_ENVELOPE_BYTES {
        return Err(invalid("invalid stored relay record length"));
    }
    let accepted = u64::from_be_bytes(value[..8].try_into().unwrap());
    let source = value[8..24].try_into().unwrap();
    let expires = u64::from_be_bytes(value[STORED_PREFIX + 60..STORED_PREFIX + 68].try_into().unwrap());
    Ok((accepted, source, expires, value.len() - STORED_PREFIX))
}

fn parse_stored(value: &[u8]) -> StoreResult<(u64, Token, Envelope)> {
    if value.len() <= STORED_PREFIX || value.len() > STORED_PREFIX + MAX_ENVELOPE_BYTES {
        return Err(invalid("invalid stored relay record length"));
    }
    let accepted = u64::from_be_bytes(value[..8].try_into().unwrap());
    let source = value[8..24].try_into().unwrap();
    let envelope =
        Envelope::decode(&value[24..]).map_err(|_| invalid("invalid stored envelope"))?;
    Ok((accepted, source, envelope))
}

impl Relay {
    pub fn open(path: impl AsRef<Path>, config: RelayConfig, clock: Clock) -> StoreResult<Self> {
        if config.queue_envelopes == 0
            || config.queue_bytes == 0
            || config.max_connections == 0
            || config.recent_entries == 0
            || config.recent_lifetime.is_zero()
            || config.max_residence.is_zero()
            || config.rate_window.is_zero()
            || config.per_connection.envelopes == 0
            || config.per_connection.bytes == 0
            || config.global.envelopes == 0
            || config.global.bytes == 0
        {
            return Err(invalid("relay limits must be nonzero"));
        }
        let database = Database::create(path)?;
        let mut transaction = database.begin_write()?;
        transaction.set_durability(Durability::Immediate);
        {
            let mut meta = transaction.open_table(META)?;
            if let Some(schema) = meta.get("schema")? {
                if schema.value() != 1 {
                    return Err(invalid("unsupported relay database schema"));
                }
            }
            meta.insert("schema", 1)?;
            let previous = meta.get("wall_high_water")?.map(|v| v.value()).unwrap_or(0);
            if clock.unix_ms < previous {
                return Err(invalid("clock rollback: relay remains closed"));
            }
            meta.insert("wall_high_water", clock.unix_ms)?;
            let queue = transaction.open_table(QUEUE)?;
            let mut bytes = 0usize;
            for row in queue.iter()? {
                let (key, value) = row?;
                let (accepted, _, envelope) = parse_stored(value.value())?;
                if accepted > clock.unix_ms || key.value() != envelope.relay_key() {
                    return Err(invalid("relay database record/index mismatch"));
                }
                bytes = bytes
                    .checked_add(value.value().len() - STORED_PREFIX)
                    .ok_or_else(|| invalid("relay size overflow"))?;
            }
            if queue.len()? > config.queue_envelopes as u64 || bytes > config.queue_bytes {
                return Err(invalid("existing custody exceeds configured limits"));
            }
        }
        transaction.commit()?;
        let mut relay = Self {
            database,
            config,
            recent: VecDeque::new(),
            connections: HashMap::new(),
            global: Window::new(clock.monotonic),
            clock_anchor: clock,
        };
        relay.prune(clock)?;
        Ok(relay)
    }

    // While running, local time must advance even if wall time freezes or goes
    // backwards. Persist this high-water mark; reopen rejects an older clock.
    fn normalize_clock(&mut self, mut clock: Clock) -> Clock {
        let elapsed = clock
            .monotonic
            .saturating_duration_since(self.clock_anchor.monotonic)
            .as_millis()
            .min(u64::MAX as u128) as u64;
        clock.unix_ms = clock
            .unix_ms
            .max(self.clock_anchor.unix_ms.saturating_add(elapsed));
        self.clock_anchor = clock;
        clock
    }

    fn check_clock(&self, transaction: &redb::WriteTransaction, clock: Clock) -> StoreResult<()> {
        let mut meta = transaction.open_table(META)?;
        let previous = meta.get("wall_high_water")?.map(|v| v.value()).unwrap_or(0);
        if clock.unix_ms < previous {
            return Err(invalid("clock rollback: no relay operations permitted"));
        }
        meta.insert("wall_high_water", clock.unix_ms)?;
        Ok(())
    }

    fn prune_transaction(
        &self,
        transaction: &redb::WriteTransaction,
        clock: Clock,
    ) -> StoreResult<()> {
        self.check_clock(transaction, clock)?;
        let mut queue = transaction.open_table(QUEUE)?;
        let mut expired = Vec::new();
        for row in queue.iter()? {
            let (key, value) = row?;
            let (accepted, _, expires_ms, _) = peek_stored(value.value())?;
            if expires_ms <= clock.unix_ms
                || Duration::from_millis(clock.unix_ms.saturating_sub(accepted))
                    >= self.config.max_residence
            {
                expired.push(key.value().to_vec());
            }
        }
        for key in expired {
            queue.remove(key.as_slice())?;
        }
        Ok(())
    }

    pub fn prune(&mut self, clock: Clock) -> StoreResult<()> {
        let clock = self.normalize_clock(clock);
        let mut transaction = self.database.begin_write()?;
        transaction.set_durability(Durability::Immediate);
        self.prune_transaction(&transaction, clock)?;
        transaction.commit()?;
        self.recent.retain(|(_, time)| {
            clock.monotonic.saturating_duration_since(*time) < self.config.recent_lifetime
        });
        Ok(())
    }

    fn charge(&mut self, source: Token, bytes: usize, clock: Clock) -> bool {
        // Rejected/malformed work is charged before parsing or hashing.
        if !self.global.charge(
            clock.monotonic,
            self.config.rate_window,
            self.config.global,
            bytes,
        ) {
            return false;
        }
        self.connections
            .retain(|_, window| !window.expired(clock.monotonic, self.config.rate_window));
        if !self.connections.contains_key(&source)
            && self.connections.len() >= self.config.max_connections
        {
            return false;
        }
        self.connections
            .entry(source)
            .or_insert_with(|| Window::new(clock.monotonic))
            .charge(
                clock.monotonic,
                self.config.rate_window,
                self.config.per_connection,
                bytes,
            )
    }

    pub fn admit(&mut self, bytes: &[u8], source: Token, clock: Clock) -> StoreResult<Admission> {
        let clock = self.normalize_clock(clock);
        if !self.charge(source, bytes.len(), clock) {
            return Ok(Admission::Rejected(Reject::RateLimited));
        }
        let envelope = match Envelope::decode(bytes) {
            Ok(value) => value,
            Err(_) => return Ok(Admission::Rejected(Reject::Malformed)),
        };
        let header = envelope.header();
        if header.expires_ms <= clock.unix_ms {
            return Ok(Admission::Rejected(Reject::Expired));
        }
        if header.created_ms > clock.unix_ms.saturating_add(self.config.max_future_skew_ms) {
            return Ok(Admission::Rejected(Reject::FutureDated));
        }
        if header.hops == 0 {
            return Ok(Admission::Rejected(Reject::HopLimit));
        }
        let key = envelope.relay_key();
        let mut transaction = self.database.begin_write()?;
        transaction.set_durability(Durability::Immediate);
        self.prune_transaction(&transaction, clock)?;
        let admission;
        {
            let mut queue = transaction.open_table(QUEUE)?;
            self.recent.retain(|(_, time)| {
                clock.monotonic.saturating_duration_since(*time) < self.config.recent_lifetime
            });
            if queue.get(key.as_slice())?.is_some() {
                admission = Admission::AlreadyStored(key);
            } else if let Some(index) = self.recent.iter().position(|(seen, _)| seen == &key) {
                let entry = self.recent.remove(index).unwrap();
                self.recent.push_back(entry); // LRU touch does not renew TTL
                admission = Admission::RecentlyForwarded;
            } else {
                let mut used = 0usize;
                let mut rows = Vec::new();
                for row in queue.iter()? {
                    let (key, value) = row?;
                    let (accepted, _, _, length) = peek_stored(value.value())?;
                    used += length;
                    if self.config.evict_oldest_when_full {
                        rows.push((accepted, key.value().to_vec(), length));
                    }
                }
                let mut count = queue.len()? as usize;
                let full = |count: usize, used: usize| {
                    count >= self.config.queue_envelopes
                        || bytes.len() > self.config.queue_bytes.saturating_sub(used)
                };
                if full(count, used)
                    && self.config.evict_oldest_when_full
                    && bytes.len() <= self.config.queue_bytes
                {
                    // Oldest first (ties broken by key so the order is stable).
                    rows.sort();
                    for (_, key, length) in rows {
                        if !full(count, used) {
                            break;
                        }
                        queue.remove(key.as_slice())?;
                        count -= 1;
                        used -= length;
                    }
                }
                if full(count, used) {
                    admission = Admission::Rejected(Reject::QueueFull);
                } else {
                    let mut stored = Vec::with_capacity(STORED_PREFIX + bytes.len());
                    stored.extend_from_slice(&clock.unix_ms.to_be_bytes());
                    stored.extend_from_slice(&source);
                    stored.extend_from_slice(bytes);
                    queue.insert(key.as_slice(), stored.as_slice())?;
                    admission = Admission::Stored(key);
                }
            }
        }
        transaction.commit()?;
        Ok(admission)
    }

    /// FIFO candidates, excluding the incoming connection. Retains custody.
    pub fn pending_for(
        &mut self,
        peer: Token,
        clock: Clock,
        limit: usize,
    ) -> StoreResult<Vec<Forward>> {
        self.prune(clock)?;
        let transaction = self.database.begin_read()?;
        let queue = transaction.open_table(QUEUE)?;
        // Order cheaply first, then decode only the envelopes actually returned.
        let mut order: Vec<(u64, RelayKey)> = Vec::new();
        for row in queue.iter()? {
            let (key, value) = row?;
            let (accepted, source, _, _) = peek_stored(value.value())?;
            if source != peer {
                order.push((accepted, key.value().try_into().unwrap()));
            }
        }
        order.sort();
        let mut result = Vec::new();
        for (_, key) in order {
            if result.len() >= limit {
                break;
            }
            let Some(value) = queue.get(key.as_slice())? else { continue };
            let (_, _, envelope) = parse_stored(value.value())?;
            if let Some(envelope) = envelope.forwarded() {
                result.push(Forward { key, envelope });
            }
        }
        Ok(result)
    }

    /// Release RELAY custody after a next-link DURABLE custody acknowledgement.
    /// Never call this on the sender's outbox or merely for a GATT write ack.
    pub fn transfer_custody(&mut self, key: RelayKey, clock: Clock) -> StoreResult<bool> {
        let clock = self.normalize_clock(clock);
        let mut transaction = self.database.begin_write()?;
        transaction.set_durability(Durability::Immediate);
        self.prune_transaction(&transaction, clock)?;
        let removed = transaction
            .open_table(QUEUE)?
            .remove(key.as_slice())?
            .is_some();
        transaction.commit()?;
        if removed {
            self.recent.retain(|(seen, time)| {
                seen != &key
                    && clock.monotonic.saturating_duration_since(*time)
                        < self.config.recent_lifetime
            });
            while self.recent.len() >= self.config.recent_entries {
                self.recent.pop_front();
            }
            self.recent.push_back((key, clock.monotonic));
        }
        Ok(removed)
    }

    pub fn queued_count(&self) -> StoreResult<u64> {
        Ok(self.database.begin_read()?.open_table(QUEUE)?.len()?)
    }
    pub fn recent_count(&self) -> usize {
        self.recent.len()
    }
}
