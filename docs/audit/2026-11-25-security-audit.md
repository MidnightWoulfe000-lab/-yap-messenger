# Security audit, 25 November 2026

**Audience: coding agents and maintainers.** Read this before touching `endpoint/`,
`native/android/`, `relay-server/`, `core/` or the Android Bluetooth/relay code.

- Scope: branch `codex/android-compose-ui` (which holds the real crypto, relay and
  networking code). `main` is only the old Bluetooth prototype.
- Method: manual code reading. **Nothing was fuzzed. libsignal itself was not
  reviewed. This is not an independent audit** and must not be described as one
  (see `docs/SECURITY.md` release guardrails).
- Baseline: `cargo test --locked` in `endpoint/` passed before and after the patch.
  Android/Gradle was not built in this session.

## Why most findings are not patched

`AGENTS.md` says never to invent cryptographic or group-governance policy, and to
write a specification for review first. Most findings below need a protocol or
policy decision (new authentication on the mesh HELLO, a rollback counter, history
retention, clock policy). Those are recorded as **needs spec**, not implemented.
Only changes that are mechanical and testable were made.

## Status

| # | Finding | Severity | Status |
|---|---|---|---|
| 1 | BLE `METADATA` characteristic returns the stable 16-byte route token to any unauthenticated reader (tracking, and links physical presence to the relay mailbox). `MeshBluetooth.kt` | Medium | **Fixed for the link layer** in [local mesh v2](../proposals/local-mesh-v2.md): links use a random ID rotated every 10–20 min; pairing bundles (identity key) are no longer broadcast. See 1b. |
| 1b | Envelope headers still carry the recipient's permanent route token and a per-contact sender hint, and envelopes go to every nearby link. Nearby modified phones can still recognise a recipient over time. | Medium | **Open; plan written** in [rotating address labels](../proposals/rotating-address-labels.md), awaiting review |
| 2 | Mesh `HELLO` is unauthenticated, and `peers.putIfAbsent(token)` lets the first claimant of a token hold it. A nearby attacker who read the token can block the real contact from connecting. Content stays safe (envelopes are verified), delivery does not. | Medium | **Reduced** by v2: IDs rotate, so blocking lasts at most one rotation; duplicate links resolve deterministically. Link authentication still **needs spec** |
| 3 | `State::check` requires `now >= last_time`. A forward clock jump that gets persisted locks the identity until real time catches up, and pruning meanwhile expires offers and outbox items early. `endpoint/src/engine.rs` | Medium | **Open, needs policy** |
| 4 | Snapshot has constant AAD and no version counter, so an older valid snapshot can be restored (needs file write access). That rewinds the ratchet and reuses message keys. `EndpointRepository.kt` | Medium (low in practice) | **Open, needs spec** |
| 5 | A paired contact can fill `messages` to 512 (or leave 64 unrelayed control items) and block sending and receiving until the user clears history. | Medium | **Open, needs product decision** |
| 6 | Whole decrypted state (identity private key, sessions) crosses JNI as a Java `String`/`JSONObject`. Cannot be wiped; Rust `Bytes` has no zeroize. Keystore protects data at rest only. | Medium | **Open, document + design** |
| 7 | `receive` runs `message_decrypt` (X3DH + Kyber for prekey messages) before any cheap rejection, and every op re-parses up to 8 MB of state. Anyone who knows a sender hint can burn CPU. | Low–medium | **Open** |
| 8 | Every op, including `view` and `mark_read`, re-encrypts and fsyncs the full snapshot (flash wear, battery). | Low–medium | **Open** |
| 9 | Pairing by pasted package has no out-of-band check; QR commitment only helps when the QR is scanned in person. UI warns, engine does not enforce a fingerprint check. | Low | **Open** |
| 10 | Mesh uses insecure L2CAP; inbound peers take one of 8 slots before authenticating. | Low | **Open** |
| 11 | JNI robustness: poisoned `RELAYS` mutex disabled relays until restart; arbitrary host `path`; possible null return to Kotlin; pending Java exception not cleared. | Low | **Fixed** (below) |
| 12 | Message time shown is sender-controlled `created_ms` (window roughly -24 h to +5 min). | Low | Open; treat as untrusted in UI |
| 13 | One Keystore key for the life of the install, so no forward secrecy at rest. | Low | Open, design |

Also open and not touched: GitHub Actions use floating tags (pin to commit SHAs);
no `cargo audit`/`cargo deny`/OSV scan in CI; `libsignal-protocol` is pinned to a git
rev, so it gets no automatic advisories; `BoundedWebSocket.kt`, `Envelope::decode`
and `PublicBundle::decode` have not been fuzzed.

## What was changed (finding 11)

All in `native/android/src/lib.rs`:

1. **`check_path()`**: host-supplied paths (`relay_*` ops and `storage_sync`) must
   be absolute, non-empty, under 4096 bytes, NUL-free and contain no `.` or `..`
   segment. Why: the native layer opened a redb database at any path it was given.
   The Kotlin host is trusted today, so this is defence in depth. Interior `.`
   needs a raw-segment check because `Path::components()` normalises it away.
   *Not done:* confining paths to `noBackupFilesDir` (Rust does not know it). If you
   add that, pass the allowed root in from Kotlin once at start-up.
2. **Poisoned mutex recovery**: if `RELAYS` is poisoned, drop every open `Relay`,
   clear the poison and reopen from disk on demand. Why: a single panic used to
   disable relay storage until the app restarted. redb is transactional, so
   durable state is unaffected; only the in-memory handles are discarded.
3. **No null return, no stale exception**: clear any pending Java exception before
   creating the response string, and fall back to a fixed JSON error if string
   creation fails. Why: Kotlin treats the result as non-null `String`, and a
   pending exception makes later JNI calls fail.
4. Unit tests for `check_path` (accepts a normal Android path; rejects empty,
   relative, `./`, `..`, interior `.`, NUL and over-long paths).

The deliberate empty panic hook was **kept**. It stops private state reaching
logcat. Do not replace it with a logging hook without reviewing what a panic
message can contain.

## Rules for agents following up

- Do not claim these are fixed because a doc says so. Check the code.
- For items marked *needs spec*: write a short proposal under `docs/` (threat,
  options, chosen design, test vectors), get review, then implement. Do not pick a
  protocol or retention policy yourself.
- Owner direction (25 Nov 2026): nothing sent to strangers may be permanent or easy to track; keep the app simple for users. Wi-Fi nearby messaging was added on that basis.
- Suggested order: 1b and 2 together (stop serving the route token to
  unauthenticated readers; bind HELLO to proof of key possession), then 4 and 3,
  then 5, then 7.
- Keep `cargo test --locked` green in `core/`, `endpoint/`, `relay-server/` and
  `native/android/`. libsignal needs `protoc` installed to build
  (`apt-get install protobuf-compiler`).
- Do not describe the app as end-to-end encrypted or verified in UI or docs until
  the open items that affect those claims are resolved and independently reviewed.
