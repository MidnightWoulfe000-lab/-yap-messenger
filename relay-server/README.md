# Experimental internet custody relay

This is a user-configured TLS WebSocket relay and synchronous client for opaque
Yap envelopes. It reuses the Rust core's durable queue and never receives endpoint
decryption keys. It has not been deployed or independently security-audited.
The Android app is not connected to it.

```sh
cargo test --locked --manifest-path relay-server/Cargo.toml
cargo clippy --locked --manifest-path relay-server/Cargo.toml --all-targets -- -D warnings
```

On Linux, install the OpenSSL development package and pkg-config. Windows uses
native TLS. The test certificate/private key in `tests/fixtures` is PUBLIC TEST
MATERIAL, password `yap-test-only`. It is never selected by the executable and
must not be used for a real endpoint.

## Configure and run locally

Supply a private JSON file matching this structure, with a certificate valid for
the configured hostname and separately generated random base64url credentials:

```json
{
  "listen": "127.0.0.1:8443",
  "identity_pfx": "/absolute/path/to/your-own-identity.p12",
  "database": "/absolute/path/to/relay.redb",
  "max_connections": 8,
  "global_envelopes_per_minute": 240,
  "global_bytes_per_minute": 1048576,
  "requests_per_connection_per_minute": 120,
  "requests_global_per_minute": 480,
  "request_bytes_global_per_minute": 2097152,
  "max_future_skew_ms": 0,
  "accounts": [
    {
      "bearer": "<replace-with-a-random-32-to-128-character-base64url-secret>",
      "peer": [1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0],
      "mailbox": [2,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0]
    }
  ]
}
```

The placeholder credential is deliberately invalid. Add separate accounts for
sender and recipient; self-addressed relay submissions are rejected. Provision
peer/mailbox IDs explicitly; they are transport routing labels, not verified user
identities. The numerical global limits above are local experiment settings, not
reviewed deployment recommendations. Set `YAP_RELAY_IDENTITY_PASSWORD` in the
environment, then run:

```sh
cargo run --locked --manifest-path relay-server/Cargo.toml -- path/to/private-config.json
```

Keep the JSON credentials, PFX and database under host access controls. The
executable reads only the specified configuration, has no default public listener
or hosted endpoint, and does not register accounts dynamically. A fixed worker
pool and bounded socket queue limit concurrency; TLS/idle and write deadlines
release blocked slots. Global request count/byte limits cover malformed requests
and polling as well as submissions. Per-account core budgets survive connection
changes within a running process. Handshake churn, fairness and disk allocation
still need deployment load tests.

## Client and access rules

Use `client::Client::connect` with an explicit `wss://hostname:port/yap/v1` URL,
bearer credential and optionally an explicit CA certificate. System certificate
and hostname validation remain enabled. Plain `ws://`, URL credentials and query
strings are rejected. DNS resolution uses the system resolver; socket connect,
TLS/read and write operations have time limits. Close the client after a protocol
or I/O error rather than attempting to reuse a partial exchange.

`submit` returns custody status, never a delivery receipt. `poll` reads only the
authenticated account's mailbox, up to 16 records. Call `acknowledge_custody` only
after committing local acceptance. Polling alone leaves the relay copy intact.
Only the mailbox owner may release a queued record, even if another account knows
its digest. A public destination or sender hint grants no mailbox authority.

Requests are binary and versioned by the `/yap/v1` path. The
[implementation notes](../docs/messaging-implementation.md) specify the byte layout.
There is no plaintext socket fallback, browser Origin access, arbitrary proxying,
internet peer discovery or assurance that a hostile relay will deliver a message.

Tests exercise real local TLS connections, certificate rejection, credential
rejection, recipient-only fetch/deletion, process restart between submissions and
polls, message-size limits, request budgets and the typed client. The separate
[Signal experiment](../experiments/signal-mesh/README.md) carries real endpoint
ciphertext through this service in both directions.
