//! Experimental, explicitly provisioned TLS WebSocket custody relay.
//! It never decrypts payloads and never issues recipient delivery receipts.
pub mod client;
use native_tls::TlsAcceptor;
use sha2::{Digest, Sha256};
use std::collections::{HashMap, HashSet};
use std::io;
use std::net::TcpStream;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};
use tungstenite::handshake::server::{Request, Response};
use tungstenite::http::{HeaderValue, StatusCode};
use tungstenite::protocol::{Message, WebSocketConfig};
use yap_core::envelope::{Envelope, Token, MAX_ENVELOPE_BYTES};
use yap_core::relay::{Admission, Budget, Clock, Relay, StoreResult};

pub struct Account {
    hash: [u8; 32],
    peer: Token,
    mailbox: Token,
}
impl Account {
    pub fn new(bearer: &str, peer: Token, mailbox: Token) -> StoreResult<Self> {
        if bearer.len() < 32
            || bearer.len() > 128
            || !bearer
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
        {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "bearer must be 32–128 random base64url characters",
            )
            .into());
        }
        Ok(Self {
            hash: Sha256::digest(bearer.as_bytes()).into(),
            peer,
            mailbox,
        })
    }
}

pub struct Limits {
    pub max_connections: usize,
    pub requests_per_connection_per_minute: u32,
    pub global_per_minute: Budget,
}

struct Gate {
    start: Instant,
    count: u32,
    bytes: usize,
}
impl Gate {
    fn new() -> Self {
        Self {
            start: Instant::now(),
            count: 0,
            bytes: 0,
        }
    }
    fn charge(&mut self, budget: Budget, bytes: usize) -> bool {
        if self.start.elapsed() >= Duration::from_secs(60) {
            *self = Self::new();
        }
        if self.count >= budget.envelopes || bytes > budget.bytes.saturating_sub(self.bytes) {
            return false;
        }
        self.count += 1;
        self.bytes += bytes;
        true
    }
}

pub struct Service {
    relay: Mutex<Relay>,
    accounts: Vec<Account>,
    by_hash: HashMap<[u8; 32], usize>,
    mailboxes: HashSet<Token>,
    limits: Limits,
    requests: Mutex<Gate>,
    active: Mutex<usize>,
}

pub fn clock() -> StoreResult<Clock> {
    let unix_ms = SystemTime::now()
        .duration_since(UNIX_EPOCH)?
        .as_millis()
        .try_into()?;
    Ok(Clock {
        unix_ms,
        monotonic: Instant::now(),
    })
}

impl Service {
    pub fn new(relay: Relay, accounts: Vec<Account>, limits: Limits) -> StoreResult<Arc<Self>> {
        if accounts.is_empty()
            || accounts.len() > 128
            || limits.max_connections == 0
            || limits.max_connections > 128
            || limits.requests_per_connection_per_minute == 0
            || limits.global_per_minute.envelopes == 0
            || limits.global_per_minute.bytes == 0
        {
            return Err(io::Error::other("invalid service limits").into());
        }
        let mut by_hash = HashMap::new();
        let mut mailboxes = HashSet::new();
        let mut peers = HashSet::new();
        for (index, account) in accounts.iter().enumerate() {
            if by_hash.insert(account.hash, index).is_some()
                || !mailboxes.insert(account.mailbox)
                || !peers.insert(account.peer)
            {
                return Err(
                    io::Error::other("credentials, peer IDs and mailboxes must be unique").into(),
                );
            }
        }
        Ok(Arc::new(Self {
            relay: Mutex::new(relay),
            accounts,
            by_hash,
            mailboxes,
            limits,
            requests: Mutex::new(Gate::new()),
            active: Mutex::new(0),
        }))
    }

    // Tokens identify a mailbox but do not grant retrieval/deletion. The
    // authenticated account index is fixed by the TLS WebSocket handshake.
    fn dispatch(&self, account: usize, request: &[u8]) -> StoreResult<Vec<Vec<u8>>> {
        let account = &self.accounts[account];
        let mut relay = self
            .relay
            .lock()
            .map_err(|_| io::Error::other("relay unavailable"))?;
        let now = clock()?;
        match request.first() {
            Some(1) => {
                let envelope = match Envelope::decode(&request[1..]) {
                    Ok(value)
                        if self.mailboxes.contains(&value.header().destination)
                            && value.header().destination != account.mailbox =>
                    {
                        value
                    }
                    _ => return Ok(vec![vec![0x81, 4]]),
                };
                let admission = relay.admit(&envelope.encode(), account.peer, now)?;
                let mut reply = vec![0x81];
                match admission {
                    Admission::Stored(key) | Admission::AlreadyStored(key) => {
                        reply.push(0);
                        reply.extend_from_slice(&key);
                    }
                    Admission::RecentlyForwarded => reply.push(1),
                    Admission::Rejected(yap_core::relay::Reject::RateLimited) => reply.push(2),
                    Admission::Rejected(yap_core::relay::Reject::QueueFull) => reply.push(3),
                    Admission::Rejected(_) => reply.push(4),
                }
                Ok(vec![reply])
            }
            Some(2) if request.len() == 3 => {
                let limit = usize::from(u16::from_be_bytes([request[1], request[2]])).min(16);
                // Bounded by the core's persistent queue limit, even when no
                // messages belong to this account. No destination is supplied.
                let pending = relay.pending_for(account.peer, now, usize::MAX)?;
                let mut replies = Vec::new();
                for candidate in pending
                    .into_iter()
                    .filter(|c| c.envelope.header().destination == account.mailbox)
                    .take(limit)
                {
                    let mut record = vec![0x82];
                    record.extend_from_slice(&candidate.key);
                    record.extend_from_slice(&candidate.envelope.encode());
                    replies.push(record);
                }
                replies.push(vec![0x83]);
                Ok(replies)
            }
            Some(3) if request.len() == 49 => {
                let key: [u8; 48] = request[1..].try_into().unwrap();
                let owned = relay
                    .pending_for(account.peer, now, usize::MAX)?
                    .iter()
                    .any(|c| c.key == key && c.envelope.header().destination == account.mailbox);
                if !owned {
                    return Ok(vec![vec![0x84, 1]]);
                }
                let removed = relay.transfer_custody(key, now)?;
                Ok(vec![vec![0x84, u8::from(!removed)]])
            }
            _ => Ok(vec![vec![0xff]]),
        }
    }

    /// Runs one bounded connection. The caller owns accept-loop/thread lifetime.
    /// All traffic passes through TLS; there is no plaintext socket fallback.
    // Tungstenite's callback API requires its 136-byte ErrorResponse by value.
    #[allow(clippy::result_large_err)]
    pub fn serve_connection(
        self: &Arc<Self>,
        socket: TcpStream,
        tls: &TlsAcceptor,
    ) -> StoreResult<()> {
        {
            let mut active = self
                .active
                .lock()
                .map_err(|_| io::Error::other("service unavailable"))?;
            if *active >= self.limits.max_connections {
                return Err(io::Error::other("connection limit").into());
            }
            *active += 1;
        }
        struct Active<'a>(&'a Mutex<usize>);
        impl Drop for Active<'_> {
            fn drop(&mut self) {
                if let Ok(mut count) = self.0.lock() {
                    *count -= 1;
                }
            }
        }
        let _active = Active(&self.active);
        socket.set_read_timeout(Some(Duration::from_secs(15)))?;
        socket.set_write_timeout(Some(Duration::from_secs(15)))?;
        let stream = tls.accept(socket)?;
        let mut account = None;
        let options = WebSocketConfig::default()
            .max_message_size(Some(MAX_ENVELOPE_BYTES + 49))
            .max_frame_size(Some(MAX_ENVELOPE_BYTES + 49));
        let mut websocket = tungstenite::accept_hdr_with_config(
            stream,
            |request: &Request, mut response: Response| {
                let selected = request
                    .headers()
                    .get("authorization")
                    .and_then(|value| value.to_str().ok())
                    .and_then(|value| value.strip_prefix("Bearer "))
                    .filter(|value| value.len() <= 128)
                    .and_then(|value| {
                        self.by_hash
                            .get(&<[u8; 32]>::from(Sha256::digest(value.as_bytes())))
                            .copied()
                    });
                if request.uri().path() != "/yap/v1"
                    || selected.is_none()
                    || request.headers().contains_key("origin")
                {
                    let mut rejected =
                        tungstenite::http::Response::new(Some("unauthorised".to_string()));
                    *rejected.status_mut() = StatusCode::UNAUTHORIZED;
                    return Err(rejected);
                }
                account = selected;
                response
                    .headers_mut()
                    .insert("cache-control", HeaderValue::from_static("no-store"));
                Ok(response)
            },
            Some(options),
        )
        .map_err(|_| {
            io::Error::new(
                io::ErrorKind::PermissionDenied,
                "WebSocket handshake rejected",
            )
        })?;
        let mut local_gate = Gate::new();
        loop {
            let message = match websocket.read() {
                Ok(value) => value,
                Err(tungstenite::Error::ConnectionClosed) => return Ok(()),
                Err(error) => return Err(error.into()),
            };
            let global_allowed = self
                .requests
                .lock()
                .map_err(|_| io::Error::other("rate gate unavailable"))?
                .charge(self.limits.global_per_minute, message.len());
            if !global_allowed
                || !local_gate.charge(
                    Budget {
                        envelopes: self.limits.requests_per_connection_per_minute,
                        bytes: self.limits.global_per_minute.bytes,
                    },
                    message.len(),
                )
            {
                return Err(io::Error::other("request budget").into());
            }
            match message {
                Message::Binary(bytes) => {
                    for reply in self.dispatch(account.unwrap(), &bytes)? {
                        websocket.send(Message::Binary(reply.into()))?;
                    }
                }
                Message::Ping(_) | Message::Pong(_) => websocket.flush()?,
                Message::Close(_) => {
                    let _ = websocket.flush();
                    return Ok(());
                }
                _ => return Err(io::Error::other("binary protocol required").into()),
            }
        }
    }
}
