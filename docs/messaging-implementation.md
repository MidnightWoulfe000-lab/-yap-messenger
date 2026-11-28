# Messaging implementation and remaining work

The Android 0.2 debug app now links the reviewed Rust endpoint and durable relay
core. It implements pinned-Signal one-to-one encryption, mutual QR/package pairing,
Keystore-backed atomic snapshots, ciphertext queues, recipient receipts, foreground
LE L2CAP transport and a configurable private TLS WebSocket client. The original
20-byte BLE prototype has been removed. See the [release guide](debug-release-0.2.md)
and [endpoint contract](android-endpoint-v1.md). This is experimental development
software, without an independent security audit or physical three-phone mesh test.

## Code and evidence

| Area | Implemented | Remaining |
| --- | --- | --- |
| Shared core | Validated envelopes, immutable metadata binding, SHA-256 relay keys, bounded framing/streams, endpoint message/receipt format and JNI | Wider interoperability and fuzzing |
| Persistent relay | Immediate-durability redb custody, pending digest index, bounded LRU cache, quotas/rate gates, expiry and clock high-water checks | Production disk/memory measurements, fair scheduling, deployment load tests |
| Internet | TLS WebSocket server, typed Rust client and bounded Android client; provisioned credentials; mailbox ownership and private CA configuration | Production deployment, credential rotation and load testing |
| Cryptography | Pinned libsignal v0.104.0 endpoint, mutual pairing, staged stores, Keystore snapshots, replay/receipt/retirement checks | Independent audit, multi-device and lost-device recovery |
| Mobile mesh | Old unencrypted BLE chat removed; v2 link layer shared by Bluetooth L2CAP and Wi-Fi (same network or hotspot) with rotating link IDs; native store-and-forward integrated ([local mesh v2](proposals/local-mesh-v2.md)) | Foreground A–B–C hardware tests, rotating envelope tags, later background/iOS work |

The original `HashSet` simulation has been replaced. Relay queue commits are
transactional; successful custody is reported only after commit. Recent-cache
eviction cannot remove the separate persistent index for queued envelopes. The
ID-plus-digest key distinguishes both copied-ID junk and altered immutable
metadata. Logical queue byte quotas exclude database pages and allocator overhead;
they are not advertised as a hard bound on total process memory or disk files.

Running expiry uses monotonic elapsed time to prevent a frozen/backwards wall
clock extending active custody. Reopening with a wall clock older than the stored
high-water mark fails closed. A positive future-clock allowance must be supplied
explicitly; tests use zero. This is a conservative experimental clock policy,
not a solution to malicious filesystem/backup rollback.

## Experimental envelope v1

All integers are unsigned big-endian. The envelope is at most 65,536 bytes; the
ciphertext must be nonempty. Invalid versions, declared lengths, more than eight
hops, and nonpositive or greater-than-24-hour lifetimes are rejected.

| Offset | Bytes | Field |
| --- | --- | --- |
| 0 | 4 | `YAP` followed by byte `01` |
| 4 | 16 | Message ID |
| 20 | 16 | Destination routing token |
| 36 | 16 | Sender session-lookup hint |
| 52 | 8 | Creation time in Unix milliseconds |
| 60 | 8 | Expiry time in Unix milliseconds |
| 68 | 1 | Remaining forwards |
| 69 | 4 | Ciphertext length |
| 73 | variable | Opaque ciphertext |

The immutable binding is bytes 0–67. A crypto adapter copies it inside the
authenticated plaintext and compares it before committing state. The endpoint
uses the reviewed `YEP1` message kinds in the Android contract and prefixes
ciphertext with the library message-type byte. It introduces no custom cipher,
ratchet, nonce or key schedule.

The relay key is the 16-byte ID followed by SHA-256 of the ASCII domain separator
`Yap relay envelope v1` plus a NUL byte and the complete canonical encoded
envelope with its hop byte set to zero. Every other byte, including length and
ciphertext, is covered. This digest enables relay deduplication, not sender
authentication. Endpoints still require authenticated contact-scoped deduplication.

Stream framing is a four-byte encoded-envelope length followed by that many bytes.
The stream adapter reads one bounded record at a time and preserves coalesced
following records. EOF mid-record is an error. Socket owners must enforce
deadlines and close a session after a framing error. This format must never be
auto-detected from legacy GATT text.

## Experimental internet protocol

Only TLS WebSocket connections to `/yap/v1` are accepted. The HTTP handshake
requires an `Authorization: Bearer ...` credential mapped to a pre-provisioned
account. Browser Origin headers are rejected. The service stores only credential
digests in its live registry; the explicitly supplied private configuration still
contains credentials and requires host access controls. Mailbox routing tokens
do not authorise mailbox access.

| Direction | Binary message | Meaning |
| --- | --- | --- |
| Client → relay | `01` + encoded envelope | Offer custody for a registered recipient other than self |
| Relay → client | `81 00` + 48-byte key | Durable custody exists, after commit |
| Relay → client | `81` + status byte | `01` recently forwarded, `02` rate limited, `03` queue full, `04` invalid/rejected; none confirms custody |
| Client → relay | `02` + 2-byte count | Poll own mailbox, at most 16 records |
| Relay → client | `82` + 48-byte key + encoded envelope | One pending opaque envelope, with hop budget decremented |
| Relay → client | `83` | End of poll |
| Client → relay | `03` + 48-byte key | Confirm local durable custody and release the relay copy |
| Relay → client | `84 00` or `84 01` | Release succeeded or key absent/not owned |
| Relay → client | `ff` | Unknown/invalid operation |

Polling does not release a record. An account cannot fetch another mailbox or
delete its entries even when it knows a message key. The service never produces
end-user delivery/read receipts. Failed commits and incomplete socket exchanges
do not produce custody success. The sender's endpoint outbox must survive next-hop
custody acceptance until a genuine authenticated recipient receipt or expiry.

This is a private experimental service, not an open discovery network or an
implemented internet peer-routing algorithm. Operators can observe metadata,
drop traffic or delete their stores. TLS is link protection, not endpoint message
encryption; use real endpoint ciphertext as in the isolated experiment.

## Checks and limits

Core tests cover malformed/truncated input, envelope metadata changes, forged IDs,
cache churn, queue/byte limits, connection/global budgets, future dates, residence
and expiry, clock rollback, restart and abrupt exit after a custody commit.
Server integration tests use real local TLS sockets and cover mailbox access,
certificate rejection, oversized requests, polling budgets and typed-client I/O.

The [Signal experiment](../experiments/signal-mesh/README.md) rejects tampering,
outer/inner metadata mismatch and replay, and returns an encrypted receipt. Its
endpoint stores are in memory. It does not implement durable endpoint receipt
creation, QR scanning, sender-hint lookup, secure erasure, restore handling or
long-running SPQR state bounds. The iOS distribution review remains open. These
remain limitations of that isolated experiment. The Android endpoint separately
implements staged snapshots, receipt caching, sender-hint lookup and explicit
pairing/retirement, with emulator tests; it does not establish secure erasure,
backup rollback resistance or an iOS distribution basis.

Local checks pass 23 core tests, eight TLS relay/client tests and the pinned
cryptographic experiment, which also runs 100 established in-memory round trips.
The observed maximum session size in those round trips was 6,958 bytes; this is
not a worst-case bound. The restarted-relay/TLS exchange is a local experiment,
not a measured physical-device delivery guarantee.

The endpoint adds 11 tests for pairing, tamper/replay, reordered messages,
retirement, full-outbox receipt admission and restart. Android adds Keystore,
uncertain-commit, restarted native relay, bounded WebSocket and actual TLS mailbox
tests. See the release verification record for the packaged build's results.

The [CI checks](../.github/workflows/rust.yml) cover all Rust packages with locked
dependencies, formatting and Clippy; Android CI now builds both native ABIs before
assembling. Updated CI workflows have not been run remotely in this session.
Physical radio/background tests, deployment tests and an independent security
audit remain required before production readiness can be claimed.
