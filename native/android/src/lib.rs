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

fn relay_call(input: &str, now: u64) -> Result<Value> {
    let request: Value = serde_json::from_str(input)?;
    let op = request["op"]
        .as_str()
        .ok_or_else(|| anyhow::anyhow!("operation missing"))?;
    let args = &request["args"];
    let path = args["path"]
        .as_str()
        .ok_or_else(|| anyhow::anyhow!("relay path missing"))?;
    ensure!(path.len() < 4096, "relay path exceeds limit");
    let mut relays = RELAYS
        .get_or_init(|| Mutex::new(BTreeMap::new()))
        .lock()
        .map_err(|_| anyhow::anyhow!("relay unavailable; restart Yap"))?;
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
                queue_envelopes: 1024,
                queue_bytes: 8 * 1024 * 1024,
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
    match env.new_string(text) {
        Ok(value) => value.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}
