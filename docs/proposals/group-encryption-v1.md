# Proposal: group encryption

**Status: proposal for review. Not implemented. No security audit.** Per `AGENTS.md`,
encryption is not built from assumptions. The project owner has decided the
choices in section 2; section 3 still needs review before any code is written.

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

All ten choices are now decided (26 November 2026). The design in section 3 follows them.

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
   limits how long someone who secretly copied a phone's keys can keep reading.
   Each change means that member hands a new key to everyone else, so more
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
post-quantum (Kyber) last-resort pre-key. A Kyber-1024 public key is 1,568 bytes,
so a bundle fits within the 8,192-byte event limit.

- The joiner's bundle travels in their `JoinRequest`. A new event lets a member
  replace their bundle, for example on a schedule.
- There are no one-time pre-keys, because a shared log cannot hand each one out
  to exactly one person. Libsignal accepts a bundle without them; the cost is
  weaker replay protection on the first message of a session. **Reviewer to
  confirm this is acceptable**, or to propose a way of giving each member a
  one-time pre-key per pair.
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
5. **Starting state for the newcomer.** The newcomer must know the current
   members, admins, settings and bundles to check later events, but the spec
   says they must not see the join history from before they arrived (§4). This
   proposal adds a **state checkpoint**:
   - The admitting event carries the hash of a canonical encoding of the group
     state as of that event's parents (members, admins, SuperAdmin, settings,
     bundles; no sponsors, approvals or reasons for earlier joins).
   - Every member checks that hash against the state they derive themselves. A
     sponsor who puts a false checkpoint in the event makes the admission
     invalid for everyone, so the newcomer is never let in on a lie.
   - The sponsor sends the checkpoint itself to the newcomer over their session.
     The newcomer checks it against the hash and starts from it.

   **Open for review:** events made on another branch of a split at the same time
   as the admission cite parents that the newcomer never received, so the
   newcomer cannot check them against their own state at that point. One
   possible fix is for the sponsor to also send checkpoints for those branches;
   this needs working out and testing before it is built.
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

### 3.5 Governance events travel encrypted

Governance events (proposals, votes, reasons, removals) are as sensitive as
messages. Between members, they travel as group messages under sending keys. A
non-member relay sees only labelled ciphertext. The signed event inside is
unchanged, so the rules in `groups/` work as built.

### 3.6 Removal and key changes

A change of keys is triggered by:
- any removal: by vote, by an admin, or of the SuperAdmin;
- a member marking one of their own devices as lost (`RevokeOwnDevice`);
- the weekly change of a member's own sending key (choice 5).

When a phone processes a removal, it makes a new sending key under a new random
distribution ID and hands it over, with a new label key, to every member active
in its state. It never sends it to the removed member. From then on it sends
only with the new key.

Old sending keys, its own and others', are kept only for the envelope lifetime
(24 hours is proposed for one-to-one), so slow messages can still be read, and
are then deleted. Deleting them keeps forward secrecy: a phone seized later does
not hold keys for old messages.

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

| Event | Private hand-overs |
| --- | --- |
| A member joins a group of *n* | about 2*n* (each member to the newcomer, and back) |
| A removal in a group of *n* | about (*n*−1)×(*n*−2): about 16,000 at 128 |
| A member's weekly key change | *n*−1 |

The hand-over message sizes, including the one-to-one session overhead, must be
measured with the pinned library; this proposal does not guess them.

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
state of the original group.

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

**Open for review:**
- How phones notice that fellow members are nearby without revealing group
  membership to strangers. This belongs with the local mesh design and must use
  rotating identifiers, never a fixed group ID broadcast over the air.
- The new governance event for "admitted by accepting an offer", and how a phone
  checks it against the original group's state.
- What happens when phones in one part disagree about who is in reach, so two
  of them each think they should do the paperwork.

## 4. What this protects, and what it does not

**Protects:**
- message and governance content from non-member phones carrying it, and from
  internet relays;
- messages sent after a removal (once the removal has reached the sender's phone)
  from the removed member;
- messages sent before a newcomer joined, from that newcomer;
- old messages on a phone seized later, once old keys have been deleted.

**Does not protect:**
- anything a member already read or copied, including a kicked member's copies;
- the window during a split before a removal reaches the other side (3.7);
- against a member's phone that is hacked while in use, until the next key change;
- metadata: relays still see envelope sizes, timing, which phones pass them on,
  and that a label recurs within its hour;
- any of this on a phone running a modified app.

## 5. Open for review

1. Pre-key bundles with no one-time pre-keys (3.2).
2. Checkpoints for events from concurrent branches (3.3, step 5).
3. How a sender learns that a hand-over was received, and when to resend it.
4. The canonical encodings: checkpoint, message header, the hand-over message
   that wraps a distribution message with a label key.
5. Storage: sessions, sending keys and label keys in the encrypted store, with
   the same atomic-commit and backup-exclusion rules as one-to-one.
6. Limits on hand-overs, sessions and stored keys per group, so that a hostile
   member cannot exhaust a phone.
7. Multiple devices per member (3.1).

## 6. Tests required before release

- A non-member relay holds every envelope and decrypts none.
- After a removal, the removed member cannot decrypt anything sent with the new
  keys, including governance events.
- A newcomer cannot decrypt messages sent before their admission.
- A false checkpoint in an admission makes it invalid for every other member.
- A split where each side removes someone: on reconnecting, every phone ends
  with keys that exclude both removed people, after one change per phone.
- Duplicate, reordered, tampered and replayed messages, and messages carrying a
  sender header that does not match how they were received.
- A message from a removed member arriving after the removal is dropped
  (choice 2).
- Old keys are deleted after the envelope lifetime, and a restored backup cannot
  bring them back.
- Hand-over counts and sizes measured in 5-, 30- and 128-member groups.

## 7. Order of work, once approved

1. Record the answers to section 2 in GROUP_MECHANICS.md.
2. Settle the open points in section 5, with review.
3. Add bundle publication, the checkpoint hash and the new events to `groups/`,
   with tests.
4. Build the encryption layer in a separate crate on the sender-key functions,
   with the tests in section 6 run on simulated phones.
5. Measure the costs in 3.9.
6. Connect it to the mesh envelope and the Android app, kept separate from the
   unencrypted legacy chat.
