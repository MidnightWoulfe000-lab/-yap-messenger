# Yap architecture and product decisions

This is a handover of decisions from the Yap planning conversations, not a claim that the features are implemented. Read with [SECURITY.md](SECURITY.md) and [design-system.md](design-system.md).

## Purpose and principles
- **Yap.** Motto: **Nobody gets to silence you.** Mission: **Communication is a right, not a privilege.**
- Free, open-source, GPLv3, decentralised and offline-first. No mandatory central server or internet connection for nearby communication.
- Android and iOS are intended targets. No phone number should be a prerequisite for participation.
- Resist censorship and single points of failure. Never promise absolute anonymity, security or unstoppable connectivity.

## Architecture direction
- **Shared Rust core** for identity, cryptography, message handling, routing, persistence and group governance, with native Kotlin Android and Swift iOS interfaces.
- **Transports:** Bluetooth Low Energy for local discovery/exchange; Wi-Fi and internet paths when available and suitable. Transport is separate from message semantics and security.
- **Store-and-forward mesh:** devices may relay opaque encrypted messages across intermittent connections. Plan for stable message IDs, deduplication, expiry/TTL, bounded storage, retry/backoff and delivery states.
- A three-device **A → B → C** test is a key milestone. A relay must not need to read message contents.
- Support partitions and reconnection without silently discarding valid messages. Specify conflict handling before implementing it.
- iOS and Android background radio restrictions are real constraints. Do not claim continuous background mesh functionality without platform tests.

## Identity and membership
- Pseudonymous cryptographic identity rather than a phone-number directory.
- Multiple devices per identity are a design goal. Device keys, authorisation and revocation need explicit protocols.
- Group membership must be cryptographically authenticated. Merely discovering a Bluetooth device does not establish identity.

## Group governance direction
- Invitations and membership changes are not unilateral: independent member approval, objection windows and group votes are part of the planned model.
- Membership decisions, permissions and device revocation require signed records and consistent verification rules.
- Partition behaviour, vote thresholds, time windows, tie handling, membership changes during votes and recovery are **not fully specified** here. Do not invent policy in implementation. Surface the decision for review first.

## Message model and product behaviour
- Message queueing, forwarding, acknowledgement and actual delivery are distinct. A Bluetooth write acknowledgement is not proof of end-user delivery or reading.
- Conversation history, encrypted rooms, group messaging, contacts and message details are planned features, not currently available.
- Prefer minimal metadata and user control over storage and retention. Storage limits and expiry policy need a written decision.

## Current implementation, November 2026
- Android proof of concept exchanges short messages between two physical phones using BLE GATT, limited to 20 UTF-8 bytes.
- Foreground-only, no verified peer identity, no end-to-end encryption, no persistent history, no relays or group governance.
- Jetpack Compose redesign is in progress. Check GitHub Actions before assuming the latest branch builds.
- Keep the proven BLE transport stable while changing UI. Tests on two actual phones remain necessary.

## Delivery sequence
1. Restore a clean Android build and preserve the tested two-phone send/receive flow.
2. Match the approved original six-screen mockup; ensure keyboard, insets, navigation and composer behaviour.
3. Introduce robust message framing and longer messages with transport tests.
4. Design and review threat model, cryptographic identity, key management and secure message format.
5. Implement and test authenticated end-to-end encryption before claiming privacy.
6. Add persistence, retry/deduplication, bounded queues and multi-hop relay; test three devices.
7. Specify and implement signed group governance and revocation with partition tests.
8. Port the shared protocol to iOS, validating real platform constraints.
9. Independent security review before positioning Yap for high-risk communication.

Changes to protocol, security or governance require explicit design review and tests. Do not quietly reinterpret this handover as a finished protocol specification.
