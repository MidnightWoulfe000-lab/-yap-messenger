# Yap security: decisions, requirements and open questions

**Status: experimental implementation; no independent security audit.** Android
0.2 debug now includes tested native one-to-one encryption, mutual key pairing
and Keystore-backed snapshots. The old unencrypted Bluetooth chat has been removed; all messaging goes through the
encrypted endpoint. The
[endpoint contract](android-endpoint-v1.md) specifies this limited release;
[release boundaries](debug-release-0.2.md) distinguish emulator evidence from
pending physical mesh, group and recovery work (multiple devices per person are no longer planned).

This document preserves the security direction discussed for Yap. It is **not** a completed cryptographic protocol or an audit. If a detail is not specified, ask rather than silently choosing one.

## Security goals
- End-to-end encrypted conversations and groups, with relays unable to decrypt payloads.
- Authenticated pseudonymous identities, signed group membership and signed governance events.
- No mandatory central authority, server, SIM or phone-number account.
- One device per identity (owner, 27 November 2026). A lost device is cut off by its contacts removing it and by group revocation of the member; the person starts again with a new identity.
- Store-and-forward resilience to outages and partitions, with bounded resource use.
- Minimise exposure of message content and metadata. Offline mesh cannot guarantee anonymity or conceal radio proximity.

## Threats to address
- Malicious nearby peers, impersonation, replay, forged messages, compromised relay nodes, malicious group members, stolen or compromised devices, spam and resource exhaustion.
- Network partition, delayed messages, conflicting group governance updates and hostile attempts to manipulate approvals or votes.
- Traffic analysis, Bluetooth device tracking and platform-level permissions/logging.
- Compromised operating systems and physical access cannot be solved solely by app-layer encryption.

## Required protocol properties
1. **Identity:** cryptographically generated keys; explicit verification UX; one device per identity; signed revocation of members.
2. **Encryption:** use established, reviewed primitives and protocols; authenticated encryption, fresh nonces, replay protection, key separation, forward secrecy where feasible, and a documented recovery/rotation story. No homemade crypto.
3. **Groups:** authenticated membership changes, encrypted group state, independently verifiable approvals, objection windows and votes. Define who can vote, quorum, thresholds, timeouts, ordering and partition resolution before writing code.
4. **Routing:** opaque encrypted envelopes; message IDs and bounded TTL; deduplication; rate limits; safe queue expiry; no trusted-relay assumption.
5. **Persistence:** protect keys at rest using platform facilities; define backup and deletion semantics (see the [local storage proposal](proposals/local-storage-v1.md)). Avoid logging secrets and plaintext.
6. **UX:** distinguish discovered, connected, identity-verified, queued, relayed, transport-acknowledged, delivered and read. Never infer delivery or identity from BLE success.
7. **Testing:** protocol test vectors, malformed packet handling, replay and tampering tests, lost-device/revocation tests, partition/rejoin tests, and independent review.

## Decisions still requiring an explicit specification
- Exact cryptographic protocol, primitives, identity-key hierarchy and group key schedule.
- How a lost device is revoked while peers are offline. (Identities are not recoverable and there are no extra devices: owner, 27 November 2026.)
- Group approval thresholds, voting rules, objection period duration and handling of competing histories during partitions.
- Message TTL, queue/storage quotas, routing incentives or abuse protections, and metadata minimisation trade-offs.
- Key verification UX, backup strategy and whether any internet relay services exist.
- Threat model and security guarantees for each release stage.

## Release guardrails
- Never advertise anonymity, an independent audit or safety for sensitive
  communication from these development tests.
- Do not implement cryptographic or governance features from assumptions. Write a design proposal, review its threat model, then implement and test.
- Do not break known-good Bluetooth messaging as a side effect of UI work.
- Do not claim an external security audit has happened unless it has.

## Present state
The old November 2026 unencrypted Bluetooth chat (unauthenticated, 20 UTF-8 bytes, no
saved history) was removed in the v3.1 clean-ups (owner decision, 27 November 2026). The
0.2 paired endpoint uses the separately reviewed contract linked above. Its native encryption and
persistence tests do not validate physical radio interoperability or establish
production readiness.

## Known issues
A manual (non-independent) code review on 25 November 2026 recorded open issues, including a stable route token readable over BLE, unauthenticated mesh HELLO, no snapshot rollback protection and clock-lockout behaviour. See [audit/2026-11-25-security-audit.md](audit/2026-11-25-security-audit.md).
