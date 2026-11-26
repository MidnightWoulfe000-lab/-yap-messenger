# Proposal: group encryption

**Status: revised after review; do not implement yet.** A Codex design review on 26 November 2026 found 16 problems. This version answers all of them (see the [review](../audit/2026-11-26-group-encryption-v1-codex-review.md) and the [response](../audit/2026-11-26-group-encryption-v1-response.md)), but the changes need a second review, and part of finding 7 waits for the mesh design.

**Status: proposal for review. Not implemented. No security audit.** Per `AGENTS.md`,
encryption is not built from assumptions. The project owner has decided the
choices in section 2. Section 5 records a review of the open
points by Claude, which also wrote this proposal: it is **not an independent
review**, and the design still needs one before release.

**Audience:** the project owner, who decides the choices in section 2, and
reviewers and coding agents, who check the design in section 3.

The group rules (who is in, who can vote, removals, splits) are built in
[`groups/`](../../groups/README.md) and specified in
[GROUP_MECHANICS.md](../GROUP_MECHANICS.md). Nothing yet stops an outsider, or a
phone carrying messages through the mesh, from reading what a group says. This
proposal covers how group messages are locked so that only current members can
read them.

## 1. The short version

- **No new encryption is invented.** Yap already uses Signal's library
  (libsignal) for one-to-one chats. The same library has Signal's group method,
  called *sender keys*, and this proposal uses it unchanged.
- **Each member has their own sending key.** When you send to the group, your
  phone locks the message once with your sending key. Every member who holds a
  copy of your sending key can unlock it. Phones that only carry the message
  through the mesh never get the key.
- **Sending keys are handed over privately.** Your phone gives your sending key
  to each member separately, through an encrypted one-to-one channel with that
  member. Nobody else can read the hand-over.
- **When someone is kicked, everyone changes keys.** Every remaining member
  makes a new sending key and hands it to the others, but not to the person who
  was kicked. Anything they already received they keep; anything sent after the
  change they cannot read.
- **A new member gets current keys only**, so they cannot read messages from
  before they joined.
- **The invite QR holds no keys.** It starts the same in-person pairing Yap uses
  for contacts, and every QR is new and works once.

## 2. Choices for the project owner

All eleven choices are decided (26 November 2026). Choice 11 came out of the review in section 5.

1. **Can new members read messages from before they joined?**
   Decided (proposed by Claude, accepted by user, 26 November 2026): **no**. Sender keys make this the natural result: a newcomer
   receives each sending key at its current position and cannot step it back.
   Letting them read older messages would mean someone re-sending the history,
   which also re-sends whatever was said about them before they joined.

2. **What happens to a message from a kicked member that reaches your phone
   after your phone knows they are out?**
   Decided (proposed by Claude, accepted by user, 26 November 2026): **your phone drops it.** This matches the decided rule that a
   member is out once the removal reaches a phone. Messages your phone already
   had stay. The catch: a message they really sent before being kicked, but that
   was slow to arrive through the mesh, is dropped too, because a phone cannot
   tell when a message was really sent.

3. **How long does a group invite QR work?**
   Decided (proposed by Claude, accepted by user, 26 November 2026): **once, and for 10 minutes at most.** The phone shows a fresh QR
   each time the invite screen opens. A photo of an old QR is useless. Joining
   also needs the inviter and the joiner to scan each other, so someone who
   photographs a fresh QR still cannot join in the joiner's place.

4. **Does joining a group make you and the person who invited you contacts?**
   Decided (proposed by Claude, accepted by user, 26 November 2026): **no.** The channel set up while joining is used for the group
   only. Either of you can still pair as contacts separately.

5. **Besides kicks, when do keys change?**
   Decided (proposed by Claude, accepted by user, 26 November 2026): **on every removal, whenever a member marks one of their devices
   as lost, and once a week for each member's own sending key.** The weekly change
   is routine practice. *Corrected after the Codex review (finding 11):* it does
   not, on its own, limit how long someone who copied a phone can keep reading,
   because the new key travels over one-to-one sessions the copier may also be
   able to read (see 3.6). Each change means that member hands a new key to everyone else, so more
   frequent changes cost more mesh traffic.

6. **Accept the cost of a kick in a large group?**
   In a full group of 128, a kick means each of the 127 remaining members hands a
   new key to the 126 others: about 16,000 small private messages spreading
   through the mesh. Decided (proposed by Claude, accepted by user, 26 November 2026): **accept this for now and measure it on real
   phones** before deciding whether large groups need a cheaper method (section
   3.9).

7. **When does a phone count as cut off, for temporary groups (3.10)?**
   **Decided (user, 26 November 2026): it has not heard from more than half of
   the group's active members for 24 hours.** 6 hours was too short: a night's
   sleep alone could set it off.

8. **Who is in charge inside a temporary group?**
   **Decided (user, 26 November 2026): nobody: it runs as a collective**,
   whatever type the original group was, because no one chose a leader for it. It keeps the original
   group's other toolkit settings.

9. **What does "nearby" mean?**
   **Decided (user, 26 November 2026): reachable through the local mesh right
   now**, not only in direct radio range. A temporary group **only forms if
   nobody in the local mesh has internet access**. This needs no separate check:
   if one phone reaches an internet relay, messages from the rest of the group
   come through it, so the phones in the mesh keep hearing from members and are
   not cut off (choice 7).

10. **What if two temporary groups from the same original group come into reach
    of each other?**
    **Decided (user, 26 November 2026): everyone in both gets a notification
    offering to merge.** How, proposed in 3.10: the merge is a new temporary
    group offered to the members of both, each person accepts or not, and each
    old temporary group's messages stay in its own chat.

11. **Can a newcomer see who has voted in a vote that is still open?**
    To work out the result of a vote that started before they joined, a
    newcomer's phone needs to know who has already voted in it (3.3). They
    cannot vote in it themselves, because its list of voters was fixed when it
    started. **Decided (proposed by Claude, accepted by user, 26 November
    2026): yes.** An open vote is the group's current business,
    not its history, and the newcomer is a member now. The alternative hides the
    voters behind hashes. (Since the Codex review, newcomers receive the full
    governance log anyway, so they see open votes as every member does.)

## 3. Design for review

### 3.1 Building blocks

All from libsignal at the revision already pinned for `endpoint/` and `groups/`
(`257105c`). No primitive or key schedule is written by hand.

- **One-to-one sessions** (PQXDH set-up, then the Double Ratchet with SPQR), as
  used by the paired endpoint. Here they carry sending keys and private group
  traffic between two members.
- **Sender keys:** `create_sender_key_distribution_message`,
  `process_sender_key_distribution_message`, `group_encrypt` and `group_decrypt`.
  A sending key is a chain key that moves forward with each message and a
  signing key pair. Each message is encrypted with the next key in the chain and
  signed, so members can tell which member sent it and that it was not altered.
  The *distribution message* is what hands a sending key to someone.
- **Identity keys:** the libsignal identity key that already signs governance
  events.

Caveat carried over from the [protocol proposal](../protocol-proposal.md):
libsignal's maintainers do not support its use outside Signal, and the Rust crate
is not a supported public API. The sender-key functions add to that maintenance
commitment.

**Scope of version 1:** one device per member, as in the one-to-one milestone.
Extra devices each need their own sending key and sessions; that is later work.

### 3.2 Pre-key bundles in the group log

Members of a group are not necessarily contacts, so they need a way to open a
one-to-one session with each other without meeting. Each member publishes a
**signed pre-key bundle** in the group log: a signed pre-key and a signed
post-quantum (Kyber) last-resort pre-key. Measured with the pinned library, a
bundle is about 1,775 bytes, so it fits within the 8,192-byte event limit.

- The joiner's bundle travels in their `JoinRequest`. A new event lets a member
  replace their bundle, for example on a schedule.
- There are no one-time pre-keys, because a shared log cannot hand each one out
  to exactly one person. Libsignal accepts a bundle without them (the one-time
  pre-key is optional in `PreKeyBundle::new`). A replayed first message is still
  refused: libsignal's store records each base key used with a last-resort
  pre-key pair (`mark_kyber_pre_key_used`), and the measurement in 3.9 confirmed
  the refusal. Yap's store must keep that record permanently, not just in
  memory.
- A member replaces their bundle monthly with a new event. Pre-key IDs are
  never reused. The old pre-keys are kept for 24 hours after replacement, for
  set-up messages still in the mesh, and then deleted together with their
  record of used base keys: a set-up message for a deleted pre-key fails anyway.
  This keeps the record bounded. Storing it durably is Yap's job; libsignal only
  calls the store.
- **Set-up limits** (Codex finding 13). A set-up message carries the sender's
  identity key in the clear, so a phone first checks, cheaply, that the sender
  is an active member, and refuses anyone else. It then accepts at most 4 new
  sessions from one member per day before doing the expensive post-quantum
  work. A member who needs more (after a restore, say) waits a day.
- When two members both open a session at once, the one with the smaller
  identity key (comparing bytes) is the initiator, as in contact pairing, and
  the other session is dropped.

### 3.3 Joining

1. **Invite QR.** The inviter's phone shows a fresh QR in the format of a contact
   pairing attempt (format version, identity key, random attempt nonce, and a
   commitment to the bundle sent over the local link). It holds no group key and
   no group ID. It is single use and expires (choice 3).
2. **In-person pairing.** Both phones scan and confirm, as for contacts. The
   result is a one-to-one session marked for this group only (choice 4).
3. **Join request.** Over that session, the inviter sends the group ID and the
   joiner signs a `JoinRequest` with their bundle. The inviter (the sponsor)
   publishes the `Propose` event. The candidate's request never travels in the
   clear.
4. **Admission** follows the built rules (`Admit`, or `AdminAdd` in a normal
   group).
5. **Starting state for the newcomer.** Decided after the Codex review (user,
   26 November 2026): the sponsor sends the newcomer, over their session, the
   **full governance log** from the group's first event. The newcomer's phone
   checks every event with the same rules as every other member, so it relies on
   signatures and rules, not on anyone's summary. It never receives earlier chat,
   which stays unreadable because of sender keys. The app shows the newcomer only
   history from their own join onwards; this is enforced by the app, not by
   encryption, so a modified app could show the earlier governance history.

   **Trust still starts with the sponsor.** A newcomer who knows no other member
   cannot tell a real group from one the sponsor made up with invented members.
   The app should say who introduced them, and mark members the newcomer has
   also verified in person.
6. **Keys.** When a member's phone processes the admission, it opens a session
   with the newcomer from the newcomer's bundle and hands over its current
   sending key. The newcomer does the same towards every member. Received keys
   start at their current position, so earlier messages stay unreadable
   (choice 1).

### 3.4 Sending a group message

- The plaintext starts with a canonical header: group ID, sender's member key,
  a random 128-bit message ID, the sending key's distribution ID, and the hashes
  of the governance events the sender had seen (its log heads). The receiver
  rejects a message whose header does not match how it was received.
- The heads let a receiver see which group state the sender was in. A receiver
  accepts a message only from a member who is active in its own current state
  (choice 2).
- The encrypted message travels in an envelope with the same mesh rules as
  one-to-one envelopes (size cap, lifetime, hop budget, deduplication).
- **Address label.** The envelope needs a label so members can spot their group's
  mail without trying to decrypt everything. Each member's phone makes a random
  32-byte label key and hands it over together with its sending key. Labels
  rotate every hour from that key, as in the
  [rotating address labels](rotating-address-labels.md) proposal. A receiver
  works out the current label for each member: at most 127 cheap calculations
  an hour.
- **Outer layer** (Codex finding 5). Libsignal leaves some fields readable:
  a group message shows its distribution ID, chain ID and step number, a
  one-to-one message its ratchet key, and a session set-up message the sender's
  identity key. Any relay could follow those across label changes. So the whole
  libsignal message is encrypted once more before it goes in the envelope, with
  a standard authenticated cipher (ChaCha20-Poly1305 or AES-256-GCM, from a
  maintained library) under a key derived with HKDF-SHA256 from the label key
  and the hour. The label and the envelope header are bound in as associated
  data. A relay then sees only the label and ciphertext. One-to-one pieces use
  the label key the pair share (3.9) in the same way. This layer is new and
  **needs its own review before it is relied on**; until then, the privacy
  claims in section 4 hold only without it.

### 3.5 Governance events travel encrypted

Governance events (proposals, votes, reasons, removals) are as sensitive as
messages. Between members, they travel as group messages under sending keys. A
non-member relay sees only labelled ciphertext. The signed event inside is
unchanged, so the rules in `groups/` work as built.

### 3.6 Removal and key changes

Each of a member's sending keys has a **generation number**, counting up from 1.
A change of keys is either:
- **justified by the group log:** any removal (by vote, by an admin, or of the
  SuperAdmin) or a member marking one of their own devices as lost
  (`RevokeOwnDevice`). The hand-over names the governance events it answers, so
  receivers can check the reason; or
- **routine:** the weekly change (choice 5), or a resynchronisation (below).

When a phone processes a removal, it makes a new sending key under a new random
distribution ID and hands it over, with a new label key, to every member active
in its state. It never sends it to the removed member. From then on it sends
only with the new key. Several removals processed together produce **one**
change, not one each.

**Limits** (Codex finding 10). Justified changes are never refused, because
refusing one could keep a removed member in. They are merged and scheduled
instead. Routine changes are limited to 2 per member per day; more are
ignored and the app warns.

**Keeping old keys.** Old sending keys, its own and others', are kept for 24
hours, so slow messages can still be read, with at most 8 generations per
member. If a member goes past 8 within a day, the oldest is deleted early and
messages still in flight under it are lost. That loss is accepted and shown.

**Erasure** (Codex finding 12). Deleting a generation deletes its chain key, the
skipped message keys libsignal kept for out-of-order messages, and any copy of
its distribution message. A distribution message is not kept after it has been
processed. The encrypted store's snapshots and journals are rewritten so the old
keys do not survive in them. Flash storage cannot promise physical erasure, so
the claim is limited to what section 4 says.

**A receiver that falls too far behind** (Codex finding 8). Resending the same
distribution message does not help: at the pinned revision, processing one for
a chain the receiver already has keeps the receiver's old position. A receiver
that cannot decrypt because it is more than 2,000 steps behind, or has no key
for that generation, asks the sender over their one-to-one session. The sender
answers with a **fresh generation** for everyone, at most once an hour however
many ask; it counts as routine. Messages in the gap stay lost for that
receiver. This needs a test against the pinned library before it is relied on.

**Copied phones** (Codex finding 11). A new sending key does not shut out
someone who copied a member's phone, if they can still read that member's
one-to-one sessions. Those sessions recover on their own once each side has
sent a message with a fresh ratchet key that the copier did not see; a copier
who only listens cannot follow that step, but one who can act in the session
can. A member who knows their phone was copied should be removed and rejoin
with a new identity, or re-pair.

A removed member's phone deletes the group's keys. With group scramble on, it also
wipes the group (§6). Like scramble, this is best effort on a phone that may be
modified.

### 3.7 Splits and reconnecting

- Each side changes keys for the removals it sees, as above.
- On reconnecting, a phone that learns a removal it had not seen changes keys
  once after it has processed all the new events, not once per event. This
  avoids a storm of changes after a long split.
- A newcomer admitted on the other side gets the current sending keys of every
  member who learns of them, with no key change.
- **Resynchronising** (Codex finding 9). A member who missed a hand-over also
  misses the new label key, so it cannot recognise the messages that would tell
  it something is missing. So each pair of members also keeps a check that does
  not use group labels. When two members' phones first hear from each other
  after a gap, they send each other, over their one-to-one session, the current
  generation number of their sending key. A phone that is behind asks for the
  missing hand-over. A pending hand-over is kept until it is acknowledged or the
  recipient stops being a member; when a newer generation exists, only the
  newest is resent.
- **Accepted limit:** while a removal has not yet reached one side, members there
  still hand keys to, and send to, the removed person. They can read that side's
  messages until the removal arrives. This is the same gap as the decided rule
  that a member is out once the removal reaches a phone; encryption cannot close
  it.

### 3.8 Rejoining

A member who is kicked and invited back (decided: same phone, new invite) goes
through 3.3 again. They get current keys only, so messages sent while they were
out stay unreadable. Messages they kept on their phone from before stay, unless
scramble removed them.

### 3.9 Cost, and the alternative considered

**Measured sizes** with the pinned libsignal, using the program in
[`experiments/sender-key-sizes/`](../../experiments/sender-key-sizes/README.md)
(one desktop run on 26 November 2026, no mesh). Each figure is the largest seen
in that case, which is evidence, not a proven maximum.

| Item | Bytes |
| --- | --- |
| Pre-key bundle | about 1,775 |
| Sender-key distribution message | 96 |
| Hand-over on a new session, before any reply (largest of 10) | 1,918 |
| Hand-over on an open session, alternating (largest of 200) | 266 |
| Hand-over on an open session, one way only (largest of 200) | 269 |
| Group message carrying 100 bytes of text | 205 |
| Group message carrying 1,024 bytes of text | 1,134 |

**Batching by size** (Codex finding 6). A removal in a full group of 128 means
127 × 126 = 16,002 hand-overs. One per envelope would be 16,002 envelopes,
against the proposed relay limits of 1,024 pending envelopes and 8 MiB per
phone. So each member packs its pieces into as few envelopes as their actual
sizes allow:
- at most 56 KiB of pieces per envelope, leaving room in the 64 KiB limit for
  the header and outer layer;
- at most 128 pieces per envelope, each under 4 KiB;
- each piece is encrypted to its recipient over their session and marked with
  a label from a key the pair share, so a recipient finds its piece without
  trying the others.

| Event, group of 128 | Envelopes | Total size |
| --- | --- | --- |
| A removal, every session open | 127, one per remaining member | about 4.6 MB |
| A removal, no sessions open yet (worst case) | about 635, five per member | about 31 MB |
| A newcomer joins | about 127 to the newcomer, and 5 batches back | about 490 KB |
| One member's weekly key change | 1 | about 36 KB |

The worst case is far over one phone's relay limits, so the mesh will drop some
of it and resynchronising (3.7) repairs the rest more slowly. Sessions are
normally opened when a member joins, which keeps removals near the first row.

**Carrying a batch** (Codex finding 7). A batch has many recipients, so the
one-recipient custody rules do not fit. Proposed:
- relays treat a batch as one envelope with one digest, and never take pieces
  out, so deduplication still works;
- a relay keeps it until it expires or its limits force it out, not until one
  recipient has it;
- each recipient acknowledges its own piece to the sender over their session;
  the sender resends only unacknowledged pieces, in a new, smaller batch;
- each piece states inside its own encryption the group, the recipient and the
  generation, so it is checked on its own and does not depend on the batch.

How relays rank batches against other traffic, and what relay storage this
really uses as copies spread, belongs to the mesh design and must be measured.
**Open until then.**

**MLS** (the IETF Messaging Layer Security standard) makes a removal cost far
less in large groups. It is not proposed for version 1 because it needs every
member to agree on one order of key changes, which a split mesh cannot give
without a coordinator. Two sides of a split would end up with two key states and
no standard way to join them. It is also a further dependency, outside the
library already reviewed for Yap. Revisit it if measurements show removals in
large groups are too costly.

### 3.10 Temporary groups for cut-off members

Decided by the project owner (GROUP_MECHANICS.md §9): temporary groups are a
toolkit setting; they form automatically, with no one picking members or
starting them; every member in reach is asked whether to move; no new QR codes
are scanned; and it only happens with at least 2 members of the group nearby.
This section proposes the mechanics.

**When.** A phone offers a temporary group when all three hold:
- the original group has the setting on;
- the phone counts as cut off: it has not heard from more than half of the
  group's active members for 24 hours (choice 7);
- at least one other active member is reachable through the local mesh right
  now, making at least 2 (choice 9);
- the cut-off rule already covers internet access: hearing from members counts
  whatever the route, so a phone in the mesh with an internet relay connection
  keeps everyone in touch and nothing forms (choice 9).

**One group per cut-off part.** Every phone in the part would otherwise make its
own. The phones in reach of each other agree on one to do the paperwork: the one
whose member key is smallest (comparing bytes). This is a fixed rule, not a
person's choice, and that phone's owner is asked like everyone else.

**The offer.** That phone makes a fresh group ID and sends an offer over the
one-to-one sessions members already have from the original group (3.2), so no
QR is needed. Those sessions are tied to identity keys that were checked in
person when each member joined. Each phone shows a notification: the original
group has lost contact with most members; move to a temporary group with the
people nearby? Accepting is the person's own explicit consent, as §9 requires.

**Starting it.** The temporary group starts when 2 people have accepted; their
phones sign its first event, as the two founders of any group do. Each later
acceptance admits that person without a vote, because the offer only goes to
active members of the original group. Every phone checks that against its own
current state of the original group (Codex finding 14): a removal the phone
already knows about always blocks the person, whatever an offer says.

**Someone arriving later.** Decided (user, 26 November 2026): when another
member of the original group comes into reach of a temporary group that has
already formed, the members get a notification saying so and asking whether to
invite them. **One member's tap is enough**; the person then gets the offer and
joins by accepting. There is no vote: everyone in the temporary group is cut off,
so a vote would have no more to go on than the person tapping. The accepted risk
is that the late arrival may be someone removed elsewhere.

**Keys and contents.** Fresh sending keys and label keys, made as in 3.3–3.4.
Nothing is copied from the original group: no keys, messages or governance
history. The temporary group lists its members from the acceptances alone, not
from the original's roster. It shows as a separate chat.

**Two temporary groups meeting.** Decided: when two temporary groups from the
same original group come into reach, everyone in both is offered a merge. The
phones can recognise each other because they all belong to the original group.
Proposed mechanics:
- The merge is a **new** temporary group, made and offered as above to every
  member of both. Each person accepts or not; nobody is moved without agreeing.
- Each old temporary group's messages stay in its own chat, which can be kept or
  dissolved. They are not copied in, because the people from the other group
  never had them.
- If the two parts together now hear from more than half of the original
  group's active members, the phones are no longer cut off. The original group
  works again, so no merge is offered; each temporary group is kept or
  dissolved as on reconnecting.

**Reconnecting.** Decided: each temporary group can be kept as a separate chat or
dissolved, and is never merged into the original.

**Limit.** A member removed elsewhere is still an active member as far as the
cut-off part knows, so they receive the offer like anyone else. A QR scan would
not reveal that either. The app must say that it cannot know who was removed
elsewhere, and never call the temporary group safe.

**Review of the mechanics (see section 5, and the Codex findings named):**
- **Who is in reach** (finding 15). Having a message from someone proves who
  wrote it, not where they are or when: a relay can deliver an old message
  late, or carry fresh ones between distant meshes. So a phone checks reach
  directly: it sends a fresh random challenge, encrypted to the group, over the
  local mesh only, and counts a member as in reach if their answer, which must
  include that challenge, comes back within 30 seconds. This shows the member
  answered just now through the local mesh. It does not prove physical
  closeness, and a relay that tunnels traffic between meshes can still make two
  places look like one. Strangers see only ordinary labelled envelopes; the
  group's ID never goes over the air.
- **Hearing from members** (finding 15). For "not heard from in 24 hours" to
  mean something in a quiet group, every member's phone sends a small encrypted
  heartbeat once a day. Being cut off is still a judgement, never certain, and
  the app says so.
- **Two phones both doing the paperwork** (finding 16). The phone with the
  smallest member key does the paperwork, but only for 2 minutes: if no offer
  has come from it by then, the next smallest takes over. Once anyone has
  accepted an offer, that formation is never replaced because a smaller key
  turns up. Two formations that have both started are two temporary groups
  meeting, and the merge offer applies.
- **Binding consent** (finding 16). An acceptance names the exact temporary
  group ID, the original group, the member's current membership in it (the
  event that admitted them, which changes if they left and rejoined), the
  settings and the offer it answers. It cannot be reused for another formation
  or merge.
- **New events.** The temporary group's first event records the original
  group's ID and the log heads of the phone that made the offer (seen only by
  members, since it is encrypted). `AcceptOffer`, signed by the person
  accepting, is valid only if they are active in the original group both at
  those heads and in the checking phone's own current state (finding 14).
  `InviteNearby`, from any member, offers the group to a late arrival, who then
  sends `AcceptOffer`.

## 4. What this protects, and what it does not

Narrowed after the Codex review (findings 5, 11 and 12).

**Protects:**
- message and governance content from non-member phones carrying it, and from
  internet relays;
- messages sent after a removal (once the removal has reached the sender's phone)
  from the removed member;
- messages sent before a newcomer joined, from that newcomer;
- old **ciphertext**, on a phone seized later, whose keys have really been
  erased (3.6). It does not protect chat history kept on the phone.

**Does not protect:**
- anything a member already read or copied, including a kicked member's copies;
- chat history stored on a phone that is seized unlocked;
- the window during a split before a removal reaches the other side (3.7);
- against someone who copied a member's phone, until that member's one-to-one
  sessions recover or they re-pair (3.6). The weekly key change alone does not
  end it;
- metadata. Relays see envelope sizes, timing and which phones pass envelopes
  on, and that a label recurs within its hour. Until the outer layer (3.4) is
  reviewed and built, they can also read libsignal's own headers and follow a
  sending key across label changes for as long as it lasts;
- any of this on a phone running a modified app.

## 5. Review of the open points

Done by Claude on 26 November 2026, against libsignal's source at the pinned
revision and the measurement in 3.9. This is the proposal's own author checking
it: **not an independent review**.

1. **No one-time pre-keys.** *Resolved:* replay of a first message is refused
   by libsignal's record of used base keys, confirmed by measurement. Yap must
   store that record permanently and replace bundles monthly (3.2).
2. **Checkpoints and concurrent branches.** *Replaced after the Codex review:*
   newcomers receive the full governance log instead (3.3, step 5).
3. **Knowing a hand-over arrived.** *Proposed, revised after Codex findings 8
   and 9:* the recipient acknowledges each piece over their session, naming the
   generation. The sender resends unacknowledged pieces, batched, until they are
   acknowledged or the recipient stops being a member, resending only the
   newest generation. Members compare generations over their one-to-one
   sessions when they reconnect (3.7). A receiver too far behind gets a fresh
   generation rather than a resend (3.6).
4. **Encodings.** *Proposed layouts.* Every field has a fixed size or a length
   limit, and each kind starts with its own tag. A hash covers a tag naming its
   purpose as well as the bytes.
   - Hand-over piece: `YGH1`, group ID (16), distribution ID (16), label key
     (32), the distribution message (length-prefixed, at most 256 bytes).
   - Group message header, inside the encryption: `YGM1`, group ID (16), sender
     member key (32), message ID (16), distribution ID (16), log heads (count of
     at most `MAX_PARENTS`, then 32 each), kind (1), payload.

   Exact limits and test vectors come with the code review.
5. **Storage.** *Proposed:* the endpoint's encrypted snapshot and staged commits
   (see the [protocol proposal](../protocol-proposal.md)), extended with a
   sender-key store. Advancing a sending key is committed together with the
   outgoing envelope, and retries resend the stored bytes. Old sending keys are
   deleted by local elapsed time, never by the sender's clock. Everything stays
   excluded from backups.
6. **Limits against a hostile member.** *Proposed:*
   - at most 127 sessions per group, following the member cap;
   - at most 8 sending-key generations kept per member, for 24 hours (3.6);
   - changes justified by the group log are never refused; routine changes are
     limited to 2 per member per day (3.6, Codex finding 10);
   - at most 4 new sessions accepted from one member per day, after a cheap
     membership check (3.2, Codex finding 13);
   - libsignal lets a message skip up to 25,000 chain steps
     (`MAX_FORWARD_JUMPS`), so a hostile member can make every phone do that
     work per message. Yap should drop a message more than 2,000 steps ahead of
     the last one seen from that sender, before handing it to libsignal. The
     pinned API makes this possible: `SenderKeyMessage::iteration` reads the
     step number without decrypting.
7. **Multiple devices.** *A planned future feature (user, 26 November 2026); for now, one device per member.* Each device would be its own
   libsignal address with its own bundle, sessions and sending key, so
   hand-overs grow with the number of devices. This needs its own proposal,
   together with the device rules in §5 of the spec.

Also still open: the review in 3.10, which depends on the local mesh design, and
an independent review of the whole design. Multiple devices per member are a
planned future feature; version 1 is one device per member.

## 6. Tests required before release

- A non-member relay holds every envelope and decrypts none.
- After a removal, the removed member cannot decrypt anything sent with the new
  keys, including governance events.
- A newcomer cannot decrypt messages sent before their admission.
- A split where each side removes someone: on reconnecting, every phone ends
  with keys that exclude both removed people, after one change per phone.
- Duplicate, reordered, tampered and replayed messages, and messages carrying a
  sender header that does not match how they were received.
- A message from a removed member arriving after the removal is dropped
  (choice 2).
- A replayed first hand-over is refused after a restart (the base-key record
  survives).
- A removal in a group of 128 with open sessions produces envelopes that are
  each under the envelope limit; the total is measured against the relay limits.
- A newcomer who receives the full governance log reaches the same state as
  existing members, including after splits.
- A message skipping more than 2,000 chain steps is dropped before decryption.
- After the Codex review:
  - the outer layer hides every libsignal header from a relay (once reviewed);
  - five removals in an hour all take effect, merged into one change each;
  - a receiver held more than 2,000 steps behind recovers through a fresh
    generation, against the pinned library;
  - a member who missed a hand-over, and its label key, catches up through the
    one-to-one check on reconnecting;
  - batches stay under the size limits for new, unacknowledged, open and
    ratcheting sessions, and a relay never splits one;
  - erased generations leave no keys in snapshots or journals;
  - a phone that knows of a removal refuses that person's `AcceptOffer`, even
    with old heads;
  - an old message delivered late does not make its author count as in reach;
  - a paperwork phone that stays silent is replaced after 2 minutes, and an
    accepted formation is never displaced.
- Old keys are deleted after the envelope lifetime, and a restored backup cannot
  bring them back.
- Hand-over counts and sizes measured in 5-, 30- and 128-member groups.

## 7. Order of work, once approved

1. Record the answers to section 2 in GROUP_MECHANICS.md.
2. Settle the open points in section 5, with review.
3. Add bundle publication, sending the full governance log to newcomers, and
   the new events to `groups/`,
   with tests.
4. Build the encryption layer in a separate crate on the sender-key functions,
   with the tests in section 6 run on simulated phones.
5. Measure the costs in 3.9.
6. Connect it to the mesh envelope and the Android app, kept separate from the
   unencrypted legacy chat.
