# Yap architecture (draft)

Yap is an experimental, free, cross-platform, offline-first messenger.

## Components
- `core/`: portable Rust messaging and synchronisation logic.
- `android/`: Kotlin UI and Android radio transports (planned).
- `ios/`: Swift UI and iOS radio transports (planned).
- `docs/`: protocol, threat model and governance specifications.

## First milestone
Demonstrate message A → B → C using a simulated store-and-forward network, then test real Bluetooth Low Energy links on physical devices.

## Security boundary
Early prototypes are **not secure for sensitive communications**. Group encryption, authenticated identity, key rotation, revocation, partition handling and independent security review must be completed before any high-risk deployment.
