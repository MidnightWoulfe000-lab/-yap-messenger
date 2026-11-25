use std::io::{self, Cursor, Read, Write};
use yap_core::envelope::{Envelope, EnvelopeError, Header, HEADER_BYTES, MAX_ENVELOPE_BYTES};
use yap_core::stream::{read_record, write_record};

fn envelope() -> Envelope {
    Envelope::new(
        Header {
            id: [1; 16],
            destination: [2; 16],
            sender_hint: [3; 16],
            created_ms: 10,
            expires_ms: 1000,
            hops: 8,
        },
        vec![42; 1024],
    )
    .unwrap()
}

#[test]
fn envelope_roundtrip_and_hash_bind_all_immutable_bytes() {
    let original = envelope();
    let bytes = original.encode();
    assert!(Envelope::decode(&bytes).unwrap() == original);
    assert_eq!(bytes.len(), HEADER_BYTES + 1024);
    assert_eq!(
        original.forwarded().unwrap().relay_key(),
        original.relay_key()
    );
    for index in [4, 20, 36, 59, 67, 73, 1000] {
        let mut changed = bytes.clone();
        changed[index] ^= 1;
        let value = Envelope::decode(&changed).unwrap();
        assert_ne!(value.relay_key(), original.relay_key());
    }
}

#[test]
fn envelope_parser_rejects_truncation_length_version_and_resource_attacks() {
    let bytes = envelope().encode();
    for length in 0..bytes.len() {
        assert!(Envelope::decode(&bytes[..length]).is_err());
    }
    let mut changed = bytes.clone();
    changed[3] = 2;
    assert!(matches!(
        Envelope::decode(&changed),
        Err(EnvelopeError::Version)
    ));
    let mut changed = bytes.clone();
    changed[69..73].copy_from_slice(&u32::MAX.to_be_bytes());
    assert!(matches!(
        Envelope::decode(&changed),
        Err(EnvelopeError::Length)
    ));
    let mut changed = bytes;
    changed[68] = 255;
    assert!(matches!(
        Envelope::decode(&changed),
        Err(EnvelopeError::Hops)
    ));
    assert!(Envelope::decode(&vec![0; MAX_ENVELOPE_BYTES + 1]).is_err());
}

struct SmallReads<T>(T);
impl<T: Read> Read for SmallReads<T> {
    fn read(&mut self, bytes: &mut [u8]) -> io::Result<usize> {
        let count = bytes.len().min(1);
        self.0.read(&mut bytes[..count])
    }
}
struct SmallWrites(Vec<u8>);
impl Write for SmallWrites {
    fn write(&mut self, bytes: &[u8]) -> io::Result<usize> {
        let count = bytes.len().min(2);
        self.0.extend_from_slice(&bytes[..count]);
        Ok(count)
    }
    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}

#[test]
fn stream_preserves_coalesced_records_and_handles_tiny_reads_and_writes() {
    let mut writer = SmallWrites(Vec::new());
    for bytes in [b"first".as_slice(), b"", b"second"] {
        write_record(&mut writer, bytes, 100).unwrap();
    }
    let mut reader = SmallReads(Cursor::new(writer.0));
    for bytes in [b"first".as_slice(), b"", b"second"] {
        assert_eq!(read_record(&mut reader, 100).unwrap(), Some(bytes.to_vec()));
    }
    assert_eq!(read_record(&mut reader, 100).unwrap(), None);
}

#[test]
fn stream_rejects_partial_headers_partial_bodies_and_large_declared_lengths() {
    for bytes in [vec![0], vec![0, 0, 0], vec![0, 0, 0, 2, 42]] {
        assert_eq!(
            read_record(&mut Cursor::new(bytes), 100)
                .unwrap_err()
                .kind(),
            io::ErrorKind::UnexpectedEof
        );
    }
    assert_eq!(
        read_record(&mut Cursor::new(u32::MAX.to_be_bytes()), 100)
            .unwrap_err()
            .kind(),
        io::ErrorKind::InvalidData
    );
    let mut writer = Vec::new();
    assert!(write_record(&mut writer, b"too large", 2).is_err());
    assert!(writer.is_empty());
}
