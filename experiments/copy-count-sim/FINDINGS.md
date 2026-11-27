# Findings: how many copies

From the run of 27 November 2026, 5 seeds per scenario (full tables in
[results/summary.md](results/summary.md)). These are model outputs under the
assumptions in the [README](README.md), not measurements on phones.

## 1. Where people all pass through the same busy places, the count barely matters

In `campus`, `town`, `crowd` and `sparse`, every rule reached every recipient and every
group member within 24 hours, including today's 12 copies. More copies only shortened
the wait: in `town`, the median one-to-one delay fell from 2.2 hours at 12 copies to 1.1
hours at 96, and 90% arrived within 9.0 and 7.2 hours. Overnight, when everyone is at
home, sets most of the long waits.

## 2. Where people stay in their own neighbourhoods, 12 and 32 are too few

In `district` and `scattered`, people mostly visit the places nearest home, so a message
has to cross from one neighbourhood to another on the few people who travel.

| One-to-one copies | 12 (today) | 24 | 48 | 96 | unlimited |
| --- | --- | --- | --- | --- | --- |
| Reached, `district` | 74% | 85% | 94% | 99% | 100% |
| Reached, `scattered` | 77% | 89% | 95% | 97% | 100% |

| Group copies, group of 30 | 32 | 64 | 128 | 255 | unlimited |
| --- | --- | --- | --- | --- | --- |
| Messages reaching **every** member, `district` | 39% | 72% | 91% | 96% | 100% |
| Messages reaching every member, `scattered` | 41% | 64% | 73% | 79% | 92% |
| Members reached, `district` | 96% | 99% | 99.7% | 99.9% | 100% |

Most members get a group message even at 32 copies; what fails is reaching the last
few. Groups of 128 did well at any count, because the members themselves carry it to
each other.

## 3. The cost is spam reach, not storage

- **Storage:** at about one message per phone per day, the busiest phone held at most
  about 165 envelopes with 96 / 255 copies, and up to 520 with unlimited copies in
  the 800-phone crowd. The carrier store holds 10,000. Storage is not the limit at this load.
- **Spam:** a junk group message, with a label nobody recognises, is carried by about as
  many strangers as it has copies: 31 at 32, 254 at 255 (where that many phones exist).
  Unlimited and hand-on 4 both spread junk to **every** phone in every scenario. Since a
  sender chooses its own copy count, the maximum allowed is what spam gets.

## 4. Hand-on 4 is unlimited flooding in practice

Every holder passing a message on 4 times reached every phone, for junk as well. It
delivers well but gives no bound at all, which confirms the review's finding that it
needs replacing.

## What this suggests (for the owner to decide)

- One-to-one: 96 copies reached 97 to 99% in the hard scenarios, 48 reached 94 to 95%.
- Group: 255, the most one byte allows, reached every member of a group of 30 in 79 to
  96% of messages.
- Either way, a spammer gets the same maximum, so the effort stamp should cost more for
  more copies (a proposal, needing review).

## Limits

- Six invented worlds. Real movement may be better or worse mixed than any of them.
- Light load: busy crowds with heavy traffic would fill links and stores sooner.
- No phones switched off, leaving for good or running out of battery.
- Receipts are instant, so one-to-one senders stop offering sooner than in reality.
