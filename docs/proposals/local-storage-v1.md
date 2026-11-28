# Proposal: local storage for your own messages, keys and groups

**Status: draft for review; do not implement.** Per `AGENTS.md` this changes how keys and
messages are encrypted and saved on the phone, so it must be reviewed before any code is
written. It ships in the same update as [local mesh v3.1](local-mesh-v3.1-group-envelopes.md)
(owner, 27 November 2026). Every phone is re-paired in that update, so nothing is
migrated: the old storage is deleted.

**Audience: reviewers, maintainers and coding agents.** Read with the
[endpoint contract](../android-endpoint-v1.md) ("Native boundary and durable state") and
audit findings 5 and 7 in the [security audit](../audit/2026-11-25-security-audit.md).

## Problem

Today everything the encrypted messenger keeps lives in **one encrypted file** of at
most 8 MiB (`messaging/endpoint-v1.bin`): identity keys, every contact's libsignal
session, pairing attempts, unsent messages, the record of received messages, cached
receipts and the whole chat history. Every operation passes the whole file into the Rust
endpoint as JSON, gets a whole new file back, and Android re-encrypts and rewrites it
before anything is sent or shown. That made "all or nothing" easy to get right for the
test build, but it cannot carry real use:

- **The cost grows with your history.** Every message, receipt and key change rewrites
  and re-encrypts the whole file, and every operation parses all of it (audit finding 7).
- **Hard stops** ([`engine.rs`](../../endpoint/src/engine.rs)):
  - once **512 messages** are saved in total, **every new incoming message is refused**
    until history is cleared, and a contact can cause this on purpose (audit finding 5);
  - at most **448 unsent messages** may wait for a receipt;
  - at most **4,096 received messages** are remembered per 24 hours, and past that new
    ones are refused;
  - at most 64 control messages and 64 contacts.
- **It cannot grow to 128 contacts:** each libsignal session may be up to 512 KiB, and
  128 of those do not fit in 8 MiB.
- **Group state has nowhere to live.** `groups/` keeps its event log only in memory, and
  works out the group's state from the whole log for every new event.
- **The profile name and colour** sit in plain Android preferences (`yap_profile`), not
  encrypted.

## Goals

1. Each change writes only what changed. Cost does not grow with history.
2. Keep the rule that makes the current design safe: a change to keys, messages, the
   received record, receipts and the outbox is saved **completely or not at all**, and
   nothing is sent, acknowledged or shown until it is saved.
3. Everything encrypted at rest under a key held by the phone's secure hardware, as
   today.
4. No fixed message counts. Limits come from storage space, with fair shares so one
   contact cannot block the others.
5. Opening the app does not depend on how much history there is.
6. Deleting really deletes the records.
7. Established, maintained components only.

## Proposed design

### 1. One database, owned by the Rust endpoint

A single [redb](https://github.com/cberner/redb) database, `messaging/yap-store-v1.redb`
in `noBackupFilesDir`. redb is already pinned in `core/` (version 2.6.0) for the carrier
store. It is pure Rust, gives all-or-nothing write transactions that survive power loss,
and lets readers see a consistent state while a write is in progress.

The Rust endpoint opens the database itself. Snapshots no longer cross the native
bridge: Android sends a small request ("send this text to this contact", "here is an
envelope") and gets a small result back. One serial worker still does all writes, as
today; screens read history through read-only transactions, page by page.

*For review:* SQLite (with or without SQLCipher) is the usual alternative. redb is
proposed because it is already in the project, needs no C library, and the encryption
below does not depend on the database encrypting itself.

### 2. Encryption at rest

- **Store key.** On first start the endpoint makes a random 32-byte store key. Android
  wraps it with AES-256-GCM under a new non-exportable Android Keystore key (alias
  `org.yapmessenger.store-v1`) and saves the wrapped key in `messaging/store-key-v1.bin`,
  written atomically. Each start unwraps it and hands it to the endpoint once. A missing
  or unreadable wrapped key with an existing database fails closed, as today.
- **Record values.** Every value is encrypted with XChaCha20-Poly1305 (the RustCrypto
  `chacha20poly1305` crate already proposed for the outer layer), a random 24-byte
  nonce, and a key made by HKDF-SHA256 from the store key with the salt
  `Yap store v1` and the table's name as info. The associated data is the table name,
  the record's on-disk key and the schema version, so a value cannot be moved to another
  record or table without failing.
- **Record keys.** Lookup keys that would reveal something (contact IDs, message IDs,
  group IDs) are replaced on disk by HMAC-SHA256 under a separate key from the store key
  (salt `Yap store index v1`), cut to 16 bytes. Where order matters (history), the key
  is that opaque value followed by a sequence number.
- **What someone with the files but not the key learns:** roughly how many records each
  unnamed contact or group has, their sizes, and when the file changed. Not who, not
  what.
- **Whole-store erase.** Deleting the Keystore key and the wrapped store key makes every
  record unreadable at once, apart from flash remnants of records written before.

### 3. What is stored

| Table | Holds | Replaces |
| --- | --- | --- |
| `identity` | Identity key pair, registration ID, prekey counters | snapshot |
| `prekeys` | One-time, signed and post-quantum prekeys | snapshot |
| `contacts` | Per contact: identity, name, sender hint, pair-secret week state, recognition switch | snapshot |
| `sessions` | One libsignal session per contact, at most 512 KiB each | snapshot |
| `pairing` | Pairing attempts and their keys | snapshot |
| `history` | Per contact or group, in order: messages, status, route, attachment references | snapshot, 512 limit |
| `outbox` | Unsent envelopes with copy counts and reservations (mesh v3.1 §1.4) | snapshot, 448/64 limits, host memory |
| `received` | Received-message records until each message's expiry, with the cached receipt | snapshot, 4,096 limit |
| `group_log` | Each group's signed events, with the results already worked out at insertion | memory only |
| `group_state` | The group's state at its current heads | recomputed each time |
| `group_keys` | Sender keys, label keys, hand-over state | proposed in group encryption |
| `profile` | Your name, colour, onboarding flag, Enhanced security switches | plain preferences |
| `meta` | Schema version, highest time seen (clock check) | snapshot |

The carrier store for other people's envelopes stays a separate database with its own
limits (10,000 envelopes, 64 MiB), but its records get the same encryption and opaque
keys, so a seized phone does not show which envelopes it carried. *For review:* the CPU
cost of that on a busy carrier.

### 4. Saving: all or nothing

Each endpoint operation is **one write transaction**:
1. Read the records it needs (the contact, its session, the received record).
2. Run libsignal against in-memory store adapters that read through the transaction and
   collect changes.
3. Write every changed record: session, history row, received record, receipt, outbox
   changes, the highest time seen.
4. Commit. Only after the commit returns does the operation hand back ciphertext to
   send, an acknowledgement, or a screen update.

Any error aborts the transaction and nothing changes, which keeps today's rule that a
failed operation never half-advances a session. A crash before the commit leaves the
store as it was; after the commit, the work is done and any resend is an exact repeat
that the received records already handle.

### 5. Limits without hard stops

- **History, outbox and received records** have no count limits. Outbox and received
  records expire with their envelopes (24 hours), so they cannot grow without end.
- **Space.** Yap keeps its store under a size the user can see and change in Settings
  (starting value 2 GB for messages; attachments have their own,
  [attachments proposal](attachments-v1.md)), and never fills the phone past its last
  1 GB of free space.
- **Fair share.** When the store is near its limit, a contact or group already using
  more than its share (the limit divided by the number of chats with recent messages,
  but at least 10%) has new incoming messages refused, with a notice naming it. Every
  other chat keeps working. This answers audit finding 5: one contact can no longer
  block the rest.
- **Rate.** At most 1,000 incoming messages an hour are accepted from one contact or
  one group sender. Over that, envelopes are not acknowledged, so honest senders retry.
- 2 GB, 1 GB, 10% and 1,000 an hour are starting values.

### 6. Group state

`group_log` keeps every event with the results `groups/` already works out when an
event is inserted (whether it is valid, its depth, the active count before it, whether
a SuperAdmin made it). `group_state` keeps the state at the current heads. A new event
whose parents are the current heads updates that state from it; only an event that
joins two branches makes the code work the state out again from the log. *For review:*
whether `groups/` can apply events one at a time for that common case; the target is
under 100 ms for a 10,000-event group on a mid-range phone, to be measured.

### 7. Deleting

- **Clear history** deletes that chat's history rows and its attachments.
- **Removing a contact** deletes its contact record, session, pair secrets, history,
  outbox entries and attachments in one transaction.
- **Leaving a group** deletes its log, state, keys, history and attachments.
- After large deletions the database is compacted so freed pages are reused.
- Deleted records may survive on flash; the docs say so and do not promise otherwise.

### 8. The clock check

The highest time the phone has seen is saved in `meta` with every commit, as today's
snapshot does. Decided (owner, 27 November 2026), replacing today's refusal to open:

1. **Never backwards.** The app's working time is the later of the phone's clock and
   the highest time seen. If the clock has gone back, the app keeps working on that
   time and shows a warning to fix the clock. Keys, labels and expiry never go
   backwards.
2. **Forward only on evidence from friends.** If authenticated messages from at least
   **2 different paired contacts** were created more than 5 minutes after the working
   time, the working time moves forward to the earlier of those two creation times.
   Strangers cannot fake these times: they are inside messages only those contacts
   can make.
3. **Strangers only cause a warning.** If most of at least 5 links in 10 minutes report
   a time far from the working time, the app shows "Your clock looks about N hours
   wrong". It never changes the working time because strangers say so. *For review:*
   how links report time without making a wrong clock a tracking signal (for example,
   only when asked, rounded to the minute).

Moving forward too far is the dangerous direction: it would expire messages and step
weekly pair secrets forward, deleting the old ones (rotating address labels §1). Rule 2
needs two contacts to agree, and never moves past what they sent.

### 9. Upgrade

Nothing is migrated. On first start of the update the app deletes
`messaging/endpoint-v1.bin`, its AtomicFile backup and new files, the Keystore alias
`org.yapmessenger.endpoint-v1` and the `yap_profile` preferences, then creates the new
store. Onboarding runs again and every contact is re-paired.

## What changes elsewhere

- **Endpoint contract:** "Native boundary and durable state" is rewritten for
  transactions instead of snapshots; the limits paragraph is replaced by §5.
- **`EndpointRepository.kt`:** wraps and unwraps the store key; no longer reads or
  writes snapshots.
- **`SecureModel.kt`, `NativeBridge.kt`, `native/android`:** small requests and results;
  history read page by page.
- **`Profile.kt`:** `ProfileStore` reads and writes through the endpoint.
- **`groups/`:** persistence through `group_log` and `group_state`.
- **Audit findings 5 and 7** are closed by this design once built and tested.

## Open questions for the reviewer

1. redb with per-record XChaCha20-Poly1305 and HMAC keys, or SQLite with SQLCipher?
2. Is the metadata left visible on disk (record counts and sizes per unnamed chat)
   acceptable?
3. Encrypting the carrier store: worth the CPU?
4. The starting values in §5.
5. Applying group events one at a time (§6).
6. The clock rule (§8), especially how links report time in rule 3.

## Test plan

- Power loss at every step of a receive, a send, a receipt, pairing and a group event:
  after restart the store holds either the whole change or none of it, and no session
  has moved forward without its message being saved.
- A failed decrypt leaves every record unchanged.
- 100,000 history messages across 128 contacts: opening the app, receiving one message
  and scrolling history each take the same time as with 100 messages (measured on a
  mid-range phone).
- One contact sending without stopping: other chats keep working; that contact is
  refused at its fair share and named in the notice.
- Removing a contact leaves no record readable for it; clearing history removes its
  rows and attachments.
- A value copied to another record or table fails to open; a changed value fails to
  open.
- A missing or wrong wrapped store key fails closed and never creates a new identity
  over existing data.
- Upgrade from 0.2 deletes every old file, alias and preference.
- A 10,000-event group: inserting an event at the heads takes under 100 ms.

## Implementation steps (after approval)

1. Endpoint (Rust): the store, encryption, tables, transactions, libsignal adapters.
2. Endpoint: limits and fair shares; group log and state.
3. Android: store key wrapping, the new bridge calls, paged history, profile moved.
4. Upgrade clean-up, then the tests above.
5. Docs: endpoint contract, `SECURITY.md`, audit findings 5 and 7.
