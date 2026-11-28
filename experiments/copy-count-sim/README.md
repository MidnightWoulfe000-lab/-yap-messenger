# Copy-count simulator

A small Java simulator that compares how many copies nearby messages should start with,
for one-to-one and group messages, under the rules in
[local mesh v3.1](../../docs/proposals/local-mesh-v3.1-group-envelopes.md). It has no
radios, no Android and no encryption. It models the routing rules, not Yap's code, so a
change to `MeshHub` or `core/src/relay.rs` does not change it.

Read [results/summary.md](results/summary.md) for the output and [FINDINGS.md](FINDINGS.md)
for what it shows. Every number is a model output under the assumptions below. None of
it is measured on phones.

## Run

Needs JDK 17 or later.

```powershell
cd experiments/copy-count-sim
javac -encoding UTF-8 -d build src/CopyCountSim.java
java -cp build CopyCountSim --seeds 5 --out results
```

Flags: `--seeds N` (default 5), `--only campus|town|crowd|sparse|district|scattered`, `--quick` (one
seed), `--out DIR`.

## What it compares

Eleven configurations run on the same movement for each seed. One-to-one counts vary
with groups fixed at 255, then group counts vary with one-to-one fixed at 96, so each
can be read on its own:

| Config | One-to-one | Group |
| --- | --- | --- |
| D12, D24, D48, D64 | v3.1, 12 / 24 / 48 / 64 | v3.1, 255 |
| P (the proposal) | v3.1, 96 | v3.1, 255 |
| G32, G64, G128, G192 | v3.1, 96 | v3.1, 32 / 64 / 128 / 192 |
| ASK | ask-mine, 96 | ask-mine, 255 |
| ALL | unlimited | unlimited |

- **v3.1 rules:** a holder with 2 or more copies gives half to a phone that does not
  already hold a split of it. A holder with one copy sends it to every phone it meets
  that has not been sent a last copy of it, and keeps its own. Only the recipient (one-to-one) or
  members (group) keep a last copy; members then hold one copy themselves. Everyone
  else drops it, and still takes a split later. No `DELIVERED`.
- **Ask-mine rules** (the first v3.1 draft, for comparison): a holder with one copy
  gives it only to a phone that says it is the recipient or a member (for groups,
  keeping its own). For one-to-one, the carrier that delivers deletes its copy
  (`DELIVERED`).
- **Unlimited:** every holder gives it to every phone that has not got it.
- In every rule a message lives 24 hours. The hop byte starts at 8, the sender sends it
  unchanged, and each carrier forward lowers it by one; a carrier never stores 0, a
  recipient or member may accept 0.

Spray 255 stands in for 256, because the copy count is one byte on the wire.

## Model

- **Well-mixed scenarios**, where a few busy places draw almost everyone: `campus` (150
  phones, 800 m square, 8 places), `town` (400 phones, 2.5 km, 20 places), `crowd` (800
  phones, 500 m, 5 places, people moving every 5 to 40 minutes, like a protest or
  festival) and `sparse` (60 phones, 3 km, 10 places). People's 3 usual places are
  picked by popularity, and 30% of trips go anywhere.
- **Neighbourhood scenarios**, with no busy shared places: `district` (400 phones, 4 km,
  40 places) and `scattered` (120 phones, 4 km, 30 places). People's usual places are
  the 3 nearest their home, and only 5% of trips go anywhere else.
- **Movement:** people stay at a place for the scenario's pause time, then walk (0.8 to
  1.5 m/s) to another, usually one of their usual places. While at a place, a person
  jumps to a new random spot within it about every 5 minutes, which makes new contacts
  without walking. From 23:00, anyone not walking heads home (a random point in the
  area); someone mid-walk finishes that walk first. In the morning, each person at home
  leaves at a random time, about an hour after 07:00 on average.
- **Links:** phones within 40 m link, up to 12 links each. A link lasts while the two
  stay in range; new links are made nearest first. A link moves at most 40 envelopes a
  minute in each direction, and a phone takes in at most 100 a minute (spread over the
  10-second steps). There is no slow mode and no byte accounting.
- **Messages:** one one-to-one message per phone per day, half of them to someone who
  shares a usual place with the sender. For each group size (5, 30 and 128, where there
  are enough phones), 3 groups of 10 messages each; members mostly share a usual place.
  10 junk messages per run have a group label nobody recognises. Messages are made at
  random times during the first day and the run lasts two days, so each lives its full
  24 hours. Expired messages are removed before any transfer in a step.
- **Receipts** are treated as instant: once the recipient has a one-to-one message, its
  sender stops offering it. Other carriers do not know.
- **What phones know** is exact: each phone's "already have it" answer comes from a
  perfect record, not from the `OFFER`/`WANT` exchange, with no lost frames.

## Measures

- **Reached:** the share of recipients (one-to-one) or other members (group) who got the
  message within 24 hours. **All reached** is the share of messages that reached
  everyone.
- **Delay:** hours from sending to arrival, median and 90th percentile, over everyone
  reached only (unreached recipients are not in these numbers).
- **Strangers storing:** how many phones that were not a recipient or member stored the
  message at some point. For junk this is how far spam is stored.
- **Dropped last copies:** last copies sent to phones that threw them away (v3.1 only):
  the extra radio cost of not asking who a message is for.
- **Sends per message:** envelope transmissions.
- **Carrier storage:** the most envelopes one phone held at once in its carrier store.

## Known gaps

- Real places, crowds and walking patterns are far more varied than this.
- Radio range, link set-up time and failures are simplified to a fixed 40 m and instant
  links.
- Phones never run out of battery, leave the area for good or switch nearby messaging
  off.
- No effort stamps, flood slow mode, byte limits, eviction, control-frame traffic, lost
  frames, copy reservations, or attackers other than junk messages. No lying phones.
- Load is light: about one message per phone per day.
- Five seeds and three fixed groups per size give limited independent evidence.
