//! Synchronous, bounded TLS client. Custody acknowledgements are NOT delivery receipts.
use native_tls::{Certificate, TlsConnector, TlsStream};
use std::io;
use std::net::{TcpStream, ToSocketAddrs};
use std::time::Duration;
use tungstenite::http::Uri;
use tungstenite::protocol::WebSocketConfig;
use tungstenite::{client::IntoClientRequest, Message, WebSocket};
use yap_core::envelope::{Envelope, RelayKey, MAX_ENVELOPE_BYTES};
use yap_core::relay::{Forward, StoreResult};

#[derive(Debug, PartialEq, Eq)]
pub enum Custody {
    Stored(RelayKey),
    RecentlyForwarded,
    RateLimited,
    QueueFull,
    Rejected,
}
pub struct Client {
    socket: WebSocket<TlsStream<TcpStream>>,
}

fn bad() -> Box<dyn std::error::Error + Send + Sync> {
    io::Error::new(io::ErrorKind::InvalidData, "invalid relay response").into()
}
impl Client {
    /// System certificate validation plus an optional explicitly supplied CA.
    /// No insecure ws://, certificate bypass, URL credentials or query tokens.
    pub fn connect(
        url: &str,
        bearer: &str,
        additional_root: Option<Certificate>,
    ) -> StoreResult<Self> {
        let uri: Uri = url.parse()?;
        if uri.scheme_str() != Some("wss")
            || uri.path() != "/yap/v1"
            || uri.query().is_some()
            || uri.authority().is_some_and(|a| a.as_str().contains('@'))
        {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "explicit wss://host/yap/v1 endpoint required",
            )
            .into());
        }
        let host = uri
            .host()
            .ok_or_else(bad)?
            .trim_start_matches('[')
            .trim_end_matches(']');
        let port = uri.port_u16().unwrap_or(443);
        let mut connected = None;
        for address in (host, port).to_socket_addrs()?.take(8) {
            if let Ok(socket) = TcpStream::connect_timeout(&address, Duration::from_secs(5)) {
                connected = Some(socket);
                break;
            }
        }
        let socket = connected
            .ok_or_else(|| io::Error::new(io::ErrorKind::NotConnected, "relay unreachable"))?;
        socket.set_read_timeout(Some(Duration::from_secs(15)))?;
        socket.set_write_timeout(Some(Duration::from_secs(15)))?;
        let mut connector = TlsConnector::builder();
        connector.min_protocol_version(Some(native_tls::Protocol::Tlsv12));
        if let Some(root) = additional_root {
            connector.add_root_certificate(root);
        }
        let stream = connector.build()?.connect(host, socket)?;
        let mut request = url.into_client_request()?;
        request
            .headers_mut()
            .insert("authorization", format!("Bearer {bearer}").parse()?);
        let options = WebSocketConfig::default()
            .max_message_size(Some(MAX_ENVELOPE_BYTES + 49))
            .max_frame_size(Some(MAX_ENVELOPE_BYTES + 49));
        let (socket, _) = tungstenite::client::client_with_config(request, stream, Some(options))?;
        Ok(Self { socket })
    }
    fn send(&mut self, bytes: Vec<u8>) -> StoreResult<()> {
        self.socket.send(Message::Binary(bytes.into()))?;
        Ok(())
    }
    fn read(&mut self) -> StoreResult<Vec<u8>> {
        loop {
            match self.socket.read()? {
                Message::Binary(bytes) => return Ok(bytes.to_vec()),
                Message::Ping(_) | Message::Pong(_) => self.socket.flush()?,
                _ => return Err(bad()),
            }
        }
    }
    pub fn submit(&mut self, envelope: &Envelope) -> StoreResult<Custody> {
        let mut bytes = vec![1];
        bytes.extend_from_slice(&envelope.encode());
        self.send(bytes)?;
        let reply = self.read()?;
        match reply.as_slice() {
            [0x81, 0, key @ ..] if key.len() == 48 && key == envelope.relay_key() => {
                Ok(Custody::Stored(key.try_into().unwrap()))
            }
            [0x81, 1] => Ok(Custody::RecentlyForwarded),
            [0x81, 2] => Ok(Custody::RateLimited),
            [0x81, 3] => Ok(Custody::QueueFull),
            [0x81, 4] => Ok(Custody::Rejected),
            _ => Err(bad()),
        }
    }
    pub fn poll(&mut self, limit: u16) -> StoreResult<Vec<Forward>> {
        if limit == 0 || limit > 16 {
            return Err(io::Error::new(io::ErrorKind::InvalidInput, "poll limit is 1–16").into());
        }
        let mut bytes = vec![2];
        bytes.extend_from_slice(&limit.to_be_bytes());
        self.send(bytes)?;
        let mut result = Vec::new();
        loop {
            let bytes = self.read()?;
            if bytes == [0x83] {
                return Ok(result);
            }
            if bytes.first() != Some(&0x82)
                || bytes.len() < 50
                || result.len() >= usize::from(limit)
            {
                return Err(bad());
            }
            let key = bytes[1..49].try_into().unwrap();
            let envelope = Envelope::decode(&bytes[49..]).map_err(|_| bad())?;
            if envelope.relay_key() != key {
                return Err(bad());
            }
            result.push(Forward {
                key,
                envelope,
                copies: 1,
                stamp: None,
            });
        }
    }
    /// Call only after local durable acceptance; never on a mere socket read.
    pub fn acknowledge_custody(&mut self, key: RelayKey) -> StoreResult<bool> {
        let mut bytes = vec![3];
        bytes.extend_from_slice(&key);
        self.send(bytes)?;
        match self.read()?.as_slice() {
            [0x84, 0] => Ok(true),
            [0x84, 1] => Ok(false),
            _ => Err(bad()),
        }
    }
    pub fn close(mut self) -> StoreResult<()> {
        self.socket.close(None)?;
        let _ = self.socket.read();
        Ok(())
    }
}
