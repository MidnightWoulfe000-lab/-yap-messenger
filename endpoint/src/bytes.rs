use base64::{Engine, engine::general_purpose::URL_SAFE_NO_PAD};
use serde::{Deserialize, Deserializer, Serialize, Serializer};

/// Compact snapshot encoding. Never format secret-bearing byte buffers in logs.
#[derive(Clone, Default, PartialEq, Eq)]
pub struct Bytes(pub Vec<u8>);
impl Serialize for Bytes {
    fn serialize<S: Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        serializer.serialize_str(&URL_SAFE_NO_PAD.encode(&self.0))
    }
}
impl<'de> Deserialize<'de> for Bytes {
    fn deserialize<D: Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        let text = String::deserialize(deserializer)?;
        if text.len() > 1_048_576 {
            return Err(serde::de::Error::custom("byte field exceeds limit"));
        }
        URL_SAFE_NO_PAD
            .decode(text)
            .map(Self)
            .map_err(serde::de::Error::custom)
    }
}
pub fn encode(bytes: &[u8]) -> String {
    URL_SAFE_NO_PAD.encode(bytes)
}
pub fn decode(text: &str, maximum: usize) -> anyhow::Result<Vec<u8>> {
    anyhow::ensure!(
        text.len() <= maximum.saturating_mul(4) / 3 + 4,
        "encoded field exceeds limit"
    );
    let bytes = URL_SAFE_NO_PAD.decode(text)?;
    anyhow::ensure!(bytes.len() <= maximum, "decoded field exceeds limit");
    Ok(bytes)
}
pub fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|v| format!("{v:02x}")).collect()
}
