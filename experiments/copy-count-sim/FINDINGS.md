# Findings: how many copies, and what the private last-copy rule costs

From the run of 28 November 2026 (fourth run, after the simulator was corrected for the
fourth Codex review), 5 seeds per scenario. Full tables are in
[results/summary.md](results/summary.md) and the raw rows in
[results/results.csv](results/results.csv). These are model outputs under the assumptions
in the [README](README.md), not measurements on phones. They compare rules against each
other on the same movement; they do not predict real delivery rates.

This run changed one modelling rule (Codex finding 11): with no hops left, a holder
cannot tell a recipient from a stranger, so a **split allocation with no hops left is
sent as a last copy**. The holder sends it to any phone that has not been sent a last
copy, keeps its own allocation (nothing is debited), the recipient or member keeps it
and a stranger drops it. Before, a holder with two or more copies skipped the send at
zero hops unless the target was a recipient or member, which used knowledge the wire does
not give it. The change moved the figures only a little: for example `crowd` one-to-one
sends went from 849 to 850 a message, `district` group-of-30 sends from 559 to 558, and
the highest v3.1 carrier peak in `crowd` from 225 to 232 envelopes.

## 1. The private rule delivers about as well as asking "is this yours?"

The v3.1 rule (a last copy goes to every phone met, only the recipient or members keep
it) and the earlier ask-mine rule (a last copy goes only to a phone that says it is the
recipient or a member) gave **similar** results in these six invented worlds, not
identical ones. Averages over 5 seeds:

| 96 / 255 copies | v3.1 rule | Ask-mine rule | Unlimited |
| --- | --- | --- | --- |
| One-to-one reached, `district` | 98.9% | 99.2% | 100% |
| One-to-one reached, `scattered` | 97.2% | 97.2% | 100% |
| Group of 30, every member reached, `district` | 97% | 97% | 100% |
| Group of 30, every member reached, `scattered` | 85% | 81% | 98% |
| One-to-one, 90% arrived within, `scattered` | 15.1 h | 15.1 h | 13.2 h |
| Group of 30, median delay, `scattered` | 6.7 h | 6.7 h | 6.1 h |
| Group of 30, 90% arrived within, `scattered` | 15.4 h | 15.5 h | 13.9 h |

The differences go both ways: v3.1 is 4 points ahead for all-member delivery in
`scattered` and about level in `district`. With 5 seeds and three fixed groups per
size, the seed-to-seed spread (up to ±9 points for all-member delivery) is larger than
these gaps, so this is not evidence that the two rules are statistically equivalent, or
that privacy has no delivery cost in general. The delay figures are over recipients who
were reached only.

This needed one detail: a phone that was sent a last copy must still accept a split
share of the same message later. When the simulator refused those too, one-to-one
delivery in `scattered` fell from 98% to 95% in a quick check on an earlier version,
because phones that had seen a last copy could no longer help spread the split copies.

## 2. The cost is radio traffic, about as much as unlimited flooding

| Sends per message | v3.1 rule | Ask-mine rule | Unlimited |
| --- | --- | --- | --- |
| One-to-one, `crowd` (800 phones) | 850 | 95 | 798 |
| One-to-one, `district` (400) | 443 | 76 | 395 |
| One-to-one, `scattered` (120) | 140 | 47 | 115 |
| Group of 30, `crowd` | 981 | 280 | 799 |
| Group of 30, `district` | 558 | 218 | 399 |

Under the v3.1 rule nearly every phone that meets a holder is sent the message once as
a last copy, and some are also sent a split share, so each message reaches nearly every
phone over the air, a little more than flooding. Unlike flooding, the phones that only
get a last copy store nothing and pass nothing on. At this light load (about one
message per phone per day) every link stayed within the model's own per-minute ceilings
(40 envelopes a link direction, 100 a phone). That is the model's counter, not a
measurement of radio headroom. Under heavy chat load this traffic would fill links
sooner, and that is not tested here.

## 3. Storage and spam storage in the model

- **Strangers storing a message** stay at about the copy count: about 90 strangers
  for a 96-copy one-to-one message in the large towns (88 to 95) and about 250 for a
  255-copy junk message in `town` and `crowd` (254), the same as the ask-mine rule.
  Unlimited stores junk on every phone (399 in `town`, 799 in `crowd`).
- **Carrier storage, `crowd`, envelopes held by one phone at once.** Two different
  numbers, kept apart:

  | Rule | Mean of each seed's busiest phone | Highest peak of any seed |
  | --- | --- | --- |
  | v3.1 | 208 | 232 |
  | Ask-mine | 135 | 150 |
  | Unlimited | 899 | 900 |

  Across all six scenarios the highest v3.1 peak in any seed was 232 and the highest
  ask-mine peak was 167. The carrier store holds 10,000. These count envelopes only.

**What this does not show.** Occupancy is small here because the load is artificial and
light (about one message per phone per day, nothing evicted). The model does not show
bounded bytes, eviction safety, radio capacity or battery cost, and it does not show that
real-world storage or traffic is bounded by the copy count: holders can meet many phones
over 24 hours, members keep last copies, and delivered carriers keep offering until
expiry. The useful bounds are the message lifetime, each phone's own limits and the
honesty assumptions, not these averages.

## 4. Copy counts, tested one at a time

Where everyone passes through the same busy places (`campus`, `town`, `crowd`,
`sparse`), every count reached everyone. In the neighbourhood scenarios:

| One-to-one copies (groups at 255) | 12 | 24 | 48 | 64 | 96 |
| --- | --- | --- | --- | --- | --- |
| Reached, `district` | 74.9% | 84.6% | 94.1% | 96.6% | 98.9% |
| Reached, `scattered` | 77.5% | 88.5% | 94.5% | 96.2% | 97.2% |

| Group copies (one-to-one at 96), group of 30 | 32 | 64 | 128 | 192 | 255 |
| --- | --- | --- | --- | --- | --- |
| Every member reached, `district` | 40% | 73% | 90% | 96% | 97% |
| Every member reached, `scattered` | 40% | 65% | 75% | 81% | 85% |
| Members reached, `district` | 96.4% | 98.6% | 99.6% | 99.9% | 99.9% |

One-to-one gains little above 64 and group gains little above 192 in `district`, but
both still gain in `scattered`. 96 and 255 remain reasonable choices to try; nothing
here shows they are optimal, and nothing here shows a better value would not do more.

## 5. Junk

Junk with 255 copies is stored by about as many strangers as it has copies, under
either rule. With the v3.1 rule it is also sent once to most other phones, which drop
it. It never travels further than one step from a holder that way. Hand-on rules were
not rerun.

## Limits

- Six invented worlds. Real movement may be better or worse mixed than any of them.
- Light load, no slow mode, no byte limits, no eviction, no lost frames, no control-frame
  traffic, and phones know perfectly what they already hold.
- No bytes, radio capacity or battery cost: nothing here shows bounded bytes or that
  nearby radios can carry the traffic.
- No phones switched off, leaving for good or running out of battery.
- No lying phones, and no real offer, want and reservation state machine.
- Receipts are instant, so one-to-one senders stop offering sooner than in reality.
- Member-only retention uses the exact member list, not anything a phone could prove from
  envelope bytes; zero-hop custody by a member is held inside the model only.
- All phones share one clock, so skew and secret ageing are not modelled.
- Five seeds and three fixed groups per size: small differences (a few per cent) may be
  noise.
