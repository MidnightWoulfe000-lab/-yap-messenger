# Proposal: encrypted pairing

**Status: draft for review; do not implement.** Per `AGENTS.md` this is a cryptographic
protocol change and must be reviewed before any code is written. It answers finding 5
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
`"Yap pairing v2"` followed by both QR payloads in identity order, so a handshake only
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
  accepted only on a link where a `PAIR_FIND` matched, or for an attempt in its resume
  window.
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
  their roles, the commitment for `PAIR_FIND`, and the exact cached bootstrap bytes it
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
labels §2).

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
   pair secret and the starting week. A signs it with its identity key, together with
   B's identity key and the group ID, so it cannot be moved to another pair or group.
2. B sees "<A> from <group> wants to add you as a friend" and accepts or ignores it.
   Accepting sends a signed **accept** (request ID, B's display name) as a piece back to
   A. B saves A as a "finishing" contact with that secret and week.
3. When A receives the accept, it saves B the same way. The two then set up their own
   one-to-one contact session from each other's bundles in the group log (the smaller
   identity key initiates), with bootstrap messages travelling as group pieces. When
   bootstrap finishes, both are Ready and use rotating labels like any contact.
4. **Lost messages:** A resends the same request (same ID, same secret) until it gets an
   accept or the deadline passes; B answers a repeated request with the same cached
   accept.

**One request per pair.** Friend requests belong to the two identities, not to a group:
- **Crossed requests.** If A has asked B and B has asked A, both have agreed, so the
  request from the **smaller identity key wins**: its request ID, secret and week are
  the only ones used. The other phone drops its own request, accepts the winner's
  without asking again, and a late accept for the losing request is ignored. So both
  phones always install the same secret.
- **Several shared groups.** A request through a second group for the same pair is the
  same request: it is answered with the cached accept, never a second secret.
- **Already friends:** a request between existing contacts is ignored.

**The friend request's own finish line:**

| State | Reached when |
| --- | --- |
| Requested (A) | Request sent |
| Accepted (B) | B accepted; A saved on B as "finishing" |
| Accepted (A) | Accept received; B saved on A as "finishing" |
| Bootstrap | Contact session being set up through group pieces |
| Ready | Bootstrap finished on that phone, as in §3 |

- **Deadline:** 7 days from the request. A phone not Ready by then deletes the
  "finishing" contact and the request, and shows "Friend request didn't finish". An
  accept or bootstrap message that arrives after the deadline is ignored.
- **Leaving the group before Ready** cancels it the same way, since its bootstrap
  travels as group pieces. After Ready, leaving changes nothing.
- **Replays:** request IDs are remembered for 30 days after they finish or are
  cancelled, and a repeated old request is ignored, including after the friend was
  deleted. To become friends again, one of them sends a new request.
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
   erased on completion or cancellation, and never fall back to a permanent token.

## Test plan

- Fixed test vectors for `psk`, the prologue and a full handshake.
- A full pairing on Bluetooth, shared-network Wi-Fi and Wi-Fi Aware, with a capture of
  every byte on the link: no identity key, bundle, confirmation or pair secret appears
  in clear.
- A phone in the middle with one QR code, or none, cannot complete the handshake.
- A changed bundle, a confirmation from another attempt, a replayed handshake and a
  second pair secret are all refused.
- A link closing at each step before Confirmed cancels the attempt and leaves nothing
  behind; after Confirmed, a new link within ten minutes resumes and finishes; past the
  deadline both phones show "Pairing didn't finish"; a cancelled attempt never sends a
  packet later; a restart before Ready cancels.
- Responder Ready while the initiator cancelled: the responder shows "Finishing
  pairing", then the re-pair prompt after 24 hours.
- Friend through a group: request, accept, lost accept (repeated request gets the
  cached accept), ignored request expiring, the limits, and "not checked in person"
  changing after a QR scan; crossed requests install the smaller identity's secret on
  both; the same pair through two groups gets one secret; leaving the group before
  Ready cancels; an old request replayed after deletion is ignored; a full contact
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
