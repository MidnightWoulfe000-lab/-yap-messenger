# Local mesh v3.1: group messages, message routes and friend names

**Status: revised after two reviews; do not implement yet.** Codex reviewed this
proposal and [rotating address labels](rotating-address-labels.md) twice on 27 November
2026: five High and nine Medium findings the first time, nine High and six Medium the
second. This revision answers the second review and records the owner's decisions of
the same day; [the first response](../audit/2026-11-27-local-mesh-v3.1-response.md) and
[the second response](../audit/2026-11-27-local-mesh-v3.1-second-response.md) map each
finding to its change. It needs another review. Part 3 adds new cryptography
(recognition tags) and needs a security review before any code. Builds on v3. It
changes v2's copy rules (§1.3) and retires v2's `DELIVERED` frame (§1.2).

Mesh v4 (link topology) is shelved: in simulation it made delivery worse and no fix has
been found yet (project owner, 27 November 2026). Nothing here depends on it.

**Audience: the project owner, coding agents and maintainers.** Read with
[local-mesh-v2.md](local-mesh-v2.md) (store and forward, spray and wait, carrier
storage, flood slow mode), [local-mesh-v3-proximity-wifi.md](local-mesh-v3-proximity-wifi.md),
the [group encryption proposal](group-encryption-v1.md) §3.4, §3.7 and §3.9,
[rotating-address-labels.md](rotating-address-labels.md),
[encrypted pairing](encrypted-pairing.md) and the
[endpoint contract](../android-endpoint-v1.md). Every file this touches is protected
(see `AGENTS.md`).

## One update

v3.1 ships in a single update together with rotating address labels (including the
outer wrapper for one-to-one messages), encrypted pairing, group encryption,
[local storage](local-storage-v1.md), [photos, voice notes and files](attachments-v1.md),
the 128-contact limit, the clean-ups listed under "Also in this update" and the screen
changes. Every phone is updated and re-paired: there is
no compatibility with older builds (owner, 27 November 2026; all test phones belong to
the owner). The envelope format version goes up by one. An older envelope or frame is
refused, and on upgrade the carrier store and any unsent outbox envelopes in the old
format are deleted rather than converted.

The internet relay server is switched off in this update. The app shows it as "Coming
soon". It gets its own design later (see "Not covered"). Today's code still uses the
relay if an address was saved: `SecureModel.pump()` reads `relay_url`, reconnects,
polls, offers outbox packets and carried packets, and `InternetRelay` callbacks receive
and acknowledge traffic. Every one of those paths is switched off in code, and a saved
address is ignored. A "Coming soon" screen alone does not turn it off.

### Also in this update (owner, 27 November 2026)

- **One device per person, for good.** Multiple devices per identity are no longer
  planned. A lost or replaced phone starts a new identity and re-pairs. The `AddDevice`
  and own-device revocation events in `groups/` are removed, with their tests
  ([GROUP_MECHANICS](../GROUP_MECHANICS.md) §5).
- **A private signing key.** Today every build is signed with the public
  `android/app/debug.keystore`, so anyone can make an app Android treats as Yap, and
  moving to another key later would force everyone to uninstall and lose their data.
  This update moves to a private key: the owner creates it, keeps an offline backup,
  and stores it as a GitHub Actions secret that CI writes out only while building. The
  public keystore is deleted from the repository. The debug updater keeps checking
  that an update carries the same key. Because everyone reinstalls for this update
  anyway, no one loses data twice. Losing the private key would mean no further
  updates, so the backup is not optional.
- **The old unencrypted Bluetooth chat is removed** (`YapBluetooth.kt` and its use in
  `MainActivity.kt`, its screens and tests). All messaging goes through the encrypted
  endpoint. `AGENTS.md` and `SECURITY.md` are updated to match.
- **Nearby messaging survives reboots and battery savers.** A `BOOT_COMPLETED`
  receiver restarts `MeshService` after the phone restarts, if nearby messaging was on
  (to confirm on Android 12 to 15, which limit what may start from boot). The first
  time nearby messaging is switched on, the app asks once to be left out of battery
  optimisation (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`), explaining why. If the service
  is still being killed, Settings shows a short guide for that phone maker.
- **The clock rule:** a clock that goes backwards no longer locks the app. Time never
  goes back, moves forward only when two friends' messages show it is behind, and
  strangers can only trigger a warning ([local storage](local-storage-v1.md) §8).

| Part | What it adds | Main files |
| --- | --- | --- |
| 1. Delivering to many | Group envelopes reach every member; no phone is sent what it already has; nobody can ask a phone whether a message is for it | `MeshHub.kt`, `SecureModel.kt`, `core/src/envelope.rs`, `core/src/relay.rs` |
| 2. Message routes | Message details shows a reported route | `endpoint/src/engine.rs`, `SecureModel.kt`, `ChatSession.kt` |
| 3. Friend names | Mesh activity recognises a live link to a paired friend | `endpoint/src/engine.rs`, `MeshHub.kt`, `SecureModel.kt`, native bridge |

All three keep the v2 rule: nothing sent to nearby strangers may be permanent or
trackable.

---

## Part 1: delivering to many

### Why

Today every envelope has **one** recipient, and the nearby mesh is built around that:
- Spray and wait: a carrier holding its last copy offers it only to the recipient.
- `DELIVERED`: when the recipient has the envelope, carriers delete their copies.

A group message has up to 127 recipients, and so does a batch of key hand-overs after
someone is removed (group encryption §3.9). Under the current rules the first member to
receive one would send `DELIVERED`, and every carrier would delete it before the other
members got it. Carriers also cannot tell who the members are: group envelopes carry
hourly labels only members can recognise.

Both of today's ways of finding the recipient also tell others who it is. A carrier
with a last copy learns which phone took it, and `DELIVERED` announces "this was for
me". This part replaces both, for one-to-one and group envelopes alike.

### What the limits really are

A phone carrying nearby messages holds up to **10,000 envelopes and 64 MiB**, oldest
(by local acceptance time) deleted first when full. The byte limit counts envelope
bytes, not database overhead. The native carrier also accepts at most 256 KiB per
connection per minute and 1 MiB per minute in total (`native/android/src/lib.rs`).
(The internet relay server's limits, 1,024 envelopes and 8 MiB, do not apply here.)

| Event, group of 128 | Envelopes | Size | Share of one phone's carrier store |
| --- | --- | --- | --- |
| A removal, every session open | 127 | about 6.7 MB | about 10% of the space |
| A removal, no sessions open yet (worst case) | about 635 | about 33 MB | about half of the space |

At 1 MiB a minute, taking in the worst case takes a carrier over half an hour.

### 1.1 The envelope header

Two new bytes go into the authenticated part of the header:

- **Routing class:** 1 for one recipient, 2 for many.
- **Starting copies:** the number of copies the sender started with, 1 to 96 for class
  1 and 1 to 255 for class 2. It never changes after sending.

`Header::binding()` grows from 68 to **70 bytes**, in this order:

| Offset | Bytes | Field |
| --- | --- | --- |
| 0 | 4 | magic and version (version goes up by one) |
| 4 | 1 | routing class |
| 5 | 1 | starting copies |
| 6 | 16 | message ID |
| 22 | 16 | destination label |
| 38 | 16 | sender hint |
| 54 | 8 | creation time (ms, big-endian) |
| 62 | 8 | expiry time (ms, big-endian) |

The hop byte stays outside the binding, straight after it (encoded byte 70), and the
relay key still zeroes it before hashing. Because both new bytes are in the binding,
they are covered by the relay key, by the effort stamp and by the outer wrapper's
associated data (rotating address labels §5, group encryption §3.4, both updated to 70
bytes). Changing either one gives a different relay key and breaks the stamp. The Rust
decoder, the Kotlin code that reads hops, destination and ID at fixed offsets
(`SecureModel.kt` `envelopeKey()` and `receive()`), the size checks and the outer-layer
test vectors all change together. A phone checks the class and starting copies before
doing anything that depends on them: a class other than 1 or 2, or starting copies of
0 or above the class limit, is refused.

Nearby, a group envelope travels in a new frame, `GROUP_ENVELOPE` (9), laid out like
`ENVELOPE` (§1.4). A phone refuses a frame whose type disagrees with the envelope's
class, and stores the class with the envelope in the same write. Every later rule reads
the stored class, never the incoming frame. A second arrival with the same relay key
but a different class is refused; this is a check against inconsistent data, since a
different class already means a different relay key.

The class says only that an envelope has many recipients. It does not say which group,
who sent it, or whether it holds chat or keys. Carriers check the stamp and the
structure; they cannot check who wrote an envelope.

*What strangers can still see:* a phone sends or carries group traffic. Over time
they can also guess at group activity from sizes, bursts, how long envelopes stay and
how they move, and a large hand-over batch may stand out by size. They cannot tell
which group, or read anything. This is the accepted cost of group messaging working at
all.

### 1.2 `DELIVERED` is retired

No phone sends `DELIVERED` (frame type 8) any more, for either class, and a phone
ignores one if it arrives. Frame type 8 is not reused. A carried envelope is deleted
when it expires (24 hours), when it is evicted, or when its copies are used up (§1.3),
never because the recipient has it. The sender still stops offering a one-to-one
envelope once the encrypted receipt arrives.

This costs storage, since carriers keep copies for the full 24 hours. The copy-count
simulation found storage far below the limit at today's load (§1.3).

### 1.3 Copies

The same rules apply to both classes (owner, 27 November 2026):

- **Starting copies:** **96** for one-to-one (up from 12) and **255** for group
  envelopes, the most one byte allows (owner, 27 November 2026). The sender writes
  this into the header (§1.1). Rust `MAX_COPIES` and Kotlin `Spray.MAX_COPIES` become
  limits per class.
- **Splitting.** A holder with `n` copies, `n` of 2 or more, gives `floor(n / 2)` to a
  phone that wants it and keeps the rest. A phone refuses an allocation larger than
  the envelope's starting copies.
- **The last copy.** A holder with one copy offers it, marked as a last copy, to every
  phone it meets that has not seen the envelope, and **keeps its own copy** until it
  expires. The phone that receives it decides privately what to do:
  - the one-to-one recipient reads it and stores the message as its own;
  - a member of the group reads it and keeps it as a last-copy holder itself, so
    members carry group messages to each other;
  - anyone else drops it without storing it, and remembers its relay key for 24 hours
    so it is not sent another last copy (§1.4). It still accepts a split allocation
    of that envelope later and carries it like anyone else. (In simulation, refusing
    those too cut delivery noticeably, because phones that had seen a last copy could
    no longer help spread the split copies.)

  In every case the receiving phone sends the same `ACK` at the same point: after the
  label lookup and the store-or-drop decision, before any decryption. The holder cannot
  tell which of the three happened. Nobody is asked whether an envelope is theirs.
- **The recipient behaves like everyone else.** A one-to-one recipient given a split
  allocation carries it like any carrier, so its later behaviour on links does not
  show that the message was for it.

So, with honest phones, at most 96 strangers store a one-to-one envelope and at most
255 a group envelope, plus the members themselves. Every phone those holders meet
receives a last copy once and drops it. Junk nobody recognises is never stored by
those phones and never travels further than one step from a holder. Duration is still
bounded by the 24-hour lifetime, and depth by 8 hops.

**The cost.** A last-copy holder sends the envelope once to every new phone it meets
for up to 24 hours. In the corrected [copy-count simulation](../../experiments/copy-count-sim/FINDINGS.md)
this delivered exactly as well as asking each phone whether a message was its own, but
sent each message about as many times as unlimited flooding does: nearly every phone
near a holder is sent it once. Those phones store nothing and pass nothing on, so
storage stays bounded (about 210 envelopes on the busiest phone, against 10,000). At
light load every link stayed within its limits; under heavy chat load this traffic
would fill links sooner. Real phones must confirm that it fits within the link and
native limits. *For the reviewer:* if it does not, a holder could send a last copy to
at most a fixed number of new phones, at some cost to delivery.

**What the copy limit assumes.** The limits above hold only while phones follow the
rules. A modified app can:
- keep a last copy it should drop, and spray it onwards;
- claim more copies than it was given, up to the envelope's starting copies, and
  spread captured envelopes as well as its own. (v2's claim that copy lies spread only
  the liar's own messages is wrong.)

The starting-copies byte stops anyone raising an envelope above what its sender chose,
but it does not stop a liar spending that allowance again and again: it bounds one
allocation, not the total across the network. Every phone's own storage, ingress and
egress limits stay the real defence, and they do not depend on anyone's honesty. There
is no membership or recipient proof: designing one would need its own reviewed
proposal, and it would itself reveal who receives what.

**Why these numbers.** The corrected [copy-count simulation](../../experiments/copy-count-sim/FINDINGS.md)
of 27 November 2026 tested 12 to 96 copies for one-to-one and 32 to 255 for groups, one
at a time, under these rules, in six invented towns. Where everyone passes through the
same busy places, every count reached everyone. Where people stay in their own
neighbourhoods, 12 copies reached 75 to 78% of recipients, 64 reached 96 to 97% and 96
reached 97 to 99%; for a group of 30, 32 copies got a message to every member only
about 40% of the time, 192 did so 81 to 97% and 255 85 to 97%. These are results from
an idealised model, not an optimum: they support
trying higher counts, and must be checked on real phones. A rule where every phone
passes an envelope on a fixed number of times was considered and rejected: in the
simulation, 4 times each spread junk to nearly every phone.

### 1.4 Offers, wants and copy accounting

Before sending envelopes on a link, a phone says which ones it means to send, and the
other phone says which it has not seen. This applies to both classes. No destination
label is sent in an offer, so an offer cannot be used to ask a phone whether a label
is its own.

**Frames.**
- `OFFER` (11): an offer ID (8 random bytes), an entry count (1, at most 64), then per
  entry the relay key (48), the routing class (1) and what is given (1): `0` for a last
  copy, otherwise the number of copies to carry. 3,209 bytes at most.
- `WANT` (12): the offer ID (8) and a 64-bit map of the entries wanted (8). A phone
  declines a split allocation of an envelope it already holds a split allocation of,
  and declines a last copy (`0`) of an envelope it has been sent a last copy of in the
  last 24 hours. Otherwise it wants the entry. A phone that was sent only a last copy
  answers the same way whether it was the recipient, a member or a stranger: it
  declines another last copy and wants a split allocation (which a recipient or member
  then carries like anyone else), so the answer does not show which it was. An entry
  with a class other than 1 or 2, or a
  "given" number above that class's limit, is not wanted.
- `ENVELOPE` (1) and `GROUP_ENVELOPE` (9): offer ID (8), entry index (1), what is given
  (1), effort stamp (8), envelope. The class, the given number and the relay key must
  match the offer entry, and the given number must not exceed the envelope's starting
  copies; otherwise the frame is refused.
- `ACK`: names the offer ID and entry index. A duplicate or unknown `ACK` is ignored.

**States, per link and envelope.**

| State | Meaning | Next |
| --- | --- | --- |
| Not offered | | Offered when it comes up in the page |
| Offered | `OFFER` sent, waiting for `WANT` | `WANT` within 10 s: wanted entries go to Sending, others to Settled. No `WANT`: back to Not offered, retried after 30, 60, 120 s and so on, up to 10 minutes |
| Sending | Copies reserved, envelope sent | `ACK` within 30 s: Settled, reservation spent. No `ACK`, or the link closes: Uncertain |
| Uncertain | It may or may not have arrived | The reservation counts as **spent**. Offered again later; if not wanted, Settled |
| Settled | Never offered on this link again | |

**Reserving copies.** When an entry moves to Sending, the native store moves the given
copies from "available" to "reserved" in one write, before the frame is sent. Splits
are worked out from what is still available, so twelve links in a row get 48, 24, 12
and so on, never 48 each. An `ACK` turns the reservation into spent copies. Anything
uncertain is treated as spent, never released: losing a few copies is safe, and
releasing them could create copies that already exist elsewhere. A last copy (`0`)
reserves nothing, since the holder keeps it. When available and reserved copies are
both zero, the carrier deletes the envelope.

**What is kept.** Copy counts and reservations are stored with the envelope, so they
survive a restart, and the sender's own counts are stored with its outbox envelope
instead of in memory (replacing v2's accepted development limit). A reservation left
over from a crash counts as spent. Link states (Offered, Settled and so on) are memory
only and cleared when the link closes; a new link starts afresh.

**Paging.** The host asks the native store for candidates page by page, in order of
acceptance time and then relay key, so a page boundary does not move when envelopes
are added or expire. It starts each link where that link's last page stopped and skips
settled ones before applying the send limit. Each link's settled list keeps the first
16 bytes of each relay key and can hold the whole store (10,000 entries, 160 KB).

**"Already has it"** means "as far as the other phone knew when it answered". If a
second link delivers the same envelope between `WANT` and the data, the phone `ACK`s
the extra copy and drops it.

**Budget.** A phone answers at most 600 offer entries a minute across all its links.
Over that, it sends an empty `WANT`, and the offer is retried later. 600, 64 entries,
and the retry times are starting values.

*What a `WANT` still reveals:* which envelopes a phone has seen. Someone who gets a
particular stamped envelope to a phone, then later offers it again under a new link ID,
can tell whether it is the same phone. This needs a fresh stamp for each such marker
and is limited by the budget above. It is accepted for this update and listed for
review. The answer no longer shows whether an envelope is a phone's own.

*What a group member still reveals:* a member keeps and re-offers a last copy where a
stranger drops it. Someone who handed a phone a last copy and later sees that phone's
new link ID offering it can tell that phone is a member of that envelope's group,
though not which group. One option for review: a member waits until its link ID has
changed before offering a last copy it received.

### 1.5 Batches stay whole

A carrier stores a key batch as one envelope, with one relay key, and never takes
pieces out, so duplicate detection keeps working. Each member acknowledges its own piece
to the sender inside the encryption (group encryption §3.9); carriers never see those
acknowledgements. The sender resends only unacknowledged pieces in a new, smaller batch.

A member that finds its piece in a batch both reads its piece and keeps the whole
batch as a carrier, under the rules in §1.3. Reading a piece never edits or
deletes the stored batch. Today the host treats "mine" and "carry" as either-or; for
class 2 envelopes it does both.

Because a phone now decides after receiving the whole envelope (§1.3), a newcomer no
longer needs to recognise a label before the batch is sent to it. It still needs a
way to find its piece inside a batch.

**Still to write before building**, in the group encryption proposal: the batch's wire
format, the routing fields a receiver can see before it has the current group key, a
bounded scan of piece labels, how a newcomer or a member who has fallen behind finds
its piece, and how piece acceptance and carrier storage are acknowledged separately.
Group encryption §3.9 defines the pieces, not the container.

### 1.6 Eviction and loss

When the carrier store is full, the oldest envelopes go first, whatever their class.
Group envelopes get no priority, because carriers cannot tell key batches from chat
without revealing which is which.

Anyone with phones can push old mail out by sending enough new, stamped envelopes:
filling 64 MiB with 56 KiB envelopes takes about 1,171 of them and about 307 million
hashes, slowed by the native limits above. So:
- a batch lost to eviction is usually repaired by the resynchronisation in group
  encryption §3.7, but only if that exchange and a new hand-over get through later;
- under continued flooding, repair can keep failing;
- a message whose key never arrived before that key was erased is lost for good.

This proposal does not promise delivery under a sustained attack.

### 1.7 Flood slow mode

A removal makes up to 127 members each send a batch within a short time. A phone in the
middle of a crowd can exceed the phone-wide limit (100 envelopes a minute) and go into
slow mode, and the native byte limits also apply. Last copies sent to every phone a
holder meets (§1.3) add to this. Refused envelopes get no `ACK` and are offered again
(§1.4), so this usually only delays them, but an envelope can still expire or be
evicted while it waits. The limits stay as they are until a simulation shows whether
the delay matters.

### 1.8 Effort stamps

Each group envelope needs its own stamp (18 bits, about 0.3 to 1 second on a phone).
Batching keeps this small: a removal costs each member 1 to 5 stamps, not 126.

*For the reviewer, not decided:* since a sender picks its own starting copies, spam
always takes the maximum. Because starting copies are now in the binding, and so in
the relay key the stamp covers, the required stamp could grow with them: for example 1
more bit for each doubling above 12, so a 255-copy envelope needs 4 more bits, 16 times
the expected hash work. How long that takes on real phones, and what it does to
battery, is not measured. This would raise the cost of making junk with a big
allowance. It would not stop a carrier that ignores the copy rules (§1.3).

---

## Part 2: message routes

Message details shows, for a received message, whether it came straight from the
sender's phone or through how many relays, as reported by the network. For a
one-to-one message you sent, it shows the same, as reported back by the recipient.

### What the code does today

Checked against the code on 27 November 2026 and confirmed by both reviews:

| Fact | Where |
| --- | --- |
| The header has one hop byte, outside the 68 bytes of `binding()` (70 after §1.1). The relay key zeroes it before hashing. | `core/src/envelope.rs` |
| The sender creates every envelope with `hops: 8`, and its own copy leaves the outbox as stored. | `endpoint/src/engine.rs` `encrypt()` |
| Every send from a carrier's relay store lowers the byte by one (`forwarded()`), down to 0. A carrier refuses to store an envelope at 0. | `core/src/relay.rs` `pending_for()`, `envelope.rs` |
| The recipient sees `header.hops` in `receive()` and then drops it. `StoredMessage` has no hop field. | `engine.rs` |
| An exact repeat of a received envelope returns the stored result and the stored receipt; it adds nothing to the history. | `engine.rs` `receive()` |

So the number of relays is `MAX_HOPS - hops_left`: 8 on arrival is direct, 7 is one
relay, and so on down to 0, which is eight relays. With the internet relay off, every
relay is a phone.

### 2.1 Changes

1. **`endpoint/src/engine.rs`, receiving.** Add `#[serde(default)] pub hops_left:
   Option<u8>` to `StoredMessage`. Set it to `Some(header.hops)` where `receive()`
   builds an incoming message, for one-to-one and group messages alike. The value comes
   from the first copy to arrive and is never rewritten. `view()` already serialises
   the messages, so the field reaches Kotlin. Use `core::envelope::MAX_HOPS` instead of
   the literal `8` in `encrypt()`.
2. **`endpoint/src/engine.rs`, receipts.** The recipient puts the forward count inside
   the authenticated receipt: payload `header.id (16) || [header.hops]`, exactly 17
   bytes. A sender refuses any other length, refuses a count above `MAX_HOPS`, still
   checks the receipt against the right contact and message, and saves the first
   receipt's exact bytes and the route together. A repeat receipt cannot change the
   saved route. The receipt's own hop byte describes the return path and is ignored.
   Every phone is re-paired in this update (see "One update"), so there is no 16-byte
   receipt to accept. The [endpoint contract](../android-endpoint-v1.md) is updated at
   the same time.
3. **`SecureModel.kt`.** In `publish()`, read `hops_left` (null when missing) and pass
   it on.
4. **`ChatSession.kt`.** Add `val hopsLeft: Int? = null` as the last parameter of
   `ChatMessage`.
5. **Native bridge** (`NativeBridge.kt`, `native/android/src/lib.rs`). The endpoint
   view passes through as JSON, so no change is expected. Confirm by building, and
   check every constructor, snapshot loading and the Kotlin mapping.

### 2.2 Group messages

Received group messages get a route like any other. Sent group messages do not: group
envelopes have no receipts (members acknowledge inside the encryption, §1.5), and 127
members would report 127 different routes. Message details shows "Not reported".

### 2.3 Limits, stated in the UI and the docs

- **The hop byte is not authenticated.** A carrier can set any value on the first copy
  it passes on, including 8. The screen shows the value as reported and unverified,
  for example "Reported direct · route unverified". It must never suggest closeness,
  trust, privacy, an internet-free path or guaranteed delivery.
- **The route says what the recipient was told.** For sent messages the number is
  authenticated by the session, but the recipient itself read the unauthenticated byte.
- **It counts the first copy to arrive**, not the shortest path. Keeping the first copy
  is a display rule, not protection.
- **It names nobody.** A count only.
- **It tells the sender something new**: roughly how far its message travelled. Only
  the sender learns it, because the receipt is encrypted. A carrier that controls the
  forward byte could use it to send a small signal to a sender working with it. The
  count is coarse and names no relay.
- **Senders must start at `MAX_HOPS`.** True for every build.

### 2.4 What the UI does with it

One function in `MessageRoute.kt` (on the UI branch) changes:

```kotlin
internal fun messageRoute(message: ChatMessage, preview: Boolean): MessageRoute = when {
    preview -> /* unchanged sample logic */
    message.hopsLeft == null -> MessageRoute.Unknown
    message.hopsLeft !in 0..MAX_HOPS -> MessageRoute.Unknown
    message.hopsLeft == MAX_HOPS -> MessageRoute.Direct
    else -> MessageRoute.Relayed(MAX_HOPS - message.hopsLeft)
}
```

with `const val MAX_HOPS = 8` in Kotlin and a unit test that fails if the Rust constant
changes. Labels read "Reported direct" and "Reported via N relays", and the disclaimer
says "reported by the network, not verified".

---

## Part 3: friend names on Mesh activity

A live link to a paired friend is shown as **"Ravi recognised"**, with the link's
radio. Links to anyone else stay "Nearby mesh node N".

### Why it needs the mesh

A link is anonymous by design. After `HELLO` a phone knows only a random link ID that
rotates and a per-link nonce (v2). `SecureModel` keeps a `pairingLinks` map, but only
while pairing. Nothing later tells a phone "this link is Ravi", so two paired phones need
a way to recognise each other that teaches a stranger nothing.

### 3.1 What recognition proves, and what it does not

Recognition proves that the other end of this link could compute a tag with the
secret you share with Ravi, during this link. It does **not** prove Ravi is nearby.
Two people working together can relay a link between two places: one near you, one
near Ravi, passing everything unchanged. Both phones then see genuine tags and show
each other's names. There is no key to steal and nothing to forge, so no tag design
stops it; only a separately reviewed distance-measuring design could.

So:
- the screen says "recognised", never "here", "nearby" or "connected to Ravi";
- recognition is never used for routing, permissions, safety decisions, temporary
  groups or anything else. It only puts a name on a row.

### 3.2 The recognition probe

After `HELLO`, each phone sends exactly one `PROBE` (10) frame of **128** tags of 16
bytes (2,048 bytes; 2,049 with the type, well inside `MAX_FRAME`). For each paired
contact `c`, the phone making the tag (the *tagger*) computes:

```
tag_c = HMAC-SHA256(linkKey_c,
          "Yap link probe v1"          (17 bytes, ASCII)
       || tagger link ID              (16 bytes)
       || other link ID               (16 bytes)
       || tagger HELLO nonce          (8 bytes)
       || other HELLO nonce           (8 bytes))[0..16]
```

All values are the raw bytes exactly as sent in the two `HELLO` frames, fixed for the
link's lifetime. The other slots are fresh random bytes from the operating system's
secure random source, and all 128 slots are shuffled with that source.

The receiver computes, for each of its own contacts, the tag that contact would make
**as tagger** (its link ID and nonce first), and compares it with all 128 slots.

Rules:
- A phone refuses a link where both link IDs are equal, or where the other link ID is
  one of its own.
- Exactly one `PROBE`, of exactly 2,048 bytes, after `HELLO`. A `PROBE` before
  `HELLO`, a second one, or one of the wrong size is ignored. Ignoring a second `PROBE`
  leaves the link's existing result, name or no name, unchanged.
- Exactly one matching contact names the link. No match, or more than one, leaves it
  anonymous.
- The result is kept in memory on the link only and dropped when the link closes.
  Nothing is written to disk, sent on, or put in an envelope.

Because the tagger's own values come first, a phone that echoes back the other's
`PROBE` does not match: the receiver expects the order reversed. Because each phone's
fresh nonce is in every tag, a `PROBE` recorded on an earlier link matches only if the
nonce happens to repeat, which at 8 random bytes is negligible but not impossible.

Why a stranger learns nothing from the frame: it sees 128 random-looking values.
Without a `linkKey` it cannot tell real tags from filler, so it learns neither who we
are nor how many contacts we have. Tags change with every link.

**Considered and rejected:** one shared "recognise me" key per phone, given to every
friend, so a phone sends one tag instead of a list. It is smaller, but removing a
friend would mean making a new key and sending it to every other friend. With one key
per friend, removing a friend just deletes that key.

### 3.3 Keeping the work bounded and invisible

The amount of work must not show how many contacts a phone has, since a stranger can
time when a `PROBE` is sent and how long the phone takes afterwards.

- **Making a probe** always computes 128 HMACs: one per contact and, for unused slots,
  one with a random throwaway key. The filler slots are then replaced with random
  bytes as in §3.2.
- **Checking a probe** always computes 256 expected tags (128 contact places, each
  with the current week's and the previous week's pair secret, see §3.4; unused places
  use random throwaway keys) and compares every one with all 128 slots, using
  constant-time comparisons with no early exit.
- **Caps**, phone-wide, separate for making and checking: at most 30 of each a minute,
  one at a time on the endpoint worker, with at most 4 waiting. Over a cap, a phone
  sends a `PROBE` of filler only, or leaves the received one unchecked, and the link
  stays anonymous. The link itself is unaffected.
- A match changes nothing a stranger could see: no reply frame, no change in what is
  sent or offered on the link, how long it is kept open, or what is logged.
- 30 a minute and 4 waiting are starting values, to check on real phones. The whole
  exchange is to be timed on real phones with 1 and with 128 contacts.

*Accepted limit:* the caps are shared and nobody is authenticated before them, so a
stranger making many links can use them up, and friends then stay anonymous for a
while. Recognition only puts a name on a row, so this degrades the screen, not
messaging.

### 3.4 The link key

`linkKey` is made from the pair's pair secret for the current week, from
[rotating-address-labels.md](rotating-address-labels.md) §1:

```
linkKey = HKDF-SHA256(ikm = pairSecret_week, salt = "Yap link key v1", info = "")  -> 32 bytes
```

The tagger uses the week of its own clock. The receiver checks both the current and
the previous week (§3.3), so two phones either side of a week boundary still recognise
each other. It is worked out when needed and never stored, sent, or passed out of the
endpoint (Rust). The separate salt means the same secret is never used directly for two
jobs. It does not protect the others if the pair secret itself leaks: whoever has the
pair secret has every key made from it.

### 3.5 Contacts and capacity

The endpoint's contact limit goes up from 64 to **128** (owner, 27 November 2026),
matching the 128 probe slots and the group size limit. A full probe then always
contains every contact, so recognition never depends on chance. Group members you have
not paired with are not contacts and never take a probe slot.

Raising one number is not enough. Today's code has these related limits, each to be
reviewed and changed together, not simply doubled:

| Limit today | Where | For 128 contacts |
| --- | --- | --- |
| 64 contacts in validation and in pairing admission | `endpoint/src/engine.rs` | 128 |
| 64 bootstrap records, 68 offers | `engine.rs` | to review against 128 |
| 64 outgoing control slots | `engine.rs` | to review |
| `pairingLinks` map of 64 | `SecureModel.kt` | to review; pending pairings stay at 4 |
| 512 saved messages (448 text) in total | `engine.rs`, endpoint contract | see below |
| 512 KiB per session, 8 MiB snapshot | `endpoint/src/store.rs`, `engine.rs`, `EndpointRepository.kt` | see below |

At the 512 KiB ceiling, 128 sessions cannot fit in one 8 MiB snapshot. Real sessions
are usually far smaller, but the code must not depend on that. The snapshot, which is
rewritten whole on every change, also holds every saved message. How a phone stores its
own chats and sessions for 128 contacts needs its own proposal (see "Not covered").

### 3.6 Changes

| File | Change |
| --- | --- |
| `endpoint/src/engine.rs` | Contact limit 128 and the related limits in §3.5. Derive `linkKey` from the contact's pair secret when needed. Add `link_probe(own_id, peer_id, own_nonce, peer_nonce) → 128 tags` and `link_match(tags, own_id, peer_id, own_nonce, peer_nonce) → contact ID or null`, following §3.2 and §3.3. |
| `native/android/src/lib.rs`, `NativeBridge.kt` | Expose both operations. |
| `MeshHub.kt` | Frame type `PROBE` (10) and the rules in §3.2. `Peer` gets `@Volatile var contact: String?`, memory only, cleared on close. The caps and queue in §3.3, applied before the call into native code. |
| `SecureModel.kt` | Pass `Peer.contact` through: `localLinks` becomes a list of `LinkInfo(link, contactId)`. |

### 3.7 What the UI does with it

`NetworkScreen` takes the `LinkInfo` list and the contacts, and gets the "Hide me now"
button (§3.9). A row with a `contactId`
shows "<name> recognised" and the contact's avatar, with the link's radio as the
subtitle; the map node shows the same. Every other row stays "Nearby mesh node N". No
"via Ravi" arcs and no distances outside the labelled sample preview. A short note on
the screen says recognition does not prove someone is nearby.

### 3.8 Limits

- **Direct links only.** A friend two hops away is not on the map. Naming them would need
  a neighbour exchange, which leaks who is near whom; that was part of the shelved mesh
  v4.
- **Relayed links** can show a recognised friend who is far away (§3.1).
- **A friend's phone in the wrong hands.** Whoever holds a friend's phone, or a copy of
  its data, holds that pair's current secret. Because each week's secret is made from
  the last (rotating address labels §1), they can work out every later week's secret
  too: they can recognise you on any link and pass as that friend in recognition, until
  you remove that friend. They cannot work out earlier weeks' secrets. Hiding from that
  friend (§3.9) stops your phone sending them tags in the meantime.
- **A name is a local label**: the name you stored for the contact, not something the
  link proves.

### 3.9 Hiding from friends (Enhanced security)

Recognition means a friend's phone, or whoever has taken it, can tell when you are on
the other end of a link (§3.8). Someone at a protest may not want that. **Enhanced
security** lets them stop it (owner, 27 November 2026).

- **"Let friends recognise me nearby"**, on by default. Switched off, the phone sends
  no real tags at all.
- **Per friend:** the same switch on each contact, for when one friend's phone may have
  been taken. That friend's tag is left out; everyone else still recognises you.
- **"Hide me now"**, a button on the Network screen and in Enhanced security, switches
  recognition off in one tap.

How it works:
- While hidden, the phone still sends its `PROBE`, with all 128 slots (or that friend's
  slot) filled with random bytes, after the same 128 HMACs (§3.3). To a stranger, and
  to a friend, it looks exactly like a phone with no friends nearby, so hiding itself
  is not visible.
- Hiding takes effect at once: the phone changes its link ID straight away, as it already
  does every 10 to 20 minutes, which closes its links and opens new ones under the new
  rules. A friend's phone loses the name with the old link.
- Nothing is deleted. The keys behind recognition are made from the secret you share
  with each friend for your chats (§3.4), so deleting them would break the chats too.
  They are not needed: someone holding a friend's phone can only recognise tags you
  send, and while hidden you send none. Switching recognition back on uses the same
  keys.
- While hidden, your phone still recognises friends who have not hidden. That happens
  only on your phone and sends nothing.
- The switches are stored on the phone only. Friends are not told.

What it does not hide:
- Messages between you and a friend. That friend's phone, or whoever holds it, can still
  recognise envelopes the two of you exchange (rotating address labels §3).
- That a phone running Yap is nearby (v2).
- Whatever is already on a taken phone: your chats with that friend and your shared
  secret. To cut that phone off for good, remove the friend.

---

## How the parts fit together

- **Frame types:** `GROUP_ENVELOPE` 9, `PROBE` 10, `OFFER` 11, `WANT` 12. `ENVELOPE`
  (1) and `ACK` gain the offer fields in §1.4. `DELIVERED` (8) is retired and not
  reused. Today's parser refuses 9 and above; that changes in this update.
- **Flood and stamps:** `PROBE`, `OFFER` and `WANT` carry no envelope and need no
  stamp. `PROBE` has its own caps (§3.3). `OFFER` and `WANT` count towards the link's
  existing frame budget, and `WANT` answers have a phone-wide budget (§1.4).
- **Routes and groups:** see §2.2.
- **Group members and friends** are separate lists. Being in a group with someone does
  not name their link: only paired contacts take part in the probe. How group members
  who are not paired contacts address their one-to-one pieces is in rotating address
  labels §7.

## Not covered

- **The internet relay server.** Off in this update and shown as "Coming soon". Its
  return needs its own design: it stores envelopes by a fixed mailbox, which would link
  rotating labels, it has no way to deliver to a group, and a fixed mailbox must never
  reach nearby traffic. Until then nothing in the app sends to or fetches from it.
- **Own chat storage.** How a phone stores its own messages and sessions for 128
  contacts (§3.5).
- **A membership or recipient proof** for carriers (§1.3). Not planned.
- **Tor.** Not designed.
- **Measurement.** The copy counts, last-copy traffic, flood limits under a removal
  burst, and carrier storage in a busy crowd all need fuller simulation, then real
  phones.
- **Proving closeness.** See §3.1.

## Decisions (project owner, 27 November 2026)

1. Group envelopes are marked as group traffic (§1.1): **accepted**.
2. ~~Group envelopes start with 32 copies.~~ **Replaced:** one-to-one envelopes start
   with 96 copies and group envelopes with 255, after the copy-count simulation, and
   group envelopes follow the same copy rules as one-to-one ones (§1.3): **decided**.
3. Message routes and friend names are part of v3.1, and everything ships in one update
   (see "One update"): **decided**.
4. Friend names use one key per friend and a fixed list of 128 tags (§3.2):
   **decided**.
5. The friend-names key is made from the pair secret when needed and not stored
   (§3.4): **decided**.
6. **No phone is sent an envelope it already has, for group and one-to-one envelopes
   alike** (§1.4): **decided**.
7. The screen says "recognised", never that a friend is nearby (§3.1): **decided**.
8. No compatibility with older builds; every phone is re-paired (see "One update"):
   **decided**.
9. The contact limit goes up to 128 (§3.5): **decided**.
10. The internet relay is off in this update and shown as "Coming soon": **decided**.
11. Enhanced security can stop friend recognition, for everyone or per friend, with a
    one-tap "Hide me now" (§3.9): **decided**.
12. The copy limit is stated as holding only for honest phones, and the sender's
    starting copies are fixed in the header (§1.1, §1.3): **decided** (after the second
    review).
13. Nobody answers whether an envelope is theirs: a last copy goes to every phone met,
    only the recipient or members keep it, and `DELIVERED` is retired (§1.2, §1.3,
    §1.4): **decided** (after the second review).

## Tests required

Part 1:
- The 70-byte binding: fixed vectors; changing the class or starting copies changes
  the relay key and fails the stamp; class 0 or 3, starting copies 0, 97 (class 1) and
  above 255 are refused; old-format envelopes are refused and old stores are cleared
  on upgrade.
- A group envelope reaches all members of a simulated group of 5, 30 and 128 through
  carriers.
- A captured envelope re-sent under the other frame type is refused; a duplicate with
  a different class is refused.
- Splitting: 2 and 3 copies split to a stranger (1 kept, 1 given; 2 kept, 1 given); an
  allocation above the starting copies is refused.
- Last copy: the recipient keeps it, a member keeps and re-offers it, a stranger drops
  it; all three send the same `ACK` at the same point, decline another last copy for
  24 hours and want a later split allocation; the holder keeps its copy. A junk last
  copy is stored by nobody it reaches.
- A `DELIVERED` frame is ignored.
- Copy accounting: twelve links offered at once get 48, 24, 12 and so on; a lost
  `WANT`, a lost `ACK`, a link closing mid-send, a duplicate `ACK`, a second link
  delivering between `WANT` and data, and a restart with a reservation outstanding
  never increase the total copies; a crash at each step leaves counts no higher than
  before.
- An envelope the other phone already has is never offered to it, for both classes; a
  settled envelope is never offered again on that link; state clears on close.
- Paging: a link sees every eligible envelope in a store holding more than 128 settled
  ones, with envelopes added and expiring during the walk.
- The `WANT` budget: over 600 entries a minute gets empty answers, and offers resume.
- A member reads its piece of a batch and still carries the whole batch.
- A removal burst from 127 senders through one carrier: slow mode and the native
  limits delay delivery.
- Sustained eviction, expired old generations, a sleeping phone and lost recovery
  traffic, reporting what is recovered and what is lost.
- Large junk envelopes, lying phones (keeping last copies, inflating counts) and rapid
  link churn in simulation.
- With a saved internet relay address, nothing connects to, polls, sends to or
  receives from the relay.

Part 2:
- `endpoint/tests/endpoint.rs`: messages arriving with hop bytes 8, 7, 1 and 0 show
  `hops_left` 8, 7, 1 and 0 in `view()`; a received group message records its hops.
- A 17-byte receipt sets the sent message's route; 16 bytes, 18 bytes and a count of 9
  are refused; a repeat receipt, including after restart, does not change the route.
- An old snapshot without the field loads.
- Android: `messageRoute` for null, 0, 7, 8 and 9.
- Two phones: direct (reported direct), one carrier between (reported via 1 relay).
  Results recorded here.

Part 3:
- Fixed test vectors for tags, including the byte order.
- Two paired endpoints match each other; a third matches neither; tags differ for
  different nonces and link IDs.
- An echoed `PROBE`, a `PROBE` from an earlier link, swapped roles, equal link IDs, a
  wrong size and a `PROBE` before `HELLO` leave the link anonymous; a second `PROBE`
  leaves the first result unchanged.
- Two phones connecting to each other at the same moment both recognise each other.
- Two phones either side of a week boundary recognise each other.
- Making and checking a probe take the same time with 1 and with 128 contacts, and
  with and without a match; a match changes nothing on the wire.
- Over either cap, links stay anonymous and keep working.
- Hidden (for everyone or for one friend): the `PROBE` is still 128 slots, sent at the
  same point and of the same size; the friend's phone shows "Nearby mesh node N"; "Hide
  me now" changes the link ID and closes links at once; switching back on recognises
  again with the same keys; a hidden phone still recognises friends.
- A two-site relay test: two phones far apart, joined by two relaying phones, show each
  other as recognised. This documents §3.1; it is expected to "succeed".

## Order of work

All three parts ship in one update. Within it:
1. Encrypted pairing, then rotating address labels and the one-to-one outer wrapper,
   since Part 3 and group encryption both need the pair secret.
2. Part 1, then its simulation.
3. Part 2.
4. Part 3, once its security review is done.

The screen changes (§2.4, §3.7, and "Coming soon" for the internet relay) are made on
the UI branch once the data exists.
