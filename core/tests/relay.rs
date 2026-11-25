use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{Duration, Instant};
use yap_core::envelope::{Envelope, Header, MAX_ENVELOPE_BYTES};
use yap_core::relay::{Admission, Budget, Clock, Reject, Relay, RelayConfig};

static NEXT_FILE: AtomicU64 = AtomicU64::new(0);
struct File(PathBuf);
impl File {
    fn new() -> Self {
        Self(std::env::temp_dir().join(format!(
            "yap-relay-{}-{}.redb",
            std::process::id(),
            NEXT_FILE.fetch_add(1, Ordering::Relaxed)
        )))
    }
}
impl Drop for File {
    fn drop(&mut self) {
        let _ = std::fs::remove_file(&self.0);
    }
}

fn config() -> RelayConfig {
    // Deliberately small caches/queues and large rate budgets for deterministic
    // attack tests. These values are not hidden deployment defaults.
    RelayConfig {
        queue_envelopes: 16,
        queue_bytes: 16 * MAX_ENVELOPE_BYTES,
        recent_entries: 2,
        recent_lifetime: Duration::from_secs(600),
        max_residence: Duration::from_secs(86_400),
        max_connections: 16,
        rate_window: Duration::from_secs(60),
        per_connection: Budget {
            envelopes: 1000,
            bytes: 1_000_000,
        },
        global: Budget {
            envelopes: 2000,
            bytes: 2_000_000,
        },
        max_future_skew_ms: 0,
    }
}
fn clock(start: Instant, elapsed_ms: u64) -> Clock {
    Clock {
        unix_ms: 1_000_000 + elapsed_ms,
        monotonic: start + Duration::from_millis(elapsed_ms),
    }
}
fn envelope(id: u8, payload: &[u8]) -> Envelope {
    Envelope::new(
        Header {
            id: [id; 16],
            destination: [9; 16],
            sender_hint: [8; 16],
            created_ms: 1_000_000,
            expires_ms: 2_000_000,
            hops: 8,
        },
        payload.to_vec(),
    )
    .unwrap()
}
fn stored(value: Admission) -> [u8; 48] {
    match value {
        Admission::Stored(key) => key,
        value => panic!("expected custody, got {value:?}"),
    }
}

#[test]
fn forged_id_or_metadata_cannot_poison_the_genuine_envelope() {
    let file = File::new();
    let start = Instant::now();
    let mut relay = Relay::open(&file.0, config(), clock(start, 0)).unwrap();
    let genuine = envelope(1, b"opaque fixture, not real encryption");
    let mut junk = genuine.encode();
    *junk.last_mut().unwrap() ^= 1;
    stored(relay.admit(&junk, [1; 16], clock(start, 0)).unwrap());
    let key = stored(
        relay
            .admit(&genuine.encode(), [2; 16], clock(start, 0))
            .unwrap(),
    );
    let mut changed_destination = genuine.encode();
    changed_destination[20] ^= 1;
    stored(
        relay
            .admit(&changed_destination, [3; 16], clock(start, 0))
            .unwrap(),
    );
    assert_eq!(relay.queued_count().unwrap(), 3);
    assert_eq!(
        relay
            .admit(&genuine.encode(), [2; 16], clock(start, 0))
            .unwrap(),
        Admission::AlreadyStored(key)
    );
    assert_ne!(Envelope::decode(&junk).unwrap().relay_key(), key);
}

#[test]
fn custody_survives_restart_and_a_b_c_forwarding_keeps_payload_identical() {
    let a_file = File::new();
    let b_file = File::new();
    let c_file = File::new();
    let start = Instant::now();
    let now = clock(start, 0);
    let original = envelope(1, &[42; 1024]);
    let mut a = Relay::open(&a_file.0, config(), now).unwrap();
    stored(a.admit(&original.encode(), [0; 16], now).unwrap());
    let to_b = a.pending_for([2; 16], now, 1).unwrap().pop().unwrap();
    {
        let mut b = Relay::open(&b_file.0, config(), now).unwrap();
        stored(b.admit(&to_b.envelope.encode(), [1; 16], now).unwrap());
        assert!(b.pending_for([1; 16], now, 10).unwrap().is_empty());
    }
    let later = clock(start, 10);
    let mut b = Relay::open(&b_file.0, config(), later).unwrap();
    let to_c = b.pending_for([3; 16], later, 1).unwrap().pop().unwrap();
    assert_eq!(to_c.envelope.ciphertext(), original.ciphertext());
    assert_eq!(to_c.envelope.header().hops, 6);
    assert_eq!(to_c.key, original.relay_key());
    let mut c = Relay::open(&c_file.0, config(), later).unwrap();
    stored(c.admit(&to_c.envelope.encode(), [2; 16], later).unwrap());
    assert!(b.transfer_custody(to_c.key, later).unwrap());
    assert_eq!(b.queued_count().unwrap(), 0);
    // Sender's outgoing copy has NOT been cleared by an intermediary ack.
    assert_eq!(a.queued_count().unwrap(), 1);
}

#[test]
fn full_recent_cache_evicts_without_a_day_long_outage() {
    let file = File::new();
    let start = Instant::now();
    let now = clock(start, 0);
    let mut relay = Relay::open(&file.0, config(), now).unwrap();
    for id in 1..=32 {
        let key = stored(
            relay
                .admit(&envelope(id, b"opaque").encode(), [1; 16], now)
                .unwrap(),
        );
        assert!(relay.transfer_custody(key, now).unwrap());
        assert!(relay.recent_count() <= 2);
    }
    assert_eq!(relay.recent_count(), 2);
    stored(
        relay
            .admit(
                &envelope(100, b"new legitimate work").encode(),
                [2; 16],
                now,
            )
            .unwrap(),
    );
    assert_eq!(
        relay
            .admit(&envelope(32, b"opaque").encode(), [1; 16], now)
            .unwrap(),
        Admission::RecentlyForwarded
    );
    // An evicted digest can return; this is the documented availability trade-off.
    stored(
        relay
            .admit(&envelope(1, b"opaque").encode(), [1; 16], now)
            .unwrap(),
    );
}

#[test]
fn full_queue_rejection_does_not_mark_the_rejected_copy_seen() {
    let file = File::new();
    let start = Instant::now();
    let now = clock(start, 0);
    let mut limits = config();
    limits.queue_envelopes = 1;
    let mut relay = Relay::open(&file.0, limits, now).unwrap();
    let first = stored(
        relay
            .admit(&envelope(1, b"one").encode(), [1; 16], now)
            .unwrap(),
    );
    let second = envelope(2, b"two").encode();
    assert_eq!(
        relay.admit(&second, [1; 16], now).unwrap(),
        Admission::Rejected(Reject::QueueFull)
    );
    relay.transfer_custody(first, now).unwrap();
    stored(relay.admit(&second, [1; 16], now).unwrap());
}

#[test]
fn queued_index_survives_recent_cache_churn_and_process_restart() {
    let file = File::new();
    let start = Instant::now();
    let now = clock(start, 0);
    let pending = envelope(99, b"still in custody").encode();
    let pending_key;
    {
        let mut relay = Relay::open(&file.0, config(), now).unwrap();
        pending_key = stored(relay.admit(&pending, [1; 16], now).unwrap());
        for id in 1..=4 {
            let key = stored(
                relay
                    .admit(&envelope(id, b"churn").encode(), [1; 16], now)
                    .unwrap(),
            );
            relay.transfer_custody(key, now).unwrap();
        }
        assert_eq!(
            relay.admit(&pending, [1; 16], now).unwrap(),
            Admission::AlreadyStored(pending_key)
        );
    }
    let mut relay = Relay::open(&file.0, config(), clock(start, 1)).unwrap();
    assert_eq!(
        relay.admit(&pending, [1; 16], clock(start, 1)).unwrap(),
        Admission::AlreadyStored(pending_key)
    );
}

#[test]
fn malformed_packets_consume_budgets_and_new_identities_cannot_bypass_global_cap() {
    let file = File::new();
    let start = Instant::now();
    let now = clock(start, 0);
    let mut limits = config();
    limits.global.envelopes = 2;
    limits.per_connection.envelopes = 1;
    let mut relay = Relay::open(&file.0, limits, now).unwrap();
    assert_eq!(
        relay.admit(b"junk", [1; 16], now).unwrap(),
        Admission::Rejected(Reject::Malformed)
    );
    assert_eq!(
        relay.admit(b"junk", [1; 16], now).unwrap(),
        Admission::Rejected(Reject::RateLimited)
    );
    assert_eq!(
        relay
            .admit(&envelope(1, b"x").encode(), [2; 16], now)
            .unwrap(),
        Admission::Rejected(Reject::RateLimited)
    );
    stored(
        relay
            .admit(&envelope(1, b"x").encode(), [2; 16], clock(start, 60_000))
            .unwrap(),
    );
}

#[test]
fn byte_and_connection_limits_bound_work_before_parsing() {
    let file = File::new();
    let start = Instant::now();
    let now = clock(start, 0);
    let mut limits = config();
    limits.per_connection.bytes = 2;
    limits.max_connections = 1;
    let mut relay = Relay::open(&file.0, limits, now).unwrap();
    assert_eq!(
        relay.admit(b"abc", [1; 16], now).unwrap(),
        Admission::Rejected(Reject::RateLimited)
    );
    assert_eq!(
        relay.admit(b"a", [2; 16], now).unwrap(),
        Admission::Rejected(Reject::RateLimited)
    );
}

#[test]
fn expiry_residence_hop_and_future_limits_are_enforced() {
    let file = File::new();
    let start = Instant::now();
    let now = clock(start, 0);
    let mut limits = config();
    limits.max_residence = Duration::from_millis(10);
    let mut relay = Relay::open(&file.0, limits, now).unwrap();
    let original = envelope(1, b"x");
    stored(relay.admit(&original.encode(), [1; 16], now).unwrap());
    relay.prune(clock(start, 10)).unwrap();
    assert_eq!(relay.queued_count().unwrap(), 0);
    let header = Header {
        created_ms: now.unix_ms + 100,
        expires_ms: now.unix_ms + 200,
        ..original.header()
    };
    let future = Envelope::new(header, b"x".to_vec()).unwrap();
    assert_eq!(
        relay
            .admit(&future.encode(), [1; 16], clock(start, 10))
            .unwrap(),
        Admission::Rejected(Reject::FutureDated)
    );
    let zero = Envelope::new(
        Header {
            hops: 0,
            ..original.header()
        },
        b"x".to_vec(),
    )
    .unwrap();
    assert_eq!(
        relay
            .admit(&zero.encode(), [1; 16], clock(start, 10))
            .unwrap(),
        Admission::Rejected(Reject::HopLimit)
    );
    assert_eq!(
        relay
            .admit(&original.encode(), [1; 16], clock(start, 1_000_000))
            .unwrap(),
        Admission::Rejected(Reject::Expired)
    );
}

#[test]
fn restart_does_not_renew_expiry_and_clock_rollback_fails_closed() {
    let file = File::new();
    let start = Instant::now();
    {
        let mut relay = Relay::open(&file.0, config(), clock(start, 0)).unwrap();
        stored(
            relay
                .admit(&envelope(1, b"x").encode(), [1; 16], clock(start, 100))
                .unwrap(),
        );
    }
    assert!(Relay::open(&file.0, config(), clock(start, 99)).is_err());
    let relay = Relay::open(&file.0, config(), clock(start, 1_000_000)).unwrap();
    assert_eq!(relay.queued_count().unwrap(), 0);
}

#[test]
fn byte_quota_counts_the_whole_encoded_envelope() {
    let file = File::new();
    let start = Instant::now();
    let now = clock(start, 0);
    let bytes = envelope(1, b"x").encode();
    let mut limits = config();
    limits.queue_bytes = bytes.len() - 1;
    let mut relay = Relay::open(&file.0, limits, now).unwrap();
    assert_eq!(
        relay.admit(&bytes, [1; 16], now).unwrap(),
        Admission::Rejected(Reject::QueueFull)
    );
    assert_eq!(relay.queued_count().unwrap(), 0);
}

#[test]
fn monotonic_residence_expires_custody_even_when_wall_clock_freezes() {
    let file = File::new();
    let start = Instant::now();
    let now = clock(start, 0);
    let mut limits = config();
    limits.max_residence = Duration::from_millis(10);
    let mut relay = Relay::open(&file.0, limits, now).unwrap();
    stored(
        relay
            .admit(&envelope(1, b"x").encode(), [1; 16], now)
            .unwrap(),
    );
    relay
        .prune(Clock {
            unix_ms: now.unix_ms,
            monotonic: start + Duration::from_millis(10),
        })
        .unwrap();
    assert_eq!(relay.queued_count().unwrap(), 0);
}

#[test]
fn committed_custody_survives_abrupt_process_exit() {
    if let Ok(path) = std::env::var("YAP_TEST_CRASH_STORE") {
        let start = Instant::now();
        let now = clock(start, 0);
        let mut relay = Relay::open(path, config(), now).unwrap();
        stored(
            relay
                .admit(
                    &envelope(1, b"committed before exit").encode(),
                    [1; 16],
                    now,
                )
                .unwrap(),
        );
        std::process::exit(0); // deliberately skip Database::drop
    }
    let file = File::new();
    let status = std::process::Command::new(std::env::current_exe().unwrap())
        .args([
            "--exact",
            "committed_custody_survives_abrupt_process_exit",
            "--nocapture",
        ])
        .env("YAP_TEST_CRASH_STORE", &file.0)
        .status()
        .unwrap();
    assert!(status.success());
    let start = Instant::now();
    let relay = Relay::open(&file.0, config(), clock(start, 1)).unwrap();
    assert_eq!(relay.queued_count().unwrap(), 1);
}
