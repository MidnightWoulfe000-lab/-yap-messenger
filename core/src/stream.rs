//! Bounded record I/O for L2CAP/TLS byte streams, not raw GATT notifications.
use std::io::{self, Read, Write};

/// Reads exactly one length-prefixed record, leaving coalesced records unread.
/// EOF between records is `None`; EOF within a header/body is an error.
/// Close the session after any error. The owner must set transport deadlines.
pub fn read_record(reader: &mut impl Read, max_payload: usize) -> io::Result<Option<Vec<u8>>> {
    let mut header = [0; 4];
    loop {
        match reader.read(&mut header[..1]) {
            Ok(0) => return Ok(None),
            Ok(_) => break,
            Err(e) if e.kind() == io::ErrorKind::Interrupted => continue,
            Err(e) => return Err(e),
        }
    }
    reader.read_exact(&mut header[1..])?;
    let size = u32::from_be_bytes(header) as usize;
    if size > max_payload {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "record exceeds receive limit",
        ));
    }
    // Allocate incrementally, never reserve an attacker-declared length before
    // receiving it. Fixed scratch space also handles small transport reads.
    let mut payload = Vec::new();
    let mut scratch = [0; 4096];
    while payload.len() < size {
        let count = (size - payload.len()).min(scratch.len());
        reader.read_exact(&mut scratch[..count])?;
        payload.extend_from_slice(&scratch[..count]);
    }
    Ok(Some(payload))
}

pub fn write_record(writer: &mut impl Write, payload: &[u8], max_payload: usize) -> io::Result<()> {
    if payload.len() > max_payload || payload.len() > u32::MAX as usize {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "record exceeds send limit",
        ));
    }
    writer.write_all(&(payload.len() as u32).to_be_bytes())?;
    writer.write_all(payload)?;
    writer.flush()
}
