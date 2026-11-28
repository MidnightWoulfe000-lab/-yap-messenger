//! Two scans must pair two phones, in any order.
//!
//! These tests drive the real endpoint the way the Android host does
//! (`SecureModel.kt`): a scan sends the new confirmation straight away, a
//! confirmation from the link goes to `confirm` and any confirmation it returns is
//! sent straight back, and every three seconds `pending` is polled and its packets
//! sent. The host offers one envelope on one link at most once a minute, so an
//! envelope the other phone refuses is not seen again for 60 seconds. None of the
//! endpoint's checks are skipped or relaxed here.
use serde_json::{Value, json};
use std::collections::BTreeMap;
use yap_endpoint::{
    bytes,
    engine::{State, transact},
    pairing::PublicBundle,
};

const START: u64 = 2_000_000;
const PUMP_MS: u64 = 3_000;

struct Phone {
    state: State,
    now: u64,
    /// Envelope key -> when this phone last offered it on the link.
    offered: BTreeMap<String, u64>,
    /// Confirmations this phone has sent over the link, for budget checks.
    confirmations_sent: Vec<u64>,
}
impl Phone {
    async fn new() -> Self {
        Self {
            state: transact(None, "init", &json!({}), START).await.unwrap().0,
            now: START,
            offered: BTreeMap::new(),
            confirmations_sent: Vec::new(),
        }
    }
    async fn call(&mut self, op: &str, args: Value) -> anyhow::Result<Value> {
        let (state, result) = transact(Some(self.state.clone()), op, &args, self.now).await?;
        self.state = state;
        Ok(result)
    }
    fn view(&self) -> Value {
        self.state.view().unwrap()
    }
    fn identity(&self) -> Vec<u8> {
        let offer = self.view()["offer"]["bundle"].as_str().unwrap().to_owned();
        PublicBundle::decode(&bytes::decode(&offer, 1883).unwrap())
            .unwrap()
            .identity
    }
    fn ready(&self) -> bool {
        self.view()["contacts"]
            .as_array()
            .unwrap()
            .first()
            .is_some_and(|c| c["ready"] == true)
    }
    fn send_confirmation(&mut self, bytes: &Value) -> Value {
        self.confirmations_sent.push(self.now);
        bytes.clone()
    }
}

/// `local` scans `remote`'s code and receives the bundle over the link, then sends
/// its new confirmation at once, as `SecureModel.pair()` does.
async fn scan(local: &mut Phone, remote: &mut Phone) {
    let offer = remote.view()["offer"].clone();
    let paired = local
        .call(
            "pair",
            json!({"qr":offer["qr"],"bundle":offer["bundle"],"name":"Friend"}),
        )
        .await
        .unwrap();
    let proof = local.send_confirmation(&paired["confirmation"]);
    link_confirmation(remote, local, proof).await;
}

/// A confirmation arrives over the link. The receiver answers with whatever
/// confirmation its endpoint returns, which may bounce back again.
async fn link_confirmation(to: &mut Phone, from: &mut Phone, proof: Value) {
    let (mut to, mut from, mut proof) = (to, from, proof);
    for _ in 0..4 {
        // An error is a dropped frame: the contract says an early confirmation
        // creates no state and the sender retries later.
        let Ok(result) = to.call("confirm", json!({"bytes":proof})).await else {
            return;
        };
        let Some(reply) = result["confirmation"].as_str() else {
            return;
        };
        proof = to.send_confirmation(&json!(reply));
        std::mem::swap(&mut to, &mut from);
    }
}

/// One `pump()` of `phone`'s host: confirmations and envelopes from `pending`.
async fn pump(phone: &mut Phone, other: &mut Phone) {
    let packets = phone.call("pending", json!({})).await.unwrap()["packets"]
        .as_array()
        .unwrap()
        .clone();
    for packet in packets {
        if packet["kind"] == "confirmation" {
            let proof = phone.send_confirmation(&packet["bytes"]);
            link_confirmation(other, phone, proof).await;
            continue;
        }
        let key = packet["key"].as_str().unwrap().to_owned();
        if phone
            .offered
            .get(&key)
            .is_some_and(|when| phone.now - when < 60_000)
        {
            continue;
        }
        phone.offered.insert(key, phone.now);
        if let Ok(result) = other
            .call("receive", json!({"bytes":packet["bytes"]}))
            .await
        {
            phone
                .call("ack", json!({"key":result["custody"]}))
                .await
                .unwrap();
        }
    }
}

async fn tick(a: &mut Phone, b: &mut Phone) {
    a.now += PUMP_MS;
    b.now += PUMP_MS;
    pump(a, b).await;
    pump(b, a).await;
}

/// `first` scans `second`, and `second` scans back `gap` ms later. Returns
/// how long after the second scan both phones were ready.
async fn two_scans(first: &mut Phone, second: &mut Phone, gap: u64) -> u64 {
    scan(first, second).await;
    let mut waited = 0;
    while waited < gap {
        tick(first, second).await;
        waited += PUMP_MS;
    }
    scan(second, first).await;
    let mut after = 0;
    while !(first.ready() && second.ready()) {
        assert!(after <= 120_000, "phones never paired after two scans");
        tick(first, second).await;
        after += PUMP_MS;
    }
    after
}

fn check_budget(phone: &Phone) {
    // The contract: a confirmation is retransmitted at most four times a minute.
    for (i, start) in phone.confirmations_sent.iter().enumerate() {
        let in_minute = phone.confirmations_sent[i..]
            .iter()
            .take_while(|t| **t - start < 60_000)
            .count();
        assert!(in_minute <= 4, "more than four confirmations in a minute");
    }
}

/// Two phones, the first returned holding the smaller identity key.
async fn phones() -> (Phone, Phone) {
    let a = Phone::new().await;
    let b = Phone::new().await;
    if a.identity() < b.identity() {
        (a, b)
    } else {
        (b, a)
    }
}

/// Every realistic delay between the two scans, from a second to most of the
/// ten-minute attempt lifetime. Paired means ready within two pumps (the
/// bootstrap and its acknowledgement) after the second scan, with no third scan
/// and no waiting for a retransmission.
async fn every_gap(first_is_smaller: bool) {
    for gap in [
        0, 1_000, 3_000, 10_000, 16_000, 30_000, 47_000, 50_000, 120_000, 400_000,
    ] {
        let (small, large) = phones().await;
        let (mut first, mut second) = if first_is_smaller {
            (small, large)
        } else {
            (large, small)
        };
        let after = two_scans(&mut first, &mut second, gap).await;
        assert!(
            after <= 2 * PUMP_MS,
            "gap {gap} ms: paired only {after} ms after the second scan"
        );
        check_budget(&first);
        check_budget(&second);
    }
}

#[test]
fn smaller_key_scans_first_then_larger_key_scans_back() {
    futures::executor::block_on(every_gap(true));
}

#[test]
fn larger_key_scans_first_then_smaller_key_scans_back() {
    futures::executor::block_on(every_gap(false));
}

#[test]
fn both_phones_scan_at_the_same_time() {
    futures::executor::block_on(async {
        for first_is_smaller in [true, false] {
            let (small, large) = phones().await;
            let (mut a, mut b) = if first_is_smaller {
                (small, large)
            } else {
                (large, small)
            };
            // Both read each other's bundle before either confirmation arrives.
            let a_offer = a.view()["offer"].clone();
            let b_offer = b.view()["offer"].clone();
            let ap = a
                .call(
                    "pair",
                    json!({"qr":b_offer["qr"],"bundle":b_offer["bundle"]}),
                )
                .await
                .unwrap();
            let bp = b
                .call(
                    "pair",
                    json!({"qr":a_offer["qr"],"bundle":a_offer["bundle"]}),
                )
                .await
                .unwrap();
            let ap = a.send_confirmation(&ap["confirmation"]);
            let bp = b.send_confirmation(&bp["confirmation"]);
            link_confirmation(&mut b, &mut a, ap).await;
            link_confirmation(&mut a, &mut b, bp).await;
            tick(&mut a, &mut b).await;
            tick(&mut a, &mut b).await;
            assert!(a.ready() && b.ready());
            check_budget(&a);
            check_budget(&b);
        }
    });
}

#[test]
fn confirmation_before_the_local_scan_is_dropped_without_state_then_two_scans_pair() {
    futures::executor::block_on(async {
        for first_is_smaller in [true, false] {
            let (small, large) = phones().await;
            let (mut a, mut b) = if first_is_smaller {
                (small, large)
            } else {
                (large, small)
            };
            let offer = b.view()["offer"].clone();
            let proof = a
                .call("pair", json!({"qr":offer["qr"],"bundle":offer["bundle"]}))
                .await
                .unwrap()["confirmation"]
                .clone();
            // B has not scanned A: the confirmation is refused and changes nothing.
            let before = serde_json::to_vec(&b.state).unwrap();
            assert!(b.call("confirm", json!({"bytes":proof})).await.is_err());
            assert_eq!(serde_json::to_vec(&b.state).unwrap(), before);
            assert!(b.view()["contacts"].as_array().unwrap().is_empty());
            // No user text can go out yet.
            let id = a.view()["contacts"][0]["id"].clone();
            assert!(
                a.call("send", json!({"contact":id,"text":"too early"}))
                    .await
                    .is_err()
            );
            assert!(a.state.crypto.sessions.0.is_empty() && b.state.crypto.sessions.0.is_empty());
            // Now B scans A: that is the second and last scan.
            scan(&mut b, &mut a).await;
            tick(&mut a, &mut b).await;
            tick(&mut a, &mut b).await;
            assert!(a.ready() && b.ready());
        }
    });
}

#[test]
fn a_forged_or_mismatched_confirmation_still_pairs_nothing() {
    futures::executor::block_on(async {
        let (mut a, mut b) = phones().await;
        let mut c = Phone::new().await;
        scan(&mut a, &mut b).await;
        // B scans a different phone's code, so A's transcript does not match B's.
        let offer = c.view()["offer"].clone();
        let wrong = b
            .call("pair", json!({"qr":offer["qr"],"bundle":offer["bundle"]}))
            .await
            .unwrap()["confirmation"]
            .clone();
        assert!(a.call("confirm", json!({"bytes":wrong})).await.is_err());
        let mut forged = bytes::decode(wrong.as_str().unwrap(), 101).unwrap();
        *forged.last_mut().unwrap() ^= 1;
        assert!(
            c.call("confirm", json!({"bytes":bytes::encode(&forged)}))
                .await
                .is_err()
        );
        for _ in 0..10 {
            tick(&mut a, &mut b).await;
        }
        assert!(!a.ready() && !b.ready());
        assert!(a.state.crypto.sessions.0.is_empty());
    });
}
