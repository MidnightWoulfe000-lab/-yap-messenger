use native_tls::{Identity, Protocol, TlsAcceptor};
use serde::Deserialize;
use std::{fs, net::TcpListener, time::Duration};
use yap_core::relay::{Budget, Relay, RelayConfig, StoreResult};
use yap_relay_server::{clock, Account, Limits, Service};

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Config {
    listen: String,
    identity_pfx: String,
    database: String,
    accounts: Vec<Registration>,
    max_connections: usize,
    global_envelopes_per_minute: u32,
    global_bytes_per_minute: usize,
    requests_per_connection_per_minute: u32,
    requests_global_per_minute: u32,
    request_bytes_global_per_minute: usize,
    max_future_skew_ms: u64,
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Registration {
    bearer: String,
    peer: [u8; 16],
    mailbox: [u8; 16],
}

fn main() -> StoreResult<()> {
    let path = std::env::args()
        .nth(1)
        .ok_or("usage: yap-relay-server path/to/private-config.json")?;
    let metadata = fs::metadata(&path)?;
    if metadata.len() > 65_536 {
        return Err("configuration exceeds 64 KiB".into());
    }
    let config: Config = serde_json::from_slice(&fs::read(path)?)?;
    let password = std::env::var("YAP_RELAY_IDENTITY_PASSWORD")?;
    let identity = Identity::from_pkcs12(&fs::read(&config.identity_pfx)?, &password)?;
    let tls = TlsAcceptor::builder(identity)
        .min_protocol_version(Some(Protocol::Tlsv12))
        .build()?;
    let relay = Relay::open(
        config.database,
        RelayConfig {
            queue_envelopes: 1024,
            queue_bytes: 8 * 1024 * 1024,
            recent_entries: 4096,
            recent_lifetime: Duration::from_secs(600),
            max_residence: Duration::from_secs(86_400),
            max_connections: config.max_connections,
            rate_window: Duration::from_secs(60),
            per_connection: Budget {
                envelopes: 60,
                bytes: 256 * 1024,
            },
            global: Budget {
                envelopes: config.global_envelopes_per_minute,
                bytes: config.global_bytes_per_minute,
            },
            max_future_skew_ms: config.max_future_skew_ms,
            // The server promises custody to senders, so it rejects when full.
            evict_oldest_when_full: false,
        },
        clock()?,
    )?;
    let accounts = config
        .accounts
        .into_iter()
        .map(|r| Account::new(&r.bearer, r.peer, r.mailbox))
        .collect::<StoreResult<Vec<_>>>()?;
    let service = Service::new(
        relay,
        accounts,
        Limits {
            max_connections: config.max_connections,
            requests_per_connection_per_minute: config.requests_per_connection_per_minute,
            global_per_minute: Budget {
                envelopes: config.requests_global_per_minute,
                bytes: config.request_bytes_global_per_minute,
            },
        },
    )?;
    let listener = TcpListener::bind(config.listen)?;
    // Fixed workers and a bounded socket queue; never spawn a task per incoming
    // unauthenticated connection. Handshake/idle deadlines release occupied slots.
    let (sender, receiver) =
        std::sync::mpsc::sync_channel::<std::net::TcpStream>(config.max_connections);
    let receiver = std::sync::Arc::new(std::sync::Mutex::new(receiver));
    for _ in 0..config.max_connections {
        let service = service.clone();
        let tls = tls.clone();
        let receiver = receiver.clone();
        std::thread::spawn(move || loop {
            let next = match receiver.lock() {
                Ok(jobs) => jobs.recv(),
                Err(_) => return,
            };
            match next {
                Ok(socket) => {
                    let _ = service.serve_connection(socket, &tls);
                }
                Err(_) => return,
            }
        });
    }
    for socket in listener.incoming() {
        let _ = sender.try_send(socket?);
    }
    Ok(())
}
