# Local mesh v3.1: carrying group messages

**Status: proposal for review. The owner accepted both choices on 27 November 2026; it still needs technical review. Not implemented. Builds on v3; replaces nothing in it.**
Mesh v4 (link topology) is shelved: in simulation it made delivery worse and no fix has
been found yet (project owner, 27 November 2026). This proposal does not depend on it.

**Audience: the project owner, coding agents and maintainers.** Read with
[local-mesh-v2.md](local-mesh-v2.md) (store and forward, spray and wait, carrier
storage, flood slow mode), [local-mesh-v3-proximity-wifi.md](local-mesh-v3-proximity-wifi.md)
and the [group encryption proposal](group-encryption-v1.md), §3.9.

## Why

Today every envelope has **one** recipient, and the nearby mesh is built around that:
- Spray and wait: a carrier holding its last copy offers it only to the recipient.
- `DELIVERED`: when the recipient has the envelope, carriers delete their copies.

A group message has up to 127 recipients, and so does a batch of key hand-overs after
someone is removed (group encryption §3.9). Under the current rules the first member to
receive one would send `DELIVERED`, and every carrier would delete it before the other
members got it. Carriers also cannot tell who the members are: group envelopes carry
hourly labels only members can recognise.

## What the limits really are

The group encryption proposal measured its costs against 1,024 envelopes and 8 MiB per
phone. Those are the internet relay server's limits. A phone carrying nearby messages
holds up to **10,000 envelopes and 64 MiB**, and deletes the oldest when full (v2,
"Carrier storage"). Against those limits:

| Event, group of 128 | Envelopes | Size | Share of one phone's carrier store |
| --- | --- | --- | --- |
| A removal, every session open | 127 | about 6.7 MB | about 10% of the space |
| A removal, no sessions open yet (worst case) | about 635 | about 33 MB | about half of the space |

So one removal fits on a carrier. The open problems are the two rules above, and
eviction when many things happen at once.

## Proposed changes

### 1. Group envelopes are marked

A new nearby frame type, `GROUP_ENVELOPE` (9), with the same layout as `ENVELOPE`:
`copies (1) + effort stamp (8) + envelope`. It tells carriers only that the envelope has
many recipients. It does not say which group, who sent it, or whether it holds chat or
keys: group messages and key batches look the same.

*Trade-off for the owner:* this shows nearby strangers that a phone is sending or
carrying group traffic. Without it, carriers cannot know that `DELIVERED` must not
apply, so it is needed for group messaging to work at all.

### 2. No `DELIVERED` for group envelopes

A member never sends `DELIVERED` for a group envelope, and a carrier ignores one if it
arrives. Members only `ACK`, like a carrier. A group envelope is deleted when it
expires (24 hours) or is evicted, never because one member has it.

### 3. Copies

Spray and wait stays, with different numbers:
- the sender starts a group envelope with **32 copies** (12 for one-to-one);
- a holder with more than one copy offers half to each new link, as now;
- a holder with **one copy keeps offering it to new links**, because any link may be a
  member. Each link is offered each envelope at most once (the existing per-link offer
  tracking), and the hop budget (8) and 24-hour lifetime still apply.

A member that recognises the label takes the envelope and also carries it on with the
copies it was given, since other members may be further along. 32 is a starting value
for simulation, like 12 was.

### 4. Batches stay whole

A carrier stores a key batch as one envelope, with one relay key, and never takes
pieces out, so duplicate detection keeps working. Each member acknowledges its own piece
to the sender inside the encryption (group encryption §3.9); carriers never see those
acknowledgements. The sender resends only unacknowledged pieces in a new, smaller batch.

### 5. Eviction

When the carrier store is full, the oldest envelopes go first, whatever their kind, as
now. Group envelopes get no priority, because carriers cannot tell key batches from chat
without revealing which is which. A batch lost to eviction is repaired by the
resynchronisation in group encryption §3.7, more slowly.

### 6. Flood slow mode

A removal makes up to 127 members each send a batch within a short time. A phone in the
middle of a crowd can exceed the phone-wide limit (100 envelopes a minute) and go into
slow mode. Refused envelopes get no `ACK` and are retried, so this delays them but does
not lose them. The limits stay as they are until a simulation shows whether this delay
matters.

### 7. Effort stamps

Each group envelope needs its own stamp (18 bits, about 0.3 to 1 second on a phone).
Batching keeps this small: a removal costs each member 1 to 5 stamps, not 126.

## Not covered

- **The internet relay server.** It stores envelopes by destination mailbox, which has no
  meaning for group envelopes. Group traffic over the internet relay needs its own design.
- **Tor.** Not designed.
- **Measurement.** The copy count, the flood limits under a removal burst, and the share
  of carrier storage used in a busy crowd all need simulation, then real phones.

## Decisions (project owner, 27 November 2026)

1. Group envelopes are marked as group traffic (change 1): **accepted**.
2. Group envelopes start with 32 copies (change 3): **accepted**, as a starting value
   to tune in simulation.

## Tests required

- A group envelope reaches all members of a simulated group of 5, 30 and 128 through
  carriers, with no `DELIVERED` deleting it early.
- A carrier ignores `DELIVERED` for a group envelope.
- A one-copy holder still offers a group envelope to a new link, and never twice to the
  same link.
- A key batch is never split by a carrier, and keeps one relay key.
- A removal burst from 127 senders through one carrier: slow mode delays but loses
  nothing.
- Eviction of a batch is repaired by resynchronisation.
- One-to-one envelopes behave exactly as before.
