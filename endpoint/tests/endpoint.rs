use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use yap_endpoint::{
    bytes,
    engine::{State, transact},
    pairing::{self, PublicBundle},
};

struct Phone {
    state: State,
    now: u64,
}
impl Phone {
    async fn new(now: u64) -> Self {
        Self {
            state: transact(None, "init", &json!({}), now).await.unwrap().0,
            now,
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
    fn restart(&mut self) {
        self.state = serde_json::from_slice(&serde_json::to_vec(&self.state).unwrap()).unwrap();
    }
    async fn envelopes(&mut self) -> Vec<Value> {
        self.call("pending", json!({"force":true})).await.unwrap()["packets"]
            .as_array()
            .unwrap()
            .iter()
            .filter(|p| p["kind"] == "envelope")
            .cloned()
            .collect()
    }
}
async fn scan(local: &mut Phone, remote: &Phone, name: &str) -> Value {
    let offer = remote.view()["offer"].clone();
    local
        .call(
            "pair",
            json!({"qr":offer["qr"],"bundle":offer["bundle"],"name":name}),
        )
        .await
        .unwrap()
}
async fn deliver(source: &mut Phone, destination: &mut Phone) {
    for packet in source.envelopes().await {
        let result = destination
            .call("receive", json!({"bytes":packet["bytes"]}))
            .await
            .unwrap();
        source
            .call("ack", json!({"key":result["custody"]}))
            .await
            .unwrap();
    }
}
async fn paired() -> (Phone, Phone) {
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_millis() as u64;
    let mut a = Phone::new(now).await;
    let mut b = Phone::new(now).await;
    let ac = scan(&mut a, &b, "Bob").await;
    let bc = scan(&mut b, &a, "Alice").await;
    a.call("confirm", json!({"bytes":bc["confirmation"]}))
        .await
        .unwrap();
    b.call("confirm", json!({"bytes":ac["confirmation"]}))
        .await
        .unwrap();
    for _ in 0..3 {
        deliver(&mut a, &mut b).await;
        deliver(&mut b, &mut a).await;
    }
    assert_eq!(a.view()["contacts"][0]["ready"], true);
    assert_eq!(b.view()["contacts"][0]["ready"], true);
    (a, b)
}
fn decode_hex(text: &str) -> Vec<u8> {
    (0..text.len())
        .step_by(2)
        .map(|i| u8::from_str_radix(&text[i..i + 2], 16).unwrap())
        .collect()
}

#[test]
fn canonical_pairing_vector_is_fixed_and_is_not_a_valid_cryptographic_offer() {
    let vector: Value = serde_json::from_str(include_str!("vectors/pairing-v1.json")).unwrap();
    let raw = decode_hex(vector["bundle_hex"].as_str().unwrap());
    let bundle = PublicBundle::decode(&raw).unwrap();
    assert_eq!(bundle.encode().unwrap(), raw);
    assert_eq!(
        bytes::hex(&pairing::commitment(&raw)),
        vector["bundle_commitment_hex"]
    );
    assert_eq!(bundle.qr().unwrap(), vector["qr"]);
    bundle.check_qr(vector["qr"].as_str().unwrap()).unwrap();
    assert!(bundle.validate().is_err());
    let mut second = raw.clone();
    second[5..37].fill(2);
    assert_eq!(
        bytes::hex(&Sha256::digest(pairing::transcript(&raw, &second).unwrap())),
        vector["transcript_sha256_hex"]
    );
}

#[test]
fn mutual_pairing_bootstraps_without_user_text_and_survives_restart() {
    futures::executor::block_on(async {
        let (mut a, mut b) = paired().await;
        a.restart();
        b.restart();
        assert!(
            a.state.crypto.prekeys.0.is_empty()
                && a.state.crypto.pq.0.is_empty()
                && a.state.crypto.signed.0.is_empty()
        );
        assert!(
            b.state.crypto.prekeys.0.is_empty()
                && b.state.crypto.pq.0.is_empty()
                && b.state.crypto.signed.0.is_empty()
        );
        let id = a.view()["contacts"][0]["id"].clone();
        a.call("send",json!({"contact":id,"text":"A long encrypted message 🌍 that exceeds the old twenty-byte limit"})).await.unwrap();
        a.restart();
        deliver(&mut a, &mut b).await;
        b.restart();
        deliver(&mut b, &mut a).await;
        assert_eq!(a.view()["messages"][0]["status"], "Delivered");
        assert_eq!(b.view()["messages"][0]["mine"], false);
        assert_eq!(a.view()["queued"], 0);
    });
}

#[test]
fn unilateral_pairing_cannot_send_or_create_a_remote_session() {
    futures::executor::block_on(async {
        let mut a = Phone::new(2_000_000).await;
        let mut b = Phone::new(2_000_000).await;
        let proof = scan(&mut a, &b, "Bob").await;
        assert!(
            b.call("confirm", json!({"bytes":proof["confirmation"]}))
                .await
                .is_err()
        );
        assert!(
            a.call(
                "send",
                json!({"contact":a.view()["contacts"][0]["id"],"text":"not yet"})
            )
            .await
            .is_err()
        );
        assert!(a.state.crypto.sessions.0.is_empty() && b.state.crypto.sessions.0.is_empty());
    });
}

#[test]
fn bad_qr_commitment_signature_and_expired_attempts_are_rejected() {
    futures::executor::block_on(async {
        let mut a = Phone::new(2_000_000).await;
        let b = Phone::new(2_000_000).await;
        let offer = b.view()["offer"].clone();
        let mut raw = bytes::decode(offer["bundle"].as_str().unwrap(), 1883).unwrap();
        *raw.last_mut().unwrap() ^= 1;
        let snapshot = serde_json::to_vec(&a.state).unwrap();
        assert!(
            a.call(
                "pair",
                json!({"qr":offer["qr"],"bundle":bytes::encode(&raw)})
            )
            .await
            .is_err()
        );
        assert!(serde_json::to_vec(&a.state).unwrap() == snapshot);
        a.now += 600_001;
        assert!(
            a.call("pair", json!({"qr":offer["qr"],"bundle":offer["bundle"]}))
                .await
                .is_err()
        );
    });
}

#[test]
fn lost_confirmation_keeps_retrying_after_local_mutual_proof_and_restart() {
    futures::executor::block_on(async {
        let mut a = Phone::new(2_000_000).await;
        let mut b = Phone::new(2_000_000).await;
        let ac = scan(&mut a, &b, "Bob").await;
        let bc = scan(&mut b, &a, "Alice").await;
        a.call("confirm", json!({"bytes":bc["confirmation"]}))
            .await
            .unwrap();
        a.restart();
        a.now += 15_001;
        b.now += 15_001;
        let pending = a.call("pending", json!({})).await.unwrap();
        assert!(
            pending["packets"]
                .as_array()
                .unwrap()
                .iter()
                .any(|p| p["kind"] == "confirmation" && p["bytes"] == ac["confirmation"])
        );
        b.call("confirm", json!({"bytes":ac["confirmation"]}))
            .await
            .unwrap();
        for _ in 0..3 {
            deliver(&mut a, &mut b).await;
            deliver(&mut b, &mut a).await;
        }
        assert_eq!(a.view()["contacts"][0]["ready"], true);
        assert_eq!(b.view()["contacts"][0]["ready"], true);
    });
}

#[test]
fn tampering_rolls_back_and_exact_replay_returns_the_original_receipt() {
    futures::executor::block_on(async {
        let (mut a, mut b) = paired().await;
        let id = a.view()["contacts"][0]["id"].clone();
        a.call("send", json!({"contact":id,"text":"Store exactly once"}))
            .await
            .unwrap();
        let packet = a.envelopes().await.pop().unwrap();
        let mut raw = bytes::decode(packet["bytes"].as_str().unwrap(), 65536).unwrap();
        *raw.last_mut().unwrap() ^= 1;
        let old = serde_json::to_vec(&b.state).unwrap();
        assert!(
            b.call("receive", json!({"bytes":bytes::encode(&raw)}))
                .await
                .is_err()
        );
        assert!(serde_json::to_vec(&b.state).unwrap() == old);
        b.call("receive", json!({"bytes":packet["bytes"]}))
            .await
            .unwrap();
        let first = b.envelopes().await.pop().unwrap();
        let received = a
            .call("receive", json!({"bytes":first["bytes"]}))
            .await
            .unwrap();
        b.call("ack", json!({"key":received["custody"]}))
            .await
            .unwrap();
        b.restart();
        b.now += 11_000;
        let replay = b
            .call("receive", json!({"bytes":packet["bytes"]}))
            .await
            .unwrap();
        assert_eq!(replay["duplicate"], true);
        let second = b.envelopes().await.pop().unwrap();
        assert_eq!(first["bytes"], second["bytes"]);
        assert_eq!(b.view()["messages"].as_array().unwrap().len(), 1);
    });
}

#[test]
fn visible_history_clear_does_not_prevent_a_receipt_from_freeing_the_outbox() {
    futures::executor::block_on(async {
        let (mut a, mut b) = paired().await;
        let id = a.view()["contacts"][0]["id"].clone();
        a.call("send", json!({"contact":id,"text":"still queued"}))
            .await
            .unwrap();
        a.call("clear_history", json!({})).await.unwrap();
        deliver(&mut a, &mut b).await;
        deliver(&mut b, &mut a).await;
        assert_eq!(a.view()["queued"], 0);
        assert!(a.view()["messages"].as_array().unwrap().is_empty());
    });
}

#[test]
fn retirement_rejects_old_hints_and_never_reuses_a_prekey() {
    futures::executor::block_on(async {
        let (mut a, mut b) = paired().await;
        let a_id = a.view()["contacts"][0]["id"].clone();
        let b_id = b.view()["contacts"][0]["id"].clone();
        a.call("send", json!({"contact":a_id,"text":"old session"}))
            .await
            .unwrap();
        let packet = a.envelopes().await.pop().unwrap();
        b.call("retire", json!({"contact":b_id})).await.unwrap();
        assert!(
            b.call("receive", json!({"bytes":packet["bytes"]}))
                .await
                .is_err()
        );
        b.call("offer", json!({})).await.unwrap();
        let current = b.view()["offer"]["bundle"].as_str().unwrap().to_owned();
        b.call("offer", json!({})).await.unwrap();
        assert_ne!(b.view()["offer"]["bundle"], current);
        assert_eq!(b.state.crypto.prekeys.0.len(), 1);
        assert_eq!(b.state.crypto.pq.0.len(), 1);
        assert_eq!(b.state.crypto.signed.0.len(), 1);
    });
}

#[test]
fn restoring_a_clock_older_than_the_snapshot_fails_closed() {
    futures::executor::block_on(async {
        let mut a = Phone::new(2_000_000).await;
        a.call("view", json!({})).await.unwrap();
        a.restart();
        a.now -= 1;
        assert!(a.call("view", json!({})).await.is_err());
        assert!(transact(None, "view", &json!({}), 2_000_000).await.is_err());
    });
}

#[test]
fn reordered_messages_and_receipts_survive_restart() {
    futures::executor::block_on(async {
        let (mut a, mut b) = paired().await;
        let contact = a.view()["contacts"][0]["id"].clone();
        for text in ["first", "second", "third"] {
            a.call("send", json!({"contact":contact,"text":text}))
                .await
                .unwrap();
        }
        let packets = a.envelopes().await;
        for packet in packets.iter().rev() {
            b.call("receive", json!({"bytes":packet["bytes"]}))
                .await
                .unwrap();
            b.restart();
        }
        let receipts = b.envelopes().await;
        for receipt in receipts.iter().rev() {
            a.call("receive", json!({"bytes":receipt["bytes"]}))
                .await
                .unwrap();
            a.restart();
        }
        assert_eq!(a.view()["queued"], 0);
        assert!(
            a.view()["messages"]
                .as_array()
                .unwrap()
                .iter()
                .all(|m| m["status"] == "Delivered")
        );
        assert_eq!(b.view()["messages"].as_array().unwrap().len(), 3);
    });
}

#[test]
fn authenticated_receipt_can_reduce_a_completely_full_outbox() {
    futures::executor::block_on(async {
        let (mut a, mut b) = paired().await;
        let contact = a.view()["contacts"][0]["id"].clone();
        a.call(
            "send",
            json!({"contact":contact,"text":"admit the receipt"}),
        )
        .await
        .unwrap();
        deliver(&mut a, &mut b).await;
        // Fault fixture: all remaining slots are occupied at receipt arrival.
        // The actual outstanding message and authenticated receipt stay intact.
        let mut saved = serde_json::to_value(&a.state).unwrap();
        let entry = saved["outbox"]
            .as_object()
            .unwrap()
            .values()
            .next()
            .unwrap()
            .clone();
        for i in 0..511 {
            let mut occupied = entry.clone();
            occupied["kind"] = json!(if i < 447 { 1 } else { 2 });
            saved["outbox"]
                .as_object_mut()
                .unwrap()
                .insert(format!("fixture-{i}"), occupied);
        }
        a.state = serde_json::from_value(saved).unwrap();
        deliver(&mut b, &mut a).await;
        assert_eq!(a.view()["queued"], 511);
        assert_eq!(a.view()["messages"][0]["status"], "Delivered");
    });
}
