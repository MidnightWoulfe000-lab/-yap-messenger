//! Isolated feasibility test. In-memory endpoint secrets; no production pairing,
//! key storage, recovery, identity UI or supported third-party SDK is implied.
//! This AGPL experiment is not a dependency of Yap's GPL core or Android app.

#[cfg(test)]
mod tests {
    use libsignal_protocol::*;
    use native_tls::{Certificate, Identity, TlsAcceptor, TlsConnector};
    use rand::{TryRngCore, rngs::OsRng};
    use std::time::{Duration, Instant, SystemTime};
    use tungstenite::{Message, client::IntoClientRequest};
    use yap_core::envelope::{Envelope, Header};
    use yap_core::relay::{Admission, Budget, Clock, Relay, RelayConfig};
    use yap_relay_server::{Account, Limits, Service};

    // A real local TLS WebSocket hop carrying the already encrypted envelope.
    // The receiving test endpoint does not issue a custody ack: endpoint durable
    // storage is outside this experiment. Only the later crypto receipt is
    // interpreted as recipient confirmation in the test.
    fn internet_hop(envelope: &Envelope, config: RelayConfig) -> Envelope {
        let (sender, receiver) = if envelope.header().destination == [2; 16] {
            (
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC",
            )
        } else {
            (
                "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC",
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            )
        };
        let path = std::env::temp_dir().join(format!(
            "yap-signal-wss-{}-{}.redb",
            std::process::id(),
            envelope.header().id[0]
        ));
        assert!(!path.exists(), "use a fresh experiment store");
        let relay = Relay::open(&path, config, yap_relay_server::clock().unwrap()).unwrap();
        let service = Service::new(
            relay,
            vec![
                Account::new(
                    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                    [1; 16],
                    [6; 16],
                )
                .unwrap(),
                Account::new(
                    "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC",
                    [2; 16],
                    [2; 16],
                )
                .unwrap(),
            ],
            Limits {
                max_connections: 2,
                requests_per_connection_per_minute: 60,
                global_per_minute: Budget {
                    envelopes: 120,
                    bytes: 1_048_576,
                },
            },
        )
        .unwrap();
        let tls = TlsAcceptor::new(
            Identity::from_pkcs12(
                include_bytes!("../../../relay-server/tests/fixtures/localhost.p12"),
                "yap-test-only",
            )
            .unwrap(),
        )
        .unwrap();
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let server = std::thread::spawn(move || {
            for _ in 0..2 {
                service
                    .serve_connection(listener.accept().unwrap().0, &tls)
                    .unwrap();
            }
        });
        let connect = |bearer: &str| {
            let connector = TlsConnector::builder()
                .add_root_certificate(
                    Certificate::from_der(include_bytes!(
                        "../../../relay-server/tests/fixtures/localhost.der"
                    ))
                    .unwrap(),
                )
                .build()
                .unwrap();
            let socket = std::net::TcpStream::connect(("127.0.0.1", port)).unwrap();
            socket
                .set_read_timeout(Some(Duration::from_secs(5)))
                .unwrap();
            let stream = connector.connect("localhost", socket).unwrap();
            let mut request = format!("wss://localhost:{port}/yap/v1")
                .into_client_request()
                .unwrap();
            request
                .headers_mut()
                .insert("authorization", format!("Bearer {bearer}").parse().unwrap());
            tungstenite::client(request, stream).unwrap().0
        };
        let mut source = connect(sender);
        let mut put = vec![1];
        put.extend_from_slice(&envelope.encode());
        source.send(Message::Binary(put.into())).unwrap();
        assert_eq!(&source.read().unwrap().into_data()[..2], &[0x81, 0]);
        source.close(None).unwrap();
        let _ = source.read();
        drop(source);
        let mut destination = connect(receiver);
        destination
            .send(Message::Binary(vec![2, 0, 1].into()))
            .unwrap();
        let bytes = destination.read().unwrap().into_data();
        assert_eq!(bytes[0], 0x82);
        let result = Envelope::decode(&bytes[49..]).unwrap();
        assert_eq!(destination.read().unwrap().into_data().as_ref(), &[0x83]);
        destination.close(None).unwrap();
        let _ = destination.read();
        drop(destination);
        server.join().unwrap();
        std::fs::remove_file(path).unwrap();
        result
    }

    async fn bundle(store: &mut InMemSignalProtocolStore, device: DeviceId) -> PreKeyBundle {
        let mut rng = OsRng.unwrap_err();
        let one = KeyPair::generate(&mut rng);
        let signed = KeyPair::generate(&mut rng);
        let pq = kem::KeyPair::generate(kem::KeyType::Kyber1024, &mut rng);
        let identity = store.get_identity_key_pair().await.unwrap();
        let signature = identity
            .private_key()
            .calculate_signature(&signed.public_key.serialize(), &mut rng)
            .unwrap();
        let pq_signature = identity
            .private_key()
            .calculate_signature(&pq.public_key.serialize(), &mut rng)
            .unwrap();
        store
            .save_pre_key(1.into(), &PreKeyRecord::new(1.into(), &one))
            .await
            .unwrap();
        store
            .save_signed_pre_key(
                2.into(),
                &SignedPreKeyRecord::new(
                    2.into(),
                    Timestamp::from_epoch_millis(1),
                    &signed,
                    &signature,
                ),
            )
            .await
            .unwrap();
        store
            .save_kyber_pre_key(
                3.into(),
                &KyberPreKeyRecord::new(
                    3.into(),
                    Timestamp::from_epoch_millis(1),
                    &pq,
                    &pq_signature,
                ),
            )
            .await
            .unwrap();
        let result = PreKeyBundle::new(
            store.get_local_registration_id().await.unwrap(),
            device,
            Some((1.into(), one.public_key)),
            2.into(),
            signed.public_key,
            signature.to_vec(),
            3.into(),
            pq.public_key,
            pq_signature.to_vec(),
            *identity.identity_key(),
        )
        .unwrap();
        eprintln!(
            "bundle public key/signature material bytes={}",
            identity.identity_key().serialize().len()
                + one.public_key.serialize().len()
                + signed.public_key.serialize().len()
                + signature.len()
                + result.kyber_pre_key_public().unwrap().serialize().len()
                + pq_signature.len()
        );
        result
    }

    async fn encrypt(
        store: &mut InMemSignalProtocolStore,
        remote: &ProtocolAddress,
        local: &ProtocolAddress,
        header: Header,
        body: &[u8],
    ) -> Envelope {
        let mut clear = header.binding().to_vec();
        clear.extend_from_slice(body);
        let message = message_encrypt(
            &clear,
            remote,
            local,
            &mut store.session_store,
            &mut store.identity_store,
            SystemTime::now(),
            &mut OsRng.unwrap_err(),
        )
        .await
        .unwrap();
        let mut bytes = vec![message.message_type() as u8];
        bytes.extend_from_slice(message.serialize());
        Envelope::new(header, bytes).unwrap()
    }

    async fn decrypt(
        store: &mut InMemSignalProtocolStore,
        remote: &ProtocolAddress,
        local: &ProtocolAddress,
        envelope: &Envelope,
    ) -> Result<Vec<u8>, &'static str> {
        let bytes = envelope.ciphertext();
        let message = match bytes[0] {
            2 => CiphertextMessage::SignalMessage(
                SignalMessage::try_from(&bytes[1..]).map_err(|_| "parse")?,
            ),
            3 => CiphertextMessage::PreKeySignalMessage(
                PreKeySignalMessage::try_from(&bytes[1..]).map_err(|_| "parse")?,
            ),
            _ => return Err("type"),
        };
        // Clone staging is sufficient for THIS in-memory spike only. A real
        // adapter requires transactional durable stores, bounded state and erasure.
        let mut staged = store.clone();
        let clear = message_decrypt(
            &message,
            remote,
            local,
            &mut staged.session_store,
            &mut staged.identity_store,
            &mut staged.pre_key_store,
            &staged.signed_pre_key_store,
            &mut staged.kyber_pre_key_store,
            &mut OsRng.unwrap_err(),
        )
        .await
        .map_err(|_| "authentication")?;
        if clear.get(..68) != Some(envelope.header().binding().as_slice()) {
            return Err("binding");
        }
        *store = staged;
        Ok(clear[68..].to_vec())
    }

    #[test]
    fn encrypted_message_survives_relay_restart_and_receipt_returns() {
        futures::executor::block_on(async {
            let mut rng = OsRng.unwrap_err();
            let mut a =
                InMemSignalProtocolStore::new(IdentityKeyPair::generate(&mut rng), 1).unwrap();
            let mut c =
                InMemSignalProtocolStore::new(IdentityKeyPair::generate(&mut rng), 2).unwrap();
            let device = DeviceId::new(1).unwrap();
            let a_address = ProtocolAddress::new("a-experiment".into(), device);
            let c_address = ProtocolAddress::new("c-experiment".into(), device);
            let prekeys = bundle(&mut c, device).await;
            // Trusted in-memory exchange, NOT an implemented QR pairing flow.
            a.save_identity(&c_address, prekeys.identity_key().unwrap())
                .await
                .unwrap();
            c.save_identity(
                &a_address,
                a.get_identity_key_pair().await.unwrap().identity_key(),
            )
            .await
            .unwrap();
            process_prekey_bundle(
                &c_address,
                &a_address,
                &mut a.session_store,
                &mut a.identity_store,
                &prekeys,
                SystemTime::now(),
                &mut rng,
            )
            .await
            .unwrap();
            let unix_ms = yap_relay_server::clock().unwrap().unix_ms;
            let header = Header {
                id: [1; 16],
                destination: [2; 16],
                sender_hint: [3; 16],
                created_ms: unix_ms,
                expires_ms: unix_ms + 1_000_000,
                hops: 8,
            };
            let text = vec![b'Y'; 1024];
            let encrypted = encrypt(&mut a, &c_address, &a_address, header, &text).await;
            assert!(
                !encrypted
                    .ciphertext()
                    .windows(text.len())
                    .any(|w| w == text)
            );
            let path =
                std::env::temp_dir().join(format!("yap-signal-spike-{}.redb", std::process::id()));
            assert!(!path.exists(), "use a fresh spike database");
            let config = RelayConfig {
                queue_envelopes: 16,
                queue_bytes: 1_048_576,
                recent_entries: 16,
                recent_lifetime: Duration::from_secs(600),
                max_residence: Duration::from_secs(86_400),
                max_connections: 8,
                rate_window: Duration::from_secs(60),
                per_connection: Budget {
                    envelopes: 60,
                    bytes: 262_144,
                },
                global: Budget {
                    envelopes: 120,
                    bytes: 524_288,
                },
                max_future_skew_ms: 0,
                evict_oldest_when_full: false,
            };
            let clock = Clock {
                unix_ms,
                monotonic: Instant::now(),
            };
            {
                let mut b = Relay::open(&path, config.clone(), clock).unwrap();
                assert!(matches!(
                    b.admit(&encrypted.encode(), [1; 16], clock).unwrap(),
                    Admission::Stored(_)
                ));
            }
            let mut b = Relay::open(&path, config.clone(), clock).unwrap();
            let incoming = b.pending_for([2; 16], clock, 1).unwrap().pop().unwrap();
            let delivered = internet_hop(&incoming.envelope, config.clone());
            let mut corrupt = incoming.envelope.ciphertext().to_vec();
            *corrupt.last_mut().unwrap() ^= 1;
            let corrupt = Envelope::new(header, corrupt).unwrap();
            assert!(
                decrypt(&mut c, &a_address, &c_address, &corrupt)
                    .await
                    .is_err()
            );
            let wrong_outer = Envelope::new(
                Header {
                    destination: [4; 16],
                    ..header
                },
                incoming.envelope.ciphertext().to_vec(),
            )
            .unwrap();
            assert_eq!(
                decrypt(&mut c, &a_address, &c_address, &wrong_outer).await,
                Err("binding")
            );
            assert_eq!(
                decrypt(&mut c, &a_address, &c_address, &delivered)
                    .await
                    .unwrap(),
                text
            );
            assert!(
                decrypt(&mut c, &a_address, &c_address, &incoming.envelope)
                    .await
                    .is_err()
            );
            b.transfer_custody(incoming.key, clock).unwrap();
            let receipt = encrypt(
                &mut c,
                &a_address,
                &c_address,
                Header {
                    id: [5; 16],
                    destination: [6; 16],
                    sender_hint: [7; 16],
                    ..header
                },
                b"received:experiment-message-1",
            )
            .await;
            assert!(matches!(
                b.admit(&receipt.encode(), [2; 16], clock).unwrap(),
                Admission::Stored(_)
            ));
            let returned = b.pending_for([1; 16], clock, 1).unwrap().pop().unwrap();
            let delivered_receipt = internet_hop(&returned.envelope, config);
            assert_eq!(
                decrypt(&mut a, &c_address, &a_address, &delivered_receipt)
                    .await
                    .unwrap(),
                b"received:experiment-message-1"
            );
            eprintln!(
                "initial_envelope_bytes={} receipt_envelope_bytes={} a_session_bytes={} c_session_bytes={} session_version={}",
                encrypted.encode().len(),
                receipt.encode().len(),
                a.load_session(&c_address)
                    .await
                    .unwrap()
                    .unwrap()
                    .serialize()
                    .unwrap()
                    .len(),
                c.load_session(&a_address)
                    .await
                    .unwrap()
                    .unwrap()
                    .serialize()
                    .unwrap()
                    .len(),
                a.load_session(&c_address)
                    .await
                    .unwrap()
                    .unwrap()
                    .session_version()
                    .unwrap()
            );
            let mut max_envelope = encrypted.encode().len();
            let mut max_session = 0;
            for index in 10u8..110 {
                let sent = encrypt(
                    &mut a,
                    &c_address,
                    &a_address,
                    Header {
                        id: [index; 16],
                        ..header
                    },
                    &text,
                )
                .await;
                max_envelope = max_envelope.max(sent.encode().len());
                assert_eq!(
                    decrypt(&mut c, &a_address, &c_address, &sent)
                        .await
                        .unwrap(),
                    text
                );
                let response = encrypt(
                    &mut c,
                    &a_address,
                    &c_address,
                    Header {
                        id: [index + 120; 16],
                        destination: [6; 16],
                        sender_hint: [7; 16],
                        ..header
                    },
                    b"received",
                )
                .await;
                max_envelope = max_envelope.max(response.encode().len());
                assert_eq!(
                    decrypt(&mut a, &c_address, &a_address, &response)
                        .await
                        .unwrap(),
                    b"received"
                );
                for record in [
                    a.load_session(&c_address).await.unwrap().unwrap(),
                    c.load_session(&a_address).await.unwrap().unwrap(),
                ] {
                    max_session = max_session.max(record.serialize().unwrap().len());
                }
            }
            eprintln!(
                "established_roundtrips=100 observed_max_envelope_bytes={max_envelope} observed_max_session_bytes={max_session}"
            );
            drop(b);
            std::fs::remove_file(path).unwrap();
        });
    }
}
