# Response to the second review of local mesh v3.1

Answers [the second Codex review](2026-11-27-local-mesh-v3.1-second-review.md) of 27
November 2026 (nine High, six Medium, no Critical). Revised the same day:
[local mesh v3.1](../proposals/local-mesh-v3.1-group-envelopes.md),
[rotating address labels](../proposals/rotating-address-labels.md), a new
[encrypted pairing](../proposals/encrypted-pairing.md) draft, one line of
[group encryption](../proposals/group-encryption-v1.md) §3.4, two notes in
[local mesh v2](../proposals/local-mesh-v2.md), and the
[copy-count simulator](../../experiments/copy-count-sim/README.md), which was corrected
and rerun. Choices marked "owner" were made by the project owner on 27 November 2026.
Nothing is implemented, and every changed proposal needs another review.

| # | Grade | Status | What changes |
| --- | --- | --- | --- |
| 1 | High | Accepted | The copy limit is stated as holding only for honest phones, with what a lying phone can do, and v2's "lies only spread the liar's own messages" is corrected. The sender's starting copies go into the authenticated header, so nobody can raise an envelope above them; this bounds one allocation, not the network total. Each phone's own storage and rate limits stay the real defence. No membership proof is designed (owner). The copy-priced stamp stays an undecided idea, described as not stopping malicious carriers. (v3.1 §1.1, §1.3, §1.8) |
| 2 | High | Accepted, fixed | Offers carry the class and what is given: `0` for a last copy, otherwise a split allocation, and the envelope must agree. Invalid counts are defined. The simulator now uses the same split and last-copy decisions. (v3.1 §1.4) |
| 3 | High | Accepted, fixed | A full state table per link and envelope (Not offered, Offered, Sending, Uncertain, Settled), with timeouts, offer IDs carried by data frames and `ACK`s, atomic reservation before sending, uncertain transfers counted as spent, counts and reservations persisted (replacing v2's memory-only source counts), cursor paging stable across inserts and expiry, and bounded per-link state. (v3.1 §1.4) |
| 4 | High | Accepted, fixed | The "mine" answer is removed (owner). Offers carry no destination label. A last copy goes to every phone met; only the recipient or members keep it; every phone sends the same `ACK` and answers later offers the same way. `DELIVERED` is retired. What remains (which envelopes a phone has seen, and a member re-offering a last copy) is stated, with a phone-wide answer budget. (v3.1 §1.2 to §1.4) |
| 5 | High | Accepted, new proposal | Encrypted pairing in this update (owner): a pairing key in each QR, a `Noise_NNpsk0` handshake on the pairing link, the whole pairing inside it, and confirmations bound to the handshake. Pairing packets go in a queue bound to the pairing link, as an enforced routing rule; today's code sending the bootstrap to every link and the relay is named. ([encrypted pairing](../proposals/encrypted-pairing.md), labels §2) |
| 6 | High | Accepted, redesigned | The pair secret is set up inside encrypted pairing, covered by both confirmations, and stepped forward weekly by HKDF on each phone, so nothing is sent and the phones cannot disagree (owner). What this gives up, and what a copied phone can and cannot do, is stated. Today's empty kind-03 bootstrap stays unchanged. (labels §1) |
| 7 | High | Accepted, partly answered (Codex said "fixed" was too strong; see the [response of 28 November](2026-11-28-v3.1-update-response.md), finding 1) | One-to-one pieces between group members never use a pair secret. They are addressed and wrapped with the recipient's own inbox key, published in its join request and replaced at each of its key changes, so newcomers and members who fell behind can always find them, and no clear identity key reaches strangers. No new primitive, and no fallback to static identity Diffie-Hellman. Group members never take contact or probe slots. (labels §7, group encryption §3.11) |
| 8 | High | No longer applies | The emergency wipe draft was withdrawn: the owner asked for a way to stop recognition, not to delete everything. Its replacement is v3.1 §3.9. |
| 9 | High | No longer applies | As 8. |
| 10 | Medium | Accepted, fixed | Exact 70-byte binding with offsets (class and starting copies added), the hop byte at encoded byte 70, class and count checks before use, old stores deleted on upgrade, both outer-layer descriptions moved to 70 bytes, and the 33-to-32-byte identity key conversion stated. (v3.1 §1.1, labels §5, group encryption §3.4) |
| 11 | Medium | Accepted, fixed | Making always computes 128 HMACs and checking always 256 expected tags (current and previous week), so work does not depend on the number of contacts. Separate caps for making and checking, one at a time, at most 4 waiting, applied before native calls. A second `PROBE` leaves the first result. Nonce wording no longer says "never". Cap exhaustion by strangers is a stated limit. (v3.1 §3.2 to §3.4) |
| 12 | Medium | Accepted | The label table changes as soon as a contact is added or removed, holds up to 4 entries per tag, and dedupes clamped epochs. The related 64-sized limits are listed for coordinated change. Snapshot and session sizes for 128 contacts are named as needing their own storage proposal. (labels §4, v3.1 §3.5) |
| 13 | Medium | Accepted, partly answered (Codex said "fixed" was too strong; see the [response of 28 November](2026-11-28-v3.1-update-response.md), findings 1, 3 to 5, 8, 11, 14 and 16) | The batch container is specified: exact layout, a bounded scan of up to 128 piece labels, whole-batch refusal for bad lengths, per-piece failure without losing the rest, inbox keys for newcomers and members who fell behind, and separate acknowledgements for storing and installing. (group encryption §3.11, v3.1 §1.5) |
| 14 | Medium | Accepted, fixed | The simulator's hop rule now matches the code (the sender's send does not count, a carrier never stores 0, an endpoint may accept 0), expiry is checked before transfer and at the exact deadline, and budgets average exactly 40 and 100 a minute. The remaining gaps (no slow mode, byte limits, control frames, lost frames, reservations, perfect knowledge) are listed. (simulator README) |
| 15 | Medium | Accepted | Copy counts now vary one at a time, with more values. Movement and "every phone" wording are corrected. The findings say the results support trying these counts, not that they are optimal. The fuller scenario list (lying phones, churn, loss, bytes, energy and more) is still to do. (simulator README, FINDINGS) |

## Owner decisions on 27 November 2026, from this review

- The copy limit is stated as holding for honest phones only, and the starting copies
  are fixed in the header.
- Nobody answers whether an envelope is theirs: last copies go to every phone met, and
  `DELIVERED` is retired.
- Encrypted pairing goes in this update.
- The pair secret is made during pairing and stepped forward weekly, with no
  replacement messages.
- Group members you have not paired with use the group's keys for their one-to-one
  pieces.

## What the corrected simulation shows

From [FINDINGS.md](../../experiments/copy-count-sim/FINDINGS.md), model results only:
- The private last-copy rule delivers as well as asking "is this yours?" in every
  scenario, provided a phone sent a last copy can still take a split share later.
- It costs radio traffic: each message is sent about as many times as under unlimited
  flooding (849 sends against 95 for a one-to-one message in the 800-phone crowd).
  Storage stays bounded, and strangers storing a message stay at about its copy count.
- Tested one at a time, one-to-one delivery in the neighbourhood scenarios rises from
  75 to 78% at 12 copies to 97 to 99% at 96; group-of-30 all-member delivery from
  about 40% at 32 copies to 85 to 97% at 255. 96 and 255 stay reasonable, not proven
  best.

## Still open

- How a phone stores its own chats and sessions for 128 contacts (v3.1 §3.5): today
  everything sits in one 8 MiB snapshot with 512 saved messages in total.
- Review of inbox keys and the batch container (group encryption §3.11), written after
  this response's first version.
- Encrypted pairing's open questions, including the pairing file.
- What a `WANT` reveals about which envelopes a phone has seen, and a member
  re-offering a last copy (v3.1 §1.4).
- The internet relay's return.
- The fuller simulation list from finding 15, then real phones.
- An independent expert review before any sensitive use.
