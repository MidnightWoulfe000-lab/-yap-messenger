//! Experimental length framing for an ordered, reliable transport session.
//!
//! This is not encryption, a mesh envelope, or a stable wire protocol. Each
//! record is a four-byte big-endian payload length followed by opaque bytes.
//! A session carries one record at a time. Discard partial state on disconnect;
//! retransmission and deduplication belong to the future message layer.

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum FrameError {
    InvalidLimits,
    PayloadTooLarge,
    FragmentTooLarge,
    TrailingBytes,
}

/// Bounds supplied by the caller, rather than an implicit product policy.
#[derive(Clone, Copy, Debug)]
pub struct FrameLimits {
    max_payload: usize,
    max_fragment: usize,
}

impl FrameLimits {
    pub fn new(max_payload: usize, max_fragment: usize) -> Result<Self, FrameError> {
        if max_payload == 0 || max_payload > u32::MAX as usize || max_fragment == 0 {
            return Err(FrameError::InvalidLimits);
        }
        Ok(Self {
            max_payload,
            max_fragment,
        })
    }

    /// Split a record into writes that fit the transport's negotiated limit.
    pub fn fragment(self, payload: &[u8]) -> Result<Vec<Vec<u8>>, FrameError> {
        if payload.len() > self.max_payload {
            return Err(FrameError::PayloadTooLarge);
        }
        let capacity = payload
            .len()
            .checked_add(4)
            .ok_or(FrameError::PayloadTooLarge)?;
        let mut record = Vec::with_capacity(capacity);
        record.extend_from_slice(&(payload.len() as u32).to_be_bytes());
        record.extend_from_slice(payload);
        Ok(record
            .chunks(self.max_fragment)
            .map(<[u8]>::to_vec)
            .collect())
    }

    pub fn decoder(self) -> FrameDecoder {
        FrameDecoder {
            limits: self,
            header: [0; 4],
            header_len: 0,
            payload: Vec::new(),
        }
    }
}

/// Reassembles one record at a time, without allocating the declared length.
///
/// Fragments must arrive once, in order, on the same transport session. There
/// are no fragment IDs, integrity checks or timeout scheduling here. The caller
/// must reset on disconnect/timeout and close a session after any framing error
/// because a raw byte stream cannot safely resynchronise after malformed data.
pub struct FrameDecoder {
    limits: FrameLimits,
    header: [u8; 4],
    header_len: usize,
    payload: Vec<u8>,
}

impl FrameDecoder {
    /// Drop buffered bytes at a transport session boundary or receive timeout.
    pub fn reset(&mut self) {
        self.header = [0; 4];
        self.header_len = 0;
        self.payload = Vec::new();
    }

    /// Returns a complete opaque payload, or `None` while awaiting fragments.
    /// Any error clears the partial record. Two records in one fragment are
    /// rejected so that completion cannot silently discard following bytes.
    pub fn push(&mut self, fragment: &[u8]) -> Result<Option<Vec<u8>>, FrameError> {
        let result = self.push_inner(fragment);
        if result.is_err() {
            self.reset();
        }
        result
    }

    fn push_inner(&mut self, mut fragment: &[u8]) -> Result<Option<Vec<u8>>, FrameError> {
        if fragment.len() > self.limits.max_fragment {
            return Err(FrameError::FragmentTooLarge);
        }
        if self.header_len < 4 {
            let count = (4 - self.header_len).min(fragment.len());
            self.header[self.header_len..self.header_len + count]
                .copy_from_slice(&fragment[..count]);
            self.header_len += count;
            fragment = &fragment[count..];
            if self.header_len < 4 {
                return Ok(None);
            }
        }
        let expected = u32::from_be_bytes(self.header) as usize;
        if expected > self.limits.max_payload {
            return Err(FrameError::PayloadTooLarge);
        }
        if fragment.len() > expected - self.payload.len() {
            return Err(FrameError::TrailingBytes);
        }
        self.payload.extend_from_slice(fragment);
        if self.payload.len() == expected {
            let complete = std::mem::take(&mut self.payload);
            self.reset();
            return Ok(Some(complete));
        }
        Ok(None)
    }
}
