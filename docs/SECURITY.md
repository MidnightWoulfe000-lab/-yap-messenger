# Yap security: decisions, requirements and open questions

**Status: security design in progress. The current Android BLE prototype is not a secure messenger.**

This document preserves the security direction discussed for Yap. It is **not** a completed cryptographic protocol or an audit. If a detail is not specified, ask rather than silently choosing one.

## Security goals
- End-to-end encrypted conversations and groups, with relays unable to decrypt payloads.
- Authenticated pseudonymous identities, signed group membership and signed governance events.
- No mandatory central authority, server, SIM or phone-number account.
- Secure multi-device authorisation and device revocation.
- Store-and-forward resilience to outages and partitions, with bounded resource use.
- Minimise exposure of message content and metadata. Offline mesh cannot guarantee anonymity or conceal radio proximity.

## Threats to address
- Malicious nearby peers, impersonation, replay, forged messages, compromised relay nodes, malicious group members, stolen or compromised devices, spam and resource exhaustion.
- Network partition, delayed messages, conflicting group governance updates and hostile attempts to manipulate approvals or votes.
- Traffic analysis, Bluetooth device tracking and platform-level permissions/logging.
- Compromised operating systems and physical access cannot be solved solely by app-layer encryption.

## Required protocol properties
1. **Identity:** cryptographically generated keys; explicit verification UX; device-level keys and authorisation; signed revocation.
2. **Encryption:** use established, reviewed primitives and protocols; authenticated encryption, fresh nonces, replay protection, key separation, forward secrecy where feasible, and a documented recovery/rotation story. No homemade crypto.
3. **Groups:** authenticated membership changes, encrypted group state, independently verifiable approvals, objection windows and votes. Define who can vote, quorum, thresholds, timeouts, ordering and partition resolution before writing code.
4. **Routing:** opaque encrypted envelopes; message IDs and bounded TTL; deduplication; rate limits; safe queue expiry; no trusted-relay assumption.
5. **Persistence:** protect keys at rest using platform facilities; define backup, multi-device recovery and deletion semantics. Avoid logging secrets and plaintext.
6. **UX:** distinguish discovered, connected, identity-verified, queued, relayed, transport-acknowledged, delivered and read. Never infer delivery or identity from BLE success.
7. **Testing:** protocol test vectors, malformed packet handling, replay and tampering tests, lost-device/revocation tests, partition/rejoin tests, and independent review.

## Decisions still requiring an explicit specification
- Exact cryptographic protocol, primitives, identity-key hierarchy and group key schedule.
- Whether identities are recoverable, how new devices join, and how a lost device is revoked while peers are offline.
- Group approval thresholds, voting rules, objection period duration and handling of competing histories during partitions.
- Message TTL, queue/storage quotas, routing incentives or abuse protections, and metadata minimisation trade-offs.
- Key verification UX, backup strategy and whether any internet relay services exist.
- Threat model and security guarantees for each release stage.

## Release guardrails
- Never advertise this prototype as encrypted, anonymous, verified or safe for sensitive communication.
- Do not implement cryptographic or governance features from assumptions. Write a design proposal, review its threat model, then implement and test.
- Do not break known-good Bluetooth messaging as a side effect of UI work.
- Do not claim an external security audit has happened unless it has.

## Present state
The November 2026 Android prototype uses unauthenticated, unencrypted, foreground-only BLE messages of up to 20 UTF-8 bytes, with no saved history. Two-phone exchange has been demonstrated in user tests. This does not establish confidentiality, authenticity or reliable delivery.
