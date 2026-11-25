# Proposal: rotating message address labels

**Status: proposal for review. Not implemented.** Per `AGENTS.md` this changes how
envelopes are addressed (a cryptographic protocol change), so it must be reviewed
before any code is written. Tracks audit finding 1b.

**Audience: maintainers, reviewers and coding agents.**

## Problem

Every envelope header carries two values in clear:
- `destination`: the recipient's 16-byte route token. Permanent, the same for every
  message to that person from anyone.
- `sender_hint`: a 16-byte value fixed per pair of contacts.

Envelopes are handed to every nearby phone for carrying. Anyone running a modified
app can log these values and see "the same person is receiving again", "these two
people talk", and roughly where and when. Link IDs already rotate (local mesh v2),
so this is now the main remaining tracking signal.

## Goals

1. Labels that strangers cannot link across time: a new label at least every hour.
2. The recipient can still recognise its mail cheaply, without decrypting everything.
3. No change for users. No extra steps, no new settings.
4. Use established primitives only (HKDF-SHA256, HMAC-SHA256, X25519 already in
   libsignal). No homemade crypto.
5. Keep the envelope size and the authenticated header binding unchanged.

Non-goals: hiding that Yap is in use; hiding message timing or size; defending
against a contact who is themselves hostile (they know the shared secret).

## Proposed design

### 1. A label secret per pair of contacts

At pairing, both phones already hold each other's identity public keys and have just
verified them. Each side computes, with no extra messages:

```
shared   = X25519(own identity private key, contact identity public key)
labelKey = HKDF-SHA256(ikm = shared,
                       salt = "Yap address labels v1",
                       info = sort(identityA, identityB))   -> 32 bytes
```

Stored in the encrypted endpoint snapshot beside the contact. Never sent.

*Alternative for review:* generate a random 32-byte `labelKey` and send it inside the
first encrypted session message. That avoids reusing identity keys for a second
purpose, but adds a bootstrap dependency. Reviewer to choose; the rest of the design
is the same either way.

### 2. Hourly labels

```
epoch = floor(created_ms / 3_600_000)
tag   = HMAC-SHA256(labelKey, "dest" || direction || epoch)[0..16]
```

`direction` is 0x01 when the lower identity key sends, 0x02 otherwise, so the two
directions of a conversation never share a label.

The header's `destination` field carries `tag`. The `sender_hint` field becomes 16
random bytes per envelope: the tag already tells the recipient which contact sent it.

### 3. Recipient matching

Each phone keeps an in-memory table of expected tags: for every contact, the
incoming-direction tag for epochs `now-25h .. now+1h` (envelopes live at most 24 h).
At 64 contacts that is about 1,700 entries, rebuilt hourly. A carried envelope is
"mine" if its `destination` is in the table. This is one hash-map lookup, the same
cost as today.

Because `created_ms` is inside the authenticated binding, a sender cannot pick an
epoch that fails to match the real creation time without the recipient detecting it
after decryption.

### 4. What changes elsewhere

- **Carriers:** nothing. They never read labels.
- **Duplicate detection and relay keys:** unchanged (they hash the whole envelope).
- **Pairing confirmations** (before a session exists) still need a destination. Use
  the one-time route from the pairing bundle, which is fresh per pairing code.
- **Internet relay server:** it matches a fixed mailbox token. With rotating labels
  the client would have to register upcoming tags, which leaks the same link to the
  server operator. Options: (a) drop the server (owner prefers no stored data;
  Tor is being considered); (b) keep a fixed mailbox only for the server path.
  Needs an owner decision.
- **Route token:** still shown in Connections for relay setup; remove when (a).

## Risks and open questions for the reviewer

1. Reusing X25519 identity keys for label derivation versus a random in-session key.
2. Hour-long epochs: shorter means less linkability but a bigger match table and
   more sensitivity to clock skew. Clocks are already required to be within 5
   minutes for envelopes to be accepted.
3. If someone gets hold of a contact's phone, it reveals `labelKey`, so past and future labels for
   that pair become linkable. Consider rotating `labelKey` with the Signal session.
4. Groups (not built yet) will need their own label scheme.
5. Migration: dev builds only, so a version bump and re-pairing is acceptable.

## Test plan

- Fixed test vectors for `labelKey` and `tag` (both derivation options).
- Two endpoints exchange messages across an epoch boundary and with ±5 min skew.
- Strangers' view: 1,000 envelopes between the same pair across 24 h produce
  distinct, unlinkable `destination` and `sender_hint` values.
- A recipient receives only its own mail from a mixed carrier store.
- Tampering with `created_ms` or `destination` fails authentication.

## Implementation steps (after approval)

1. Endpoint (Rust): `labelKey` per contact; tag derivation; header construction.
2. Endpoint: match table and `receive` lookup; keep the old route path behind a
   version flag for one dev release.
3. Android: "mine?" check uses the native match instead of comparing to the route.
4. Docs: update `local-mesh-v2.md`, audit finding 1b, README.
