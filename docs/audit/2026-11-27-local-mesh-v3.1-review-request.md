# Review request: local mesh v3.1 and rotating address labels

**From:** the project owner, 27 November 2026.
**For:** Codex, or any reviewer.

Please review two proposals that ship in the same update. Nothing in them is built
yet. Write your findings to a new file,
`docs/audit/2026-11-27-local-mesh-v3.1-review.md`, and change no other file.

## What to review

1. [Local mesh v3.1](../proposals/local-mesh-v3.1-group-envelopes.md), all three parts:
   - Part 1, group envelopes: carriers deliver one envelope to many group members.
   - Part 2, message routes: Message details shows "Direct" or "Via N relays".
   - Part 3, friend names: a live nearby link to a paired friend shows their name,
     using a list of 128 recognition tags. **This is new cryptography and needs the
     closest look.**
2. [Rotating address labels](../proposals/rotating-address-labels.md). It has never
   been reviewed. Part 3 of v3.1 derives its key from this proposal's `labelKey`, and
   the group encryption outer layer uses `labelKey` too.

## Read with

- [local-mesh-v2.md](../proposals/local-mesh-v2.md): links, spray and wait, carrier
  storage, `DELIVERED`, flood slow mode, and the rule that nothing sent to nearby
  strangers may be permanent or trackable.
- [group-encryption-v1.md](../proposals/group-encryption-v1.md), §3.4 (sending, including
  the outer layer), §3.7 (splits, reconnecting and resynchronisation) and §3.9 (cost
  and key hand-over batches).
- [android-endpoint-v1.md](../android-endpoint-v1.md), for the receipt format that
  Part 2 changes.
- [The security audit](2026-11-25-security-audit.md), finding 1b.

Check the proposals' claims about today's code against:
- `core/src/envelope.rs` (`binding()`, `relay_key()`, `forwarded()`, `MAX_HOPS`);
- `core/src/relay.rs` (`pending_for()`, carrier store limits, eviction);
- `endpoint/src/engine.rs` (`encrypt()`, `receive()`, receipts, `StoredMessage`);
- `relay-server/tests/tls_relay.rs`;
- in `android/app/src/main/java/org/yapmessenger/app/`: `MeshHub.kt` (frame types,
  `MAX_FRAME`, `HELLO`, per-link offer tracking), `FloodGuard.kt`, `EffortStamp.kt`
  and `SecureModel.kt`.

## Questions

### Part 1: group envelopes

1. Is the `GROUP_ENVELOPE` frame type bound to anything? Could a carrier or a stranger
   re-send a one-to-one envelope as a group envelope, or the reverse, to stop
   `DELIVERED` deleting it or to change how it spreads?
2. A holder with one copy keeps offering a group envelope to every new link for up to
   24 hours. Is the traffic and storage this causes bounded? Can someone with an
   18-bit effort stamp per envelope use it to fill carriers or push out other people's
   mail?
3. Eviction treats all envelopes the same and drops the oldest first. Can an attacker
   evict key hand-over batches on purpose, and is resynchronisation (§3.7 of group
   encryption) a sound repair?
4. Does marking group traffic leak more than the proposal says?
5. Do 32 copies and the 8-hop limit work together sensibly?

### Part 2: message routes

6. The hop byte is not authenticated. Is the wording ("reported by the network, not
   verified") enough, or can a carrier use a false count to mislead someone in a way
   that matters?
7. Is the receipt change (16 or 17 bytes) safe, and does old and new compatibility
   work as described?
8. Does sending the forward hop count back inside the receipt leak anything new?

### Part 3: friend names

9. Is the tag construction sound? Check the HMAC inputs, domain separation, whether
   8-byte link nonces are enough, and the HKDF derivation of `linkKey` from
   `labelKey`.
10. Replay, reflection and relaying. Can a recorded or echoed `PROBE` make a phone
    show a friend's name? Can an attacker relay the probes between two places so that
    your phone shows "Ravi" while Ravi's phone is far away? If so, how bad is that,
    and is there a fix?
11. Can a stranger learn anything from the probe: who you are, how many contacts you
    have, whether a match happened (for example from timing or from how the phone
    behaves afterwards)?
12. With more than 128 contacts, each link carries a random 128. Does choosing a new
    subset per link leak anything over repeated links?
13. The proposal says a seized friend's phone can recognise you live on any link.
    Is that described correctly, and is there a cheap way to narrow it?

### Rotating address labels

14. The open questions in that proposal's "Risks and open questions for the reviewer" section:
    deriving `labelKey` from X25519 identity keys versus a random key sent in the
    session, hour-long epochs and clock tolerance, a seized phone, and the internet
    relay server. Note that Part 3 and the group outer layer both build on `labelKey`,
    so this choice affects them too.

### Across everything

15. Anything else that would break the v2 rule, lose messages, or let one phone
    overload others.

## How to write the findings

Follow the format of
[the outer-layer review](2026-11-27-group-encryption-v1-outer-layer-review.md):
- reviewer, date and the revision you read at the top, then a one-paragraph verdict;
- findings numbered and graded Critical, High, Medium or Low, each with its locations,
  what is wrong, and a suggested fix;
- say where the proposals' claims about the code are right or wrong;
- state that this is an AI-assisted review, not a professional security audit.

Use plain British English. Do not mark anything as approved for building.
