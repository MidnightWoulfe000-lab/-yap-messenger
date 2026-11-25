# Encrypted mesh and internet messaging: proposal

Status: revised after the supplied 24 November 2026 audit and authorised for
implementation. The relay foundation and internet prototype are implemented;
endpoint/mobile integration decisions remain open. See the
[implementation notes](messaging-implementation.md) and [audit response](protocol-audit-response.md).
This is an implementation plan and threat-model outline, not a finished
cryptographic specification or a completed implementation audit. No new security
guarantees apply to the Android prototype. Changes below are proposals for review.

## First milestone

Two previously paired devices, A and C, exchange an encrypted text message through
an untrusted device B. B stores the message, restarts, and forwards it after C
becomes reachable. A receives an authenticated recipient receipt. Repeat with
Bluetooth on A–B and an internet connection on B–C. Disable internet and prove
that the three-device Bluetooth path still works.

Proposed pass criteria: in 100 controlled trials per topology, with a 1 KiB text
payload and no injected faults after C becomes reachable, C durably accepts the
message and A receives its authenticated receipt within 30 seconds of a usable
link. Report failures and latency distributions, including connection setup time
separately. Require zero duplicate chat entries and zero forged receipts accepted
in all fault-injection trials. Include restart, forged-ID, full-cache and mixed
transport cases. These are test targets, not measured guarantees.

Start with one device per identity, one-to-one text and explicit contact pairing.
Groups, account recovery, additional devices and revocation remain separate
design work. Do not imply that an identity can be recovered or a lost device
remotely revoked in this milestone.

## Shared core and transports

Keep message creation, cryptographic session state, durable queues, deduplication
and receipt processing in Rust. Android owns permissions, BLE sessions, UI and
platform-protected key storage. A later Swift adapter uses the same core.

BLE and internet adapters carry opaque records. Neither adapter decides that a
message is delivered. A transport write acknowledgement means only that the next
link accepted bytes. The message and receipt keep their identity across transport
changes; retrying must reuse the stored ciphertext rather than encrypt again.

The experimental [framing module](../core/src/framing.rs) implements a four-byte
big-endian length followed by opaque payload bytes. It splits a record into writes
of a caller-supplied maximum size. It requires an ordered, reliable session with
one record at a time. The decoder bounds accepted payload and fragment sizes,
rejects trailing bytes, and clears partial state on error. Its caller must reset
on disconnect/timeout and close a session on framing errors. This framing carries
no authentication, fragment recovery, version negotiation or delivery guarantee.
It is not connected to Android and is not a stable wire format. A future BLE
adapter must explicitly negotiate the new protocol; never auto-detect it from
legacy chat text or silently reinterpret old peers' messages.

Prototype LE L2CAP connection-oriented channels before selecting the new BLE
adapter. Android's [L2CAP socket API] starts at API 29; the current app also supports
older Android versions. Keep legacy GATT chat on those versions while evaluating
the new transport. Do not raise the app's minimum API as a side effect.
Use a separate GATT service UUID for new-protocol discovery, version and PSM
negotiation, not the legacy chat characteristic. Test the eventual iOS path on
hardware. Bluetooth link security is distinct from endpoint identity verification.

Raw write-without-response or notifications do not satisfy the framing contract.
A GATT alternative needs its own reviewed sequencing, acknowledgements, flow
control and timeout/restart behaviour. L2CAP reads can combine adjacent records;
the adapter must consume exactly a header and its bounded body, leaving following
bytes for the next record. The current decoder deliberately rejects two records
in one push, so it cannot be attached directly to arbitrary socket reads.

## Cryptography and pairing

Proposed direction: evaluate the maintained Signal protocol implementation rather
than construct a new encryption protocol. Signal's [PQXDH specification] describes
asynchronous session setup; revision 4 of the [Double Ratchet specification]
already includes SPQR and the Triple Ratchet. The [Signal ratchet announcement]
provides the deployment context. Do not assume a classical-only session lifecycle.
Pin an exact library tag and full commit before the integration spike, then
record the actual ratchet/version negotiation used by that build. Use its session API and test
vectors, with no hand-written replacements for its primitives or key schedule.

[libsignal] is the first implementation candidate because it contains Rust
implementations and Java/Swift bridges. Its maintainers explicitly do not support
use outside Signal, and its repository uses AGPLv3. Yap currently declares
GPL-3.0-only. Before adopting it, review distribution obligations, API stability,
Android/iOS builds, dependency size and offline operation. The named products are
Java, Swift and TypeScript libraries; direct use of the underlying Rust crate is
an additional maintenance commitment, not a supported third-party Rust SDK.
Keep that integration behind a narrow internal adapter and budget for pinned
upgrade and interoperability checks.

The distribution review must cover iOS App Store terms as well as GPL/AGPL
combination obligations. The historical [Signal licence update] concerns
libsignal-protocol-c; it is not evidence of permission for today's library. Inspect
the selected revision's licence, source headers and additional permissions and
record an applicable distribution basis before committing to that dependency for
iOS. The [current libsignal licence] alone does not establish that basis here.
This proposal neither adds the dependency nor changes Yap's licence. If the
candidate fails review, return with an alternative; do not invent a protocol.

For the first milestone, propose explicit mutual in-person pairing:

1. Each phone creates a fresh pairing attempt. Its QR carries a format version,
   public identity key, fresh random attempt nonce and SHA-256 commitment to the
   exact canonical public bundle bytes. Target at most 256 encoded bytes and test
   scan success on real screens; do not put a full post-quantum bundle in the QR.
2. Exchange bounded public bundles over the local link. Verify the scanned
   commitment, identity and library-defined bundle signatures before accepting
   one. Define canonical encoding and hash domain separation in the integration
   specification. A matching hash alone is not completed mutual authentication.
3. Require both scans and explicit confirmation. The lexicographically smaller
   canonical public identity key is the session initiator; equal keys abort.
   This resolves simultaneous scans without creating two sessions. Bind both
   attempt nonces and roles to the reviewed pairing transcript.
4. Reserve unique one-time prekeys per attempt; consume them atomically with
   successful session creation. Mark the attempt used durably. Define expiry,
   abort/retry and crash recovery before implementation. Reused QR attempts must
   not create another session or share a one-time prekey across contacts.

[QR capacity depends on encoding and error correction][QR versions]; exact
post-quantum bundle size and scanning reliability must be measured for the pin.
Bind the verified contact to keys, not names or Bluetooth addresses. Without a
directory, a different identity is a new contact. Replacing a contact's keys is
explicit in-person re-pairing, never an automatic name-based update. For the
first milestone, a broken session requires re-pairing too. Disable sending through
the retired session. No phone number or central key directory is introduced.

Still required before crypto integration: select and pin a library version;
define canonical contact-bundle encoding, freshness and one-time-prekey handling;
define fingerprint/QR verification; specify the library's complete session
lifecycle and malformed-input limits; and document key erasure and recovery
behaviour. Require review of that concrete integration specification. Approving
this milestone plan alone does not approve unspecified cryptographic details.

Persist session updates and outgoing ciphertext atomically before transmission.
Persist incoming session advancement, deduplication and the received message
atomically before issuing a receipt. Specify crash recovery and rollback handling
with the selected library before implementing the store. Android key protection
must cover the persisted session secrets as well as the identity key.

Use a transaction-scoped staging implementation of the library's [store interfaces].
Buffer session, identity, classical/PQ prekey and one-time-prekey consumption
changes. On send, commit them with the exact outgoing envelope. On receive,
commit them with authenticated deduplication, message storage and persisted
receipt ciphertext. Roll back all staged changes on authentication failure,
inner/outer mismatch, rejection or storage failure. Serialize operations per
session and define lock ordering; do not permit overlapping ratchet mutations.
Store callbacks are not evidence that these operations are atomic together.

Encrypt the database with a key wrapped by Android Keystore; Keystore is not the
ratchet-state database. Exclude all cryptographic/session/prekey databases,
journals, wrapped-key files and related preferences from cloud backup and
device transfer, using [modern and legacy Android backup rules]. Keep
`allowBackup="false"` as well; [Android documents OEM transfer exceptions]. The
current prototype sets that manifest flag and explicitly excludes all app-data
domains in both legacy backup rules and modern cloud/device-transfer rules. It
has no persistent session store. Test both backup routes before adding one.
An absent/invalid key or detectable restore must fail closed and require re-pairing;
never silently regenerate a key against an old session database. Backup exclusions
reduce accidental rollback risk; they do not solve malicious same-device rollback.

## Message envelope and receipts

Propose a versioned outer envelope containing a random 128-bit message ID, an
opaque destination token, a sender lookup token, creation/expiry data, a hop budget, payload length and
the cryptographic library's ciphertext. The destination-token format and
distribution still need review; it must not be derived from a Bluetooth address.
During pairing, each receiver proposes a fresh random 128-bit sender lookup token
for incoming messages from that contact, checks local uniqueness, and shares it
through the authenticated pairing exchange. It maps to exactly one known session.
It is a public lookup hint, not proof of sender identity or mailbox authority.
Reject unknown hints without trial-decrypting across contacts or creating state;
apply global decryption budgets even for known hints. Re-pairing retires the old
hint. Token encoding and exchange require review with the pairing transcript.
Relays will observe identifiers, sizes, timing and next-hop connections. A stable
destination or sender token can allow tracking and link conversations. Accept
that metadata exposure explicitly for this experiment; this milestone makes no
anonymity claim and introduces no unreviewed rotating-hint or sealed-sender scheme.

Immutable envelope fields, including version, sender hint, destination, ID and
creation/expiry, must be bound to authenticated message content. Plan to include
a canonical copy inside the encrypted plaintext and reject any mismatch with
the outer envelope before committing staged crypto state. Verify this approach
against the pinned API; do not assume it accepts caller-supplied associated data.
Define all field encodings and length limits before implementation. Random padding
inside the plaintext should use reviewed size buckets; choose those buckets only
after measuring ratchet overhead, including SPQR, within the 64 KiB envelope cap.
Hop budgets change during forwarding; they are local loop/resource controls,
not cryptographic proof that a hostile relay has obeyed the path limit.

Receipts are encrypted authenticated messages from C to A, identifying the
original message. B cannot manufacture one by acknowledging a transport write.
C issues a receipt only after durable acceptance. A receipt means received by
the recipient device, not read by a person. Duplicate messages must not create
duplicate chat entries; repeat receipts should be rate-limited. Receipt expiry
can leave A uncertain even when C has received the original message.

After successful authentication, endpoint deduplication uses the verified contact
and message ID, not an unauthenticated outer ID. Store the accepted immutable
envelope digest and exact receipt ciphertext with that record. On an exact replay
of that accepted envelope, schedule the stored receipt under a separate bounded
retry budget without decrypting or advancing the session again. A different
envelope with the same outer ID is not an authenticated duplicate; authenticate
it under resource limits before deciding, without replacing an existing message.
Commit receipt ratchet state and ciphertext atomically with acceptance; a crash
before receipt transmission must retry those same bytes on restart. Deduplicate
incoming authenticated receipts, and never issue receipts for receipts.

## Store-and-forward mesh

For the initial experiment, propose forwarding to explicitly connected peers,
excluding the incoming peer, rather than implementing a proximity-based route
algorithm. Test with a controlled three-device topology. This is bounded epidemic
forwarding for an experiment, not a claim of scalable routing.

Relays must not deduplicate on message ID alone. Propose the tuple of message ID
and SHA-256 digest of the canonical immutable envelope, including ciphertext and
immutable routing fields. Exclude only the mutable hop budget and link framing.
Validate bounds and canonical encoding before hashing. Junk with a copied ID
and different bytes cannot mark the genuine envelope seen. A relay still cannot
authenticate its content: attackers can send many variants, so admission and work
budgets remain necessary. At endpoints, only authenticated identities and IDs
govern durable message deduplication.

Proposed initial limits for review:

| Resource | Proposed limit / behaviour |
| --- | --- |
| Encoded envelope | 64 KiB including metadata and ciphertext |
| Message lifetime | 24 hours, with a local residence-time cap |
| Hop budget | 8 forwards |
| Relay storage | 8 MiB and 1,024 pending envelopes per device |
| Recent relay duplicate cache | At most 4,096 entries and a measured memory budget; 10-minute local retention, LRU eviction allowed |
| Receive rate | 60 envelopes and 256 KiB per minute per connection; also a global device budget |
| Full durable relay queue | Reject new custody visibly; preserve already accepted, unexpired envelopes |
| Full recent duplicate cache | Evict the least recently used entry; cache fullness alone does not reject new custody |
| Retry | Backoff from 1 second to 60 seconds with jitter; reset only on meaningful progress |

These values are proposals, not implemented defaults. Define the global receive
budget and per-peer scheduling before shipping the relay. A peer can open new
connections or identities, so per-connection limits alone cannot prevent abuse.
Bound contact/prekey/ratchet state and pairing attempts separately.

Retain a separate digest index for every envelope still in the bounded durable
queue. Evicting a recent-cache entry must not duplicate that queued envelope.
Record recent forwarding only after queue admission succeeds. Cache expiry is
local and independent of a sender's claimed 24-hour lifetime. Accept the
availability trade-off explicitly: an evicted or expired entry can be forwarded
again. Hop controls, admission limits and global/per-peer byte/work budgets must
bound the cost. LRU does not prevent cache churn or Sybil attacks; those remain
load-test requirements. Do not claim complete relay replay suppression.

Do not size this structure at 16 bytes per entry. A 128-bit ID plus a 256-bit
digest alone takes 48 bytes before timestamps, indexing and allocator overhead.
Measure the pinned implementation's complete memory usage before raising caps.

Use local elapsed time for running-session residence limits and a documented
conservative wall-clock policy after restart. Clock rollback must not extend
retention indefinitely. Specify time and cache-full behaviour in detail before
persistence implementation. Never treat the sender's expiry timestamp as proof
of trustworthy time. A malicious relay can keep its own copy beyond expiry.
Define a clock-skew tolerance and tests for future-dated creation times, expiry
boundaries, wall-clock jumps, reboot and long offline periods; no numerical skew
allowance is approved yet. Recent-cache retention uses local monotonic elapsed
time while running; restart must not renew durable custody indefinitely.

Persist accepted envelopes before acknowledging custody. A relay custody
acknowledgement is distinct from a recipient receipt. Do not delete A's outgoing
copy merely because B accepted it. Expired, rejected and storage-full messages
must remain distinguishable in UI state.

## Internet path

Propose an explicitly configured, user-controlled WebSocket relay over TLS for
the first internet test. No public service, address book or default hosted
infrastructure is introduced. Local Bluetooth messaging must work without it.

The relay stores and forwards the same opaque envelopes; it never holds endpoint
decryption keys. TLS protects a network link, while endpoint encryption protects
the message from the relay. A relay can drop, delay, replay and observe traffic.
It sees client IP addresses and routing metadata. Do not describe this as anonymous.

Before implementing the service, specify endpoint registration, destination-token
ownership, connection authentication, mailbox access, custody acknowledgements,
quotas and expiry. A destination token alone must not authorise mailbox retrieval
or deletion. Require valid TLS certificates, bounded frames, connection limits,
backoff, and an explicit user action to configure a remote endpoint. Do not
implement a general-purpose network proxy or claim peer-to-peer internet routing.

## Verification and implementation order

1. Review milestone scope, proposed limits, pairing model and internet relay role.
2. Complete and review the concrete crypto/dependency, envelope and storage
   specification: sender lookup, compact QR commitment and roles, deduplication
   keys, cache admission/eviction, clock policy, backup/restore and the iOS
   distribution basis. Record accepted decisions and unresolved evidence.
3. Add a pinned crypto dependency and interoperability/vector tests. Exercise
   A–C pairing, re-pairing, malformed messages, tampering and replay rejection.
   Record the pin's negotiated ratchets and maximum measured bundle, ciphertext,
   session, skipped-key and SPQR state sizes against storage/parser limits. Treat
   representative measurements as evidence, not a proof of every worst case.
4. Build transactional persistence and crash tests. Test crash points between
   encryption, session-state commit, queue insertion, reception and receipt creation.
   Include concurrent session operations, prekey consumption, disk-full rollback,
   receipt retransmission, cloud restore and old device-transfer restore attempts.
5. Prototype L2CAP on real phones, then connect the reviewed envelope through
   an explicitly negotiated BLE service and a stream adapter that preserves record
   boundaries. Test partial reads, coalesced reads, backpressure and disconnects.
   Keep the existing legacy Bluetooth prototype available during migration.
6. Test a direct encrypted exchange on two phones, then A–B–C with B restarting,
   duplicate/reordered transfers, forged IDs, cache flooding, full queues and expiry.
   Assert only A/C hold endpoint keys, altered copies cannot poison the genuine
   envelope, cache churn does not produce duplicate chat entries, and new valid
   work remains admissible when only the recent cache is full.
7. Implement the configured TLS WebSocket relay and test a mixed Bluetooth/internet
   path, transport switching, duplicate suppression and internet loss.
8. Review the resulting threat model and release claims before wider distribution.

Simulator tests establish core behaviour; physical phones establish radio and
lifecycle behaviour. Foreground operation is the first target. Background relay
support and iOS interoperability require their own real-device evidence.

## Threats and limits of this milestone

Protect message content and peer authentication against hostile links and relays
using the reviewed crypto integration. Test that relays cannot forge recipient
receipts or modify authenticated destination/content without rejection.

Exercise oversized inputs, duplicate floods, partial frames, connection churn,
lost acknowledgements, expired queues, process crashes, key changes and partitions.
Bound parser work, memory, disk and concurrent sessions. Do not log plaintext,
secret keys, QR bundles or full envelopes in production diagnostics.

Compromised endpoints can read their messages. Relays can censor delivery.
Radio proximity and traffic metadata remain observable. Offline lost-device
revocation, group governance, account recovery and resistance to a global traffic
observer are outside this milestone and must not appear as working features.

## Current implementation evidence

- The Rust core has replaced the simulation with transactional bounded custody,
  ID-plus-immutable-envelope-digest indexing, bounded LRU/rate state, expiry and
  validated envelope/stream codecs. Tests cover the audited poisoning/cache cases.
- Framing is not called by Android; the existing 20-byte BLE chat is unchanged.
- A private TLS WebSocket service and client are implemented. A pinned-Signal
  experiment sends real ciphertext through a restarted relay and local TLS hops,
  with an encrypted return receipt. Endpoint stores remain in memory; no production
  secure mobile exchange or physical multi-hop mesh is demonstrated by that test.
- A user-supplied proposal audit was received on 24 November 2026 and is tracked
  in the response linked above. Reviewer independence and methodology have not
  been established; no implementation security audit has been completed.

[PQXDH specification]: https://signal.org/docs/specifications/pqxdh/
[Double Ratchet specification]: https://signal.org/docs/specifications/doubleratchet/
[libsignal]: https://github.com/signalapp/libsignal
[Signal ratchet announcement]: https://signal.org/blog/spqr/
[Signal licence update]: https://signal.org/blog/license-update/
[current libsignal licence]: https://github.com/signalapp/libsignal/blob/main/LICENSE
[store interfaces]: https://github.com/signalapp/libsignal/blob/main/rust/protocol/src/storage/traits.rs
[QR versions]: https://www.qrcode.com/en/about/version.html
[L2CAP socket API]: https://developer.android.com/reference/android/bluetooth/BluetoothDevice#createL2capChannel(int)
[modern and legacy Android backup rules]: https://developer.android.com/identity/data/autobackup
[Android documents OEM transfer exceptions]: https://developer.android.com/about/versions/12/behavior-changes-12#backup-restore
