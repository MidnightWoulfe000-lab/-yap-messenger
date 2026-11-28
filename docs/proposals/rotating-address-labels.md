# Proposal: rotating message address labels

**Status: revised after two reviews, then touched again after Codex's fifth and sixth reviews of
the v3.1 update (28 November 2026); do not implement yet and it needs another review.** Per `AGENTS.md` this changes
how envelopes are addressed (a cryptographic protocol change), so it must be reviewed
before any code is written. Tracks audit finding 1b. Codex reviewed it twice on 27
November 2026 together with [local mesh v3.1](local-mesh-v3.1-group-envelopes.md); this
revision answers the second review
([response](../audit/2026-11-27-local-mesh-v3.1-second-response.md)) and needs another
review. It ships in the same update as mesh v3.1, [encrypted pairing](encrypted-pairing.md)
and group encryption.

**Audience: maintainers, reviewers and coding agents.**

## Problem

Every envelope header carries two values in clear:
- `destination`: the recipient's 16-byte route token. Permanent, the same for every
  message to that person from anyone.
- `sender_hint`: a 16-byte value fixed per pair of contacts.

Inside the envelope, libsignal also leaves fields readable: an established session's
message shows its ratchet public key and counters, and the first message of a session
shows the sender's identity key. A ratchet key can stay the same across many messages;
an identity key never changes.

Envelopes are handed to every nearby phone for carrying. Anyone running a modified app
can log these values and see "the same person is receiving again", "these two people
talk", and roughly where and when. Link IDs already rotate (local mesh v2), so these
are now the main remaining tracking signals. Changing the labels alone is not enough:
the libsignal fields must be hidden too.

## Goals

1. Labels that strangers cannot link across hours.
2. Nothing permanent inside the envelope either: the whole libsignal message is
   wrapped.
3. The recipient can still recognise its mail cheaply, without decrypting everything.
4. No change for users. No extra steps, no new settings.
5. Established primitives only (HKDF-SHA256, HMAC-SHA256, XChaCha20-Poly1305 as in
   group encryption §3.4). No homemade crypto.

Non-goals: hiding that Yap is in use; hiding message timing or size; defending
against a contact who is themselves hostile (they hold the shared secret).

What rotation does **not** give: every envelope in the same pair, direction and hour
shares one label, by design. A retried envelope keeps the label of the hour it was
made in, so it can show yesterday's label today. A phone seen carrying the same
envelope before and after an hour boundary can be linked by that envelope. Rotation
removes the permanent header signals; it does not make traffic unlinkable in general.

## Proposed design

### 1. The pair secret, stepped forward each week

**At pairing.** The pair secret is set up inside [encrypted pairing](encrypted-pairing.md):
the phone with the smaller identity key makes a random 32-byte secret, sends it inside
the encrypted pairing channel, and both phones' signed confirmations cover it. The
secret is installed with the contact when the bootstrap finishes and the phone is
Ready (encrypted pairing §3), so a Ready phone already holds it. A phone that
validated the confirmations but is not yet Ready holds nothing it can use. No later message is needed to install it, and
today's empty first session message (kind 03) and its cached reply stay as they are.
The initiator also sends the **starting week number**, the week of its own working
clock, and both confirmations sign it together with the secret, so the two phones
always agree which week the secret belongs to, even if they pair either side of a week
boundary. A phone refuses to pair if the starting week is not its own current week or
the one either side. Each phone stores the secret with that starting week.

**Each week.** Weeks are numbered from the Unix epoch:

```
week = floor(ms / 604_800_000)                      (unsigned 64-bit, big-endian)
pairSecret[week + 1] = HKDF-SHA256(ikm  = pairSecret[week],
                                   salt = "Yap pair secret step v1",
                                   info = week + 1)                 -> 32 bytes
```

Both phones work this out on their own, so nothing is sent and the two phones cannot
fall out of step, even if they never meet again. Per contact, a phone keeps the secrets
for its current week and the previous week, and works out the next week's when it
needs it (labels near the boundary, recognition, §4). Weeks before the starting week
have no secret: envelopes created before the contact's starting week are refused.

**Stepping and deleting.**
- A week's secret is deleted 26 hours after that week ends, by the phone's working time
  ([local storage](local-storage-v1.md) §8). Stepping forward and deleting happen in
  one transaction per contact, so a crash leaves either the old state or the new one.
- A phone that was off for a while steps forward from its newest saved week, one step
  per week missed, when it next runs. At most 520 steps (ten years) are taken; more
  than that is treated as a clock error.
- Stepping that would delete secrets because the working time jumped forward more than
  2 days at once waits for the user to confirm the new time (local storage §8).
- It never steps backwards. A restored older copy of the phone's data can still hold
  older secrets; rollback protection for restored copies is a separate open problem
  (local storage, open questions).

Every key below is made from the secret for the week of the envelope's authenticated
creation time, each with its own salt:

```
labelKey = HKDF-SHA256(ikm = pairSecret[week], salt = "Yap address labels v1", info = "")
linkKey  = HKDF-SHA256(ikm = pairSecret[week], salt = "Yap link key v1",       info = "")
```

`labelKey` is the input to the labels (§3) and to the outer wrapper (§5). `linkKey` is
for friend names (mesh v3.1 §3.4). Separate salts mean the secret is never used
directly for two jobs. They do not protect the others if a pair secret itself leaks:
whoever holds it holds everything made from it.

**What stepping protects, and what it does not.**
- Someone who copies a phone's data gets the current secrets and can work out every
  later week's, so they can read labels, open outer wrappers and recognise that pair
  until the contact is removed. Removing the contact (and re-pairing, if wanted) is the
  only way to shut them out.
- They cannot work out earlier weeks' secrets once those are deleted, so labels from
  before then stay unlinkable to them.
- This gives up one thing an earlier draft offered: sending a fresh random secret each
  week could shut out someone who copied only the pair secret. The review pointed out
  that a full copy of a phone also includes the libsignal session, which would let a
  passive copier read the new secret anyway until the session recovers. In return,
  stepping removes the risk of the two phones disagreeing about which secret is live
  (owner, 27 November 2026).

*Why not derive it from the identity keys:* an X25519 result of the two identity keys
never changes, so anyone who ever gets either identity private key can recompute it,
and every key made from it, for as long as the pair exists.

### 2. Pairing traffic stays on the pairing link

The public bundles, the signed confirmations, the pair secret and the first libsignal
message (which shows the sender's identity key) all travel inside the encrypted pairing
channel, on a direct local link between the two phones. This is enforced in how
packets are routed, not inferred from what a packet contains: pairing packets go in a
separate queue bound to the attempt's current authenticated link (the first one, or the
link of a successful resume handshake, encrypted pairing §3), and never enter the
outbox, a carrier's store, any other link or the internet relay. Today `SecureModel.pump()` confines only signed
confirmations (through `linkFor()`) and sends other pending packets, including the
bootstrap, to every local link and the internet relay; that changes.

If the pairing link closes before the phone is Confirmed, the attempt is cancelled; from
Confirmed until the deadline it may resume on another authenticated link, and it is
cancelled at the deadline if not finished (encrypted pairing §3); it never moves to a
carrier. Only after pairing completes (Ready) does the pair send
anything through carriers, and then always with labels and the outer wrapper.

### 3. Hourly labels

```
epoch = floor(created_ms / 3_600_000)               (unsigned 64-bit, big-endian)
tag   = HMAC-SHA256(labelKey, "dest" || direction || epoch)[0..16]
```

`labelKey` comes from the week of the same `created_ms` (§1). `direction` is `0x01`
when the sender's identity key is the smaller of the two (comparing the canonical
33-byte identity keys as unsigned bytes), `0x02` otherwise, so the two directions of a
conversation never share a label.

The header's `destination` field carries `tag`. The `sender_hint` field becomes 16
random bytes per envelope: the tag already tells the recipient which contact sent it.

### 4. Recipient matching

Each phone keeps an in-memory table of expected tags: for every contact, the
incoming-direction tag for every epoch from 25 hours before its current hour to 1 hour
after it, inclusive (27 epochs), each made with the right week's secret. At 128
contacts that is 3,456 entries (about 54 KiB of tags before map overhead). Group labels
are a separate table.

- **When it changes.** Entries for a contact are added as soon as pairing completes and
  removed as soon as the contact is removed, not at the next hourly rebuild. A
  deleted week's secret leaves no entries behind. The hourly rebuild adds the new hour
  and drops the oldest.
- **What an entry holds.** The contact, the direction, the epoch and the week. If two
  entries share a tag, both are kept (at most 4 per tag) and each is tried in turn;
  more than 4 is treated as no match.
- **Edges.** Epoch arithmetic saturates and never wraps; near zero, repeated clamped
  epochs are listed once.

A carried envelope is "mine" if its `destination` is in the table: one hash-map lookup.
The table is only for finding mail. After decrypting with the entry's secret, and
before any libsignal state is committed, the receiver **recomputes** the expected tag
from the authenticated `created_ms` and the contact and direction it matched, and
refuses the envelope if they differ. The envelope's own exact expiry and lifetime
checks, and today's check against creation times in the future, still apply.

### 5. The outer wrapper for one-to-one messages

Every one-to-one libsignal message (chat and receipts) is wrapped once more before it
goes in the envelope, with the outer layer from group encryption §3.4:
- **Cipher:** XChaCha20-Poly1305 with a random 24-byte nonce, format
  `"YGO1" || nonce || ciphertext and tag`.
- **Key:** HKDF-SHA256 with the same week's `labelKey` that made the envelope's label
  as input, the salt `Yap outer layer v1`, and as info (89 bytes): kind `0x03`
  (one-to-one message), 16 zero bytes in place of a group ID, the sender's identity key
  and the recipient's identity key (each 32 bytes: the canonical 33-byte libsignal
  encoding with its leading type byte `0x05` removed) and the hour number (8,
  big-endian), taken from the envelope's creation time. 32 bytes out.
- **Associated data:** the 70 bytes of `Header::binding()` (mesh v3.1 §1.1).

A receiver accepts hours from 25 before its current hour to 1 after, as for group
messages. New test vectors for kind `0x03` are added to
[`experiments/outer-layer-vectors/`](../../experiments/outer-layer-vectors/README.md),
and the existing vectors move to the 70-byte binding.

### 6. What changes elsewhere

- **Carriers:** nothing. They never read labels.
- **Duplicate detection and relay keys:** unchanged (they hash the whole envelope).
- **The QR's bundle commitment** (32 bytes, sent in `PAIR_FIND`) is used only to find
  the pairing link (§2; encrypted pairing §3). It is not a destination, and no
  envelope destination is taken from the QR.
- **Internet relay server:** off in this update and shown in the app as "Coming soon"
  (owner, 27 November 2026). Nothing is sent to or fetched from it, so no fixed mailbox
  value reaches nearby traffic. Its return needs its own design.
- **Route token:** no longer shown in Connections while the internet relay is off.
- **Contacts:** the limit goes up from 64 to 128, with the related limits in mesh v3.1
  §3.5.
- **Compatibility:** none. Every phone is updated and re-paired in this update.

### 7. Group members you have not paired with

In a group you can be with people you never paired with in person. They have no pair
secret with you, so nothing in this proposal applies to the one-to-one pieces between
you. Those pieces never use a pair secret, even between members who are also paired
contacts:

- Pieces are addressed and wrapped with the group's own keys (owner, 27 November
  2026): each member's **inbox key**, published in their join request and replaced at
  each of their key changes ([group encryption §3.11](group-encryption-v1.md)).
  Strangers see only random-looking labels. Other members could tell who a piece is
  for; they already know who the members are.
- A **newcomer** knows its own inbox key from the start, so even its first pieces,
  including the libsignal set-up message that shows the sender's identity key, are
  hidden inside the outer layer. No new primitive is needed, and nothing falls back to
  static identity-key Diffie-Hellman or an assumed shared secret.
- The group proposal's cheap admission check now runs after the outer layer is
  removed.

These pieces travel inside group batches (mesh v3.1 §1.5) and are written up fully in
the group encryption proposal. Group members are never added to the contact list or
the label table here.

## Risks and open questions for the reviewer

1. The pair secret (§1): is a secret set up inside encrypted pairing and stepped
   forward weekly with HKDF sound? Is losing recovery from a copied pair secret
   acceptable in exchange for never disagreeing?
2. Hour-long epochs: shorter means less linkability but a bigger table and more
   sensitivity to clock differences. Today a receiver refuses envelopes created more
   than five minutes in its future and checks expiry; it does not otherwise require
   clocks to agree. Week boundaries add a second clock dependency (§1); the clock rule
   is in local storage §8.
3. A friend's phone in the wrong hands reveals the current pair secret, and with it
   every later one: every label, outer key and recognition tag for that pair until the
   contact is removed.
4. Group members who are not paired contacts (§7), through inbox keys.

## Test plan

- Fixed test vectors for the weekly step, `labelKey`, `linkKey`, `tag` and the kind
  `0x03` outer layer with the 70-byte binding.
- Pair secret: both phones hold it when pairing completes; a pairing link closing
  after Confirmed does not cancel pairing: the attempt resumes on another authenticated
  link within its window (encrypted pairing §3), while a link lost before Confirmed
  cancels it, and the attempt is cancelled at the deadline if not finished; pairing either side of a week boundary gives both phones
  the same starting week; both phones step to the same secret across one, two and ten
  weeks without meeting; a week's secret is deleted 26 hours after the week ends; a
  clock moved back never brings back a deleted secret; a crash during catch-up leaves a
  consistent state; envelopes from before the starting week are refused.
- Pairing traffic is never offered to a carrier or the internet relay, on every
  transport, and only goes over a link authenticated for that attempt (the first, or
  one that passed the resume handshake); a pairing link closed before Confirmed cancels
  the attempt, and one closed from Confirmed until the deadline resumes on another link
  within the window.
- Two endpoints exchange messages across an hour boundary, across a week boundary, and
  with ±5 minutes skew.
- The table: a new contact's mail matches straight away; a removed contact's mail stops
  matching straight away; two entries sharing a tag both open; more than 4 do not.
- Envelopes in the same pair, direction and hour share a label; across hours they
  differ; a delayed retry keeps its creation hour's label and still opens.
- Strangers' view: complete captured envelopes (not just destination fields) between
  one pair across 24 hours show no repeated value other than a label within its hour
  and a retried envelope's own bytes.
- A recipient receives only its own mail from a mixed carrier store.
- An envelope whose label does not match its authenticated creation hour is refused
  before any libsignal state changes; tampering with `created_ms` or `destination`
  fails.

## Implementation steps (after approval)

1. Endpoint (Rust): pair secret per contact from encrypted pairing, weekly step and
   deletion; derived keys; tag derivation; header construction; outer wrapper.
2. Endpoint: match table, `receive` lookup and label recomputation.
3. Android: "mine?" check uses the native match instead of comparing to the route;
   pairing packets in their own queue bound to the pairing link; every internet relay
   path off.
4. Docs: update `local-mesh-v2.md`, audit finding 1b, the endpoint contract, README.
