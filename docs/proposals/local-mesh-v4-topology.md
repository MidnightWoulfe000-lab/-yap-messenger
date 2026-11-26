# Local mesh v4: link topology for large crowds

**Status: draft proposal; the owner answered the open questions on 26 November 2026 (see
[Decisions](#decisions-26-november-2026)). Nothing here is implemented. Parameter values
are starting guesses to be tested in simulation, not decisions. The cryptographic details
still need a written specification and review before any code.**

**Audience: the project owner, coding agents and maintainers.** Read with
[local-mesh-v2.md](local-mesh-v2.md) (link IDs, privacy rule) and
[local-mesh-v3-proximity-wifi.md](local-mesh-v3-proximity-wifi.md) (Aware and Direct).

## Why

Today every phone links to whichever Yap phones it finds first, until it hits a cap
(`MeshHub.maximumLinks` = 12, plus the radio limits below). With two or three phones that
is fine. In a crowd of hundreds it goes wrong in three ways:

- **Islands.** Each phone fills its slots with its nearest neighbours, and those
  neighbours link to each other. The result is tight groups full of triangles, with few
  or no links between groups. A message cannot leave its group.
- **No room for newcomers.** Once every phone is full, a phone that walks into the crowd
  finds nobody with a free slot and stays cut off.
- **Wasted long-range slots.** Wi-Fi Aware reaches much further than Bluetooth but has
  few slots. Spending them on phones Bluetooth already reaches throws that range away.

## Goals

1. One connected mesh across the crowd, with short paths between any two phones.
2. A phone arriving at a full crowd can always join within seconds.
3. The mesh repairs itself when phones leave or move.
4. No new permanent or trackable data sent to strangers (the v2 rule).
5. Bluetooth links that already work are never disturbed for an attempt that may fail.

## What we know about the radios (measured 26 November 2026)

| | Links per phone | Shared with | App can ask the phone? |
|---|---|---|---|
| Wi-Fi Aware | 8 data paths, 1 data interface (Pixel 9 Pro XL and Nothing Phone A063) | Other apps using Aware (rare) | Yes: `Characteristics`, Android 13+ |
| Bluetooth LE | About 7 on many phones; varies by chip | Every Bluetooth gadget (earbuds, watch, car) | No |

- A Nothing Phone A063 cannot **start** a new Aware data path while it is joined to a
  Wi-Fi network. An existing Aware link survives it rejoining. The Pixel has no such limit.
- Wi-Fi Aware and Wi-Fi Direct cannot talk to each other.

## Proposal

Four rules that work together. The 2-hop rule is the core; the others make it safe.

### 1. A target number of links, below the maximum

Each phone aims for **T** links (starting guess: 4 to 6), not as many as it can get. It
never goes above a hard cap **M**: the lower of `MeshHub.maximumLinks` and what its radios
allow.

### 2. The 2-hop rule (no triangles)

Each phone knows its direct neighbours and **their** neighbours: everything within two
hops. It does not link directly to a phone that is already within two hops. A phone
three or more hops away is allowed.

Example: A is linked to B and C, and B is linked to D. A and D are two hops apart through
B, so A refuses a direct link to D. If later E links to D and F links to E, A may link to
E (three hops) or F (four hops).

Why it helps: islands are made of triangles. Forbidding links between phones that
already share a neighbour pushes every new link towards phones far away in the mesh,
which are exactly the links that join islands into one network.

Details:

- **Both sides agree.** A sees D at two hops through B, and D sees A at two hops through
  B, so they reach the same decision without negotiating.
- **Decide before connecting where possible.** Bluetooth discovery (GATT read) and Aware
  discovery (publish info) already reveal the other phone's link ID, so a phone can skip a
  forbidden peer before spending a connection on it. A phone receiving a connection
  checks again after HELLO and closes it with a logged reason.
- **The rule lifts at once when the path breaks.** If B leaves, A and D are no longer two
  hops apart and may link. Neighbour information updates on every link change (below),
  so this needs no timer.
- **Soften it when slots are free.** A phone below its target T accepts anyone, 2-hop or
  not. The rule only applies once a phone is at or above T. This keeps small groups
  well connected and only shapes the mesh when slots are scarce.
- **Exception: a weak path (decided).** If the route to a 2-hop phone runs over a weak
  link, a direct link is allowed. "Weak" is still to define (see
  [Still to define](#still-to-define)).
- **Exception: a single bridge (decided).** If B is the *only* route between A and D,
  losing B splits them, so A may also link to D directly, unless A is close to its cap.
  Near the cap, free slots are kept for farther connections.

### 3. A reserved slot for newcomers

Each phone keeps **R** slots free (starting guess: 1). When a newcomer takes the reserved
slot, the phone drops its least useful other link to free a slot again. "Least useful":
a link to a phone that is also reachable another way within two hops, oldest first.
A newcomer can therefore always get in, and the mesh keeps rewiring.

### 4. Rewiring through link ID rotation (decided)

Random rewiring is what keeps large networks connected and paths short. Link IDs already
rotate every 10 to 20 minutes for privacy, and every link drops and re-forms then, with
the 2-hop rule and the reserved slot shaping the new links. That is the rewiring; there
is no separate timer. Only if the simulation shows the mesh still clumps between
rotations should a separate rotation every **P** minutes (starting guess: 5 to 10, with
jitter, never a phone's only route to anyone) be reconsidered.

### Radio roles

- **Bluetooth** carries links to close neighbours. It is the baseline: almost every phone
  has it.
- **Wi-Fi Aware** carries long links: to phones Bluetooth cannot reach, or reaches only
  with weak signal (signal strength is available at discovery). These are the links the
  2-hop rule favours, and they are what turn islands into one mesh.
- **Aware takes over from Bluetooth when Bluetooth is weak or unreliable (decided).** A
  strong, stable Bluetooth link stays on Bluetooth, so Aware slots go to distance. Today's
  code never lets Aware replace Bluetooth; this changes that once "weak" is defined.
- **Wi-Fi Direct** stays a fallback for phones without Aware.

## Privacy

The 2-hop rule needs each phone to tell its direct neighbours who it is linked to. That
is new information sent to strangers: it reveals who is near whom. The IDs are random and
rotate, but the shape of the network can still help traffic analysis.

Proposed way to send less:

- B never sends a readable list. For each neighbour it sends a short digest:
  `truncate(HMAC(link key, neighbour link ID))`, where the link key is derived from both
  HELLOs on the A–B link (both link IDs and nonces). A can test "is D one of B's
  neighbours?" for a phone it is already considering, but cannot read the list.
- Digests change with every link and every ID rotation, so they are not linkable over
  time.
- Sent only to direct neighbours, at most once per link change, capped at the link
  limit (12 entries).

**Known limits, stated plainly:**

- Links are not encrypted at the link layer, and link IDs are broadcast in Bluetooth and
  Aware adverts. Without further protection, someone nearby who records adverts **and**
  sniffs the A–B link can recompute the link key and test every advertised ID. That
  rebuilds the local graph. The owner asked for the exchange to be encrypted to close this
  for passive listeners (decision 1). An active attacker in the middle of an
  unauthenticated link would still see it.
- A rejection itself tells the rejected phone "you are within two hops of me".
- The owner accepted sharing digests under the v2 rule (decision 1). Per
  [AGENTS.md](../../AGENTS.md), no cryptographic detail here is decided until the scheme
  is specified and reviewed.

**Fallback without any sharing:** drop the 2-hop rule, keep target T, reserve R and
random rotation. That has weaker shaping but sends nothing new. The simulation should
measure how much the 2-hop rule actually adds over this.

## Protocol change (if approved)

- New frame type `NEIGHBOURS` (9) in `MeshFrames`: a list of digests, at most 12, sent on
  link up and when the sender's links change, rate-limited.
- `MeshHub` keeps, per link, the latest digest set and answers "is this ID within two
  hops?". Peers on older builds simply never send it and count as having no neighbours.
- Transports ask the hub before dialling a discovered phone.

## How to test

You cannot test hundreds of phones by hand, so in order:

1. **Simulation (new work).** A small JVM simulator, for example
   `experiments/mesh-topology-sim`, reusing `MeshHub`'s rules with no radios. Hundreds
   of phones, positions and movement, Bluetooth and Aware ranges, slot limits. Compare
   today's "first come" behaviour with each rule above, alone and together. Measure:
   - the share of phones in the largest connected group
   - average and longest hop count between phones
   - how long a newcomer takes to join
   - how the mesh recovers when a share of phones leave

   The existing `experiments/signal-mesh` package is a cryptography test, not a network
   simulator, so this would be new.
2. **Pick T and R** from the simulation results, and check whether link ID rotation
   alone rewires the mesh enough (decision 4).
3. **Phones.** Three or more phones in a line, then a small group. Check the nearby log
   for rejections, rotations and the reserved slot. Note that multi-hop delivery has not
   yet been tested on physical phones at all.

## Decisions (26 November 2026)

The owner's answers to the questions this draft raised. Where the answer leaves a detail
to define, the detail is listed under [Still to define](#still-to-define).

1. **Neighbour digests: yes, encrypted if possible.** Sharing neighbour digests with
   direct neighbours is acceptable under the v2 rule. The exchange should also be
   encrypted so that a passive listener cannot recompute the digests (see the known
   limits under [Privacy](#privacy)). One direction to specify: an ephemeral key
   agreement on every link (fresh keys per link, never reused, nothing permanent) that
   encrypts the `NEIGHBOURS` frame. That stops passive sniffing but not an active
   attacker sitting between two phones, because links are unauthenticated. The scheme
   needs a written specification and review before code, per [AGENTS.md](../../AGENTS.md).
2. **Refuse links within two hops, unless the existing path is really weak.** If the
   route to a 2-hop phone runs over a weak link, a direct link is allowed.
3. **A second route to a phone reachable through only one neighbour: yes, unless the
   phone is close to its cap.** Near the cap, free slots are kept for farther
   connections.
4. **No separate link rotation: link ID rotation does the rewiring.** Link ID rotation
   already drops and re-forms every link every 10 to 20 minutes, and the 2-hop rule and
   reserved slot shape the links as they re-form. A separate "drop one link every few
   minutes" would add interruptions for little gain. The simulation should still confirm
   that ID rotation alone rewires the mesh enough.
5. **Aware takes over from Bluetooth when Bluetooth is weak or unreliable.** That is what
   Aware is for. A strong, stable Bluetooth link to a close phone stays on Bluetooth, so
   Aware slots are kept for distance.

## Still to define

- **What counts as "weak" or "unreliable".** Candidates, to be tuned in simulation and on
  phones:
  - Bluetooth signal strength (RSSI) below a threshold, measured at discovery and on the
    link.
  - A link that dropped and re-formed several times within a few minutes.
  - For decision 2, a phone only measures its own links. Judging the far half of a route
    (B to D) would need B to share a coarse quality flag with its digests.
- **"Close to its cap"** for decision 3: for example within one slot of the reserve.
- **The encryption scheme** for decision 1: key agreement, cipher, and how it fits the
  existing frame format. To be specified and reviewed separately.
