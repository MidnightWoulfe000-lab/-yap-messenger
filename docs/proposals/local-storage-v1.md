# Proposal: local storage for your own messages, keys and groups

**Status: draft for review; do not implement.** Revised after Codex's fifth, sixth, seventh and eighth reviews of the
v3.1 update (28 November 2026) and needs another review. Per `AGENTS.md` this changes how keys and
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
pinned, as libsignal is). **Crypto provider (planner decision, 28 November 2026):**
LibTomCrypt 1.18.2, built as in `experiments/sqlcipher-store/`. SQLCipher supports it,
and it builds on the Windows development machine and in Linux CI without Perl (the
stock OpenSSL provider did not build on the Windows machine). Its speed is
unmeasured and goes into gate 6's measurements; gate 1's results apply to that
provider only, and a change of provider means rerunning gate 1. Decided (owner, 28 November 2026): SQLCipher encrypts every
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
failed operation never half-advances a session. **One exception, by design:**
admission charges (the set-up allowance and phone-wide set-up limit in group
encryption §3.11, the work gate and shares in §5, and reply-rate counters) are
committed in their own small transaction **before** the operation starts, so a
failed operation still uses up its charge. They hold only counters, never keys,
sessions or messages. A crash before the commit leaves the
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
  chunk, not only at start, against the phone's real free space (counted record sizes
  are not file sizes). Both stores draw on the same free space, so there are fixed
  tiers (fifth review finding 7):

  | Real free space | What may write |
  | --- | --- |
  | 1 GB + 512 MB or more | everything, including the carrier store |
  | 1 GB + 256 MB or more | everything except new carried envelopes |
  | 1 GB + 32 MB or more | only essentials (below), shrinking operations, checkpoints and space reclamation |
  | less | only checkpoints, space reclamation, cleanup batches and deleting files (see the page allowances below) |

  **Physical size of a write** (sixth review finding 4). What a transaction costs on
  disk is the pages it changes, not the bytes it adds: an update or a deletion (with
  `secure_delete` on) can change many pages while adding nothing. So every write
  transaction has a **page allowance** by class.

  **How the allowance is enforced** (seventh review finding 5). Watching the
  write-ahead log during a transaction does not work: SQLite can keep changed pages in
  its cache and write them only at commit, too late to roll back. So the bound is
  worked out **before** the transaction starts, from what it will touch: the estimate
  is the sum of the maximum encoded sizes of the rows it will write or delete, each
  rounded up to whole pages of the pinned page size (usable bytes per page, not 4,096: SQLCipher reserves 80 bytes of each page, so 4,016 of 4,096 are usable), doubled, plus 16 pages for
  indexes and accounting.

  **Record and operation contract** (eighth review finding 3).
  - *Every record type has an encoded maximum.* Known ones: a session 512 KiB, an
    envelope 64 KiB, a group event 8 KiB. The rest (history row, sender-key state,
    pending and cached pieces, friend requests, admission counters, each index) get
    their maxima written into §3's table as the **first output of gate 1** ([gates before integration](local-mesh-v3.1-group-envelopes.md#gates-before-integration))
    below, before any code depends on them. Group state is stored as **one row per
    member and one row per open proposal, election or warning**, each at most 8 KiB,
    so a governance step changes a bounded number of rows; if any derived group state
    cannot be split that way within the bound, gate 1 fails and the layout is
    redesigned first.
  - *Atomic operations only refuse, never split.* Any operation that runs libsignal
    (decrypt, encrypt, a set-up) commits its session change, history, receipt and
    output together or not at all. If its estimate is over its class allowance, it is
    refused, and gate 1 must show that no valid operation is intrinsically oversized.
  - *Two-stage operations, with a saved input.* Only two kinds may be split, each
    with a durable intent: **cleanup** (as in §7), and **a group piece carrying inner
    contact material** (friend bootstrap): stage 1 decrypts the group piece, commits
    the group session change and saves the inner bytes as a pending input in the same
    transaction; stage 2 processes those saved bytes against the contact session and
    deletes the pending input in its own commit. A restart repeats stage 2 from the
    saved input. The pending input counts against the endpoint quota until stage 2
    commits.
  - *Retirement is bounded by construction:* the first transaction (§7) only sets the
    mark, raises the generation and deletes a bounded number of small secrets; outbox
    entries are cancelled **logically** by the mark (unsent reservations released,
    possibly-sent ones treated as spent, as in mesh v3.1 §1.4), not rewritten one by
    one.

  **Gate 1's measurement** (eighth review finding 4). Whether the estimates really
  bound the pages written is checked on the pinned SQLCipher build before dependent
  work, in a test harness, never in the app's own commit path:
  - each test operation runs on its own connection right after a truncating
    checkpoint, so the write-ahead log starts empty and the frame count SQLite's
    write-ahead-log hook reports after the commit is exactly what that transaction
    appended; the harness also checks that the hook was called for every
    **committed** transaction (a committed test where it was not called fails). The hook
    never fires for a rolled-back transaction, so for those the check is the file
    lengths below, not the hook. Installing the hook replaces SQLite's automatic
    checkpoint, so the app runs its own checkpoints (as this section already says) and
    the harness checks that none ran in between;
  - peak space is measured too: the sizes of the database, log and any temporary files
    are sampled after every statement and at commit, with cache spill both on and
    off, and for rolled-back transactions. Peak space is taken from **file lengths**,
    not from the hook's frame count: with a small cache and spill on, a rolled-back
    transaction left 251 frames (about 1 MB) in the log. The pinned build uses no
    temporary files (`TEMP_STORE` 3), so that figure should always read 0;
  - **pass condition:** for every operation type at its maximum record sizes, frames
    appended and peak extra space are at most its estimate; any excess fails the gate
    and the layout or estimate is revised first;
  - the hook always returns success: it is an observer, and a commit it reports has
    already happened.
  - **First run** (28 November 2026, `experiments/sqlcipher-store/`, desktop and
    Android x86_64 emulator, Android 9; not a real phone): 56 of 56 measured runs
    passed with about 2x headroom, on **seven cases only**. Not yet tested, and still
    gate 1 work: history rows, sender-key state, group state, retirement, and a full
    session plus history, WAL reuse across several commits, and real flash. This is a
    feasibility result, not an audit.

  | Class | Changed pages at most | Allowed down to |
  | --- | --- | --- |
  | Ordinary (messages, carried envelopes, state) | 1,024 (4 MiB) | its tier line above |
  | Essential (receipts, key changes, governance, pairing, accounting) | 320 (1.25 MiB, enough to rewrite one maximum-size session) | 1 GB + 32 MB |
  | Cleanup batch (deleting retired data, marking files to delete) | 1,024 | 64 MB free in all |
  | Checkpoint and reclamation | `incremental_vacuum(256)` steps | 64 MB free in all |

  Before a transaction starts, twice its page allowance must fit between the real free
  space and its line, so one write cannot jump a tier. An attachment chunk adds at most
  64 KiB. Both stores check the same real free space at the start of every
  transaction, and the carrier stops first because its line is highest. Cleanup and
  reclamation free space, so they may run below the 1 GB floor, down to 64 MB free;
  below that Yap writes nothing and shows that the phone is full. Below the
  second line the app shows "Phone storage almost full". Another app filling the phone
  has the same effect. **No floor can guarantee progress** if another app takes all the
  space: Yap then stops writing, keeps what it has committed, and says so.
  - **Reclaiming space.** Both databases are created with `auto_vacuum = INCREMENTAL`
    and `secure_delete = ON`. Setting the mode does nothing by itself: after any commit
    that frees pages, a background step runs `PRAGMA incremental_vacuum(256)` (256
    pages) at most once every 10 seconds until no free pages are left. A full `VACUUM`
    is never run, because it rebuilds the database through a temporary copy that can
    need up to twice its size.
  - **The write-ahead log.** After any commit that leaves it over 16 MB, a passive
    checkpoint runs. If it is still over **64 MB**, a truncating checkpoint waits up to
    1 second for readers; if that fails too, ordinary writes to that store pause, as in
    the third tier, until a checkpoint succeeds. Read transactions for screens are
    short (one page of history each); the worker holds no read transaction across a
    radio wait.
  - **A failed commit** (disk full or an I/O error) is rolled back. Nothing it would
    have acknowledged is acknowledged, the store moves to the tier the real free space
    now gives, and the work is retried only after a later free-space check passes.
  - Tests: another app filling the disk while both stores write; a blocked reader
    holding the log; a commit failing with disk full; deleting a chat at each tier;
    incremental reclamation after a large deletion.
- **Kept back for essentials: 64 MB**, used only for receipts, acknowledgements,
  group key changes, governance events, pairing and the space accounting itself.
  Operations that only shrink the store (receipts that clear the outbox, deletions)
  are always allowed, within the physical floor's tiers.
- **One admission order for incoming messages** (fifth review finding 8). Whether
  something is an essential is known only after decryption, so:
  1. **Work gate, before decryption** (sixth review finding 3; owner, 28 November
     2026: everyone gets a guaranteed share). Each contact and each group sender
     has a **guaranteed share of 60 decryptions an hour** that no other contact can use
     up (see the stated limits below for strangers and group members): these do not
     count against the phone-wide gate. Beyond its share, a sender may
     use the **shared gate**: at most 2,000 an hour per sender and 6,000 an hour in
     total. Over both, the envelope is not processed. A sender's own share is used by
     whatever it sends first, so a contact who floods can delay only its own receipts
     and key changes.

     **How shares are set** (seventh review finding 3). Shares belong to identities, so
     one person who is a contact and in several groups has one share. Each **working-time
     hour** is an epoch. At its start, the phone counts the eligible identities (its
     contacts and the current members of its groups) and gives each the same whole
     number of decryptions: 60, or `floor(12,000 / eligible)` if that is smaller. So
     the shares are fixed in advance and together never exceed 12,000. With no eligible
     identities nothing is allocated. **Over 12,000 identities** (owner, 28 November
     2026, eighth review finding 1: absurd in practice, so they take turns): the
     identities are put in a fixed order (by identity key) and each hour the next
     12,000 in that order get a share of 1, carrying on from where the last hour
     stopped, so every identity gets one at least once every `ceil(eligible / 12,000)`
     hours; the rest may use only the shared gate that hour. The epoch's eligible set,
     its position in the order and the spent counts are saved per epoch, so a restart
     or a clock moved back into an epoch already visited reuses its accounting and
     never mints it again.
     Someone who becomes eligible during the hour gets a share from the next hour; until
     then they may use only the shared gate. Nothing is taken back from a share already
     used.

     **What a share is charged for** (seventh review finding 4). A label is visible to
     anyone and proves nothing, so the share is charged only once the **outer layer has
     opened**, which a stranger who copied the label cannot do, and an unknown label
     gets no share at all. For a contact the outer key comes from the pair secret, so
     only that contact can spend its share.

     **The outer-attempt order** (eighth review finding 2). The unit is **one outer
     AEAD trial**: one candidate key tried on one outer layer (a batch can need up to
     32, local mesh and group encryption §3.11). In order: (a) label lookup, which is
     free; (b) before each trial, one unit is taken from a phone-wide **outer budget of
     6,000 trials an hour**, whether the trial then succeeds or fails, and with no
     refund; (c) if the budget is used up, no trial is made, the envelope is not
     handled and gets no mesh `ACK`, so honest senders retry next hour; (d) a trial
     that opens attributes the envelope to the identity it claims, and only then step
     1's share or shared gate is charged for the inner decryption; (e) outer layers
     opened before the mesh `ACK` are kept opened for the worker and never tried or
     charged twice. The outer budget is counted in memory, saved at most once a minute
     and at shutdown, so a crash can forget at most a minute of it (stated, to avoid a
     write per trial).

     *Stated limits, in full:* (1) a stranger who copies recognised labels can use up
     the outer budget and so delay **every** sender's classification on this phone
     until the next hour; the mesh's existing intake limits and effort stamps bound
     how fast, but this is a denial of service the design accepts, like sustained
     flooding. (2) For group traffic the outer layer proves only knowledge of an inbox
     key, which every member (and a removed member, for fallback inboxes) has, so a
     current or former group member can spend another member's share by naming them as
     sender, until libsignal's inner check fails, for as long as the attack goes on.
     Because shares belong to identities, that also uses up the share of a person who
     is both that member and one of your contacts. Neither is a way in: inner
     authentication still decides what is accepted.
  2. **Decrypt and authenticate** in memory, without committing anything.
  3. **Classify.** An ordinary message counts against the ordinary rates (below): 1,000
     an hour per sender, 3,000 in total. An essential counts against its own: 1,000 an
     hour per sender, 3,000 in total, and may use the 64 MB reserve. So once a receipt
     or key change has been decrypted, a full ordinary allowance never blocks it; and
     step 1's guaranteed share means other **contacts'** floods cannot stop it being
     decrypted, within the two stated limits above.
  4. **Commit** only if the message is within its rate and its storage tier allows it.
     Otherwise nothing is committed (the session change included), so the sender's
     retry is decrypted afresh later.

  When storage is low, the same order runs: ordinary messages are decrypted under
  step 1's limits but not committed; essentials are. **What a refusal withholds** is
  the end-to-end acknowledgement (the receipt or piece acknowledgement that lets the
  sender clear its outbox). The mesh `ACK` only ever means "this phone has handled the
  envelope" (local mesh v3.1 §1.3) and may already have been sent, by this phone or an
  earlier hop; a refusal never claims otherwise. Tests: a receipt and a justified key
  change from one contact after three other contacts have each sent 2,060 ordinary
  messages in the hour (each uses its 60 guaranteed and 2,000 shared, so the three
  together use exactly the 6,000 shared gate; seventh review finding 8); the same contact's receipt after it
  has itself sent 1,000 and then 2,000; and each again with the 2 GB limit reached and
  with real free space in the third tier.
- **Each chat's share** at all times, not only near the limit: a chat (contact or
  group) may use at most the larger of **10%** of the limit and an equal share among
  chats active in the last 30 days. A chat at its share has new incoming messages
  refused (not acknowledged, so the sender keeps them), with a notice naming it and
  offering to clear history or raise the limit. Other chats are unaffected until the
  whole limit is reached; then every chat over its equal share is refused first.
- **Rates:** at most 1,000 incoming ordinary messages an hour from one contact or one
  group sender, at most 3,000 an hour in total, and at most 1,000 an hour created by
  this phone. Essentials have their own budget and the guaranteed share in the
  admission order above; these ordinary rates never apply to them. Over these,
  envelopes are not acknowledged, so honest senders retry.
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
- **Removing a contact** happens in two parts (sixth review finding 4), because a
  single transaction for hundreds of megabytes of history cannot fit a page allowance
  (§5):
  1. **Retire, in one small essential transaction** (seventh review finding 6: sized
     so it always fits): delete its pair secrets and link keys (a few hundred bytes),
     cancel its outbox entries, raise the recognition sending generation (mesh v3.1
     §3.3) and mark the contact "removing". From this commit the contact cannot send,
     receive or be recognised: every read, send, receive, queued worker job, attachment
     step and label table checks the mark, after a restart too. It vanishes from every
     screen.
  2. **Clean up, in batches:** a background job deletes, in this order, its session
     (up to 512 KiB, one batch of its own), then its history rows, and marks its
     attachment files for deletion (§7a), each batch one cleanup transaction (§5),
     resuming after a restart, until nothing is left; then the contact record itself is
     deleted. Until then the session and history are unusable through the app but
     still in the encrypted database. The first batch normally runs straight after
     retirement. If space is too low even for cleanup, the job pauses and the app says
     so; the stated cost is that the session stays stored, unused, until it can run.
- **Leaving a group** works the same way: one small transaction deletes this phone's
  own inbox private keys (a few, each 32 bytes), raises the generation and marks the
  group "leaving", which stops every use of the group's keys at once; then its
  sender-key states and skipped keys, sessions (each a batch of its own), pre-keys,
  log, state, pending pieces, history and attachments are deleted in cleanup batches,
  with the same stated cost: until cleanup reaches them they stay in the encrypted
  database, unusable by the app but readable to anyone who unlocks the store.
  Tests: a contact with a maximum-size session, leaving a full 128-member group,
  pending outbox reservations, and a restart while cleanup is paused.
- Clearing a chat's history uses the same cleanup batches.
- Tests: removing a contact with a large history at each tier line, a restart in the
  middle of cleanup, and cleanup pausing at 64 MB free.
- After deletions, freed pages are overwritten (`secure_delete`) and returned to the
  phone a few at a time by incremental reclamation (§5). A full `VACUUM` is never run.
- Deleted records may survive on flash, and SQLCipher pages written before a deletion
  stay readable to anyone who has both an old copy and the store key; neither
  overwriting nor reclamation is secure erasure. The docs say so and do not promise otherwise. Signed group logs keep
  fallback inbox keys for as long as the log is kept (group encryption §3.11).

### 7a. Attachment files and the database

Files cannot be in a database transaction, so the order is fixed:
- **Adding:** write the file under a temporary name, sync it, rename it to its final
  random name, sync the folder, and only then commit the database reference. A crash
  before the commit leaves an orphan file, deleted at the next start.
- **Deleting:** commit the database change first, marking the file "to delete"; then
  delete the file; then remove the mark. At every start, marked files are deleted and
  marks cleared.
- **Incoming attachments have their own states** (fifth review finding 11), stored
  with the reference: **Receiving** (a staging file, with saved received ranges),
  **Checking** (all bytes present, full validation running), **Published** (a
  finished file, renamed to its final name as under "Adding") and **Deleting** (as
  under "Deleting"), plus two short intent states, **Resetting** and **Publishing**
  (below; sixth review finding 5).
- **One charge per attachment.** From the moment its manifest is accepted until its
  row is gone, an attachment is charged its full encrypted size exactly once, whatever
  its state: as a reservation while Receiving, Resetting, Checking or Publishing, and
  as a file once Published. At start, the total is recounted from every row that is not
  gone, so nothing is charged twice or dropped in between.
- **Resetting after failed validation,** in this order: (1) commit the row as
  Resetting, with all ranges cleared and the failure count raised; (2) empty the
  staging file and sync it; (3) commit it as Receiving. Ranges are cleared before the
  file is touched, so stale bytes can never make a reset transfer look complete. At
  start, a Resetting row has its file emptied again and moves to Receiving.
- **Publishing after successful validation,** in this order: (1) commit the row as
  Publishing, with the final random name chosen; (2) rename the staging file to that
  name and sync the folder; (3) commit it as Published. At start, a Publishing row is
  finished: if the final file exists, commit Published; if only the staging file
  exists, do the rename and continue; if neither does, go back to Receiving with a new
  empty staging file and no ranges. The start-up sweep never deletes a file named by a
  Receiving, Resetting, Checking, Publishing or Deleting row.
- **Writing into a staging file:** write the bytes, sync the file, and only then commit
  the range as received. A crash in between loses at most a range that is not yet
  recorded, which is simply received again. A range is acknowledged to the sender only
  after its commit.
- **At every start:** files with no reference are deleted; for **Receiving**, a missing
  staging file is recreated empty and its ranges reset (attachments §4), and an existing
  one keeps its recorded ranges; **Checking** goes back to Receiving and is checked
  again; **Resetting** and **Publishing** are finished as above; a **Published**
  reference whose file is missing is shown as "Attachment missing"; **Deleting** is
  finished. Nothing is sent or shown until this check has run.
- Tests: power loss before and after each step of resetting and publishing, two failed
  validations in a row, and the charged total checked after every restart.

### 8. The clock check

Decided (owner, 28 November 2026), replacing today's refusal to open, and replacing an
earlier draft that let two friends' messages move the clock forward (the review found
that a phone with a wrong clock cannot reliably receive those messages, and that two
contacts could lie together).

One **working time** is used by everything: the endpoint, the carrier store, pairing,
groups, labels, weekly secrets and attachments.

1. **It never goes backwards (one accepted exception, rule 5), and it keeps ticking.** Each time the working time is
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
   never brings it back (except for the 1-hour carrier-recreation exception in rule 5).
   The app shows "Your phone's clock has gone back; Yap is using
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
   **A "Yes" given by mistake cannot be undone,** because working time never goes back (apart from the one accepted exception below,
   at most 1 hour):
   messages that expired stay expired and deleted secrets stay deleted. The prompt says
   so. The only way back is to re-pair the affected contacts. Re-pairing does not change
   the phone's working time, so the prompt must not suggest that it does: it says only
   that expired messages and deleted secrets stay gone.
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

5. **Implementation rules** (fifth review, clock assessment):
   - **One serialised authority.** The endpoint worker owns the working time; the carrier
     store asks it and never works time out itself.
   - **The durable floor is the last committed value.** A value returned just before
     power loss, but not yet committed, may not survive.
   - **The endpoint store always leads** (sixth review finding 8). The authority never
     hands anyone, the carrier included, a working time more than **1 hour** past the
     floor last committed in the endpoint store; before it would, it commits a new
     endpoint floor first (a small essential write, about once an hour while Yap runs).
     So the endpoint holds a committed floor within 1 hour of any time the carrier
     ever used.
   - **Recreating an empty carrier store** takes its floor from the endpoint store and
     never resets it. If the carrier had committed a later time, working time can
     therefore go back by at most 1 hour after such a recreation. That is the one
     accepted exception to "never goes back": it can delay an expiry by up to an hour,
     and cannot undo a weekly secret step, because those are committed in the endpoint
     store. Test: a carrier-only advance, then the carrier key lost and the store
     recreated.
   - **Arithmetic saturates** rather than overflowing.
   - **If a store cannot be read,** nothing is expired, no labels are generated and the
     carrier is not pruned until both stores are open or the carrier has been recreated.

**Tests:** an advance of under 2 days followed by setting the clock back in the same
run; a confirmed and a refused larger jump; a reboot; a crash between the two stores'
commits; a carrier-only advance followed by the carrier key lost and the store
recreated, where working time may go back by at most 1 hour and no further.

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
- A failed decrypt leaves every record unchanged except its admission charge, which was
  committed before it started; a restart straight after the charge, and straight after
  the refusal, keeps the charge.
- 100,000 history messages across 128 contacts: opening the app, receiving one message
  and scrolling history each take the same time as with 100 messages (measured on a
  mid-range phone).
- One contact sending without stopping: other chats keep working; that contact is
  refused at its share and named in the notice; receipts and group key changes still
  go through with the store full.
- Every chat at its share at once: the reserve still accepts receipts, key changes and
  governance events.
- Removing a contact: at retirement, and again after a restart, the contact is denied
  every use (read, send, receive, queued jobs, attachment steps, labels) and a durable
  cleanup mark exists; after cleanup completes, no record for it, no orphan file and no
  dangling reference remains. Clearing history removes its rows and attachments the
  same way. A crash at each step of §7a leaves no orphan file and no dangling
  reference after the next start, except what a pending cleanup mark still names.
- A changed page fails when that page is read (opening alone does not check every page).
  A deliberate whole-file check runs SQLCipher's `PRAGMA cipher_integrity_check` at
  start after an unclean shutdown and on demand. The pragma is confirmed in SQLCipher's
  API documentation (added in version 4.2.0, sixth review); the Android SQLCipher
  version is not yet pinned, so it must still be checked against the pinned version.
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
