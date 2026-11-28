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

A single **SQLCipher** database, `messaging/yap-store-v1.db` in `noBackupFilesDir`,
opened by the Rust endpoint through `rusqlite` with its bundled SQLCipher build (both
pinned, as libsignal is). Decided (owner, 28 November 2026): SQLCipher encrypts every
database page, so a seized file shows only one encrypted file of a certain size, not
how many messages each chat has. An earlier draft used redb with per-record encryption,
which left per-chat counts and sizes visible.

SQLite gives all-or-nothing transactions that survive power loss (write-ahead log,
`synchronous = FULL`), and lets screens read a consistent state while a write is in
progress.

The Rust endpoint opens the database itself. Snapshots no longer cross the native
bridge: Android sends a small request ("send this text to this contact", "here is an
envelope") and gets a small result back. One serial worker still does all writes, as
today; screens read history through read-only transactions, page by page.

### 2. Encryption at rest

- **Store key.** The endpoint makes a random 32-byte store key, used as SQLCipher's raw
  key (no passphrase stretching is needed for a random key). Android wraps it with
  AES-256-GCM under a non-exportable Android Keystore key (alias
  `org.yapmessenger.store-v1`) and saves the wrapped key in
  `messaging/store-key-v1.bin`. Each start unwraps it and hands it to the endpoint once.
- **First start, in this order:** create the Keystore key; make and wrap the store key;
  write the wrapped key to a temporary file, sync it and rename it into place; only then
  create the database. Recovery at start:
  - wrapped key and database both present: open normally;
  - wrapped key present, no database: an interrupted first start; delete the wrapped
    key and alias and start again;
  - database present, wrapped key or Keystore key missing or unreadable: **fail
    closed**, never create a new identity over it, and tell the user the store cannot
    be opened;
  - neither: first start.
- **SQLCipher settings** (page size, KDF iterations set for a raw key, HMAC algorithm)
  are fixed in the code and recorded in `meta`; changing them is a schema change.
- **What someone with the files but not the key learns:** the size of the database,
  its write-ahead log and the attachments folder, and when they changed. Not who, not
  what, not per-chat counts.
- **Whole-store erase.** Deleting the Keystore key and the wrapped store key makes the
  database unreadable, apart from flash remnants written before.
- **Not protected:** a phone that is unlocked and running, with the store open; and
  **rollback**, where someone replaces the whole database with an older copy. Encrypted
  pages stop changes, not a complete older file. That stays open (see "Open questions").

### 3. What is stored

| Table | Holds | Replaces |
| --- | --- | --- |
| `identity` | Identity key pair, registration ID, prekey counters | snapshot |
| `prekeys` | One-time, signed and post-quantum prekeys | snapshot |
| `contacts` | Per contact: identity, name, sender hint, pair-secret week state, recognition switch | snapshot |
| `sessions` | One libsignal session per contact, at most 512 KiB each | snapshot |
| `pairing` | Pairing attempts from Confirmed until their resume window ends: reserved pre-keys, `psk`, handshake hash, roles, commitment and cached bootstrap bytes; earlier states are memory only and a restart cancels them (encrypted pairing §3) | snapshot |
| `friend_requests` | Friend requests sent and received through groups, with their cached accepts (encrypted pairing §5) | new |
| `history` | Per contact or group, in order: messages, status, route, attachment references | snapshot, 512 limit |
| `outbox` | Unsent envelopes with copy counts and reservations (mesh v3.1 §1.4) | snapshot, 448/64 limits, host memory |
| `received` | Received-message records until each message's expiry, with the cached receipt | snapshot, 4,096 limit |
| `seen_last_copies` | Relay keys of last copies dropped, for 24 hours (mesh v3.1 §1.3) | new |
| `group_log` | Each group's signed events, with the results already worked out at insertion | memory only |
| `group_state` | The group's state at its current heads | recomputed each time |
| `group_sessions` | One-to-one libsignal sessions with group members who are not contacts, by group and member | new |
| `group_prekeys` | This phone's group bundles, replaced monthly, and the record of base keys used with each last-resort pre-key (group encryption §3.2) | new |
| `group_keys` | Own and received sending keys and label keys by generation, at most 8 per member (§3.6) | new |
| `group_inboxes` | Own fallback and current inbox keys per group, and every member's known inbox keys with their generation (§3.11) | new |
| `group_pending` | Pieces waiting for acknowledgement, and the newest pending generation per sender (§3.6) | new |
| `group_accepted` | Accepted-piece records: sender, digest of the inner message, generation and the acknowledgement sent (§3.6, "Repeated pieces") | new |
| `attachments` | Manifests, file keys, reservations and received byte ranges (attachments §1, §4) | new |
| `profile` | Your name, colour, onboarding flag, Enhanced security switches | plain preferences |
| `meta` | Schema version, SQLCipher settings, working time (§8), the space accounting in §5 | snapshot |

Memory only, never written: link states, the address-label and piece-label lookup
tables, and the recognition key table (mesh v3.1 §3.3).

**The carrier store** for other people's envelopes moves from today's plain
`relay-v1.redb` into a **second SQLCipher database**, `messaging/carrier-v1.db`, under
its own random key, so a seized phone does not show which envelopes it carried. Its key
lifecycle is its own: Keystore alias `org.yapmessenger.carrier-v1`, wrapped key in
`messaging/carrier-key-v1.bin`, created in the same order as §2. Unlike the endpoint
store, it holds only other people's envelopes, so if its key is missing or unreadable,
the carrier database is deleted and a new empty one created, without stopping the app
(carried envelopes are lost, which v2 already allows on eviction). It keeps its own limits (10,000 envelopes, 64 MiB) and uses the
same working time (§8). Its acceptance-time and expiry indexes live inside the
encrypted pages, so scans do not need to decrypt each envelope. *For review:* the CPU
and write cost on a busy carrier, to be measured.

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

Attachment files live outside the database (§7a), so they follow a separate,
stated order instead of sharing the transaction.

### 5. Limits without hard stops

The review pointed out that "it expires in 24 hours" bounds age, not bytes or work, and
that disk exhaustion is not a fix for audit finding 5. So the limits are **bytes,
counted before writing**, with room kept back for what must never be refused.

- **Space accounting.** `meta` keeps a running byte count per chat (history and its
  attachments), per group (log, keys, sessions, pending pieces), and for the shared
  tables (outbox, received, seen last copies). Each write adds its size to the right
  count in the same transaction. The real file sizes (database, write-ahead log,
  attachments, staging files) are checked against these counts at every start.
- **Overall limits** the user can see and change in Settings: **2 GB** for messages
  and group data, **5 GB** for attachments (attachments §4). Raising either checks the
  phone's real free space first.
- **The physical floor,** checked before every write transaction and every attachment
  chunk, not only at start: Yap writes only while the phone has at least **1 GB** free
  plus **256 MB** of headroom for its own database work. Counted record sizes are not
  file sizes, so this check reads the real free space.
  - Both databases use `auto_vacuum = INCREMENTAL`, so freed pages are returned a few
    at a time and compaction never needs a second copy of the database.
  - The write-ahead log is checkpointed after any commit that leaves it over 16 MB.
    Read transactions for screens are short (one page of history each), so they never
    hold the log open for long.
  - **Below the floor**, in this order: stop accepting carried envelopes; then stop
    accepting new incoming messages and attachments (not acknowledged, so senders
    keep them); keep allowing receipts, deletions and the reserve's essentials; show
    "Phone storage almost full". Another app filling the phone has the same effect.
- **Kept back for essentials: 64 MB**, used only for receipts, acknowledgements,
  group key changes, governance events, pairing and the space accounting itself.
  Whether something is an essential is known only after it is decrypted and
  authenticated, so an envelope first passes the ordinary per-sender rate below; once
  authenticated as an essential, it may use the reserve. Essentials have their own
  limit of 1,000 an hour per contact or group sender, so the reserve cannot become an
  unlimited path. Operations that only shrink the store (receipts that clear the
  outbox, deletions) are always allowed, within the physical floor's headroom.
- **Each chat's share** at all times, not only near the limit: a chat (contact or
  group) may use at most the larger of **10%** of the limit and an equal share among
  chats active in the last 30 days. A chat at its share has new incoming messages
  refused (not acknowledged, so the sender keeps them), with a notice naming it and
  offering to clear history or raise the limit. Other chats are unaffected until the
  whole limit is reached; then every chat over its equal share is refused first.
- **Rates:** at most 1,000 incoming messages an hour from one contact or one group
  sender, at most 3,000 an hour in total, and at most 1,000 an hour created by this
  phone. Over these, envelopes are not acknowledged, so honest senders retry.
- **Bounded tables:** outbox and received rows are bytes in the shared count and
  expire with their envelopes; seen last copies are 48 bytes each and expire after 24
  hours (at most 200,000, about 10 MB); pending group pieces keep only the newest per
  sender and recipient.
- 2 GB, 5 GB, 1 GB, 64 MB, 10%, 30 days and the rates are starting values.

### 6. Group state

`group_log` keeps every event with the results `groups/` already works out when an
event is inserted (whether it is valid, its depth, the active count before it, whether
a SuperAdmin made it). `group_state` keeps the state at the current heads.

- **Full working-out stays the reference.** The existing `derive()` from the log is
  always correct and always available.
- **One shortcut, only when it is provably the same:** if a new event's parents are
  exactly **all** the current heads, it extends the whole history in a straight line
  and cannot create a new concurrent branch, so the stored state is updated from that
  event alone.
- **Everything else is worked out in full:** an event whose parents are not all the
  current heads, including a late event that starts a branch from an older point (a
  SuperAdmin decision made alongside an admin's, say, which can overrule it), a merge,
  or any invalid event.
- `groups/` has no public "apply one event" step yet; adding one is part of this work.
  Before the shortcut is used, a test compares it with full working-out on every case
  (forks, late arrivals, merges, invalid events, removals, settings, elections). Only
  then is it timed: the target is under 100 ms for a straight-line event in a
  10,000-event group on a mid-range phone.

### 7. Deleting

- **Clear history** deletes that chat's history rows and its attachments.
- **Removing a contact** deletes its contact record, session, pair secrets, history
  and outbox entries in one transaction, and marks its attachment files for deletion
  (§7a).
- **Leaving a group** deletes its log, state, sessions, pre-keys, keys, inboxes,
  pending pieces, history and attachments the same way.
- After large deletions the database is compacted (`VACUUM`) so freed pages are reused;
  space for that is kept free (§5).
- Deleted records may survive on flash, and SQLCipher pages written before a deletion
  stay readable to anyone who has both an old copy and the store key; compaction is not
  secure erasure. The docs say so and do not promise otherwise. Signed group logs keep
  fallback inbox keys for as long as the log is kept (group encryption §3.11).

### 7a. Attachment files and the database

Files cannot be in a database transaction, so the order is fixed:
- **Adding:** write the file under a temporary name, sync it, rename it to its final
  random name, sync the folder, and only then commit the database reference. A crash
  before the commit leaves an orphan file, deleted at the next start.
- **Deleting:** commit the database change first, marking the file "to delete"; then
  delete the file; then remove the mark. At every start, marked files are deleted and
  marks cleared.
- **At every start:** files with no reference are deleted; references whose file is
  missing are shown as "Attachment missing". Nothing is sent or shown until this check
  has run.

### 8. The clock check

Decided (owner, 28 November 2026), replacing today's refusal to open, and replacing an
earlier draft that let two friends' messages move the clock forward (the review found
that a phone with a wrong clock cannot reliably receive those messages, and that two
contacts could lie together).

One **working time** is used by everything: the endpoint, the carrier store, pairing,
groups, labels, weekly secrets and attachments.

1. **It never goes backwards, and it keeps ticking.** Each time the working time is
   read, it is worked out in this order, from the **last working time returned** (not
   the value at start) and the phone's elapsed-time stopwatch
   (`SystemClock.elapsedRealtime`):
   1. `base` = last working time + stopwatch time since then;
   2. if the phone's clock is at or behind `base`, the working time is `base`;
   3. if the clock is ahead of `base` by up to 2 days, the working time is the clock;
   4. if it is ahead by more, the working time is `base` and the question in rule 2 is
      shown;
   5. the result is remembered as the last working time, and saved in `meta` with the
      next commit.

   So once time has moved forward, setting the clock back, with or without a restart,
   never brings it back. The app shows "Your phone's clock has gone back; Yap is using
   the time it last knew". After a reboot the stopwatch restarts, so the saved value is
   the floor and the clock is compared with it as above.
2. **Big forward jumps wait for you.** "Your phone's clock jumped forward by N days. Is
   that right?" Until you answer, Yap keeps ticking from `base`. **Yes** makes the clock
   the working time from then on, and only then do expiring messages, stepping weekly
   secrets and deleting old ones, and expiring group keys catch up. **No** keeps Yap's
   own time and asks you to fix the clock; the question comes back if the clock is
   still far ahead after an hour. Jumps up to 2 days, such as a phone that was off over
   a weekend, are taken without asking; even those can expire a day's undelivered mail,
   which is the same as if the time had really passed.
   **A "Yes" given by mistake cannot be undone,** because working time never goes back:
   messages that expired stay expired and deleted secrets stay deleted. The prompt says
   so. The only way back is to re-pair the affected contacts.
3. **Nearby phones can only cause a note.** Links do not send their clock by default.
   A phone whose working time and clock disagree by more than an hour may ask up to 5
   links for their time, rounded to the minute, at most once an hour. If most answers
   disagree with it, the app shows "Nearby phones' clocks disagree with yours". This
   never changes the working time, never allows deleting anything, and never locks
   the app. Five links can be one person with five phones, so the note says only
   that they disagree.
4. **Both stores share one time.** The carrier store does not keep its own clock. At
   start, the working time is the later of the two stores' saved values, and both
   save the same working time from then on. If the phone stops between the two
   commits, the later value wins at the next start.

**Tests:** an advance of under 2 days followed by setting the clock back in the same
run; a confirmed and a refused larger jump; a reboot; a crash between the two stores'
commits.

*Still open for review:* a restored older copy of the whole store can bring back an
older working time and older secrets (rollback); this rule does not solve that.

### 9. Upgrade

Nothing is migrated. On first start of the update the app deletes
`messaging/endpoint-v1.bin`, its AtomicFile backup and new files, the Keystore alias
`org.yapmessenger.endpoint-v1`, the `yap_profile` preferences and the old carrier store
`messaging/relay-v1.redb`, then creates the new stores. Onboarding runs again and every
contact is re-paired.

## What changes elsewhere

- **Endpoint contract:** "Native boundary and durable state" is rewritten for
  transactions instead of snapshots; the limits paragraph is replaced by §5.
- **`EndpointRepository.kt`:** wraps and unwraps the store key; no longer reads or
  writes snapshots.
- **`SecureModel.kt`, `NativeBridge.kt`, `native/android`:** small requests and results;
  history read page by page.
- **`Profile.kt`:** `ProfileStore` reads and writes through the endpoint.
- **`groups/`:** persistence through the group tables, and a one-event step for §6.
- **`core/src/relay.rs`, `native/android`:** the carrier store moves to SQLCipher;
  `peek_stored()` and the fixed offsets change with it.
- **Audit finding 7** (whole-snapshot cost) is addressed by this design once built.
  **Audit finding 5** (one contact blocking the rest) is addressed by the byte shares
  and reserve in §5, once built and tested under flooding.

## Open questions for the reviewer

1. SQLCipher through `rusqlite`: versions, Android build, and the settings for a raw
   key.
2. Moving the carrier store to SQLCipher: CPU and write cost (§3).
3. The byte accounting, reserve and shares in §5, and their starting values.
4. The group-state shortcut and its equivalence test (§6).
5. The file order in §7a.
6. The clock rule (§8).
7. **Rollback:** a restored older copy of the whole database brings back older state.
   Not solved here; options include a counter in the Keystore, which needs its own
   review.

## Test plan

- Power loss at every step of a receive, a send, a receipt, pairing and a group event:
  after restart the store holds either the whole change or none of it, and no session
  has moved forward without its message being saved.
- A failed decrypt leaves every record unchanged.
- 100,000 history messages across 128 contacts: opening the app, receiving one message
  and scrolling history each take the same time as with 100 messages (measured on a
  mid-range phone).
- One contact sending without stopping: other chats keep working; that contact is
  refused at its share and named in the notice; receipts and group key changes still
  go through with the store full.
- Every chat at its share at once: the reserve still accepts receipts, key changes and
  governance events.
- Removing a contact leaves no record for it; clearing history removes its rows and
  attachments; a crash at each step of §7a leaves no orphan file and no dangling
  reference after the next start.
- A changed page fails to open the database.
- Each first-start recovery case in §2 behaves as stated; a missing or wrong wrapped
  store key never creates a new identity over existing data.
- Upgrade from 0.2 deletes every old file, alias, preference and the old carrier store.
- The group-state shortcut gives exactly the same state as full working-out on every
  case in §6; then a straight-line event in a 10,000-event group takes under 100 ms.

## Implementation steps (after approval)

1. Endpoint (Rust): the SQLCipher store, key handling, tables, transactions,
   libsignal adapters; the carrier store moved to SQLCipher.
2. Endpoint: byte accounting, reserve and shares; group tables and state.
3. Android: store key wrapping, the new bridge calls, paged history, profile moved.
4. Upgrade clean-up, then the tests above.
5. Docs: endpoint contract, `SECURITY.md`, audit findings 5 and 7.
