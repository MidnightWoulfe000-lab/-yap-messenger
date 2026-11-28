# Proposal: encrypted pairing

**Status: draft for review; do not implement.** Revised after Codex's fifth, sixth, seventh and eighth reviews of the
v3.1 update (28 November 2026) and needs another review. Per `AGENTS.md` this is a
cryptographic protocol change and must be reviewed before any code is written. It answers finding 5
of the [second Codex review](../audit/2026-11-27-local-mesh-v3.1-second-review.md) of
27 November 2026, and ships in the same update as
[local mesh v3.1](local-mesh-v3.1-group-envelopes.md) and
[rotating address labels](rotating-address-labels.md). Every phone is re-paired in that
update, so there is no older pairing to stay compatible with.

**Audience: reviewers, maintainers and coding agents.** Read with the pairing section
of the [endpoint contract](../android-endpoint-v1.md).

## Problem

Pairing travels over local links that do not encrypt it:
- Bluetooth uses `createInsecureL2capChannel()` and `listenUsingInsecureL2capChannel()`
  (`MeshBluetooth.kt`).
- Wi-Fi on a shared network uses ordinary TCP sockets (`MeshWifi.kt`).
- Wi-Fi Aware tries an open data path first, then a passphrase written into the app,
  which gives no secrecy (`MeshWifiAware.kt`).

So someone listening nearby while two people pair can read everything pairing sends:
both public bundles, both signed confirmations, and the first libsignal message, which
shows the sender's identity key. Identity keys never change, so this gives a listener
a permanent identifier for each person and proof that the two paired. A phone sitting
in the middle of the link and passing traffic on can read it too.

The QR codes are not the problem: they are shown on screen to the person pairing.

## Goals

1. Everything pairing sends over a radio is encrypted, from the first byte after the
   phones find each other.
2. Only the two phones whose QR codes were scanned can complete it. A phone in the
   middle that has not seen both QR codes cannot read or change it.
3. Pairing sets up the pair secret for [rotating address labels](rotating-address-labels.md)
   §1, so no later message is needed to install it.
4. Established, published protocols and maintained libraries only. Nothing new is
   designed here beyond how they are joined to today's pairing.
5. No extra steps for users: they still scan each other's QR and confirm.

Non-goals: protecting against someone who sees both screens (they could also just
watch the two people); hiding that two Yap phones are talking (v2 already accepts that
a phone running Yap is visible); proving the other phone is physically close.

## Proposed design

### 1. A pairing key in each QR

Each QR gains a fresh random 32-byte **pairing key**, made from the operating system's
secure random source for each attempt. The QR format becomes `yap:v2:` plus
base64url-no-padding of `YPR2`, the 33-byte public identity, the 16-byte attempt nonce,
the bundle hash, the 16-byte destination token (all as today) and the 32-byte pairing
key. That is 32 bytes more than today's QR. The key expires with the attempt (ten
minutes) and is never reused.

Both people scan each other's QR, as today, so both phones then know both pairing
keys. A radio listener knows neither.

```
psk = HKDF-SHA256(ikm  = pairing key of smaller identity || pairing key of larger identity,
                  salt = "Yap pairing psk v1",
                  info = attempt nonce of smaller identity || attempt nonce of larger identity)
                                                                         -> 32 bytes
```

"Smaller" compares the canonical 33-byte identity keys as unsigned bytes, as elsewhere.

### 2. A Noise handshake on the pairing link

The phones find each other's link much as today, where each sends the 32-byte bundle
commitment from the scanned QR in `BUNDLE_REQUEST` to the links it has; in this update
that frame becomes `PAIR_FIND` (below). The destination token is not used for this (the
hub's `HELLO` carries only a rotating ID and a nonce). Before sending anything else on
the link, they run the published Noise Protocol Framework handshake
**`Noise_NNpsk0_25519_ChaChaPoly_SHA256`** (revision 34), with the phone holding the
smaller identity key as initiator and `psk` from §1. The prologue is
`"Yap pairing v2"` followed by both QR payloads in identity order, where a QR payload
is the **decoded raw bytes** (`YPR2` and the fields after it, 133 bytes), not the
`yap:v2:` text or its base64url form and with no length prefixes, so a handshake only
succeeds between the two phones that scanned each other's codes.

- `NN` means neither side uses a long-term key in the handshake. Identities are still
  proved by today's signed confirmations (§3), so no new identity key handling is
  added.
- `psk0` mixes the pre-shared key in from the first message, so a phone in the middle
  without both QR codes cannot complete the handshake or read anything after it.
- Fresh ephemeral keys on both sides mean that a later leak of the QR contents does not
  reveal a recorded pairing, provided the ephemeral keys were deleted.

The implementation uses a maintained Rust Noise library inside the endpoint, pinned and
reviewed like libsignal, not hand-written code. The candidate, suggested by the review,
is `snow = "=0.10.0"` with its 25519, ChaChaPoly and SHA256 resolver and a locked
dependency graph; its Android build and dependencies still need checking. Libsignal's
identity keys are never used as Noise keys. The first handshake message necessarily
shows its fresh ephemeral public key and the frame sizes; everything after the
handshake is encrypted.

**Frames.**
- **Finding the link: `PAIR_FIND` (7),** replacing today's `BUNDLE_REQUEST` at the same
  type number. It carries only a format version (1) and the 32-byte bundle commitment
  from the QR the sender scanned, which is scoped to this attempt: it changes with every
  QR and is erased when the attempt is cancelled or its resume window ends. It is sent
  to the links the phone has while the attempt is in Scanned state or resuming, at most once per
  link per 10 seconds. A phone that receives a `PAIR_FIND` matching its own current QR,
  for an attempt it has also scanned, answers by starting the handshake on that link.
  Anything else is ignored. Nothing else about the person travels in it.
- **`PAIR_HANDSHAKE` (17) and `PAIR_DATA` (18),** each holding one Noise message,
  accepted only on a link where a `PAIR_FIND` matched (below). There is no other way
  in: resuming also starts with a matching `PAIR_FIND`.

**Finding and dispatch** (fifth review finding 5). Each attempt keeps two discovery
values: its **local commitment** (from its own QR, which the other phone sends) and its
**remote commitment** (from the QR it scanned, which it sends). A phone looks up an
incoming `PAIR_FIND` in two small lists:
- the **shown QR**: the local commitment of the QR on screen now, while its attempt is
  Scanned;
- **retained attempts**: the local commitment of each attempt in its resume window,
  at most 2. If a third attempt reaches Confirmed, the oldest retained one ends early
  and is erased as at its deadline.

Opening a new QR does not remove retained attempts. A `PAIR_FIND` body is exactly 33
bytes (version 1, then 32 bytes); any other length or version is ignored before
lookup. A match is on all 32 bytes, against at most 3 values, and **binds that link to
exactly one attempt**. A `PAIR_HANDSHAKE` on a link with no bound attempt is dropped
without any cryptographic work. On a bound link only that attempt's `psk` is tried,
and the attempt's state picks which handshake runs: the original (prologue as above)
if it is Scanned, the resume (§3) if it is Confirmed or later. Handshake messages carry
no payload, so each is exactly 48 bytes (32-byte ephemeral key plus 16-byte tag); any
other length is dropped. **This is a length check Yap makes itself before handing the
message to `snow`**, because `snow` accepts a valid 49-byte message (the gate 4 run
showed this). A wrong `psk` or prologue fails silently at the responder, so the
initiator shows "Couldn't connect. Check you both scanned the right code" after 30
seconds without an answer, kept apart from "other phone not found". A bound link gets at most one handshake start per 10 seconds,
and an attempt has at most 4 bound links at once. If two links both complete a resume
handshake, the first to finish wins and the other is unbound. Nothing in these frames
shows a permanent identity.
- Today's cleartext `BUNDLE` and confirmation frames are removed, not kept as a
  fallback. Two attempts at once on the same link: the one started
by the smaller identity key continues, the other is cancelled. The display name that
today travels after the bundle now travels inside the channel too.

### 3. Pairing inside the channel

After the handshake, every pairing message is a Noise transport message on that link:
1. Each phone sends its public bundle. It must match the bundle hash in the QR, as
   today.
2. The initiator sends the 32-byte random pair secret and the starting week (rotating
   address labels §1).
3. Each phone sends its signed confirmation. Today's confirmation signs the two
   complete bundles; it now also signs the Noise handshake hash (32 bytes, Noise's
   channel binding value, saved before switching to transport messages), the starting
   week, and SHA-256 of `"Yap pair secret v1\0"` followed by the pair secret. A
   confirmation therefore covers this channel, this week and this pair secret, and
   cannot be replayed into another. Equal identities, a QR that does not match, a
   second pair secret and a confirmation from another attempt are refused.
4. Bootstrap, as today, but inside the channel: the initiator starts PQXDH and sends the
   first libsignal message (kind 03), and the responder answers with its exact cached
   acknowledgement (kind 04).

**States and the finish line.** Each phone moves through:

| State | Reached when | If the link drops or the attempt fails |
| --- | --- | --- |
| Scanned | Both QR codes scanned | Cancel |
| Handshake | Noise handshake under way | Cancel |
| Exchange | Bundles, secret and week exchanged | Cancel |
| Confirmed | Both confirmations valid; contact saved as "finishing" | Resume (below) |
| Ready | Responder: kind 03 accepted. Initiator: kind 04 received | Nothing to do |

- **Cancel** deletes the attempt, its reserved pre-keys, any "finishing" contact and
  every queued pairing packet in one transaction. Nothing from a cancelled attempt is
  ever sent later.
- **The resume window** runs to the attempt's ten-minute deadline, **whatever either
  phone's state**, including after it is Ready. Until then each phone keeps, saved so
  it survives a restart: the `psk`, the original handshake hash, both identity keys and
  their roles, both discovery values (the local commitment it answers to and the remote
  commitment it sends in `PAIR_FIND`), and the exact cached bootstrap bytes it
  may need to resend (the initiator's kind 03, the responder's kind 04). At the
  deadline all of these are erased, whatever the state.
- **Resume:** within the window, a phone that is not Ready, or a Ready responder asked
  again, finds the other with `PAIR_FIND` on any local link and runs a fresh Noise
  handshake with the same `psk`, the same roles (the smaller identity key is still the
  initiator) and the prologue `"Yap pairing v2 resume"` followed by the original
  handshake hash and both identity keys in order. Then it sends whatever bootstrap step
  is missing, using the exact cached bytes as today's retry rules require. After a
  successful resume handshake, the attempt's queue moves to that link. Bootstrap
  retries go only through the pairing queue, never the normal outbox.
- **At the deadline,** a phone that is not Ready cancels and shows "Pairing didn't
  finish. Scan each other again."
- **One side Ready, the other cancelled.** This can happen if the responder accepted
  kind 03 but the initiator never received kind 04. The responder then has a contact
  the initiator does not. Until the responder receives any authenticated message from
  that contact after Ready, it shows the contact as "Finishing pairing". After 24 hours
  without one, it shows "Pairing may not have finished. Scan each other again", and
  re-pairing replaces the contact. Neither phone can know for certain that the other
  committed its last step; this rule makes the gap visible instead of hiding it.
- **Restart:** a restart before Confirmed cancels the attempt. After Confirmed, the
  saved resume state lets either phone resume within the window.

All pairing packets stay in a queue bound to the attempt's current authenticated link:
the first one, or the link of the latest successful resume handshake (rotating address
labels §2). When the queue moves, packets not yet handed to the old link are moved;
packets already handed to it count as possibly sent and are sent again on the new link
from the exact cached bytes, which the other phone handles as a repeat. The old link is
then unbound, and pairing frames arriving on it are ignored.

Tests for finding and dispatch: a Ready phone restarted with no QR open, then asked to
resume; a new QR opened while an older attempt is still in its window; queued packets
on a link that is replaced; two resume links at once; a third attempt pushing out the
oldest retained one; a `PAIR_HANDSHAKE` on an unbound link; wrong-length frames.

Transport messages are limited to Noise's 65,535 bytes; a public bundle is 1,883 bytes.

### 4. The pairing file is removed

Today a bundle can also travel in an explicitly shared pairing file. It cannot be made
confidential in this update without a design of its own, so it is **removed** (owner,
28 November 2026). People who cannot meet to scan can still become friends if they
share a group (§5).

### 5. Adding a friend through a group

Decided (owner, 28 November 2026): two members of the same group can become friends
without meeting, through the group's own encrypted connection between them.

1. A taps "Add as friend" on B in the group's member list. A's phone sends B a
   **friend request** as a group piece (group encryption §3.11), over their existing
   one-to-one group session: a random request ID, A's display name, a random 32-byte
   pair secret, the starting week, a **created** time (A's working time, local storage
   §8) and an **expires** time exactly 7 days later. A signs the format version, request
   ID, both identity keys, display name, starting week, created, expires and SHA-256 of
   `"Yap friend secret v1\0"` followed by the pair secret, so it cannot be moved to
   another pair or changed. It does not sign a group: the request belongs to the pair,
   and a group is only the route it travels by (fifth review finding 6). B accepts it
   only through a group that, in B's own copy of the log, has both A and B as current
   members; otherwise it is ignored.
2. B sees "<A> from <group> wants to add you as a friend" (the group it first arrived
   through) and accepts or ignores it. Accepting sends a signed **accept** (format
   version, request ID, both identity keys, B's display name and the request's expires
   time) as a piece back to A, through any group they both still share. B saves A as a
   "finishing" contact with that secret and week.

   **Which bundle the contact set-up uses** (seventh review finding 7, revised for
   eighth review finding 5). Both phones know from the start which of them will be the
   responder (the larger identity key). The responder **names its bundle in its own
   signed message**: in the request if the requester is the responder, in the accept
   otherwise. That message carries the responder's current monthly bundle in full (the
   same signed bytes as in the group log, about 1.9 KB), so the initiator never depends
   on a possibly stale log. **From the moment the responder signs that message, it
   pins that bundle:** its private pre-keys and used-base-key record are kept until the
   request's signed expiry by the responder's working time plus 2 days (the clock
   rule's allowed skew), even if the monthly bundle is replaced meanwhile (group
   encryption §3.2). Because the pin is made before the initiator can learn the bundle,
   the initiator can never use an unpinned one. The initiator builds kind 03 only from
   the named bundle, after checking its signature. If the responder no longer has the
   named bundle's keys (a reset), it refuses kind 03, and the request ends at expiry
   with "Friend request didn't finish". A crossed request keeps only the winner's
   named bundle pinned; when a request is cancelled or expires, its pin is released
   unless another live request names the same bundle. At most 25 pending requests can
   each pin one bundle. No new key exchange is involved: this only keeps an existing
   pre-key longer. Tests: a request made just before a monthly replacement and not yet
   received by the responder; an initiator with a stale group log; the largest allowed
   clock difference; crossed requests; cancellation on one phone; kind 04 lost and a
   restart; the last shared group lost.
3. When A receives the accept, it saves B the same way. The two then set up their own
   one-to-one contact session from each other's bundles in the group log (the smaller
   identity key initiates), with bootstrap messages travelling as group pieces. When
   bootstrap finishes, both are Ready and use rotating labels like any contact.
4. **Lost messages:** A resends the same request (same signed bytes) at most once every
   6 hours until it gets an accept or the request expires; B answers a repeated request
   with the same cached accept. **Bootstrap retries** follow the same route and pace,
   not §3's ten-minute window: the initiator keeps its exact kind 03 bytes and resends
   them until it receives kind 04, then stops (it is Ready); the responder, **even
   after it is Ready**, keeps its exact cached kind 04 and answers a repeated kind 03
   with it until the request expires (sixth review finding 6).

   **How bootstrap travels on this route.** Today's kind 03 and kind 04 are bound to a
   mesh envelope that expires in 24 hours, so their exact bytes cannot simply be
   carried for 7 days. Today the binding is inside the authenticated plaintext (the
   endpoint contract: `YEP1`, then the 68-byte envelope header binding, then the
   message). On this route the authenticated plaintext of kind 03 and kind 04 carries,
   in place of that envelope binding, a route binding: `"Yap friend bootstrap v1"`,
   the request ID and the request's signed expiry. The receiver checks both against
   the stored request before acting, and the creation-time and 24-hour checks are
   replaced, on this route only, by "the request has not expired". Each resend travels
   in a new group piece dated for its own hour; the inner bytes stay exactly the same.
   Ordinary envelopes keep their 24-hour lifetime; nothing else is extended. This
   changes only what Yap puts in its own plaintext; no new cryptography.

   **At expiry with one side Ready.** If B is Ready but A reached expiry and deleted
   its "finishing" contact, B shows A as "Finishing" until any authenticated message
   from A arrives. If none has arrived by expiry, B shows "Friend request may not have
   finished. Send a new request", and a new request from either replaces that
   contact. As in §3, neither phone can know for certain the other committed its last
   step.

   Tests: kind 04 lost and then the responder restarted; the first kind 03 delivered
   more than 24 hours after it was made; expiry reached on one phone first; the last
   shared group lost before Ready.

**One request per pair.** Friend requests belong to the two identities, not to a group:
- **Crossed requests.** If A has asked B and B has asked A, both have agreed, so the
  request from the **smaller identity key wins**: its request ID, secret and week are
  the only ones used. The other phone drops its own request, accepts the winner's
  without asking again, and a late accept for the losing request is ignored. So both
  phones always install the same secret.
- **Several shared groups.** The same signed request arriving through a second group is
  the same request: it is answered with the cached accept, never a second secret. A
  different request ID for a pair that already has a live request follows the crossed
  rule above if it is from the other phone, and is ignored if it is from the same
  phone.
- **Already friends:** a request between existing contacts is ignored.

**The friend request's own finish line:**

| State | Reached when |
| --- | --- |
| Requested (A) | Request sent |
| Accepted (B) | B accepted; A saved on B as "finishing" |
| Accepted (A) | Accept received; B saved on A as "finishing" |
| Bootstrap | Contact session being set up through group pieces |
| Ready | Bootstrap finished on that phone, as in §3 |

- **Deadline:** the signed expires time, the same on both phones. B refuses a request
  that has already expired by its own working time, or whose created time is more than
  2 days ahead of it (the clock rule's threshold). A phone not Ready by expiry deletes
  the "finishing" contact and the request, and shows "Friend request didn't finish".
  An accept or bootstrap message that arrives after expiry is ignored.
- **Leaving the group before Ready** cancels it the same way only if the two phones no
  longer share any group, since bootstrap travels as group pieces; otherwise it carries
  on through another shared group. After Ready, leaving changes nothing.
- **Replays:** request IDs, with their cached accepts, are remembered until 30 days
  after the request expires. After that, a replayed request is refused anyway because
  it has expired, and working time does not go back (local storage §8; the one accepted exception is up to 1 hour after the carrier store is recreated), so an old
  request is never accepted again, including after the friend was deleted. The one
  exception is the open limit of a restored older copy of the whole store. To become
  friends again, one of them sends a new request.
- Tests: crossed requests sent through different groups; the same request through two
  groups; leaving one of two shared groups before Ready, and the only one; a request
  first delivered 6 days after it was made; kind 04 lost, then a restart; a replay
  after the remembered ID has been dropped; a request whose created time is far ahead.
- **Space:** accepting needs a free contact place (128); if there is none, the app says
  "Contacts full" and the request stays waiting. A phone keeps at most 5 outgoing and
  20 incoming requests across all groups; further incoming ones are dropped. It sends
  at most 1 a day to the same member.
5. **How it shows:** "Added through <group>, not checked in person". If they later scan
   each other's QR codes in person, the app checks the keys match and shows "Checked in
   person". The difference matters: in a group, you trust the group's record of who B
   is, which starts with whoever invited B (group encryption §3.3).

Other members cannot read a friend request (it is a one-to-one piece), and strangers
see only group labels. Leaving or being removed from the group does not end the
friendship. A friend request never travels outside the group's connection, and the
contact session it creates is separate from the group session (choice 4 of the group
proposal still holds: joining a group does not make anyone contacts by itself).

## What this does and does not protect

- **Protected:** bundles, identity keys, confirmations, the pair secret and the first
  libsignal message, against radio listeners and against a phone in the middle that has
  not seen both QR codes.
- **Not protected:** someone who sees both screens or photographs both QR codes during
  the attempt; the fact that two phones are exchanging pairing-sized traffic; the
  bundle commitments used to find the link, sent in `PAIR_FIND`: short-lived values
  that link one attempt's broadcasts, never a permanent identity.
- **Not proved:** that the other phone is physically present. A relay that has both QR
  codes, for example from a camera, could pass the pairing between two places.

## Risks and open questions for the reviewer

1. Is `Noise_NNpsk0` with a QR-derived pre-shared key the right pattern, given that
   identities are proved afterwards by signed confirmations? Would `XXpsk3` with the
   identity keys be better, and is it worth the extra key handling?
2. Is binding the confirmations to the Noise handshake hash enough to tie them to this
   channel?
3. `snow` 0.10.0: dependencies and Android build.
4. The finish-line rules (§3), especially the one-side-Ready case.
5. Adding friends through a group (§5).
6. The value used to find the pairing link: it should be scoped to the attempt,
   erased at the resume deadline or on cancellation (not on completion, since a phone
   may still need to resume until then), and never fall back to a permanent token.

## Test plan

- Fixed test vectors for `psk`, the prologue and a full handshake. One prologue vector
  from the gate 4 experiment (identities `02 0a…` and `03 15…`; test data in
  `experiments/noise-pairing/tests/noise.rs`): the 280-byte original prologue has
  SHA-256 `958e39c34212fc9f3f2b55c45d542575f0bc1860e774ffdea67562fb4950b05f`, with the
  `psk` `fb574fe0abfae561de6fb3e1359eaa3431cff45bceef6cb0232579893b335984`. The
  prologue hash was worked out from that test data, and not copied from a saved
  run output.
- Wrong `psk` or prologue: the initiator shows "Couldn't connect. Check you both
  scanned the right code" after 30 seconds, not "other phone not found".
- A full pairing on Bluetooth, shared-network Wi-Fi and Wi-Fi Aware, with a capture of
  every byte on the link: no identity key, bundle, confirmation or pair secret appears
  in clear.
- A phone in the middle with one QR code, or none, cannot complete the handshake.
- A changed bundle, a confirmation from another attempt, a replayed handshake and a
  second pair secret are all refused.
- A link closing at each step before Confirmed cancels the attempt and leaves nothing
  behind; after Confirmed, a new link within ten minutes resumes and finishes; past the
  deadline a phone that is not Ready shows "Pairing didn't finish", while a Ready phone
  keeps its contact (the one-side-Ready case below); a cancelled attempt never sends a
  packet later; a restart before Confirmed cancels, and after Confirmed the attempt
  resumes from its saved state.
- Responder Ready while the initiator cancelled: the responder shows "Finishing
  pairing", then the re-pair prompt after 24 hours.
- Friend through a group: request, accept, lost accept (repeated request gets the
  cached accept), ignored request expiring, the limits, and "not checked in person"
  changing after a QR scan; crossed requests install the smaller identity's secret on
  both; the same pair through two groups gets one secret; leaving one of two shared
  groups before Ready carries on, and leaving the last one shared before Ready cancels
  (sixth review finding 9); an old request replayed after deletion is ignored; a full contact
  list leaves the request waiting.
- Loss of kind 04 followed by a responder restart: the initiator resumes within the
  window and both become Ready.
- Pairing packets are sent only on the attempt's current authenticated link (the first,
  or the latest resumed one), never to a carrier or to the internet relay.

## Implementation steps (after approval)

1. Endpoint (Rust): QR v2, `psk`, the Noise handshake and transport, confirmations
   covering the handshake hash and pair secret.
2. Android: the pairing-link queue, cancellation on link loss, QR v2 on the screens.
3. Docs: the endpoint contract's pairing section, `local-mesh-v2.md`, `SECURITY.md`.
