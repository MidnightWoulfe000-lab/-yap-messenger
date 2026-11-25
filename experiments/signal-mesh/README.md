# Pinned Signal mesh feasibility experiment

This AGPL-3.0-only test package is isolated from Yap's GPL core and Android build.
It depends on libsignal v0.104.0, commit
`257105c55a7389ca6b1e85185e2769465e6729f1`, with transitive dependencies recorded
in its own lockfile. It is not a supported third-party Signal SDK or a production
messaging implementation. See [libsignal's README](https://github.com/signalapp/libsignal/tree/257105c55a7389ca6b1e85185e2769465e6729f1).

The test establishes trusted in-memory A/C sessions through the public prekey API,
encrypts 1 KiB with an inner copy of immutable envelope metadata, persists the
opaque payload at an intermediary, restarts that relay, and carries the payload
through a real local TLS WebSocket service. C decrypts it and returns an encrypted
receipt through the relay and another TLS connection. B and the internet service
receive no endpoint key stores. Tampering, outer-metadata mismatch and replay are
rejected; failed decrypts discard cloned staging stores.

## Run

Install Rust 1.100.0, Protobuf `protoc`, and the platform TLS prerequisites
described in the [relay README](../../relay-server/README.md). Point `PROTOC` at
the executable if it is not on PATH.

```sh
cargo test --locked --manifest-path experiments/signal-mesh/Cargo.toml -- --nocapture
```

On Windows GNU, put the toolchain's `bin/self-contained` directory and a working
GNU assembler plus its runtime DLLs on PATH. The test logs sizes only, never keys,
plaintext or bundles. It uses the PUBLIC TEST certificate in the relay fixtures.

Observed for one initial exchange: public key/signature material 1,796 bytes,
initial envelope 2,922 bytes, receipt envelope 242 bytes, A session 4,294 bytes and
C session 635 bytes. A further 100 established in-memory round trips observed a
maximum envelope of 2,922 bytes and session state of 6,958 bytes. These round trips
are not the proposed 100 physical-topology trials. The library reports session version 4. Public material size
is not a serialized pairing bundle size, and these are sample measurements rather
than worst-case bounds. The pin includes SPQR 1.6.0, but this test does not prove
long-run SPQR epoch coverage or post-quantum recovery properties.

There is no implemented QR flow, sender-hint registry, durable endpoint staging
store, Keystore integration, backup recovery or Android/iOS binding. Endpoint
secrets live in memory. Cloning a test store is not a secure production erasure or
transaction strategy. Receipt generation here is an experiment using in-memory
acceptance, not the durable recipient acceptance required by the production design.
The iOS distribution/licence review remains open; this package is not linked into
or distributed as the Yap mobile application.
