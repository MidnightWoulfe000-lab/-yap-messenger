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

Six configurations run on the same movement for each seed:

| Config | One-to-one | Group |
| --- | --- | --- |
| A | spray 12 (today) | spray 32 |
| B | spray 24 | spray 64 |
| C | spray 48 | spray 128 |
| D | spray 96 | spray 255 |
| E | unlimited | unlimited |
| F | spray 12 | hand-on 4 |

- **Spray N (one-to-one):** the sender starts with N copies. A holder with more than one
  copy gives half to a phone that has not got it. A holder with one copy gives it only to
  the recipient. The carrier that delivers deletes its copy.
- **Spray N (group):** the same, except that a holder with one copy gives a copy to any
  group member it meets and keeps its own. Members pass it to other members the same
  way.
- **Unlimited:** every holder gives it to every phone that has not got it.
- **Hand-on 4:** every holder, members included, passes it on at most 4 times.
- In every rule, no phone is sent a message it already has, and a message travels at
  most 8 hops and lives 24 hours.

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
  1.5 m/s) to another, usually one of their usual places. From 23:00 to
  07:00 everyone is at home, at a random point in the area, and leaves home at a random
  time after 07:00.
- **Links:** phones within 40 m link, nearest first, up to 12 links each. A link moves
  at most 7 envelopes per 10 seconds (40 a minute), and a phone takes in at most 17 per
  10 seconds (100 a minute).
- **Messages:** one one-to-one message per phone per day, half of them to someone who
  shares a usual place with the sender. For each group size (5, 30 and 128, where there
  are enough phones), 3 groups of 10 messages each; members mostly share a usual place.
  10 junk messages per run have a group label nobody recognises. Messages are made at
  random times during the first day and the run lasts two days, so each lives its full
  24 hours.
- **Receipts** are treated as instant: once the recipient has a one-to-one message, its
  sender stops offering it. Other carriers do not know and keep it until they meet the
  recipient or it expires.

## Measures

- **Reached:** the share of recipients (one-to-one) or other members (group) who got the
  message within 24 hours. **All reached** is the share of messages that reached
  everyone.
- **Delay:** hours from sending to arrival, median and 90th percentile, over everyone
  reached.
- **Strangers carrying:** how many phones that were not a recipient or member held the
  message at some point. For junk this is how far spam spreads.
- **Sends per message:** envelope transmissions.
- **Carrier storage:** the most envelopes one phone held at once in its carrier store.

## Known gaps

- Real places, crowds and walking patterns are far more varied than this.
- Radio range, link set-up time and failures are simplified to a fixed 40 m and instant
  links.
- Phones never run out of battery, leave the area for good or switch nearby messaging
  off.
- No effort stamps, flood slow mode beyond the per-link and per-phone limits, eviction,
  or attackers other than junk messages.
- Load is light: about one message per phone per day.
