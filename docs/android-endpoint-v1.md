# Android endpoint v1 debug implementation contract

This contract extends the reviewed relay proposal into the Android debug build.
It is an experimental one-device-per-identity, one-to-one protocol adapter using
the exact libsignal revision already tested. It is not an independent security
audit or a specification for group governance, recovery or device revocation.

## Native boundary and durable state

An AGPL native adapter links the pinned libsignal and the GPL Yap message core.
Android invokes it through JNI on one serial worker. Operations take a complete
versioned endpoint snapshot and return a replacement snapshot plus public results.
Every cryptographic mutation is staged in the operation's private copy. An error
returns no replacement. Android encrypts and atomically commits a successful
snapshot before sending a ciphertext, acknowledging reception or updating history.

The snapshot contains identity/session/prekey records, verified contacts, pairing
attempts, outgoing ciphertext, accepted-message digests, exact receipt ciphertext
and history. All secret records and history are protected at rest by AES-GCM with
an Android Keystore-generated nonexportable AES-256 key and a fresh platform nonce.
The file format is `YKS1`, a 12-byte IV and authenticated ciphertext, with fixed AAD
`Yap endpoint snapshot v1`. Use AtomicFile in noBackupFilesDir. Missing keys for an
existing snapshot, failed tags, unknown versions or clock rollback fail closed;
never silently replace a restored/corrupt identity. No secrets enter logs, saved
instance state or diagnostics. Restoring a prior snapshot on the same compromised
device is outside the stated guarantees.

Limit contacts to 64, pending pairing offers to four, saved history to 512 messages,
outgoing records to 512 (448 text and 64 control) and authenticated text dedup entries to 4096 for the 24-hour
message lifetime. A full endpoint store rejects new work before advancing a
ratchet. A snapshot cannot exceed 8 MiB. Removing visible history does not remove
unexpired dedup records or cryptographic state. Queue/receipt changes and prekey
consumption share the same atomic snapshot.

## Pairing

Each fresh pairing attempt reserves new classical and post-quantum one-time
prekeys, an identity-signed classical prekey and an identity-signed PQ prekey.
The public bundle uses the binary schema below. Require exact canonical lengths,
byte-for-byte re-encoding and valid libsignal bundle signatures.

The QR is `yap:v1:` plus base64url-no-padding of: `YPR1`, 33-byte public identity,
16-byte random attempt nonce, SHA-256 of `Yap public bundle v1\0` followed by the
exact public-bundle bytes, and a 16-byte public destination token. The full bundle
travels over a local connection or an explicitly shared pairing file; it is not
placed inside the QR. Attempts expire after ten minutes unless already bound to
an accepted contact. Keys reserved for accepted attempts remain until consumed.
Regeneration never reuses a one-time prekey or silently changes an existing contact.

Both participants scan/enter each other's QR and explicitly confirm. Before
creating a session, exchange identity signatures over `Yap pairing confirmation
v1\0`, followed by the two complete committed public bundles in lexicographic
identity-key order, each prefixed with a four-byte length. This binds identities,
attempts, routing tokens and the receiver-assigned random sender lookup hints in
the bundles. Identity signatures use libsignal's existing signing API; no custom
cipher or key schedule is introduced. Each attempt binds to one remote identity.
Equal identities abort. The smaller identity key alone initiates PQXDH using
process_prekey_bundle. Do not send user text until both confirmations validate.
The other participant accepts its first authenticated prekey message using its
reserved keys. The one-time classical/PQ consumption is committed with acceptance.

Contact IDs are hex SHA-256 of the canonical public identity. Signal addresses use
that contact ID and device ID 1, including the local address expected by the pin.
A different key is a different contact; there is no name-based replacement.
The sender hint is a fresh random 128-bit value assigned by each receiver during
pairing and bound by the transcript. Exactly one verified contact may own a hint.
Broken sessions require explicit re-pairing; no directory or automatic key reset.

## Messages and receipts

The reviewed 73-byte outer envelope stays unchanged. Its ciphertext begins with
the libsignal message type byte (`2` established or `3` prekey), then library bytes.
The authenticated plaintext is `YEP1`, the 68-byte immutable Header binding, and:

- kind `01`, a four-byte UTF-8 byte length and 1–4096 bytes of valid text; or
- kind `02`, the original 16-byte message ID, with no trailing bytes;
- kind `03`, an empty encrypted session bootstrap; or
- kind `04`, the bootstrap's 16-byte ID, an encrypted bootstrap acknowledgement.

Reject unknown kinds, trailing bytes, bad UTF-8 and binding mismatches before
committing staged state. Select one session by the previously verified hint;
unknown hints never trigger contact creation or trial decryption across contacts.
Use secure randomness for IDs, hints and tokens. Default envelope lifetime is
24 hours and initial hop budget eight. Permit at most five minutes of future
creation-time skew; expired messages are not decrypted or acknowledged.

After authenticating a new text, atomically store its contact-scoped ID, immutable
digest, history row and exact newly encrypted receipt together with all ratchet
and prekey mutations. Only then acknowledge link custody. Exact duplicate text
reuses the stored receipt bytes without decrypting again. Changed bytes carrying
the same untrusted ID do not match that cache. Different authenticated content
with the same contact/ID is rejected without replacing accepted history.
Receipts are authenticated against the correct pending contact and message ID.
They mark device receipt, never reading. Do not generate receipts for receipts.

Sender data stays queued through intermediary custody acknowledgements until an
authenticated receipt or expiry. Retry the stored ciphertext, never re-encrypt
the same logical message on a transport retry. Exact receipt bytes remain in the
accepted-message cache so loss/crash can be recovered by retransmitting the text.

## Transports and UI

> **Superseded for local transports by [local mesh v2](proposals/local-mesh-v2.md).**
> v2 adds Wi-Fi, replaces the routing token on the link with a rotating random link
> ID, and stops broadcasting pairing bundles. The paragraphs below describe v1.

Keep the legacy GATT service intact and explicitly separate. New local discovery
uses UUID `a6f0c9e1-5b2d-4f8e-9a17-3c6d0e2b4201`; its metadata characteristic
`a6f0c9e1-5b2d-4f8e-9a17-3c6d0e2b4202` contains version byte 1, a two-byte PSM
and the 16-byte routing token. Android 10+ uses LE L2CAP CoC with bounded framed
records. The link itself is not identity verification; QR commitments and endpoint
encryption supply that layer. A relay cannot read messages destined elsewhere.

Link records carry a one-byte operation followed by bounded data: hello/routing,
public bundle, signed pairing confirmation, opaque envelope, and durable-custody
key acknowledgement. Never infer recipient delivery from Bluetooth, internet or
relay custody success. Close malformed/oversized sessions; bound connections,
requests, retries and relay memory/disk. Only honour custody acknowledgements for
keys actually offered on that connection. Foreground discovery and periodic retry
stop when the app leaves the foreground; queued data survives restart.

Internet uses the already implemented TLS WebSocket protocol, configured URL,
credential and local mailbox token. Android's client must retain certificate and
hostname validation and reject plaintext URLs. No relay is enabled by default.
Registered relay mailbox tokens must match the phone's public routing token;
bearer credentials authorise mailbox access, not peer identity. A recipient's
envelope and receipt can cross either path without changing ID/ciphertext.

The approved dark/lime/cobalt UI remains. Chats list real paired contacts, with
pairing/queued/error/delivered states driven by stored events. Settings exposes
the local pairing QR, scanner/manual entry, fingerprint, relay configuration,
retry and storage controls. Preview stays read-only and labelled. Display an
experimental-build security explanation in privacy details; do not imply an audit
or background/iOS/group capability that has not been implemented and verified.

## Reviewed state-machine and resource details

Public bundle bytes, in this exact order: `YPB1` (4 bytes), public identity (33),
attempt nonce (16), destination (16), receiver hint (16), creation and expiry
milliseconds (u64 each), registration ID (u32, 14-bit range), device ID (u8, always
1), one-time EC prekey ID (u32) and public key (33), signed EC prekey ID (u32),
public key (33) and identity signature (64), one-time PQ prekey ID (u32), serialized
PQ public-key byte length (u16), serialized Kyber1024 public key (1569 including
the library type byte), and identity signature (64). All integers are big-endian.
The total is 1883 bytes. Reject extra bytes, duplicate/zero hints or destinations,
wrong device IDs, invalid signatures and lifetime other than ten minutes.
Domain separators in this document end in an actual NUL byte, never backslash-zero.
The signed confirmation is `YPC1`, the signer's 33-byte identity and 64-byte
signature over the ordered, length-prefixed complete bundles defined above.
Only a locally confirmed candidate for that identity/transcript can accept it.

Persist attempt phases: offered, locally confirmed, mutually confirmed,
bootstrapping, established, expired and cancelled. Binding an offered nonce to one
remote identity is atomic with local confirmation. A signed confirmation is stored
as exact bytes and retransmitted at most four times a minute until authenticated
session traffic proves that the peer progressed, or expiry. Receiving a matching
confirmation also schedules the exact local confirmation within that budget.
Retransmissions use at most three of the four, counting the send made at scan
time, so the first valid confirmation from the peer is always answered at once.
A phone that has handed its bundle to a phone that proved it scanned the QR keeps
that offer on screen instead of rotating it, until it scans back or the offer expires.
Repeated exact confirmations are idempotent; they never recreate a
session or replace a prekey. Confirmations arriving before the user has scanned
the peer do not create state and may be retried later.

The smaller-key participant creates and persists an encrypted kind-03 bootstrap
immediately after mutual confirmation; sending it must not depend on user text.
The larger-key participant atomically accepts it, creates exact kind-04 bytes and
becomes ready. The initiator becomes ready after that authenticated acknowledgement
(or another correctly bound authenticated message from the confirmed session).
Exact bootstrap replays reuse the persisted kind-04 bytes. Reserve one bootstrap
digest/ack record per contact generation through the bootstrap envelope's expiry,
independent of the control LRU. Replays never recreate a session or consume a
second prekey. The initiator retries
the original bootstrap ciphertext with ordinary outbox backoff. An interrupted
pairing therefore resumes after restart without a competing session.

Expired/cancelled unbound offers delete reserved keys. Bound offers expire after
ten minutes unless mutual confirmation completed; pending confirmation then fails
visibly and its reserved keys are removed. Mutually confirmed offers remain tied
to that one contact until bootstrap consumption or the 24-hour bootstrap deadline;
failure after that deadline requires explicit re-pairing. Limit retained offers
to four unbound/pending plus one per contact. No accepted attempt becomes reusable.

Re-pairing an existing identity requires explicit session retirement first. That
atomic operation removes its old hint/session, cancels old outgoing ciphertext,
marks affected history unconfirmed and removes old pending pairing control records.
It does not recall copies already held by other nodes. Only the new generation's
receiver hint selects the new session. Dedup keys include contact ID, local
receiver-hint generation and message ID. Different identity keys always remain
different contacts, even when display names match.

One serial Android transaction encompasses current-snapshot read, native staging,
encryption, AtomicFile.finishWrite and only then in-memory state/UI/network
publication. Encryption, write or sync failure calls failWrite, retains the old
snapshot in memory and emits no ciphertext/ack. Bound the native request to 12 MiB
before JSON parsing and its result snapshot to 8 MiB before publication. Each
serialized session is at most 512 KiB; larger library state fails the operation.

Apply process-global decryption admission before JNI: at most 120 envelopes and
2 MiB per minute; per connection 60 and 256 KiB; at most eight local connections.
Connection churn does not reset the process-global gate. Pairing work is separately
limited to eight bundle/confirmation verifications per minute globally. Cached
receipt scheduling is limited to 64 per minute globally, six per contact per
minute and once per accepted message per ten seconds, with last-scheduled time
persisted. Restarts retain that per-message time and do not amplify replay sends.
These are debug-build bounds, not claims of fairness or Sybil resistance.

Retain authenticated text dedup records through the original expiry. Incoming
receipt/bootstrap-ack digests use a separate 512-entry LRU cache; eviction can cause
the library to reject a later replay but cannot create another history row or
receipt. Authenticated state-reducing receipt operations may remove queued data
even when text/history capacity is full. Never evict unexpired text dedup to accept
new text. Full control capacity rejects a new text before ratchet commit; exact
duplicates may still acknowledge existing durable acceptance while receipt retry
is throttled. No acknowledgement is issued for unverified/evicted replay bytes.

Persist the maximum effective Unix time with every committed snapshot. During a
process, the controller derives effective time from max(previous effective time,
wall time, startup wall time plus elapsedRealtime). Startup with a wall time older than the stored maximum
fails closed with a visible clock error; correcting time re-enables opening the
same state. No automatic identity reset or retention renewal is permitted.

## Verification

Test mutual pairing, unilateral confirmation, QR/bundle mismatch, reused attempts,
simultaneous scans, invalid signatures, forged hints, staged-state failure,
replay/tampering, altered bindings, out-of-order messages, exact receipt retry,
restart, expired queues and full stores. Android device tests cover native loading,
Keystore encryption, missing/corrupt keys, process/activity restart and the UI.
The debug APK must package arm64-v8a for phones and x86_64 for the emulator.
Physical three-phone relay and radio/background compatibility still require actual
devices; an emulator test cannot establish those results.
