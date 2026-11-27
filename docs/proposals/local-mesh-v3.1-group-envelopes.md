# Local mesh v3.1: group messages, message routes and friend names

**Status: revised after review; do not implement yet.** Codex reviewed this proposal and
[rotating address labels](rotating-address-labels.md) on 27 November 2026 (five High,
nine Medium, no Critical). This revision answers every finding and records the owner's
decisions of the same day; [the response](../audit/2026-11-27-local-mesh-v3.1-response.md)
maps each finding to its change. It needs another review. Part 3 adds new cryptography
(recognition tags) and needs a security review before any code. Builds on v3. It changes
one v2 setting: one-to-one envelopes start with 96 copies instead of 12 (§1.3).

Mesh v4 (link topology) is shelved: in simulation it made delivery worse and no fix has
been found yet (project owner, 27 November 2026). Nothing here depends on it.

**Audience: the project owner, coding agents and maintainers.** Read with
[local-mesh-v2.md](local-mesh-v2.md) (store and forward, spray and wait, carrier
storage, flood slow mode), [local-mesh-v3-proximity-wifi.md](local-mesh-v3-proximity-wifi.md),
the [group encryption proposal](group-encryption-v1.md) §3.4, §3.7 and §3.9,
[rotating-address-labels.md](rotating-address-labels.md) and the
[endpoint contract](../android-endpoint-v1.md). Every file this touches is protected
(see `AGENTS.md`).

## One update

v3.1 ships in a single update together with rotating address labels (including the
outer wrapper for one-to-one messages), group encryption, the 128-contact limit and the
screen changes. Every phone is updated and re-paired: there is no compatibility with
older builds (owner, 27 November 2026; all test phones belong to the owner). The
envelope format version goes up by one, and an older envelope or frame is refused.

The internet relay server is switched off in this update. The app shows it as "Coming
soon". It gets its own design later (see "Not covered").

| Part | What it adds | Main files |
| --- | --- | --- |
| 1. Delivering to many | Group envelopes reach every member; no phone is sent what it already has | `MeshHub.kt`, `SecureModel.kt`, `core/src/envelope.rs`, `core/src/relay.rs` |
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

### 1.1 The routing class is part of the envelope

Each envelope gets a **routing class** byte: 1 for one recipient, 2 for many. It sits
inside the authenticated header, so it is covered by `binding()`, by the relay key and
so by the effort stamp. A captured envelope cannot be re-sent as the other class
without breaking its stamp and its relay key.

Nearby, a group envelope travels in a new frame, `GROUP_ENVELOPE` (9), with the same
layout as `ENVELOPE`: `copies (1) + effort stamp (8) + envelope`. A phone refuses a
frame whose type disagrees with the envelope's class, and stores the class with the
envelope in the same write. Every later rule (`DELIVERED`, copies)
reads the stored class, never the incoming frame. A second arrival with the same
relay key but a different class is refused.

The class says only that an envelope has many recipients. It does not say which group,
who sent it, or whether it holds chat or keys.

*What strangers can still see:* a phone sends or carries group traffic. Over time
they can also guess at group activity from sizes, bursts, how long envelopes stay and
how they move, and a large hand-over batch may stand out by size. They cannot tell
which group, or read anything. This is the accepted cost of group messaging working at
all.

### 1.2 No `DELIVERED` for group envelopes

A member never sends `DELIVERED` for a group envelope, and a carrier ignores one for
an envelope whose stored class is 2, including one it is offering at that moment.
Members only `ACK`, like a carrier. A group envelope is deleted when it expires (24
hours) or is evicted, never because one member has it.

### 1.3 Copies: the same rules as one-to-one messages

Group envelopes follow the same spray-and-wait rules as one-to-one envelopes. The only
difference is that a group has many recipients instead of one.

- **Starting copies:** **96** for one-to-one (up from 12) and **255** for group
  envelopes, the most the one-byte copy count allows (owner, 27 November 2026). Rust
  `MAX_COPIES` and Kotlin `Spray.MAX_COPIES` become limits per class, and carriers clamp
  a higher count down to them.
- A holder with more than one copy gives half to each new link that wants it, and keeps
  the rest, as now.
- A holder with **one copy** gives it only to a phone that says the envelope is **its
  own**: the recipient of a one-to-one envelope, or a member of the group for a group
  envelope (§1.4). For a group envelope the holder keeps its own copy too, since other
  members may still be ahead. Anyone else is refused, as now.
- A member that has a group envelope gives a copy to every other member it meets, in
  the same way.
- A one-to-one recipient still answers `DELIVERED`, and the carrier that delivered
  deletes its copy. Group envelopes have no `DELIVERED` (§1.2).

So at most 255 strangers carry a group envelope, plus the members themselves, and at
most 96 carry a one-to-one envelope. An envelope nobody recognises, such as junk, stops
once its copies are used up. Duration is still bounded by the 24-hour lifetime, and
depth by 8 hops. A sender of many different envelopes is held back by effort stamps,
flood slow mode and the native carrier limits.

**Why these numbers.** The [copy-count simulation](../../experiments/copy-count-sim/FINDINGS.md)
of 27 November 2026 compared 12 to 96 copies for one-to-one and 32 to 255 for groups in
six invented towns. Where everyone passes through the same busy places, every count
reached everyone. Where people stay in their own neighbourhoods, 12 copies reached 74
to 77% of recipients and 96 reached 97 to 99%; for a group of 30, 32 copies got a
message to every member only about 40% of the time, and 255 did so 79 to 96% of the
time. Storage stayed far below the limit. The cost is spam reach: junk is carried by
about as many strangers as the maximum copy count. These are model results, to be
checked on real phones.

A rule where every phone passes an envelope on a fixed number of times was considered
and rejected: in the simulation, 4 times each already spread junk to every phone.

### 1.4 Skip phones that already have it (every envelope)

Before sending envelopes on a link, a phone says which ones it means to send, and the
other phone says which it is missing. This applies to **both** classes.

- `OFFER` (11): up to 64 entries, each the envelope's relay key (48 bytes), its
  destination label (16 bytes) and the number of copies the sender would give (1 byte).
- `WANT` (12): for each entry the receiver has not got, whether it wants it **to
  carry** or **as its own** (it is the recipient, or recognises its group's label). A
  phone that already holds an envelope, has delivered it to itself, or refused it as a
  recent duplicate leaves it out. A phone not the recipient or a member leaves out a
  one-copy offer, as it declines one today.
- The sender then sends only the wanted envelopes, in `ENVELOPE` or `GROUP_ENVELOPE`
  frames as now, with the copies the rules in §1.3 allow.

Per live link, each envelope is in one of three states:
- **not offered yet**;
- **wanted or in flight**: if no `ACK` comes (a lost frame, or slow mode refusing it),
  it is offered again after 30 seconds, then 60, then 120, up to 10 minutes;
- **settled**: the other phone said it had it, or `ACK`ed it. It is never offered again
  on this link.

All of this is memory only and cleared when the link closes. A new link starts afresh.
64 keys per `OFFER` and the retry times are starting values.

The host asks the native store for candidates **page by page** across the whole store,
starting where the last page for that link stopped, and skips settled ones before
applying the send limit. Old settled envelopes can no longer hide the rest of a
10,000-envelope store from a link.

*For the reviewer:* the `WANT` answer tells the sender what the receiver already
holds. A stranger who knows an envelope's relay key could offer it to phones and learn
which ones have seen it, which might help follow a phone across link-ID changes. Today
a carrier already shows its stored envelopes to every link it offers them to, so the
offering side reveals nothing new; the answering side does. Saying "as its own" shows
that the phone is the recipient or a member for that hour's label; declining a one-copy
envelope already shows the opposite today. Options: accept it, answer
with a small random share of false "want"s, or let a phone answer only for envelopes
it got in the last hour. Not settled.

### 1.5 Batches stay whole

A carrier stores a key batch as one envelope, with one relay key, and never takes
pieces out, so duplicate detection keeps working. Each member acknowledges its own piece
to the sender inside the encryption (group encryption §3.9); carriers never see those
acknowledgements. The sender resends only unacknowledged pieces in a new, smaller batch.

A member that finds its piece in a batch both reads its piece and keeps the whole
batch as a carrier, under the rules in §1.3. Reading a piece never edits or
deletes the stored batch. Today the host treats "mine" and "carry" as either-or; for
class 2 envelopes it does both.

**Still to write before building**, in the group encryption proposal: the batch's wire
format, the routing fields a receiver can see before it has the current group key, a
bounded scan of piece labels, and how a newcomer or a member who has fallen behind
finds its piece. Group encryption §3.9 defines the pieces, not the container.

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
slow mode, and the native byte limits also apply. Refused envelopes get no `ACK` and
are offered again (§1.4), so this usually only delays them, but an envelope can still
expire or be evicted while it waits. The limits stay as they are until a simulation
shows whether the delay matters.

### 1.8 Effort stamps

Each group envelope needs its own stamp (18 bits, about 0.3 to 1 second on a phone).
Batching keeps this small: a removal costs each member 1 to 5 stamps, not 126.

*For the reviewer, not decided:* since a sender picks its own copy count, spam always
takes the maximum. The stamp could cost more for more copies, for example 1 more bit
for each doubling above 12, so a 255-copy envelope needs 4 more bits, 16 times the work
of a 12-copy one: a few seconds for a person, but slow for anyone sending thousands. The
copy count would then have to be covered by the stamp, so a carrier cannot raise it.

---

## Part 2: message routes

Message details shows, for a received message, whether it came straight from the
sender's phone or through how many relays, as reported by the network. For a
one-to-one message you sent, it shows the same, as reported back by the recipient.

### What the code does today

Checked against the code on 27 November 2026 and confirmed by the review:

| Fact | Where |
| --- | --- |
| The header has one hop byte, outside the 68 bytes of `binding()`. The relay key zeroes it before hashing. | `core/src/envelope.rs` |
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
  `HELLO`, a second one, or one of the wrong size is ignored and the link stays
  anonymous.
- Exactly one matching contact names the link. No match, or more than one, leaves it
  anonymous.
- The result is kept in memory on the link only and dropped when the link closes.
  Nothing is written to disk, sent on, or put in an envelope.

Because the tagger's own values come first, a phone that echoes back the other's
`PROBE` does not match: the receiver expects the order reversed. Because each phone's
fresh nonce is in every tag, a `PROBE` recorded on an earlier link never matches.
Eight-byte nonces are enough for this: the honest phone's nonce is fresh, and a
16-byte tag leaves no room to guess.

Why a stranger learns nothing: it sees 128 random-looking values. Without a `linkKey`
it cannot tell real tags from filler, so it learns neither who we are nor how many
contacts we have. Tags change with every link.

**Considered and rejected:** one shared "recognise me" key per phone, given to every
friend, so a phone sends one tag instead of a list. It is smaller, but removing a
friend would mean making a new key and sending it to every other friend. With one key
per friend, removing a friend just deletes that key.

### 3.3 Keeping the work bounded and invisible

- Matching always checks every contact against all 128 slots, with constant-time
  comparisons and no early exit, so the time taken does not depend on whether there
  was a match.
- Making and checking probes is capped phone-wide: at most 30 probes a minute are
  matched. Over the cap, the probe is ignored and the link stays anonymous; the
  link itself is unaffected.
- A match changes nothing a stranger could see: no reply frame, no change in what is
  sent or offered on the link, how long it is kept open, or what is logged.
- 30 a minute is a starting value, to check on real phones.
- The cost is about 2 KiB each way per link and at most 128 × 128 comparisons.

### 3.4 The link key

`linkKey` is made from the pair's random pair secret in
[rotating-address-labels.md](rotating-address-labels.md) §1:

```
linkKey = HKDF-SHA256(ikm = pairSecret, salt = "Yap link key v1", info = "")  -> 32 bytes
```

It is worked out when needed and never stored, sent, or passed out of the endpoint
(Rust). The separate salt means the same secret is never used directly for two jobs.
It does not protect the others if the pair secret itself leaks: whoever has the pair
secret has every key made from it. When the pair secret is replaced, `linkKey`
changes with it.

### 3.5 Contacts

The endpoint's contact limit goes up from 64 to **128** (owner, 27 November 2026),
matching the 128 probe slots and the group size limit. A full probe then always
contains every contact, so recognition never depends on chance. The extra cost is
snapshot storage and a larger hourly label table (rotating address labels §3).

### 3.6 Changes

| File | Change |
| --- | --- |
| `endpoint/src/engine.rs` | Contact limit 128. Derive `linkKey` from the contact's pair secret when needed. Add `link_probe(own_id, peer_id, own_nonce, peer_nonce) → 128 tags` and `link_match(tags, own_id, peer_id, own_nonce, peer_nonce) → contact ID or null`, following §3.2 and §3.3. |
| `native/android/src/lib.rs`, `NativeBridge.kt` | Expose both operations. |
| `MeshHub.kt` | Frame type `PROBE` (10) and the rules in §3.2. `Peer` gets `@Volatile var contact: String?`, memory only, cleared on close. The probe rate cap. |
| `SecureModel.kt` | Pass `Peer.contact` through: `localLinks` becomes a list of `LinkInfo(link, contactId)`. |

### 3.7 What the UI does with it

`NetworkScreen` takes the `LinkInfo` list and the contacts. A row with a `contactId`
shows "<name> recognised" and the contact's avatar, with the link's radio as the
subtitle; the map node shows the same. Every other row stays "Nearby mesh node N". No
"via Ravi" arcs and no distances outside the labelled sample preview. A short note on
the screen says recognition does not prove someone is nearby.

### 3.8 Limits

- **Direct links only.** A friend two hops away is not on the map. Naming them would need
  a neighbour exchange, which leaks who is near whom; that was part of the shelved mesh
  v4.
- **Relayed links** can show a recognised friend who is far away (§3.1).
- **A friend's phone in the wrong hands.** Whoever holds a friend's phone holds that
  pair's secret. Until the secret is replaced, they can recognise you on any link as
  soon as it opens, and can pass as that friend in recognition. Replacing the pair
  secret (rotating address labels §1) shuts out someone holding an **old copy**; it
  cannot shut out someone who still holds the friend's working phone. Removing that
  friend does.
- **A name is a local label**: the name you stored for the contact, not something the
  link proves.

---

## How the parts fit together

- **Frame types:** `GROUP_ENVELOPE` 9, `PROBE` 10, `OFFER` 11, `WANT` 12. Types 1 to 8
  are unchanged. Today's parser refuses 9 and above; that changes in this update.
- **Flood and stamps:** `PROBE`, `OFFER` and `WANT` carry no envelope and need no
  stamp. `PROBE` has its own rate cap (§3.3). `OFFER` and `WANT` count towards the
  link's existing frame budget.
- **Routes and groups:** see §2.2.
- **Group members and friends** are separate lists. Being in a group with someone does
  not name their link: only paired contacts take part in the probe.

## Not covered

- **The internet relay server.** Off in this update and shown as "Coming soon". Its
  return needs its own design: it stores envelopes by a fixed mailbox, which would link
  rotating labels, it has no way to deliver to a group, and a fixed mailbox must never
  reach nearby traffic. Until then nothing in the app sends to or fetches from it.
- **Tor.** Not designed.
- **Measurement.** The copy counts, flood limits under a removal
  burst, and carrier storage in a busy crowd all need simulation, then real phones.
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

## Tests required

Part 1:
- A group envelope reaches all members of a simulated group of 5, 30 and 128 through
  carriers, with no `DELIVERED` deleting it early.
- A carrier ignores `DELIVERED` for a stored class 2 envelope, including one it is
  offering.
- A captured envelope re-sent under the other frame type is refused; a duplicate with
  a different class is refused.
- A one-copy holder gives a group envelope only to members and keeps its own copy; it
  refuses strangers. A junk group envelope stops after its copies are used up.
- Carriers clamp copy counts above 96 (one-to-one) and 255 (group).
- `OFFER` and `WANT`: an envelope the other phone already has is never sent, for both
  classes; a lost `ACK` and a slow-mode refusal are retried with backoff; a settled
  envelope is never offered again on that link; state clears on close.
- A link sees every eligible envelope in a store holding more than 128 settled ones.
- A member reads its piece of a batch and still carries the whole batch.
- A removal burst from 127 senders through one carrier: slow mode and the native
  limits delay delivery.
- Sustained eviction, expired old generations, a sleeping phone and lost recovery
  traffic, reporting what is recovered and what is lost.
- Large junk envelopes and rapid link churn in simulation.

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
  wrong size, a second `PROBE` and a `PROBE` before `HELLO` all leave the link
  anonymous.
- Two phones connecting to each other at the same moment both recognise each other.
- Matching takes the same time with and without a match; a match changes nothing on
  the wire.
- Over the rate cap, links stay anonymous and keep working.
- A two-site relay test: two phones far apart, joined by two relaying phones, show each
  other as recognised. This documents §3.1; it is expected to "succeed".

## Order of work

All three parts ship in one update. Within it:
1. Rotating address labels and the one-to-one outer wrapper, since Part 3 and group
   encryption both need the pair secret.
2. Part 1, then its simulation.
3. Part 2.
4. Part 3, once its security review is done.

The screen changes (§2.4, §3.7, and "Coming soon" for the internet relay) are made on
the UI branch once the data exists.
