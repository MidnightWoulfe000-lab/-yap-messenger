# Findings: how many copies, and what the private last-copy rule costs

From the run of 27 November 2026 (second run, after the simulator was corrected for the
second Codex review), 5 seeds per scenario. Full tables are in
[results/summary.md](results/summary.md). These are model outputs under the assumptions
in the [README](README.md), not measurements on phones. They compare rules against each
other on the same movement; they do not predict real delivery rates.

## 1. The private rule delivers as well as asking "is this yours?"

The v3.1 rule (a last copy goes to every phone met, only the recipient or members keep
it) and the earlier rule (a last copy goes only to a phone that says it is the
recipient or a member) reached the same share of recipients in every scenario, with the
same delays:

| 96 / 255 copies | v3.1 rule | Ask-mine rule | Unlimited |
| --- | --- | --- | --- |
| One-to-one reached, `district` | 99% | 99% | 100% |
| One-to-one reached, `scattered` | 97% | 97% | 100% |
| Group of 30, every member reached, `district` | 97% | 97% | 100% |
| Group of 30, every member reached, `scattered` | 85% | 81% | 98% |
| One-to-one, 90% arrived within, `scattered` | 15.1 h | 15.1 h | |

This needed one detail: a phone that was sent a last copy must still accept a split
share of the same message later. When the simulator refused those too, one-to-one
delivery in `scattered` fell from 98% to 95% in a quick check, because phones that had
seen a last copy could no longer help spread the split copies.

## 2. The cost is radio traffic, about as much as unlimited flooding

| Sends per message | v3.1 rule | Ask-mine rule | Unlimited |
| --- | --- | --- | --- |
| One-to-one, `crowd` (800 phones) | 849 | 95 | 798 |
| One-to-one, `district` (400) | 443 | 76 | 395 |
| One-to-one, `scattered` (120) | 140 | 47 | 115 |
| Group of 30, `crowd` | 980 | 280 | 799 |
| Group of 30, `district` | 561 | 218 | 399 |

Under the v3.1 rule nearly every phone that meets a holder is sent the message once as
a last copy, and some are also sent a split share, so each message reaches nearly every
phone over the air, a little more than flooding. Unlike flooding, the phones that only
get a last copy store nothing and pass nothing on. At this light load (about one
message per phone per day) every link stayed within its limits. Under heavy chat load
this traffic would fill links sooner, and that is not tested here.

## 3. Storage and spam storage stay bounded

- **Strangers storing a message** stay at about the copy count: about 90 strangers
  for a 96-copy one-to-one message and about 250 for a 255-copy junk message in the
  large towns, the same as the ask-mine rule. Unlimited stores junk on every phone.
- **Carrier storage:** the busiest phone held at most about 210 envelopes with the
  v3.1 rule, against about 160 with ask-mine and up to about 900 with unlimited
  flooding in the 800-phone crowd. The carrier store holds 10,000.

## 4. Copy counts, tested one at a time

Where everyone passes through the same busy places (`campus`, `town`, `crowd`,
`sparse`), every count reached everyone. In the neighbourhood scenarios:

| One-to-one copies (groups at 255) | 12 | 24 | 48 | 64 | 96 |
| --- | --- | --- | --- | --- | --- |
| Reached, `district` | 75% | 85% | 94% | 97% | 99% |
| Reached, `scattered` | 78% | 88% | 94% | 96% | 97% |

| Group copies (one-to-one at 96), group of 30 | 32 | 64 | 128 | 192 | 255 |
| --- | --- | --- | --- | --- | --- |
| Every member reached, `district` | 42% | 71% | 92% | 97% | 97% |
| Every member reached, `scattered` | 40% | 65% | 75% | 81% | 85% |
| Members reached, `district` | 96% | 99% | 100% | 100% | 100% |

One-to-one gains little above 64 and group gains little above 192 in `district`, but
both still gain in `scattered`. 96 and 255 remain reasonable choices; nothing here
shows they are the best ones.

## 5. Junk

Junk with 255 copies is stored by about as many strangers as it has copies, under
either rule. With the v3.1 rule it is also sent once to most other phones, which drop
it. It never travels further than one step from a holder that way. Hand-on rules were
not rerun.

## Limits

- Six invented worlds. Real movement may be better or worse mixed than any of them.
- Light load, no slow mode, no byte limits, no lost frames, no control-frame traffic,
  and phones know perfectly what they already hold.
- No phones switched off, leaving for good or running out of battery.
- No lying phones.
- Receipts are instant, so one-to-one senders stop offering sooner than in reality.
- Five seeds and three fixed groups per size: small differences (a few per cent) may be
  noise.
