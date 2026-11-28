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

The phones find each other's link by the destination token, as today. Before sending
anything else on it, they run the published Noise Protocol Framework handshake
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
reviewed like libsignal, not hand-written code. Which library is a question for review.

### 3. Pairing inside the channel

After the handshake, every pairing message is a Noise transport message on that link:
1. Each phone sends its public bundle. It must match the bundle hash in the QR, as
   today.
2. The initiator sends the 32-byte random pair secret.
3. Each phone sends its signed confirmation. Today's confirmation signs the two
   complete bundles; it now also signs the Noise handshake hash (32 bytes, Noise's
   channel binding value) and SHA-256 of `"Yap pair secret v1\0"` followed by the pair
   secret. A confirmation therefore covers this channel and this pair secret, and
   cannot be replayed into another.
4. The initiator starts PQXDH and sends the first libsignal message, as today.

A contact becomes ready only when both confirmations validate, as today. If the link
closes, the handshake fails, or ten minutes pass before then, the attempt is cancelled:
nothing is kept, and the people pair again with new QR codes. All pairing packets stay
in a queue bound to that one link (rotating address labels §2).

Transport messages are limited to Noise's 65,535 bytes; a public bundle is 1,883 bytes.

### 4. The pairing file

Today a bundle can also travel in an explicitly shared pairing file instead of over a
link. That route does not use a radio link, so this proposal does not cover it. Options
for review: drop the file route in this update, or encrypt the file under `psk`. Until
decided, the file route shows a warning that the file reveals the person's identity key
to anyone who sees it.

## What this does and does not protect

- **Protected:** bundles, identity keys, confirmations, the pair secret and the first
  libsignal message, against radio listeners and against a phone in the middle that has
  not seen both QR codes.
- **Not protected:** someone who sees both screens or photographs both QR codes during
  the attempt; the fact that two phones are exchanging pairing-sized traffic; the
  destination tokens used to find the link, which are sent as today (for review:
  whether they should also be hidden).
- **Not proved:** that the other phone is physically present. A relay that has both QR
  codes, for example from a camera, could pass the pairing between two places.

## Risks and open questions for the reviewer

1. Is `Noise_NNpsk0` with a QR-derived pre-shared key the right pattern, given that
   identities are proved afterwards by signed confirmations? Would `XXpsk3` with the
   identity keys be better, and is it worth the extra key handling?
2. Is binding the confirmations to the Noise handshake hash enough to tie them to this
   channel?
3. Which Rust Noise library, and at what version?
4. The pairing file (§4).
5. Whether the destination tokens used to find the pairing link leak anything worth
   hiding.

## Test plan

- Fixed test vectors for `psk`, the prologue and a full handshake.
- A full pairing on Bluetooth, shared-network Wi-Fi and Wi-Fi Aware, with a capture of
  every byte on the link: no identity key, bundle, confirmation or pair secret appears
  in clear.
- A phone in the middle with one QR code, or none, cannot complete the handshake.
- A changed bundle, a confirmation from another attempt, a replayed handshake and a
  second pair secret are all refused.
- A link closing at each step cancels the attempt and leaves nothing behind; a restart
  mid-pairing does the same.
- Pairing packets are never sent on another link, to a carrier or to the internet
  relay.

## Implementation steps (after approval)

1. Endpoint (Rust): QR v2, `psk`, the Noise handshake and transport, confirmations
   covering the handshake hash and pair secret.
2. Android: the pairing-link queue, cancellation on link loss, QR v2 on the screens.
3. Docs: the endpoint contract's pairing section, `local-mesh-v2.md`, `SECURITY.md`.
