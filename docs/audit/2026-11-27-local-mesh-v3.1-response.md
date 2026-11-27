# Response to the review of local mesh v3.1 and rotating address labels

Answers [the Codex review](2026-11-27-local-mesh-v3.1-review.md) of 27 November 2026
(five High, nine Medium, no Critical). Both proposals were revised the same day:
[local mesh v3.1](../proposals/local-mesh-v3.1-group-envelopes.md) and
[rotating address labels](../proposals/rotating-address-labels.md). Choices marked
"owner" were made by the project owner on 27 November 2026. Nothing is implemented,
and both proposals need another review.

| # | Grade | Status | What changes |
| --- | --- | --- | --- |
| 1 | High | Accepted, fixed | A routing class byte inside the authenticated header, so it is covered by `binding()`, the relay key and the stamp. Frames must agree with it; the class is stored with the envelope in one write; later rules read the stored class; a duplicate with a different class is refused. Envelope format version goes up. (v3.1 §1.1, §1.2) |
| 2 | High | Accepted, fixed | One-copy holders no longer pass a group envelope to everyone: group envelopes follow the one-to-one spray rules, and a one-copy holder gives only to members, keeping its own copy (owner). At most 255 strangers carry a group envelope and 96 a one-to-one one, chosen from a [copy-count simulation](../../experiments/copy-count-sim/FINDINGS.md) (owner). A per-phone limit of 4 hand-ons was tried first and rejected: in simulation it spread junk to every phone. Many different envelopes stay limited by stamps, slow mode and native carrier limits; a stamp that costs more for more copies is proposed for review. (v3.1 §1.3, §1.8) |
| 3 | High | Accepted, fixed | The false "offered once per link" claim is removed. New `OFFER` and `WANT` frames so no phone is sent an envelope it already has, for group and one-to-one envelopes alike (owner). Per-link states: not offered, wanted or in flight (retried with backoff), settled. Cleared on close. Candidates paged through the whole store. A group hand-over keeps the holder's copy. (v3.1 §1.3, §1.4) |
| 4 | High | Accepted | No tag design stops a relayed link. The screen says "<name> recognised", never nearby or connected (owner), with a note that recognition does not prove closeness. Recognition is used for nothing but the label. A two-site relay test documents the limit. Proving closeness is out of scope. (v3.1 §3.1, §3.7) |
| 5 | High | Accepted, fixed | Every one-to-one libsignal message (chat and receipts) is wrapped with the group outer layer, as kind `0x03` (owner). Pairing traffic, including the first message showing the identity key, stays on the direct pairing link and never reaches carriers. Tests use complete captured envelopes. (labels §2, §5) |
| 6 | Medium | Accepted | Eviction by flooding is described with its cost and the native byte limits. Repair by resynchronisation is stated as conditional, and permanent loss as possible; no promise under sustained attack. Simulations of eviction, sleeping phones and lost recovery traffic added. (v3.1 §1.6, §1.7) |
| 7 | Medium | Accepted, simplified | No compatibility with older builds: every phone is updated and re-paired (owner; all test phones are the owner's). Receipts are exactly 17 bytes, the count must be 0 to 8, and the first receipt's bytes and route are saved together. (v3.1 §2.1) |
| 8 | Medium | Accepted, fixed | Exact byte layout with tagger-first order, raw `HELLO` values fixed for the link, own or equal link IDs refused, exactly one 2,048-byte `PROBE` after `HELLO`, one match or none. Reflection, stale, swapped-role, wrong-size and simultaneous-connect tests added. Eight-byte nonces kept, as the review found them sufficient. (v3.1 §3.2) |
| 9 | Medium | Accepted, fixed | Matching checks every contact with no early exit; a phone-wide cap of 30 probes matched a minute (starting value); a match changes nothing on the wire, the link's lifetime or the logs. Timing test added. (v3.1 §3.3) |
| 10 | Medium | Accepted, fixed | The pair secret is random, sent as the first session message, and replaced every 7 days with 48 hours' overlap (owner; starting values). The claim that separate salts stop one leak reaching the others is corrected. What replacement can and cannot shut out is stated. (labels §1, v3.1 §3.4, §3.8) |
| 11 | Medium | Accepted, fixed | The contact limit goes from 64 to 128 (owner), so a full probe always holds every contact and sampling is dropped. (v3.1 §3.5) |
| 12 | Medium | Accepted, fixed | The false "all labels distinct" test claim is corrected: labels repeat within an hour and retries keep their label. Epoch is unsigned 64-bit big-endian, direction compares canonical identity bytes, window ends are exact, and the receiver recomputes the label from the authenticated creation time. Clock wording corrected. (labels §3, §4, test plan) |
| 13 | Medium | Accepted, deferred | The internet relay is off in this update and shown as "Coming soon" (owner). Nothing is sent to or fetched from it, so no fixed mailbox reaches nearby traffic. Its return needs its own design. (v3.1 "Not covered", labels §6) |
| 14 | Medium | Partly answered | A member reads its piece and still carries the whole batch. The batch container (wire format, visible routing fields, piece scan, newcomer path) must be written in the group encryption proposal before building. (v3.1 §1.5) |

The review's notes on routes and metadata are also taken in: route labels read
"Reported direct · route unverified" and similar, counts outside 0 to 8 are refused,
zero counts as a valid arrival, the new information a route gives the sender is
described, and the patterns strangers can still see in group traffic are listed
beside the group marker. (v3.1 §1.1, §2.3, §2.4)

## Owner decisions on 27 November 2026, from this review

- 96 copies for one-to-one and 255 for group envelopes, with the same copy rules for
  both, and no phone is sent what it already has, for every envelope.
- "Recognised" wording for friend names.
- The outer wrapper for one-to-one messages.
- No compatibility with older builds; re-pair everything.
- A random pair secret, replaced over time.
- 128 contacts.
- Internet relay off, shown as "Coming soon".
- A separate proposal for an emergency wipe ("go anonymous") button:
  [emergency-wipe.md](../proposals/emergency-wipe.md).

## Still open

- Whether a `WANT` answer reveals too much about what a phone holds (v3.1 §1.4).
- Whether the local pairing link's own encryption hides pairing traffic from radio
  listeners (labels §2).
- The batch container, in the group encryption proposal (finding 14).
- The internet relay's return.
- Simulation of every starting value, then real phones.
- An independent expert review before any sensitive use.
