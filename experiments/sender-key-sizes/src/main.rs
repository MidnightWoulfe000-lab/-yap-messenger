//! Measures the sizes the group encryption proposal relies on, with the
//! pinned libsignal. Prints the largest size seen in each case; a largest
//! seen value is evidence, not a proven maximum.

use libsignal_protocol::*;
use rand::{TryRngCore, rngs::OsRng};
use std::time::SystemTime;

const HAND_OVER: usize = 96 + 32 + 4 + 16 + 16; // distribution message, label key, tag, IDs

fn addr(n: &str) -> ProtocolAddress {
    ProtocolAddress::new(n.into(), DeviceId::new(1).unwrap())
}

struct Pair {
    alice: InMemSignalProtocolStore,
    bob: InMemSignalProtocolStore,
}

impl Pair {
    async fn new() -> Result<(Self, usize), SignalProtocolError> {
        let mut rng = OsRng.unwrap_err();
        let mut alice = InMemSignalProtocolStore::new(IdentityKeyPair::generate(&mut rng), 1)?;
        let mut bob = InMemSignalProtocolStore::new(IdentityKeyPair::generate(&mut rng), 2)?;
        let id = bob.identity_store.get_identity_key_pair().await?;
        let signed = KeyPair::generate(&mut rng);
        let sig = id.private_key().calculate_signature(&signed.public_key.serialize(), &mut rng)?;
        let pq = kem::KeyPair::generate(kem::KeyType::Kyber1024, &mut rng);
        let pq_sig = id.private_key().calculate_signature(&pq.public_key.serialize(), &mut rng)?;
        let zero = Timestamp::from_epoch_millis(0);
        bob.signed_pre_key_store
            .save_signed_pre_key(1.into(), &SignedPreKeyRecord::new(1.into(), zero, &signed, &sig))
            .await?;
        bob.kyber_pre_key_store
            .save_kyber_pre_key(1.into(), &KyberPreKeyRecord::new(1.into(), zero, &pq, &pq_sig))
            .await?;
        let bundle_size =
            33 + 4 + 33 + sig.len() + 4 + pq.public_key.serialize().len() + pq_sig.len() + 4;
        let bundle = PreKeyBundle::new(
            2,
            DeviceId::new(1).unwrap(),
            None,
            1.into(),
            signed.public_key,
            sig.to_vec(),
            1.into(),
            pq.public_key.clone(),
            pq_sig.to_vec(),
            *id.identity_key(),
        )?;
        process_prekey_bundle(
            &addr("bob"),
            &addr("alice"),
            &mut alice.session_store,
            &mut alice.identity_store,
            &bundle,
            SystemTime::now(),
            &mut rng,
        )
        .await?;
        Ok((Self { alice, bob }, bundle_size))
    }

    /// Alice to Bob when `forward`, else Bob to Alice. Returns the size.
    async fn send(&mut self, forward: bool, body: &[u8]) -> Result<usize, SignalProtocolError> {
        let mut rng = OsRng.unwrap_err();
        let (from, to, me, them) = if forward {
            (&mut self.alice, &mut self.bob, "alice", "bob")
        } else {
            (&mut self.bob, &mut self.alice, "bob", "alice")
        };
        let m = message_encrypt(
            body,
            &addr(them),
            &addr(me),
            &mut from.session_store,
            &mut from.identity_store,
            SystemTime::now(),
            &mut rng,
        )
        .await?;
        let out = message_decrypt(
            &m,
            &addr(me),
            &addr(them),
            &mut to.session_store,
            &mut to.identity_store,
            &mut to.pre_key_store,
            &to.signed_pre_key_store,
            &mut to.kyber_pre_key_store,
            &mut rng,
        )
        .await?;
        assert_eq!(out, body);
        Ok(m.serialize().len())
    }
}

fn main() {
    futures::executor::block_on(run()).unwrap();
}

async fn run() -> Result<(), SignalProtocolError> {
    let body = vec![7u8; HAND_OVER];
    println!("hand-over plaintext used: {HAND_OVER} bytes");

    // New session, several hand-overs sent before any reply.
    let (mut pair, bundle) = Pair::new().await?;
    println!("pre-key bundle: about {bundle} bytes");
    let mut largest = 0;
    for _ in 0..10 {
        largest = largest.max(pair.send(true, &body).await?);
    }
    println!("unacknowledged session, 10 sends: largest {largest} bytes");

    // Established session, strict alternation.
    pair.send(false, b"ok").await?;
    let mut largest = 0;
    for _ in 0..200 {
        largest = largest.max(pair.send(true, &body).await?);
        pair.send(false, b"ok").await?;
    }
    println!("established session, 200 alternating rounds: largest {largest} bytes");

    // Established session, one direction only.
    let mut largest = 0;
    for _ in 0..200 {
        largest = largest.max(pair.send(true, &body).await?);
    }
    println!("established session, 200 sends one way: largest {largest} bytes");

    // Group messages.
    let mut rng = OsRng.unwrap_err();
    let dist = uuid::Uuid::from_bytes([7; 16]);
    let skdm = create_sender_key_distribution_message(
        &addr("alice"),
        dist,
        &mut pair.alice.sender_key_store,
        &mut rng,
    )
    .await?;
    println!("distribution message: {} bytes", skdm.serialized().len());
    for size in [100usize, 1024] {
        let m = group_encrypt(
            &mut pair.alice.sender_key_store,
            &addr("alice"),
            dist,
            &vec![1u8; size],
            &mut rng,
        )
        .await?;
        println!("group message, {size}-byte text: {} bytes", m.serialized().len());
    }
    Ok(())
}
