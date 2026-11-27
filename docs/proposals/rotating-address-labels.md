# Proposal: rotating message address labels

**Status: revised after review; do not implement yet.** Per `AGENTS.md` this changes how
envelopes are addressed (a cryptographic protocol change), so it must be reviewed
before any code is written. Tracks audit finding 1b. Codex reviewed it on 27 November
2026 together with [local mesh v3.1](local-mesh-v3.1-group-envelopes.md); this revision
answers that review ([response](../audit/2026-11-27-local-mesh-v3.1-response.md)) and
needs another review. It ships in the same update as mesh v3.1 and group encryption.

**Audience: maintainers, reviewers and coding agents.**

## Problem

Every envelope header carries two values in clear:
- `destination`: the recipient's 16-byte route token. Permanent, the same for every
  message to that person from anyone.
- `sender_hint`: a 16-byte value fixed per pair of contacts.

Inside the envelope, libsignal also leaves fields readable: an established session's
message shows its ratchet public key and counters, and the first message of a session
shows the sender's identity key. A ratchet key can stay the same across many messages;
an identity key never changes.

Envelopes are handed to every nearby phone for carrying. Anyone running a modified app
can log these values and see "the same person is receiving again", "these two people
talk", and roughly where and when. Link IDs already rotate (local mesh v2), so these
are now the main remaining tracking signals. Changing the labels alone is not enough:
the libsignal fields must be hidden too.

## Goals

1. Labels that strangers cannot link across hours.
2. Nothing permanent inside the envelope either: the whole libsignal message is
   wrapped.
3. The recipient can still recognise its mail cheaply, without decrypting everything.
4. No change for users. No extra steps, no new settings.
5. Established primitives only (HKDF-SHA256, HMAC-SHA256, XChaCha20-Poly1305 as in
   group encryption §3.4). No homemade crypto.

Non-goals: hiding that Yap is in use; hiding message timing or size; defending
against a contact who is themselves hostile (they hold the shared secret).

What rotation does **not** give: every envelope in the same pair, direction and hour
shares one label, by design. A retried envelope keeps the label of the hour it was
made in, so it can show yesterday's label today. A phone seen carrying the same
envelope before and after an hour boundary can be linked by that envelope. Rotation
removes the permanent header signals; it does not make traffic unlinkable in general.

## Proposed design

### 1. A random pair secret, replaced over time

At pairing, after both confirmations validate and the session exists, the phone with
the **smaller identity key** (the one that already starts PQXDH) makes a random 32-byte
`pairSecret` from the operating system's secure random source and sends it as the first
message in the new session. The other phone acknowledges it inside the session. If both
somehow send one, the one from the smaller identity key wins.

Every key below is made from `pairSecret` with its own salt:

```
labelKey = HKDF-SHA256(ikm = pairSecret, salt = "Yap address labels v1", info = "")
linkKey  = HKDF-SHA256(ikm = pairSecret, salt = "Yap link key v1",       info = "")
```

`labelKey` is also the input to the outer wrapper (§5) and to group encryption's
pairwise pieces. `linkKey` is for friend names (mesh v3.1 §3.4). Separate salts mean
the secret is never used directly for two jobs. They do not protect the others if
`pairSecret` itself leaks: whoever holds it holds everything made from it.

**Replacing it.** Every 7 days the smaller-identity phone sends a new random
`pairSecret`, numbered one higher, inside the session. Both phones keep the old one for
48 hours after the new one is acknowledged, so delayed mail still opens, then delete
it. An unacknowledged replacement is resent. 7 days and 48 hours are starting values.

This shuts out someone who copied an old secret from a backup or a seized phone once
the old one is deleted. It cannot shut out someone who holds a friend's working phone:
that phone receives every new secret. Removing the friend does.

*Why not derive it from the identity keys:* an X25519 result of the two identity keys
never changes, so anyone who ever gets either identity private key can recompute it,
and every key made from it, for as long as the pair exists. "Replacing" it would
recompute the same value.

### 2. Before the pair secret exists

The pairing confirmations, the first libsignal message (which shows the sender's
identity key) and the message carrying `pairSecret` are sent **only on the pairing
link**, the direct local link the two phones paired over. They are never put in the
outbox for carriers, never handed to a carrier and never sent to the internet relay.
If the pairing link closes first, pairing has to be repeated. Only after `pairSecret`
is acknowledged does the pair use labels and the outer wrapper.

*For the reviewer:* this relies on the pairing link being the two paired phones and
nothing else. Whether the local link's own encryption also hides these frames from
radio listeners nearby needs checking.

### 3. Hourly labels

```
epoch = floor(created_ms / 3_600_000)               (unsigned 64-bit, big-endian)
tag   = HMAC-SHA256(labelKey, "dest" || direction || epoch)[0..16]
```

`direction` is `0x01` when the sender's identity key is the smaller of the two
(comparing the canonical 33-byte identity keys as unsigned bytes), `0x02` otherwise,
so the two directions of a conversation never share a label.

The header's `destination` field carries `tag`. The `sender_hint` field becomes 16
random bytes per envelope: the tag already tells the recipient which contact sent it.

### 4. Recipient matching

Each phone keeps an in-memory table of expected tags: for every contact, the
incoming-direction tag for every epoch from 25 hours before its current hour to 1 hour
after it, inclusive (27 epochs). At the new limit of 128 contacts that is about 3,500
entries, rebuilt hourly, plus the previous `pairSecret`'s tags while it is kept. A
carried envelope is "mine" if its `destination` is in the table: one hash-map lookup.

The table is only for finding mail. After decrypting, the receiver **recomputes** the
expected tag from the authenticated `created_ms` and the contact and direction it
matched, and refuses the envelope if they differ. The envelope's own exact expiry and
lifetime checks still apply. All epoch arithmetic saturates; nothing wraps.

### 5. The outer wrapper for one-to-one messages

Every one-to-one libsignal message (chat and receipts) is wrapped once more before it
goes in the envelope, with the outer layer from group encryption §3.4:
- **Cipher:** XChaCha20-Poly1305 with a random 24-byte nonce, format
  `"YGO1" || nonce || ciphertext and tag`.
- **Key:** HKDF-SHA256 with `labelKey` as input, the salt `Yap outer layer v1`, and as
  info: kind `0x03` (one-to-one message), 16 zero bytes in place of a group ID, the
  sender's identity key and the recipient's identity key (each 32 bytes, without the
  type byte) and the hour number (8, big-endian), taken from the envelope's creation
  time. 32 bytes out.
- **Associated data:** the 68 bytes of `Header::binding()`.

A receiver accepts hours from 25 before its current hour to 1 after, as for group
messages. New test vectors for kind `0x03` are added to
[`experiments/outer-layer-vectors/`](../../experiments/outer-layer-vectors/README.md).

### 6. What changes elsewhere

- **Carriers:** nothing. They never read labels.
- **Duplicate detection and relay keys:** unchanged (they hash the whole envelope).
- **The one-time destination** in a pairing QR is used only on the pairing link (§2).
- **Internet relay server:** off in this update and shown in the app as "Coming soon"
  (owner, 27 November 2026). Nothing is sent to or fetched from it, so no fixed mailbox
  value reaches nearby traffic. Its return needs its own design.
- **Route token:** no longer shown in Connections while the internet relay is off.
- **Contacts:** the limit goes up from 64 to 128 (mesh v3.1 §3.5).
- **Compatibility:** none. Every phone is updated and re-paired in this update.

## Risks and open questions for the reviewer

1. The pair secret bootstrap (§1, §2): is sending it as the first session message,
   on the pairing link only, sound? Is the 7-day replacement with 48 hours' overlap
   reasonable?
2. Hour-long epochs: shorter means less linkability but a bigger table and more
   sensitivity to clock differences. Today a receiver refuses envelopes created more
   than five minutes in its future and checks expiry; it does not otherwise require
   clocks to agree.
3. A friend's phone in the wrong hands reveals the current `pairSecret`: every label,
   outer key and recognition tag for that pair until the secret is replaced, and the
   kept previous one for its 48 hours.
4. Groups use their own label keys (group encryption §3.4).

## Test plan

- Fixed test vectors for `labelKey`, `linkKey`, `tag` and the kind `0x03` outer layer.
- The pair secret: sent first, acknowledged, replaced after 7 days, old one opening
  delayed mail for 48 hours and refused after; both phones sending one at once.
- Bootstrap traffic is never offered to a carrier or the internet relay.
- Two endpoints exchange messages across an epoch boundary and with ±5 minutes skew.
- Envelopes in the same pair, direction and hour share a label; across hours they
  differ; a delayed retry keeps its creation hour's label and still opens.
- Strangers' view: complete captured envelopes (not just destination fields) between
  one pair across 24 hours show no repeated value other than a label within its hour
  and a retried envelope's own bytes.
- A recipient receives only its own mail from a mixed carrier store.
- An envelope whose label does not match its authenticated creation hour is refused;
  tampering with `created_ms` or `destination` fails.

## Implementation steps (after approval)

1. Endpoint (Rust): `pairSecret` per contact, bootstrap, replacement; derived keys;
   tag derivation; header construction; outer wrapper.
2. Endpoint: match table, `receive` lookup and label recomputation.
3. Android: "mine?" check uses the native match instead of comparing to the route;
   pairing traffic stays on the pairing link; the internet relay is off.
4. Docs: update `local-mesh-v2.md`, audit finding 1b, the endpoint contract, README.
