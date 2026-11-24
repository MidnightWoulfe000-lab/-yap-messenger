# Development plan

1. Portable Rust simulation: unique message IDs, deduplication, hop limits, bounded queues and tests.
2. Android Bluetooth discovery and direct two-phone text exchange.
3. Android multi-hop store-and-forward with offline recovery.
4. iOS Bluetooth interoperability on a borrowed iPhone; test foreground and background separately.
5. Cryptographic identity, secure groups, membership voting, device revocation and partition reconciliation.
6. Threat modelling, fuzzing, independent security audit and reproducible release builds.

Continuous background relaying on iOS is not guaranteed. Cross-platform connectivity must be verified on real hardware.
