# Codex security design review: group encryption v1

**Author:** Codex (OpenAI), at the project owner's request.  
**Date:** 26 November 2026.  
**Intended reader:** Claude and the project owner.  
**Status:** Unresolved review findings. This is an AI-assisted design review, not an independent professional security audit or evidence of production readiness.

## Scope and verdict

Reviewed:

- [Group encryption v1 proposal](../proposals/group-encryption-v1.md).
- [Group mechanics](../GROUP_MECHANICS.md).
- [Protocol proposal](../protocol-proposal.md).
- [Rotating address labels](../proposals/rotating-address-labels.md).

The review also inspected the existing `groups/` state machine and the locally installed libsignal source at revision `257105c55a7389ca6b1e85185e2769465e6729f1`, which is pinned by `endpoint/` and `groups/`.

**Verdict: do not implement this version yet.** The main blockers are checkpoint authentication, state recovery after partitions, and assumptions about what libsignal hides or repairs.

This file records the review supplied in chat. No implementation or proposal changes were made as part of that review. Findings describe concrete failures or specification gaps; they are not claims that every attack was reproduced in an executable test.

Severity:

- **Critical:** permits governance takeover.
- **High:** breaks a stated security property or causes substantial messaging failure.
- **Medium:** exposes metadata or creates exploitable availability gaps.

## Instructions for Claude when addressing this review

Keep the finding numbers below when responding. For each, record whether it is accepted, disputed with evidence, or resolved by a specific specification change. Distinguish an accepted product trade-off from a security property the design actually provides. In particular, finding 4 challenges a previously accepted product decision; do not silently replace that decision or treat the review as approval to change it.

The owner requested a review and this saved handover. This file does not authorise implementation of unresolved cryptographic or governance policy. Follow the repository's review requirements before implementation.

## Findings

### 1. High — A newcomer's checkpoint authenticates itself

**Location:** group encryption proposal, §3.3, step 5.

A malicious sponsor can invent a checkpoint, sign its hash, put another attacker-controlled identity in its roster, then produce the required “second member” confirmation. The newcomer has no independently authenticated basis for deciding that this second identity is a real member. Other members rejecting the false admission does not stop the attacker running this false view for the newcomer.

**Suggested fix:** Define the bootstrap trust model explicitly. Use independently verified checkpoint signers or a certificate whose signers and threshold derive from an already trusted membership commitment. If the sponsor alone is trusted to introduce the group, describe that honestly. Do not distribute the newcomer's keys or allow sensitive sending while confirmation is provisional.

### 2. High — A signed merge checkpoint cannot replace validation of missing events

**Location:** group encryption proposal, §3.3, step 5, concurrent branches.

When parents are unavailable, the newcomer cannot verify the merge author's authority, the votes behind removals, or the resulting roster. Matching the checkpoint to that author's signed hash proves consistency between two attacker-supplied objects. It does not prove a valid transition. This reintroduces the bootstrap problem after every sufficiently complicated split.

**Suggested fix:** Require a reviewed checkpoint certification and transition-proof mechanism. A replacement checkpoint must preserve previously verified removals and accepted history, and must not become authoritative merely because a later event names it.

### 3. High — The checkpoint is not sufficient state for the existing governance algorithm

**Location:** group encryption proposal, §3.3 checkpoint contents; [`groups/src/state.rs`](../../groups/src/state.rs).

The current implementation derives state from event ancestry and resolves concurrent admin decisions by asking whether either decision saw the other. A roster plus open business omits historical decisions needed to resolve a newly arriving concurrent branch. Closed elections and earlier revocations can also affect later transitions. A state hash does not restore that missing information.

**Suggested fix:** Specify a complete checkpoint-based state machine before adding the hash. Prove that applying later events to a checkpoint produces the same result as replaying the complete DAG, including concurrent admin decisions, rejoining and election runoffs. Specify pre-event versus post-event hashing and avoid including an event's own hash recursively.

### 4. Critical — The proposer-selected electorate allows unilateral governance takeover

**Location:** GROUP_MECHANICS.md, §3, “Measuring active in the last 30 days”; [`groups/src/state.rs`](../../groups/src/state.rs).

The rule that “the vote's list wins” lets a hostile member claim everyone else was inactive and name only themselves. The current rules then permit a one-person majority for removing the SuperAdmin or changing collective settings. That can lead to selecting themselves as SuperAdmin, removing others, and triggering the encryption layer's automatic key redistribution.

This follows an explicitly accepted product decision, but its security consequence is much stronger than accepting imperfect activity measurements.

**Suggested fix:** Derive electorates from authenticated, agreed membership state. If inactivity must reduce the electorate, require a reviewed certification mechanism. A proposer's unsupported observation cannot safely determine the voting threshold. Changing the accepted rule requires an explicit product decision.

### 5. High — Hourly address rotation leaves stable identifiers inside libsignal messages

**Location:** group encryption proposal, §3.4; rotating address labels proposal, goals and wire design.

`SenderKeyMessage` serialises its distribution UUID, chain ID and iteration outside the encrypted application plaintext. A relay can follow the same sending chain across hourly label changes, potentially for a week. Pairwise `SignalMessage` headers also expose ratchet public keys; initial `PreKeySignalMessage` headers expose the sender's identity public key. Batched hand-overs therefore risk revealing persistent identities and associations despite opaque piece labels.

**Suggested fix:** Review privacy across the complete transmitted bytes, including libsignal headers. Meeting the stated unlinkability goal requires a reviewed outer protection mechanism that hides those headers, or weaker documented privacy claims. Changing only `destination` and `sender_hint` is insufficient.

**Evidence:** pinned libsignal [message serialisation source](https://github.com/signalapp/libsignal/blob/257105c55a7389ca6b1e85185e2769465e6729f1/rust/protocol/src/protocol.rs), inspected locally.

### 6. High — One removal batch is not guaranteed to fit

**Location:** group encryption proposal, §3.9.

The 30 KB estimate assumes every pair already has an established session. That need not hold after interrupted admission or delayed session establishment. Using the proposal's own first-hand-over measurement, `126 × 1,886 = 237,636` bytes, before batch framing. Even established sessions need bounds covering SPQR overhead, the proposed hand-over fields and piece labels; twenty measurements establish no maximum.

**Suggested fix:** Batch by actual encoded byte length, permit multiple envelopes, and bound piece count and size. Retain the measurement program and test initial, unacknowledged, established and ratcheting sessions. Withdraw the unconditional “127 envelopes” release assertion.

### 7. High — An opaque multi-recipient batch needs different custody semantics

**Location:** group encryption proposal, §3.9; protocol proposal, envelope and relay rules.

The ordinary envelope has one destination and recipient acceptance semantics. A batch has many recipients. If one recipient's delivery causes deletion, the others lose their keys; if everyone retains and rebroadcasts the whole batch, storage and retransmission costs multiply. Removing acknowledged pieces changes the envelope digest and bypasses relay deduplication. The existing envelope rules do not settle these cases.

**Suggested fix:** Specify batch routing, independent piece acknowledgements, custody expiry and retransmission. Authenticate each piece's relevant context independently, so relays can carry bounded pieces without relying on an authenticated outer batch. Measure retransmitted traffic and queue occupancy, not just initially created bytes.

### 8. High — A current distribution-message resend does not necessarily repair a lagging receiver

**Location:** group encryption proposal, §5, missing-key recovery and jump limit.

At this pin, processing a distribution message for an existing chain ID and signing key preserves the existing receiver state; it does not advance it to the supplied iteration. A relay can withhold enough messages that the next genuine message exceeds Yap's 2,000-step limit. Sending the current distribution message again will leave that receiver behind.

**Suggested fix:** Define explicit recovery for excessive gaps, such as an authenticated fresh distribution ID. Do not assume the public distribution-processing function performs fast-forward recovery. Test this exact case against the pin.

**Evidence:** `add_sender_key_state` in pinned libsignal [sender_keys.rs](https://github.com/signalapp/libsignal/blob/257105c55a7389ca6b1e85185e2769465e6729f1/rust/protocol/src/sender_keys.rs), inspected locally.

### 9. High — Missing a hand-over can also make recovery traffic unrecognisable

**Location:** group encryption proposal, §3.6 label changes and §5 retry expiry.

The new sender key and new label key arrive together. A recipient missing that hand-over cannot match subsequent messages addressed with the new label. The proposed “receive a message under a missing key, then request it” path therefore cannot be relied upon. Stopping retries after 24 hours can leave the member disconnected until another rotation.

**Suggested fix:** Provide bounded pairwise resynchronisation independent of group labels: advertise current key generations, request missing hand-overs on reconnect, and authenticate acknowledgements against the exact generation.

### 10. High — The rotation limits can block legitimate revocations

**Location:** group encryption proposal, §5 resource limits.

Five removals within an hour legitimately require five rotations from each sender. Ignoring hand-overs after four can reject the key that finally excludes every removed member. Keeping only four generations also conflicts with accepting delayed traffic across a 24-hour lifetime.

**Suggested fix:** Distinguish unsolicited key churn from rotations justified by authenticated governance changes. Coalesce changes safely and bound work through scheduling and backpressure. Do not silently ignore the latest necessary generation. Document which delayed messages must be sacrificed when retention caps are reached.

### 11. High — Weekly sender-key replacement does not guarantee recovery from copied phone state

**Location:** group encryption proposal, choice 5 and protection claims.

An attacker who copied the phone may hold its pairwise session states and retained pre-key private keys, as well as group keys. Fresh sender keys delivered through a still-compromised pairwise channel remain readable. Sender-key chains themselves have no mechanism for injecting fresh recipient contributions.

**Suggested fix:** Separate sender-key rotation from post-compromise recovery. State the conditions under which the pairwise ratchets heal, and require an appropriate fresh authenticated exchange or re-pairing for lost-device recovery. Remove the implied one-week compromise bound.

### 12. Medium — Forward-secrecy claims omit skipped keys, distribution snapshots and stored plaintext

**Location:** group encryption proposal, §3.6 retention and §4 protection claims.

Deleting retired sending chains is only part of erasure. Libsignal retains skipped message keys for out-of-order delivery; a saved early distribution message can retain an earlier chain seed. Plaintext history remains readable on an unlocked seized phone regardless of cryptographic key deletion.

**Suggested fix:** Specify erasure for skipped keys, distribution-message plaintext, snapshots and journals, including current chains. Qualify the promise as protection of old ciphertext whose relevant secrets have actually been erased, rather than protection of retained chat history.

### 13. Medium — Monthly bundle replacement does not bound replay-record storage as written

**Location:** group encryption proposal, §3.2.

Permanent records accumulate across monthly replacements. Within one month, a hostile member can also create arbitrarily many distinct session initiations unless separately restricted. The base-key record rejects repeated use of the same tuple; it is not a session-creation quota.

**Suggested fix:** Rate-limit and authorise session creation before expensive processing. Define when records for a permanently retired, non-reusable pre-key ID can be pruned. Replay refusal depends on Yap's persistent store correctly implementing the callback; it is not automatic durable storage supplied by libsignal.

**Evidence:** pinned libsignal [store interfaces](https://github.com/signalapp/libsignal/blob/257105c55a7389ca6b1e85185e2769465e6729f1/rust/protocol/src/storage/traits.rs) and [session management](https://github.com/signalapp/libsignal/blob/257105c55a7389ca6b1e85185e2769465e6729f1/rust/protocol/src/session_management.rs), inspected locally.

### 14. High — Temporary-group eligibility can use a history that omits a known removal

**Location:** group encryption proposal, §3.10, “Starting it” and “New events”.

The proposal first says eligibility is checked against each phone's own state, then says `AcceptOffer` is valid at the organiser's recorded heads. A hostile organiser can select old heads at which a removed member was active. If the latter rule wins, even a phone that already knows the removal can admit that member. This exceeds the acknowledged risk of an unknown remote removal.

**Suggested fix:** A locally known removal must veto eligibility through an old offer. Specify rejoining and membership incarnations explicitly, and distinguish “active at these old heads” from “currently eligible according to this receiver”.

### 15. Medium — Recent delivery proves neither current reachability nor absence of internet

**Location:** group encryption proposal, §3.10 detection and presence messages.

A relay can deliver a previously unseen, delayed message and make its author appear nearby. It can tunnel fresh messages between distant meshes. Conversely, internet access does not guarantee fresh messages from a quiet majority; the “still here” traffic starts only after isolation is detected. Authentication proves the author, not the route or physical presence.

**Suggested fix:** Use bounded nonce challenges for recent responsiveness and state exactly what they establish. Treat internet availability as a separate local observation and isolation as uncertain. Define whether “reachable” permits tunnelling; cryptography alone cannot establish radio proximity.

### 16. Medium — Temporary-group coordination gives the smallest-key member a denial-of-service role

**Location:** group encryption proposal, §3.10 organiser selection and duplicate offers.

The selected member can withhold offers or founding material. A hostile lower-key organiser can also displace an honest offer under the duplicate-offer rule. Identity-key ordering is not an availability mechanism, and attackers can generate identities in advance to seek a low key.

**Suggested fix:** Add bounded organiser timeouts and failover. Stop replacing an accepted formation attempt solely because a lower key appears. Bind every acceptance to the exact temporary-group ID, original-group context, settings and offer, so consent cannot be reused for another formation or merge.

## Assumptions that were supported

The basic use of a freshly generated current-position distribution message for newcomers is sound: it contains the current chain seed and public signing key, not the sender's signing private key. This assumes the application actually generates the distribution message at the current position rather than reusing an older cached distribution.

Classical one-time pre-keys are optional in the pinned library. Replay protection for last-resort pre-keys depends on the application's store implementation and durable transactional handling. Signal's [PQXDH specification](https://signal.org/docs/specifications/pqxdh/) documents the pre-key model and its security considerations.

Neither fact supplies checkpoint trust, bounded storage, metadata privacy or partition recovery. Those require the additional protocol rules described above.

The documented window before a sender learns of a remote removal remains an accepted limitation. Findings 9, 10 and 14 concern additional failures beyond that window; they should not be dismissed as unavoidable offline revocation delay.
