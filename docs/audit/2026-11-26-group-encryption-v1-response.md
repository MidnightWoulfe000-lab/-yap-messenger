# Response to the Codex review of group encryption v1

**Author:** Claude, 26 November 2026.
**Responds to:** [2026-11-26-group-encryption-v1-codex-review.md](2026-11-26-group-encryption-v1-codex-review.md).
**Status:** Every finding is accepted. The project owner took the recommended
option for both decisions (A and B below) on 26 November 2026. Finding 4 is
fixed in `groups/`. Findings 1 to 3 are resolved in the proposal by decision A.
The rest still need proposal changes, and the proposal must not be implemented
until they are made and reviewed.

Finding numbers are Codex's.

## Checked against the code

- **Finding 4 is real in the built `groups/` crate.** A throwaway test (not
  kept) built a normal group of 5 with A as SuperAdmin. Member B proposed
  removing A with the list `[B]`. The proposal was valid, A was removed at
  once, and B's own vote made B SuperAdmin. One member took over the group.
- **Finding 5 is correct.** In the pinned libsignal's `wire.proto`,
  `SenderKeyMessage` carries `distribution_uuid`, `chain_id` and `iteration`
  outside its ciphertext.
- **Finding 8 is correct.** In the pinned `sender_keys.rs`,
  `add_sender_key_state` keeps the existing state when the chain ID and signing
  key match, so resending a distribution message does not move a lagging
  receiver forward.

## Each finding

| # | Status | What changes |
| --- | --- | --- |
| 1 | Accepted, resolved by decision A | The newcomer checks the full governance log, so the roster comes from signed events, not a checkpoint. Trust still starts with the sponsor: someone who knows no other member cannot tell a real group from one the sponsor made up. The proposal now says so plainly. |
| 2 | Accepted, resolved by decision A | No checkpoints: the newcomer receives and checks the events behind every branch. |
| 3 | Accepted, resolved by decision A | The newcomer has the whole log, ancestry included, and runs the same rules as everyone else. |
| 4 | Accepted, confirmed, fixed | Decision B: a vote always counts at least half of all current members, leaving out the person it is about. Built in `groups/` (`counted`), with a test that one member listing only themselves cannot remove the SuperAdmin. |
| 5 | Accepted, confirmed | Hourly labels alone do not stop tracking: libsignal's own headers are readable. Proposed fix: one more layer of encryption around every group envelope, using a key from the label key, so a relay sees only the label and ciphertext. This is a new construction and needs its own review. Until then, the privacy claims are weakened in writing. |
| 6 | Accepted | Batches are split by actual size into as many envelopes as needed, with limits on pieces per envelope. The "127 envelopes" claim is withdrawn. The measurement program is kept in `experiments/` and extended to new, unacknowledged and ratcheting sessions. |
| 7 | Accepted | Carrying a batch addressed to many people needs its own rules in the mesh design: each piece acknowledged separately, plus custody, expiry and resending. Left open until the mesh design takes it up. |
| 8 | Accepted, confirmed | Recovery from a gap that is too large: the receiver asks the sender over their one-to-one session, and the sender changes to a fresh sending key. That change counts as justified (see 10). Tested against the pinned library before relying on it. |
| 9 | Accepted | Each pair of members keeps a resynchronisation step that does not depend on group labels. On reconnecting they compare the generation numbers of their current keys over their one-to-one session and request anything missing. |
| 10 | Accepted | Key changes caused by a governance event are never capped; they are merged and scheduled instead. Only unexplained changes are limited. Which delayed messages are lost when storage limits are hit is written down. |
| 11 | Accepted | The weekly change stays as routine practice, but the claim that it limits how long a copied phone can read is removed. Recovering from a copied phone needs the one-to-one sessions to recover first, or re-pairing; the proposal will state when each happens. |
| 12 | Accepted | The forward-secrecy claim is narrowed to old ciphertext whose keys have really been erased. Erasure of skipped message keys, saved distribution messages, snapshots and journals is specified. Chat history kept on an unlocked phone is not protected by key deletion. |
| 13 | Accepted | Records for retired pre-key IDs, which are never reused, are pruned. Session set-ups are rate-limited per member before the expensive work. The base-key record is Yap's job to store durably. |
| 14 | Accepted | A removal a phone already knows about always blocks eligibility, whatever heads an offer names. |
| 15 | Accepted | Being heard from proves who wrote a message, not where they are or that it is new. "In reach" becomes answering a fresh challenge within a short time. Members also send a small encrypted heartbeat every day, so "not heard from" means something even when the group is quiet. The proposal will say that isolation is always uncertain. |
| 16 | Accepted | The paperwork phone gets a timeout and a fallback. A formation that people have accepted is never replaced because a smaller key appears. Each acceptance is bound to the exact temporary group, original group, settings and offer. |

## Decisions (project owner, 26 November 2026: recommended option for both)

**A. How a newcomer learns the group's state (findings 1–3).**
- *Option 1, recommended:* the newcomer's phone receives the full governance log
  (votes, joins, removals), never the chat. It checks every event itself, as
  every member's phone does now. The app shows only history from their own join
  onwards. A modified app could show the earlier governance history; earlier
  chat stays unreadable because of sender keys.
- *Option 2:* build a checkpoint system with certified signers and a proof that
  it gives the same result as replaying the full log. This keeps earlier
  governance history from newcomers, but it is a large, risky piece of protocol
  work.

**B. Stopping one member from shrinking a vote's list (finding 4).**
- *Option 1, recommended:* a vote always counts at least half of all current
  members, even if its list names fewer. The person the vote is about is never
  counted. Inactive members can still be left out, but never more than half.
- *Option 2:* every member on the list must sign that they are on it before the
  vote opens. This needs everyone to be reachable, which a split mesh often is
  not.
- *Option 3:* drop the 30-day rule and count all current members again.
