# Response to the Codex review of group encryption v1

**Author:** Claude, 26 November 2026.
**Responds to:** [2026-11-26-group-encryption-v1-codex-review.md](2026-11-26-group-encryption-v1-codex-review.md).
**Status:** Every finding is accepted. The project owner took the recommended
option for both decisions (A and B below) on 26 November 2026. Finding 4 is
fixed in `groups/`. Findings 1 to 3 are resolved in the proposal by decision A.
Findings 5 to 16 are answered by changes to the proposal (same day). Those
changes are Claude's own and **need a second review**; the proposal must not be
implemented until then. Finding 7 is only partly settled: part of it belongs to
the mesh design.

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
| 5 | Accepted, confirmed; proposal revised | §3.4 adds an outer layer: the whole libsignal message is encrypted again under a key derived from the label key, so a relay sees only the label and ciphertext. It is a new construction and needs its own review. §4 now says relays can read libsignal's headers until it is built. |
| 6 | Accepted; proposal revised | §3.9: batches are split by size (at most 56 KiB and 128 pieces per envelope), with the worst case shown (about 635 envelopes, 31 MB, when no sessions are open). The "127 envelopes" claim is withdrawn. The measurement program is kept in `experiments/sender-key-sizes/` and now covers new, unacknowledged, alternating and one-way sessions. |
| 7 | Accepted; partly settled | §3.9: relays treat a batch as one envelope and never take pieces out; it is kept until expiry or limits, not until one recipient has it; each piece is acknowledged to the sender and checked on its own. How relays rank batches and the real storage used belong to the mesh design and stay open. |
| 8 | Accepted, confirmed; proposal revised | §3.6: a receiver too far behind asks the sender over their session, and the sender answers with a fresh generation for everyone, at most once an hour. Needs a test against the pinned library. |
| 9 | Accepted; proposal revised | §3.7: pairs compare sending-key generation numbers over their one-to-one session when they reconnect, without group labels. Pending hand-overs are kept until acknowledged or the recipient leaves, not dropped after 24 hours. |
| 10 | Accepted; proposal revised | §3.6: changes justified by the group log are never refused and name the events they answer; several are merged into one. Only routine changes are limited (2 per member per day). Up to 8 generations are kept for 24 hours, and losing messages past that is accepted and shown. |
| 11 | Accepted; proposal revised | Choice 5 (weekly change) stays as decided, but its stated reason is corrected. §3.6 and §4 now say a copier is only shut out once the one-to-one sessions recover or the member re-pairs. |
| 12 | Accepted; proposal revised | §3.6 specifies erasure of chain keys, skipped message keys, distribution messages, snapshots and journals. §4 narrows the claim to erased ciphertext keys and says stored chat history is not protected. |
| 13 | Accepted; proposal revised | §3.2: pre-key IDs are never reused, and the base-key record for a deleted pre-key is deleted with it. A cheap membership check, then at most 4 new sessions per member per day, before the expensive work. |
| 14 | Accepted; proposal revised | §3.10: a removal the checking phone knows about always blocks an acceptance, whatever heads the offer names. |
| 15 | Accepted; proposal revised | §3.10: reach is checked with a fresh challenge answered within 30 seconds over the local mesh, which shows a recent answer, not physical closeness; tunnelling is named as a limit. Members send a daily heartbeat so "not heard from" means something. Isolation is shown as uncertain. |
| 16 | Accepted; proposal revised | §3.10: the paperwork phone has 2 minutes before the next one takes over; an accepted formation is never replaced by a smaller key; each acceptance is bound to the exact temporary group, original group, membership, settings and offer. |

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

## Second review (27 November 2026)

Answers [the second Codex review](2026-11-27-group-encryption-v1-response-review.md).
Its numbers are used here. All six findings are accepted.

| # | Status | What changes |
| --- | --- | --- |
| 1 | Accepted, fixed | Decision (user, 27 November 2026): a vote always needs at least 2 votes when 2 or more people could vote, and in a group of 2 nobody can remove the SuperAdmin. Built in `groups/` (`needed`), with tests for groups of 2 and 3 and a table of list lengths and group sizes. |
| 2 | Accepted; proposal revised | §3.6: recovery has its own limit (one per sender per hour) and never uses up the weekly change; the weekly change is limited separately. |
| 3 | Accepted; proposal revised | §3.4 now specifies the outer layer: XChaCha20-Poly1305 with random 24-byte nonces, the HKDF key schedule with both directions of a pair separated, associated data from `Header::binding()`, the format and retries. Pieces are bound to their own label so they can move between batches. §1 no longer says nothing new is invented. Test vectors are still needed before approval. |
| 4 | Accepted; proposal revised | §3.10: deadlines at each step (offer, founding event, first keys). A missed deadline abandons the attempt, and a fresh one needs fresh consent. |
| 5 | Accepted; decision changed | Decision (user, 27 November 2026): internet access is not checked. What counts is being cut off from the main group, whatever the reason. Heartbeats go every 6 hours, leaving margin against the 24-hour rule. The proposal no longer claims the trigger proves nobody has internet. |
| 6 | Accepted; proposal revised | §5: hand-over pieces carry the recipient, a generation number, the kind of change and the governance events answered; acknowledgements name the generation. Each event justifies one change per sender, and an older generation never replaces a newer one. |

The changes are again Claude's own and need review before implementation.

## Third review (27 November 2026)

Answers [the third Codex review](2026-11-27-group-encryption-v1-third-review.md),
which found no critical problems: 1 high and 3 medium. All four are accepted.

| # | Status | What changes |
| --- | --- | --- |
| 1 | Accepted; proposal revised | §3.4: the outer key's hour comes from the envelope's own creation time (checked by the cipher) or, for a piece, from an hour field bound into it. Receivers accept 25 hours back to 1 hour ahead, matching address labels and the 24-hour lifetime. Pieces waiting more than 20 hours are re-wrapped: the libsignal message inside is unchanged, only the outer layer is redone with a fresh random nonce. |
| 2 | Accepted; proposal revised | §5's limit checklist now gives the separate weekly and recovery limits, enforced by receivers, instead of the old "2 per day". |
| 3 | Accepted, fixed | `revocation_threshold` and `super_admin_removal_threshold` now take the vote's list and the member count and are the calculation the state machine itself uses, so they cannot drift apart. A test checks that a 2-person group's SuperAdmin needs 2 votes in both. |
| 4 | Accepted; measurement and proposal revised | The program in `experiments/sender-key-sizes/` now encodes the current hand-over with 0, 1 and 16 events and counts the piece's label, hour, outer layer and batch length. §3.9's tables are refreshed from it; a removal with open sessions is now about 6.4 MB, not 4.6 MB. Batch storage stays open. |

Still outstanding before implementation: outer-layer test vectors, the batch
custody questions left to the mesh design, and review of these changes.

## Fourth review (27 November 2026)

Answers [the fourth Codex review](2026-11-27-group-encryption-v1-fourth-review.md),
which found 2 medium problems and confirmed the third review's four are
addressed. Both are accepted.

| # | Status | What changes |
| --- | --- | --- |
| 1 | Accepted; proposal revised | §3.6 "Repeated pieces": on accepting a piece, the receiver saves in the same commit the sender, the digest of the exact libsignal message, the group, the generation and its acknowledgement. A later copy whose inner digest matches, after its outer layer checks out, gets the stored acknowledgement again without calling libsignal. A `DuplicatedMessage` with no record is never taken as acceptance. |
| 2 | Accepted; proposal revised | §3.6: the weekly and recovery limits are now budgets for senders, measured by a creation hour the hand-over carries. Receivers never refuse the newest generation for its rate; they process at most 4 pieces per sender per hour, keep only the newest waiting (saved across restarts), never charge repeats, and warn about senders over budget. The new creation-hour field adds 8 bytes to each piece; §3.9's figures are re-measured (a removal with open sessions is now about 6.7 MB). |

This round found nothing critical or high, which meets the stopping point
agreed with the project owner for design review rounds. Still outstanding
before implementation: outer-layer test vectors, the batch custody questions
left to the mesh design, and an independent expert review before any sensitive
use.

## Outer-layer test vectors (27 November 2026)

[`experiments/outer-layer-vectors/`](../../experiments/outer-layer-vectors/README.md)
now provides the test vectors the second and third reviews asked for. It pins
the remaining byte details in proposal §3.4: kind `0x01` for group messages and
`0x02` for pieces; an envelope's associated data is exactly the 68 bytes of
`Header::binding()`, which already holds the label; a piece's is its label then
its hour. Its tests check the HKDF library against RFC 5869 test case 1 and the
XChaCha20-Poly1305 library against draft-irtf-cfrg-xchacha-03 appendix A.3.1,
both passing, and cover tampering, direction separation, hour boundaries and
the unbound hop count. GitHub's Rust checks now run it.

Still outstanding before implementation: review of the outer layer with these
vectors, the batch custody questions left to the mesh design, and an
independent expert review before any sensitive use.
