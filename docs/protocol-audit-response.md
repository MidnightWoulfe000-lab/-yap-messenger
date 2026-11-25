# Response to the encrypted messaging proposal audit

The supplied **Audit encrypted mesh and internet messaging proposal**, dated
24 November 2026, identifies 13 findings. The [revised proposal](protocol-proposal.md)
addresses them as design changes or explicit evidence requirements. This is not
a claim that the whole secure mobile milestone is complete. Subsequent authorised
work has implemented the relay/store/stream foundation, TLS WebSocket service,
encrypted endpoint and Android debug integration; see the
[implementation notes](messaging-implementation.md) and
[reviewed endpoint contract](android-endpoint-v1.md).
The original attached Word document has not been altered.
The original Rust ID-only/unbounded simulation has been replaced. Poisoning,
cache-churn and restart tests now exercise the implemented relay policy. Endpoint
identity, staged crypto stores, Keystore snapshots and mobile integration now
have emulator evidence. Physical mesh, independent audit, groups, iOS and recovery
remain outside the debug release's verified scope.

## Findings and disposition

| # | Finding | Response and remaining evidence |
| --- | --- | --- |
| 1 | No sender identification | Propose a receiver-assigned random public sender lookup token mapping to one paired session. No trial decryption across contacts or automatic session creation. Bind the hint inside authenticated content; review metadata exposure and pairing encoding. |
| 2 | Full bundle in QR | Replace with a compact identity/nonce/bundle commitment; transfer the bounded bundle locally. Require mutual confirmation, unique prekeys per attempt and deterministic initiation. Measure encoded QR size and scan success for the pin; no precise bundle-size claim is adopted from general knowledge. |
| 3 | Forged-ID deduplication poisoning | Relays key on ID plus a digest of the complete canonical immutable envelope, not just ciphertext. This also distinguishes altered immutable metadata. Endpoints deduplicate only after authentication, scoped to the verified contact. Add hostile-relay tests. |
| 4 | Full cache stops new work | Separate the durable queue/index from an evictable recent-forwarding cache with a shorter local lifetime. Preserve accepted custody, permit recent-cache LRU eviction, and record the replay/availability trade-off. Do not solve this merely by raising the cap. Measure memory and load-test churn. |
| 5 | Ratchet specification outdated | Qualification: the cited live specification already includes SPQR and Triple Ratchet in revision 4. The real gap is failing to state the pinned implementation's negotiated lifecycle and size bounds. Add that requirement and measurements; do not claim a particular build was tested. |
| 6 | iOS distribution | Make the applicable App Store distribution basis an explicit dependency gate. Historical permission for libsignal-protocol-c does not establish permission for a current libsignal pin. No licence change or legal compatibility conclusion is made here. |
| 7 | Associated data unavailable | Plan an encrypted canonical copy of immutable metadata and reject mismatches before staged-state commit. API support and precise encoding remain to be demonstrated against the pin. |
| 8 | Ambiguous key changes | A different identity is a new contact; replacement and broken-session recovery require explicit in-person re-pairing in this milestone. No silent update based on a matching name. |
| 9 | Backup rollback | Added explicit legacy, cloud and Android device-transfer exclusions alongside the existing manifest flag. Require Keystore-wrapped database keys, fail-closed restore handling and future old-backup/transfer tests. Configuration alone does not establish rollback resistance. |
| 10 | Non-transactional store callbacks | Specify staged writes across all session/identity/prekey stores, atomic commit with messages/queues/receipts and per-session serialization. Implement and test fault injection at every commit boundary before claiming atomicity. |
| 11 | Receipt duplicates | Store exact receipt ciphertext with acceptance; resend it for an exact accepted-envelope replay without decrypting again. Bound retry work, deduplicate receipts and never acknowledge a receipt with another receipt. |
| 12 | Framing versus GATT | Prototype L2CAP on API 29+ before adapter selection; keep legacy Android 8/9 GATT chat. New negotiation uses a separate service. A GATT fallback needs explicit reliability/flow control. A stream adapter must handle split/coalesced reads because the current decoder accepts only one record per push. |
| 13 | Rust dependency support | State that direct Rust-crate integration adds maintenance risk beyond the named Java/Swift/TypeScript products. Require a narrow adapter, exact pin and upgrade/interoperability checks. No third-party support is promised. |

Padding, clock skew and measurable milestone criteria are also added. Size buckets
and numerical clock tolerance remain decisions to review after library sizing and
offline-clock tests; they are not silently implemented defaults.

The audit's 16-byte cache-entry estimate counts only the ID. The proposed ID plus
SHA-256 digest already needs 48 bytes before expiry, indexing and allocation.
Its exact post-quantum bundle and QR figures were marked for checking by the
reviewer and are not treated as measurements of Yap.

## What may proceed

The user authorised implementation of the revised design. The core relay and
private internet service are implemented, with a pinned cryptographic feasibility
test. Production endpoint integration still requires concrete pairing/receipt
encoding, staged durable endpoint stores, mobile key protection and distribution
review. Approval of the direction and passing prototype tests do not establish
that the resulting mobile implementation is secure.

## Checks performed

The Android debug build and all nine Android unit tests pass after the
manifest/resource change. Lint completes with no errors, seven warnings and two
informational findings in unchanged code/configuration. The merged debug manifest references both rule files.
A local XML check confirms all nine documented app-data domains are excluded in
the legacy rules and each modern cloud/device-transfer section. Local Markdown
links and explicit references were checked. No cryptographic-state restore test
or physical-device transfer test has been run; the required session store does
not exist yet. These checks do not establish end-to-end backup exclusion on OEM
devices or validate the proposed mesh and crypto design.

## Sources checked

- [Signal ratchet specification, revision 4](https://signal.org/docs/specifications/doubleratchet/)
  and [SPQR announcement](https://signal.org/blog/spqr/): SPQR and Triple Ratchet.
- [libsignal README](https://github.com/signalapp/libsignal): named products and
  unsupported outside use; [store traits](https://github.com/signalapp/libsignal/blob/main/rust/protocol/src/storage/traits.rs): separate persistence callbacks.
- [libsignal licence](https://github.com/signalapp/libsignal/blob/main/LICENSE)
  and [historical licence announcement](https://signal.org/blog/license-update/):
  inputs to the pending distribution review, not a legal determination.
- [Android backup rules](https://developer.android.com/identity/data/autobackup)
  and [Android 12 transfer behaviour](https://developer.android.com/about/versions/12/behavior-changes-12#backup-restore): explicit backup/transfer controls.
- [Android L2CAP API](https://developer.android.com/reference/android/bluetooth/BluetoothDevice#createL2capChannel(int)):
  LE connection-oriented channels, API 29 and PSM negotiation.
- [DENSO WAVE QR versions](https://www.qrcode.com/en/about/version.html): capacity
  depends on symbol version, encoding and error correction.
