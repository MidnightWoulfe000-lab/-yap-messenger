use native_tls::{Certificate, Identity, TlsAcceptor, TlsConnector, TlsStream};
use std::net::{TcpListener, TcpStream};
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use std::thread::JoinHandle;
use std::time::Duration;
use tungstenite::client::IntoClientRequest;
use tungstenite::{Message, WebSocket};
use yap_core::envelope::{Envelope, Header, MAX_ENVELOPE_BYTES};
use yap_core::relay::{Budget, Relay, RelayConfig};
use yap_relay_server::{clock, Account, Limits, Service};

const A: &str = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
const C: &str = "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC";
static FILE: AtomicU64 = AtomicU64::new(0);
struct Database(PathBuf);
impl Database {
    fn new() -> Self {
        Self(std::env::temp_dir().join(format!(
            "yap-wss-{}-{}.redb",
            std::process::id(),
            FILE.fetch_add(1, Ordering::Relaxed)
        )))
    }
}
impl Drop for Database {
    fn drop(&mut self) {
        let _ = std::fs::remove_file(&self.0);
    }
}

fn start(path: &Database, connections: usize, requests: u32) -> (u16, JoinHandle<()>) {
    let relay = Relay::open(
        &path.0,
        RelayConfig {
            queue_envelopes: 16,
            queue_bytes: 1_048_576,
            recent_entries: 16,
            recent_lifetime: Duration::from_secs(600),
            max_residence: Duration::from_secs(86_400),
            max_connections: 8,
            rate_window: Duration::from_secs(60),
            per_connection: Budget {
                envelopes: 60,
                bytes: 262_144,
            },
            global: Budget {
                envelopes: 120,
                bytes: 524_288,
            },
            max_future_skew_ms: 0,
            evict_oldest_when_full: false,
        },
        clock().unwrap(),
    )
    .unwrap();
    let service = Service::new(
        relay,
        vec![
            Account::new(A, [1; 16], [11; 16]).unwrap(),
            Account::new(C, [2; 16], [12; 16]).unwrap(),
        ],
        Limits {
            max_connections: 8,
            requests_per_connection_per_minute: requests,
            global_per_minute: Budget {
                envelopes: 1000,
                bytes: 2_000_000,
            },
        },
    )
    .unwrap();
    let tls = TlsAcceptor::new(
        Identity::from_pkcs12(include_bytes!("fixtures/localhost.p12"), "yap-test-only").unwrap(),
    )
    .unwrap();
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    let thread = std::thread::spawn(move || {
        for _ in 0..connections {
            let (socket, _) = listener.accept().unwrap();
            let _ = service.serve_connection(socket, &tls);
        }
    });
    (port, thread)
}

fn connect(
    port: u16,
    bearer: &str,
) -> Result<WebSocket<TlsStream<TcpStream>>, Box<dyn std::error::Error>> {
    let socket = TcpStream::connect(("127.0.0.1", port))?;
    socket.set_read_timeout(Some(Duration::from_secs(5)))?;
    socket.set_write_timeout(Some(Duration::from_secs(5)))?;
    let tls = TlsConnector::builder()
        .add_root_certificate(Certificate::from_der(include_bytes!(
            "fixtures/localhost.der"
        ))?)
        .build()?;
    let stream = tls.connect("localhost", socket)?;
    let mut request = format!("wss://localhost:{port}/yap/v1").into_client_request()?;
    request
        .headers_mut()
        .insert("authorization", format!("Bearer {bearer}").parse()?);
    Ok(tungstenite::client(request, stream)?.0)
}
fn send(socket: &mut WebSocket<TlsStream<TcpStream>>, bytes: Vec<u8>) -> Vec<u8> {
    socket.send(Message::Binary(bytes.into())).unwrap();
    read(socket)
}
fn read(socket: &mut WebSocket<TlsStream<TcpStream>>) -> Vec<u8> {
    socket.read().unwrap().into_data().to_vec()
}
fn close(mut socket: WebSocket<TlsStream<TcpStream>>) {
    socket.close(None).unwrap();
    let _ = socket.read();
}

#[test]
fn tls_custody_survives_restart_and_only_the_recipient_can_fetch_or_ack() {
    let path = Database::new();
    let (port, thread) = start(&path, 1, 100);
    let now = clock().unwrap().unix_ms;
    let envelope = Envelope::new(
        Header {
            id: [5; 16],
            destination: [12; 16],
            sender_hint: [6; 16],
            created_ms: now,
            expires_ms: now + 60_000,
            hops: 8,
        },
        vec![42; 1024],
    )
    .unwrap();
    let mut a = connect(port, A).unwrap();
    let mut put = vec![1];
    put.extend_from_slice(&envelope.encode());
    let accepted = send(&mut a, put);
    assert_eq!(&accepted[..2], &[0x81, 0]);
    assert_eq!(&accepted[2..], envelope.relay_key());
    close(a);
    thread.join().unwrap();

    let (port, thread) = start(&path, 2, 100);
    let mut a = connect(port, A).unwrap();
    assert_eq!(send(&mut a, vec![2, 0, 16]), vec![0x83]);
    let mut ack = vec![3];
    ack.extend_from_slice(&envelope.relay_key());
    assert_eq!(send(&mut a, ack.clone()), vec![0x84, 1]);
    close(a);
    let mut c = connect(port, C).unwrap();
    let incoming = send(&mut c, vec![2, 0, 16]);
    assert_eq!(incoming[0], 0x82);
    let forwarded = Envelope::decode(&incoming[49..]).unwrap();
    assert_eq!(forwarded.ciphertext(), envelope.ciphertext());
    assert_eq!(forwarded.header().hops, 7);
    assert_eq!(read(&mut c), vec![0x83]);
    assert_eq!(send(&mut c, ack), vec![0x84, 0]);
    assert_eq!(send(&mut c, vec![2, 0, 16]), vec![0x83]);
    close(c);
    thread.join().unwrap();
}

#[test]
fn missing_credentials_are_rejected_before_mailbox_access() {
    let path = Database::new();
    let (port, thread) = start(&path, 1, 100);
    assert!(connect(port, "wrong").is_err());
    thread.join().unwrap();
}

#[test]
fn tls_rejects_an_untrusted_server_certificate() {
    let path = Database::new();
    let (port, thread) = start(&path, 1, 100);
    let socket = TcpStream::connect(("127.0.0.1", port)).unwrap();
    socket
        .set_read_timeout(Some(Duration::from_secs(5)))
        .unwrap();
    assert!(TlsConnector::new()
        .unwrap()
        .connect("localhost", socket)
        .is_err());
    thread.join().unwrap();
}

#[test]
fn tls_rejects_the_wrong_hostname_even_with_the_test_ca_trusted() {
    let path = Database::new();
    let (port, thread) = start(&path, 1, 100);
    let socket = TcpStream::connect(("127.0.0.1", port)).unwrap();
    socket
        .set_read_timeout(Some(Duration::from_secs(5)))
        .unwrap();
    let connector = TlsConnector::builder()
        .add_root_certificate(
            Certificate::from_der(include_bytes!("fixtures/localhost.der")).unwrap(),
        )
        .build()
        .unwrap();
    assert!(connector.connect("wrong-host.invalid", socket).is_err());
    thread.join().unwrap();
}

#[test]
fn oversized_messages_close_the_connection_without_a_custody_ack() {
    let path = Database::new();
    let (port, thread) = start(&path, 1, 100);
    let mut client = connect(port, A).unwrap();
    let _ = client.send(Message::Binary(vec![0; MAX_ENVELOPE_BYTES + 50].into()));
    assert!(client.read().is_err());
    drop(client);
    thread.join().unwrap();
}

#[test]
fn request_limits_apply_to_polling_and_invalid_requests_too() {
    let path = Database::new();
    let (port, thread) = start(&path, 1, 1);
    let mut client = connect(port, A).unwrap();
    assert_eq!(send(&mut client, vec![255]), vec![255]);
    client.send(Message::Binary(vec![2, 0, 16].into())).unwrap();
    assert!(client.read().is_err());
    drop(client);
    thread.join().unwrap();
}

#[test]
fn typed_client_submits_and_polls_without_treating_custody_as_delivery() {
    use yap_relay_server::client::{Client, Custody};
    let path = Database::new();
    let (port, thread) = start(&path, 2, 100);
    let now = clock().unwrap().unix_ms;
    let envelope = Envelope::new(
        Header {
            id: [5; 16],
            destination: [12; 16],
            sender_hint: [6; 16],
            created_ms: now,
            expires_ms: now + 60_000,
            hops: 8,
        },
        vec![42; 1024],
    )
    .unwrap();
    let root = || Some(Certificate::from_der(include_bytes!("fixtures/localhost.der")).unwrap());
    let url = format!("wss://localhost:{port}/yap/v1");
    let mut a = Client::connect(&url, A, root()).unwrap();
    assert_eq!(
        a.submit(&envelope).unwrap(),
        Custody::Stored(envelope.relay_key())
    );
    a.close().unwrap();
    let mut c = Client::connect(&url, C, root()).unwrap();
    let pending = c.poll(16).unwrap();
    assert_eq!(pending.len(), 1);
    assert_eq!(pending[0].envelope.ciphertext(), envelope.ciphertext());
    // A second poll returns the same custody until explicitly acknowledged.
    assert_eq!(c.poll(16).unwrap()[0].key, pending[0].key);
    c.close().unwrap();
    thread.join().unwrap();
}

#[test]
fn typed_client_rejects_insecure_and_credential_bearing_urls() {
    use yap_relay_server::client::Client;
    for url in [
        "ws://localhost/yap/v1",
        "wss://user:secret@localhost/yap/v1",
        "wss://localhost/yap/v1?token=secret",
    ] {
        assert!(Client::connect(url, A, None).is_err());
    }
}
