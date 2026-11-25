//! Small JNI host boundary. Endpoint secrets are committed by Android Keystore;
//! the separate native relay database contains only opaque envelopes.
use anyhow::{ensure, Result};
use jni::{
    objects::{JClass, JString},
    sys::{jlong, jstring},
    JNIEnv,
};
use serde_json::{json, Value};
use std::{
    collections::BTreeMap,
    sync::{Mutex, Once, OnceLock},
    time::{Duration, Instant},
};
use yap_core::relay::{Admission, Budget, Clock, Relay, RelayConfig};
use yap_endpoint::bytes;

static RELAYS: OnceLock<Mutex<BTreeMap<String, Relay>>> = OnceLock::new();
static PANIC_HOOK: Once = Once::new();

/// Host-supplied filesystem paths must be absolute, free of `..` and NUL, and
/// bounded. The Kotlin host is trusted, but this keeps a future host bug (or a
/// crafted request) from steering the relay database to an arbitrary location.
fn check_path(path: &str) -> Result<()> {
    use std::path::{Component, Path};
    ensure!(!path.is_empty() && path.len() < 4096, "invalid native path length");
    ensure!(!path.contains('\0'), "invalid native path");
    // Path::components() silently normalises interior "." segments, so inspect
    // the raw segments as well.
    ensure!(
        !path.split('/').any(|segment| segment == "." || segment == ".."),
        "native path must not contain relative components"
    );
    let path = Path::new(path);
    ensure!(path.is_absolute(), "native path must be absolute");
    ensure!(
        path.components().all(|c| !matches!(c, Component::ParentDir | Component::CurDir)),
        "native path must not contain relative components"
    );
    Ok(())
}

fn relay_call(input: &str, now: u64) -> Result<Value> {
    let request: Value = serde_json::from_str(input)?;
    let op = request["op"]
        .as_str()
        .ok_or_else(|| anyhow::anyhow!("operation missing"))?;
    let args = &request["args"];
    let path = args["path"]
        .as_str()
        .ok_or_else(|| anyhow::anyhow!("relay path missing"))?;
    check_path(path)?;
    let mut relays = match RELAYS.get_or_init(|| Mutex::new(BTreeMap::new())).lock() {
        Ok(guard) => guard,
        Err(poisoned) => {
            // A panic inside a previous call may have left an in-memory Relay
            // half-updated. Durable state lives in redb (transactional), so drop
            // every open handle, clear the poison and reopen from disk on demand.
            // Previously one panic disabled relay storage until the app restarted.
            let mut guard = poisoned.into_inner();
            guard.clear();
            RELAYS.get().map(|m| m.clear_poison());
            guard
        }
    };
    if op == "relay_close" {
        relays.remove(path);
        return Ok(json!({"ok":true}));
    }
    let clock = Clock {
        unix_ms: now,
        monotonic: Instant::now(),
    };
    if !relays.contains_key(path) {
        ensure!(relays.len() < 8, "too many native relay contexts");
        let relay = Relay::open(
            path,
            RelayConfig {
                // Carrier store: up to 10,000 envelopes (64 MiB cap, since one
                // envelope can be up to 64 KiB). When full, the oldest carried
                // envelopes are deleted to make room. The phone's own messages
                // live in the separate encrypted endpoint snapshot, not here.
                queue_envelopes: 10_000,
                queue_bytes: 64 * 1024 * 1024,
                recent_entries: 4096,
                recent_lifetime: Duration::from_secs(600),
                max_residence: Duration::from_secs(86_400),
                max_connections: 8,
                rate_window: Duration::from_secs(60),
                per_connection: Budget {
                    envelopes: 60,
                    bytes: 256 * 1024,
                },
                global: Budget {
                    envelopes: 240,
                    bytes: 1024 * 1024,
                },
                max_future_skew_ms: 300_000,
                evict_oldest_when_full: true,
            },
            clock,
        )
        .map_err(|e| anyhow::anyhow!(e.to_string()))?;
        relays.insert(path.to_owned(), relay);
    }
    let relay = relays.get_mut(path).unwrap();
    let data = match op {
        "relay_admit" => {
            let raw = bytes::decode(
                args["bytes"]
                    .as_str()
                    .ok_or_else(|| anyhow::anyhow!("envelope missing"))?,
                65536,
            )?;
            let peer = bytes::decode(
                args["peer"]
                    .as_str()
                    .ok_or_else(|| anyhow::anyhow!("peer missing"))?,
                16,
            )?
            .try_into()
            .map_err(|_| anyhow::anyhow!("invalid peer token"))?;
            match relay
                .admit(&raw, peer, clock)
                .map_err(|e| anyhow::anyhow!(e.to_string()))?
            {
                Admission::Stored(key) | Admission::AlreadyStored(key) => {
                    json!({"custody":bytes::encode(&key)})
                }
                Admission::RecentlyForwarded => json!({"recent":true}),
                Admission::Rejected(_) => json!({"rejected":true}),
            }
        }
        "relay_pending" => {
            let peer = bytes::decode(
                args["peer"]
                    .as_str()
                    .ok_or_else(|| anyhow::anyhow!("peer missing"))?,
                16,
            )?
            .try_into()
            .map_err(|_| anyhow::anyhow!("invalid peer token"))?;
            let pending = relay
                .pending_for(peer, clock, 16)
                .map_err(|e| anyhow::anyhow!(e.to_string()))?;
            json!({"packets":pending.into_iter().map(|p|json!({"kind":"envelope","key":bytes::encode(&p.key),"bytes":bytes::encode(&p.envelope.encode()),"destination":bytes::encode(&p.envelope.header().destination)})).collect::<Vec<_>>()})
        }
        "relay_ack" => {
            let key = bytes::decode(
                args["key"]
                    .as_str()
                    .ok_or_else(|| anyhow::anyhow!("key missing"))?,
                48,
            )?
            .try_into()
            .map_err(|_| anyhow::anyhow!("invalid relay key"))?;
            json!({"released":relay.transfer_custody(key,clock).map_err(|e|anyhow::anyhow!(e.to_string()))?})
        }
        "relay_count" => {
            relay
                .prune(clock)
                .map_err(|e| anyhow::anyhow!(e.to_string()))?;
            json!({"queued":relay.queued_count().map_err(|e|anyhow::anyhow!(e.to_string()))?})
        }
        _ => anyhow::bail!("unknown native relay operation"),
    };
    Ok(json!({"ok":true,"data":data}))
}

#[no_mangle]
pub extern "system" fn Java_org_yapmessenger_app_NativeBridge_execute(
    mut env: JNIEnv,
    _: JClass,
    input: JString,
    now: jlong,
) -> jstring {
    // Native panics never print private snapshot material into Android logcat.
    PANIC_HOOK.call_once(|| std::panic::set_hook(Box::new(|_| {})));
    let response = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| -> Result<String> {
        ensure!(now >= 0, "invalid clock");
        let input: String = env.get_string(&input)?.into();
        ensure!(input.len() <= 12 * 1024 * 1024, "native request too large");
        #[derive(serde::Deserialize)]
        struct Operation {
            op: String,
        }
        let operation: Operation = serde_json::from_str(&input)?;
        if operation.op == "storage_sync" {
            let header: Value = serde_json::from_str(&input)?;
            let path = header["args"]["path"]
                .as_str()
                .ok_or_else(|| anyhow::anyhow!("directory missing"))?;
            check_path(path)?;
            ensure!(
                std::path::Path::new(path).is_dir(),
                "storage directory missing"
            );
            std::fs::File::open(path)?.sync_all()?;
            Ok(json!({"ok":true}).to_string())
        } else if operation.op.starts_with("relay_") {
            Ok(relay_call(&input, now as u64)?.to_string())
        } else {
            Ok(yap_endpoint::engine::execute(&input, now as u64))
        }
    }));
    let text = match response {
        Ok(Ok(text)) => text,
        Ok(Err(error)) => json!({"ok":false,"error":error.to_string()}).to_string(),
        Err(_) => json!({"ok":false,"error":"Native operation failed; no result was committed"})
            .to_string(),
    };
    // A failed JNI call (for example get_string on a bad argument) can leave a
    // Java exception pending, which makes every later JNI call fail and would
    // return null into Kotlin. Clear it and always hand back a JSON error.
    let _ = env.exception_clear();
    match env.new_string(text) {
        Ok(value) => value.into_raw(),
        Err(_) => {
            let _ = env.exception_clear();
            match env.new_string(r#"{"ok":false,"error":"Native response could not be returned"}"#) {
                Ok(value) => value.into_raw(),
                Err(_) => std::ptr::null_mut(),
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::check_path;

    #[test]
    fn accepts_absolute_paths() {
        assert!(check_path("/data/user/0/org.yapmessenger.app/no_backup/messaging/relay-v1.redb").is_ok());
    }

    #[test]
    fn rejects_unsafe_paths() {
        for bad in ["", "relative/path", "./x", "/a/../etc/passwd", "/a/./b", "/a\0b"] {
            assert!(check_path(bad).is_err(), "{bad:?} should be rejected");
        }
        assert!(check_path(&format!("/{}", "a".repeat(4096))).is_err());
    }
}
