# Development plan

## Current work

The [implementation notes](messaging-implementation.md) record the current code:
durable bounded Rust relaying, validated envelopes, record streams, a private TLS
WebSocket relay/client and an isolated pinned-Signal encrypted relay experiment.
Android 0.2 debug now links the endpoint and relay core, with mutual pairing,
Keystore-backed snapshots, foreground L2CAP and a private internet relay client.

The supplied 24 November [audit](protocol-audit-response.md) has informed the
implementation and the [Android endpoint contract](android-endpoint-v1.md).
Since then (25 November 2026): Wi-Fi nearby messaging, rotating link IDs, no more
broadcast pairing bundles, Android background running, a separate 10,000-message
carrier store and flood slow mode. See [local mesh v2](proposals/local-mesh-v2.md)
and the [main README](../README.md) for exactly what's done and what's left.

Next are real-phone tests (Wi-Fi, hotspot, A–B–C, locked phones), limited-copy
routing, rotating message address labels, broader fault/fuzz testing and an
independent security audit. Group governance requires the missing policy decisions
before implementation. iOS remains open. The [proposal](protocol-proposal.md) preserves the intended milestone;
its unimplemented endpoint requirements are not established by prototype tests.

## Original roadmap

1. Portable Rust simulation: unique message IDs, deduplication, hop limits, bounded queues and tests.
2. Android Bluetooth discovery and direct two-phone text exchange.
3. Android multi-hop store-and-forward with offline recovery.
4. iOS Bluetooth interoperability on a borrowed iPhone; test foreground and background separately.
5. Cryptographic identity, secure groups, membership voting, device revocation and partition reconciliation.
6. Threat modelling, fuzzing, independent security audit and reproducible release builds.

Continuous background relaying on iOS is not guaranteed. Cross-platform connectivity must be verified on real hardware.
