# Review of local mesh v3.1 and rotating address labels

**Reviewer:** Codex, at the project owner's request.  
**Date:** 27 November 2026.  
**Revision:** HEAD `66efb2816560d705a313fb1290488dbb4eb885da`, with the working-tree proposals as read, including the uncommitted additions to local mesh v3.1. SHA-256 of `local-mesh-v3.1-group-envelopes.md`: `7d44c85bcb75d6c3a84e312e1bb2e5e46e9abeee4fc3977dbf3f459b3f1383b8`; of `rotating-address-labels.md`: `623d771ff49e7ee374cd59c696ca6ce3808615d22c5a006c4e6ba35acf93e692`.

**Verdict:** five High and nine Medium findings; no Critical finding. The proposed group routing loses its copy bound, its frame classification can be changed without changing the stamp, and the retry and custody rules need a complete state machine. Recognition tags can provide private evidence of a shared contact secret, but cannot establish that the friend is physically nearby. Rotating header labels also leave ordinary one-to-one libsignal metadata exposed. The proposals need revision and further review; this report does not approve them for building.

**This is an AI-assisted review, not a professional security audit.** Findings concern the written designs and their integration with today's code, not reproduced failures of an implemented v3.1.

## Findings

### 1. High — frame type does not bind the group routing policy

**Locations:** v3.1 §§1.1–1.2; `core/src/envelope.rs`, `Header::binding()` and `Envelope::relay_key()`; `EffortStamp.kt`, `meets()`; `SecureModel.kt`, `localRecord()` and `handedOff()`.

Neither the authenticated binding nor the relay key includes the nearby frame type. The stamp authenticates no sender and covers only the relay key. Anyone who records an ordinary envelope and its stamp can send them under type 9, gaining group spreading and retention. Sending a group envelope under type 4 can instead induce ordinary single-recipient handling or make a carrier accept deletion. If first admission determines the stored type, an attacker can race the genuine copy; if duplicates overwrite it, an attacker can change it afterwards. The proposal defines neither case. This is separate from v2's already acknowledged forged `DELIVERED` problem.

**Suggested fix:** give the envelope an immutable routing class covered by its authenticated context, relay key and stamp, with an explicit wire-version migration. Require the outer frame to agree with that class and persist it atomically with custody. Reject conflicting duplicates. Carriers cannot verify end-to-end authorship, but binding the class into the digest and work prevents free reclassification of a captured envelope. Define `DELIVERED` handling from stored class, including outstanding offers, rather than trusting the incoming frame. This does not prevent a sender minting fresh group junk; finding 2 still applies.

### 2. High — one-copy forwarding turns limited-copy routing into epidemic spreading

**Locations:** v3.1 §§1.3, 1.7; `core/src/relay.rs`, `hand_off()` and `pending_for()`; `FloodGuard.kt`; `EffortStamp.kt`.

If a one-copy holder retains its copy after sending one to each new link, each recipient becomes another holder able to do the same. A single such holder meeting N new peers creates N additional holders. Eight hops bound depth, not breadth; 32 is an initial spray parameter, not a maximum number of copies. All descendants reuse the same effort stamp. A 24-hour lifetime bounds duration, but supplies no useful fixed bound on work, radio traffic or total replicated storage per stamped envelope.

Local stores remain bounded, but the network can spend many phones' budgets on one attacker's work. Eighteen bits costs about 262,144 hashes on average regardless of envelope size or replication. Neither this nor per-phone admission throttling restores a network copy bound. A malicious carrier can also reset the unauthenticated hop byte without invalidating that stamp, an existing limitation that becomes more damaging here.

**Suggested fix:** explicitly choose and analyse either bounded replication or controlled flooding. Specify retained/transferred copies, per-envelope transmission limits, outgoing byte budgets, fair scheduling and behaviour on reconnect. If many-recipient availability requires flooding, state that trade-off and measure its upper operating limits; do not describe 32 as a copy cap. Include large opaque junk and rapid link churn in simulations. Software copy limits alone cannot constrain malicious senders, so retain independent local resource limits.

### 3. High — the existing offer and hand-off machinery does not implement the promised delivery rules

**Locations:** v3.1 §§1.3, 1.6 and required tests; `SecureModel.kt`, `offer()`, `handedOff()` and `pump()`; `MeshHub.kt`, `Peer.send()` and `consumeAck()`; `core/src/relay.rs`, `hand_off()`.

The claimed existing rule, “each link is offered each envelope at most once”, is false. `offers` suppresses a pending offer for 60 seconds, `handedOff()` removes it on ACK, and `Peer` has a separate 10-second in-flight suppression. A retained group envelope can therefore be offered repeatedly to the same acknowledged peer. Calling today's `relay_handoff` with `given = 1` at one copy deletes local custody, contradicting continued group carrying.

A literal once-ever offer rule has the opposite failure: a flood refusal or lost frame gets no ACK, but the same live link can never be retried. Also, `pending_for()` returns the oldest candidates before Kotlin excludes already-offered ones. Retaining 128 old group records can hide the rest of a 10,000-record store from that link indefinitely. Existing cooldowns can already impair fairness; lifetime-long exclusions make the problem decisive.

**Suggested fix:** distinguish pending, retryable and durably acknowledged offers per live link. Suppress repeat successful transfers, retry failures with backoff, and clear state on close. Define a group ACK operation that retains the required custody at one copy. Page or rotate through the complete eligible queue before applying the send limit. Test lost ACKs, slow-mode refusals, more than 128 retained records, restart, late ACKs and simultaneous offers; a five-member delivery example is insufficient.

### 4. High — a live tunnel makes a distant friend appear directly connected

**Locations:** v3.1 §§3.1, 3.4–3.5; `MeshHub.kt`, `Peer.readLoop()`.

An attacker near Alice forwards Alice's HELLO to an accomplice near Ravi, forwards Ravi's HELLO back, then tunnels both PROBE frames unchanged. Alice and Ravi see the genuine IDs and nonces from each other and compute precisely the genuine tags. No key, replay or HMAC forgery is needed. Rejecting one's own reflected HELLO does not reject this exchange.

“Direct links only”, “Ravi · Wi-Fi Aware” and “Connected” can consequently make false claims about Ravi's physical presence and the radio path to him. This matters for people using the map to decide whom to approach or trust. Naming the contact as a local label does not remove that implication.

**Suggested fix:** state that recognition proves access to the contact secret through the exchange, with relaying possible. Change proximity wording accordingly and keep recognition out of routing, authorisation and safety decisions. Bind the full transcript to any later authenticated channel to prevent unrelated channel substitution, but acknowledge that ordinary authenticated key exchange still permits a transparent tunnel. Defending physical proximity requires a separately reviewed distance-bounding or trusted physical verification design; an application timeout is not a reliable fix. Add a two-site tunnelling test.

### 5. High — rotating headers alone leave ordinary one-to-one traffic trackable

**Locations:** rotating labels, Problem, Goals and §§2–4; group encryption §3.4; `endpoint/src/engine.rs`, `encrypt()` and `receive()`.

Today's ordinary envelopes carry a libsignal type byte followed by the library serialisation. Changing destination and hint does not hide the established-message ratchet public key and counters or the initial pre-key message's identity material. A ratchet key can recur across hourly label boundaries; initial identity material can be permanent. Group encryption recognises this problem and wraps group messages and pairwise hand-over pieces. Its stated KDF context requires a group ID and does not specify ordinary one-to-one chat, receipts or bootstrap traffic.

The combined update therefore does not yet meet its claim that nothing exposed to nearby strangers is permanent or trackable. This finding does not imply that relays can decrypt the inner message.

**Suggested fix:** specify an outer privacy layer for every affected one-to-one message kind, including first-session bootstrap, with its own reviewed context and recovery rules. Establish its key before exposing an initial libsignal message. Treat the one-time pairing route separately. Test actual complete wire captures across epoch boundaries, rather than testing destination fields alone. Preserve explicit limits for retries, timing and size correlation.

### 6. Medium — targeted eviction is affordable, and resynchronisation is only conditional repair

**Locations:** v3.1 §§1.5–1.7; group encryption §§3.6–3.7, 3.9; `core/src/relay.rs`, `admit_carried()`; `native/android/src/lib.rs`, carrier configuration.

An attacker need not identify key batches. Continuously admitting newer stamped junk pushes all older mail out. At about 56 KiB per envelope, roughly 1,171 new envelopes replace a full 64 MiB of content; the expected stamp work is about 307 million hashes in total. The native carrier's additional limits, 256 KiB per connection per minute and 1 MiB globally per minute, slow this down but do not prevent sustained eviction. These are important bounds absent from the proposal's flood discussion; actual throughput can be lower.

The label-independent pairwise generation check is a sensible way to discover a missed key. It repairs current access only if that exchange and the hand-over eventually get through, while membership, session and pre-key state remain usable. Continued eviction defeats it. A newer generation does not recover ciphertext whose old key was never received and has since been erased. “Slow mode delays but loses nothing” is also too strong with expiry, bounded stores and finite contacts.

**Suggested fix:** document conditional eventual recovery and possible permanent message loss. Specify how generation checks occur over indirect paths without depending on the missing group label or a successful friend probe. Add fair admission/egress and byte budgets without revealing key-batch priority. Test sustained eviction, expired old generations, sleeping phones and lost recovery traffic; do not promise eventual delivery under continuing denial of service.

### 7. Medium — receipt compatibility works in only one direction

**Locations:** v3.1 §2.1; endpoint contract, Messages and receipts; `endpoint/src/engine.rs`, `receive()` kind 2.

A new sender can accept a 16-byte receipt from an old recipient. An old sender rejects a 17-byte receipt from a new recipient because it requires `payload.len() == 16`. It therefore does not clear the outbox or mark delivery, despite the text having arrived. The recipient's duplicate cache resends the same incompatible receipt, so retries do not heal this combination.

**Suggested fix:** negotiate the receipt capability through authenticated version/capability data and send 16 bytes to older peers, or require an explicit incompatible version bump and re-pairing. State which policy this update uses. Keep parsing strict to exactly 16 or 17, validate the added value as 0 through `MAX_HOPS`, retain contact/generation matching, and atomically save the first receipt's exact bytes and route. Test all four old/new pairings and duplicate retry after restart. A defaulted optional snapshot field is otherwise a reasonable migration mechanism.

### 8. Medium — probe transcript encoding and receive state are underspecified

**Locations:** v3.1 §§3.1–3.3; `MeshHub.kt`, HELLO handling.

The HMAC contains sender then receiver IDs, but `nonceA` and `nonceB` have no canonical definition. If each implementation interprets A as itself, peers disagree. If IDs are sorted without a distinct sender role, reflection protection can disappear. With distinct fixed-width sender/receiver IDs in the stated order and a canonical nonce order, simply echoing one's own tag does not match the expected peer tag. A previous link's tag also fails when a fresh local challenge is included. Those properties must be specified, not left to naming conventions.

Eight random nonce bytes give a birthday collision probability of approximately q(q−1)/2^65 after q links in an otherwise repeated context. The honest phone's fresh nonce protects against an attacker choosing or repeating its own, and link IDs add context, but IDs persist for 10–20 minutes and cannot replace a per-link challenge. No practical ordinary replay attack follows merely from using eight bytes; sixteen bytes is cheap additional margin.

**Suggested fix:** define exact raw-byte encodings, role and nonce order, reject equal/own IDs, and bind immutable HELLO values for the link's lifetime. Accept exactly one correctly sized PROBE after HELLO; define duplicate, early, late and multiple-match handling. Use fresh CSPRNG filler and shuffle. Keep role separation when strengthening the transcript. Add swapped-role, reflection, stale-probe, repeated-ID, malformed-length and simultaneous-connect vectors. A 16-byte tag supplies ample forgery margin; the weakness here is protocol definition, not a need for a homemade primitive.

### 9. Medium — fixed probe length does not bound computation or hide behavioural matches

**Locations:** v3.1 §§3.1, 3.3 and How the parts fit together; `MeshHub.kt`, frame budget and `Peer.readLoop()`; `SecureModel.kt`, serial endpoint work.

To an observer without any relevant key, correctly generated 128-tag lists reveal neither the real slot count nor identity through their contents. Constant-time equality alone does not make the full operation constant time: HMAC generation, matching across contacts, early return and match-dependent subsequent traffic can expose contact count or a match. Repeated reconnects create unstamped 2 KiB exchanges and endpoint work; limiting PROBE to one per link does not bound churn across links. Existing frame budgets help within a link but do not establish an aggregate recognition budget.

**Suggested fix:** bound global handshake rate, concurrent recognition and endpoint queue work; define overload behaviour and scheduling. Pad work to a documented contact capacity if contact-count timing privacy is a requirement. Process the full bounded match set, avoid match-specific wire replies and keep subsequent mesh behaviour independent of the result. Measure whether a match changes timing, traffic, connection retention or logs. Keeping a name in local memory is not by itself an over-the-air leak; do not claim one without an observable difference.

### 10. Medium — static identity derivation makes compromise long-lived across all three uses

**Locations:** rotating labels §§1, Risks 1 and 3; v3.1 §§3.2, 3.5; group encryption §3.4.

Static X25519 plus HKDF can produce a suitable shared secret when the verified key encodings and DH validation are correct. The more serious problem is lifetime. Seizing either endpoint's identity private key and the relevant public identities permits recomputing this pair's label key indefinitely. Erasing the stored `labelKey`, retiring a session or deriving `linkKey` only in memory does not remove that ability. The attacker can test recorded labels, derive past/future outer keys and recognise live probes until a genuinely new secret is installed. Inner libsignal protection remains a separate layer.

The claim that a separate HKDF salt means a flaw in one use cannot reach the others is too broad. Purpose-separated KDF outputs avoid accidental key reuse; exposure of the parent compromises every child. The proposed `linkKey` derivation itself is a valid HKDF-shaped use with a distinct domain and empty info; no specific attack on that derivation was found. RFC 5869 explicitly allows empty info and describes binding context through it. [RFC 5869 §§2–3](https://www.rfc-editor.org/rfc/rfc5869.html).

**Suggested fix:** prefer a random pair key installed through an authenticated, confidential session, with deterministic initiation, conflict resolution, acknowledgement and bootstrap routing specified. Define separate purpose keys and versioned contexts. Rotate from fresh authenticated secret material, with bounded overlap for delayed mail and deletion of retired roots; do not merely rerun static DH. Reject all-zero DH outputs if retaining that option, specify identity-key encoding and sorted concatenation, and bind both identities. RFC 7748 discusses the zero-output and identity-binding considerations. [RFC 7748 §§6–7](https://www.rfc-editor.org/rfc/rfc7748.html).

A seized friend's key enables active recognition and impersonation of that friend. Rotating automatically can narrow a *past snapshot* compromise once fresh material is exchanged and old keys expire; it cannot exclude an attacker still controlling an authorised friend. There is no cheap unilateral rotation that both cuts off that holder and preserves their unattended access. Deletion/revocation and authenticated re-pairing need explicit semantics.

### 11. Medium — the over-128 policy has unacknowledged recognition and privacy limits

**Locations:** v3.1 §§3.1, 3.5; `endpoint/src/engine.rs`, `validate()` and pairing admission.

Today the endpoint caps contacts at 64. The proposed over-128 behaviour therefore needs a separate capacity decision, storage budget and migration; it is not reachable by just adding probes. If N contacts are supported, one specific friend appears in a sent probe with probability 128/N. Each side samples independently, so recognition can be one-sided. Failure to recognise proves nothing about identity or presence.

Fresh random subsets do not by themselves leak a linkable fingerprint to a keyless stranger: it cannot identify the slots. A holder of one pair key can, however, test that contact over repeated links. Recognition at least once after L independent links has probability 1−(1−128/N)^L. At N=256, ten links give about 99.9%. Several colluding contacts can learn more from inclusion frequencies and co-occurrence. This qualifies “on any link” in the seizure claim and means random omission is not a durable privacy defence.

**Suggested fix:** document asymmetric and probabilistic recognition, keep anonymous fallback neutral, and distinguish keyless observers from hostile contacts. Specify the supported maximum, sampling distribution and full matching set. Do not silently raise the existing 64-contact limit. Test repeated-link disclosure and independently sampled peers before presenting sampling as protection.

### 12. Medium — hourly label claims and epoch validation need correction

**Locations:** rotating labels §§2–3, Risks 2 and Test plan; `endpoint/src/engine.rs`, `receive()`; group encryption §3.4.

All envelopes in one pair-direction-hour intentionally share a destination. A thousand messages over 24 hours cannot all have distinct destination values; the test plan's claim is false. Retries retain IDs, ciphertext and creation-hour labels, so a delayed envelope can repeat yesterday's label today. Observing the same envelope across a boundary also links its holders. Rotation reduces a particular stable header signal; it cannot establish general unlinkability.

The 27 integer epochs from current−25 through current+1 cover 24-hour delivery with the stated small clock tolerance. They are not a precise expiry check. Today's receive code checks expiry and at most five minutes of *future* creation skew; it does not require all clocks to be symmetrically within five minutes, nor does it prove the wall clock is correct. Merely authenticating `created_ms` and destination does not prove their mathematical relationship. For an ordinary pairwise message, a key-holding sender can authenticate a destination from a different accepted hour unless the receiver explicitly recomputes and compares it.

**Suggested fix:** define epoch as an unsigned, fixed-width, big-endian integer, direction comparison on canonical identity bytes, exact window endpoints and safe arithmetic. Recompute the expected label from authenticated creation time and the selected contact/direction. Keep exact lifetime/expiry checks separate from lookup windows and clock rollback handling. Test repeated labels within an hour, changed labels across hours, delayed retries, boundary skew and mismatched authenticated epoch/label pairs. Retain old group label keys for delayed generations as required by group encryption.

### 13. Medium — internet fallback can reintroduce the permanent nearby identifier

**Locations:** rotating labels §4; v3.1 Not covered; `SecureModel.kt`, `pump()` and internet relay receive path; `relay-server/src/main.rs`.

The internet decision remains unresolved although the changes ship together. Registering successive labels under one authenticated mailbox lets that server link them. A fixed server-only destination leaks that link to nearby strangers too if its envelope enters the ordinary outbox pump: today's pump sends the same raw envelope to local links and the server, and also exposes carried records to the internet path. Calling the mailbox “server-only” does not enforce separation.

The server currently matches destination tokens; it has no group recipient fan-out design. Excluding this work from v3.1 does not make current internet group delivery work. A server may also correlate credentials, connections and timing even after destination rotation.

**Suggested fix:** settle whether this update disables the server, retains a deliberately linkable server path, or implements a new mailbox protocol. Specify separate transport wrappers or enforceable path restrictions so permanent mailbox values never enter nearby traffic. Define what happens to group traffic and server-fetched envelopes. Test both pumps and reconnection; Tor consideration alone supplies none of these semantics.

### 14. Medium — key batches still lack a complete outer addressing and receive contract

**Locations:** v3.1 §§1.1, 1.4 and 2.2; group encryption §§3.4, 3.7, 3.9; rotating labels §3; `SecureModel.kt`, `receive()`.

The proposals define per-piece pairwise labels and encryption, and group-chat labels from a sender's group label secret. They do not fully specify the outer destination/class/encoding of a multi-recipient hand-over batch, how a receiver identifies its pieces, or how the host both processes its piece and retains the entire envelope for others. Today's host takes mutually exclusive endpoint-versus-carrier paths from the one outer destination. Its proposed single-label “mine?” replacement is insufficient for a batch whose recognisable labels are inside it.

Encrypting the whole batch under the new group label secret would prevent exactly the newcomer or behind member who needs that hand-over from opening it. Using only an old group secret also needs a defined newcomer path. The independently encrypted pieces offer a way forward, but the container and admission rules must actually say how.

**Suggested fix:** write the batch wire format, bootstrap-visible routing fields, bounded piece scan and per-piece lookup/decryption rules. Define endpoint acceptance separately from carrier custody so consuming one piece never deletes or edits the batch. Bind each piece's authenticated context as already required, reject invalid containers within resource limits, and specify duplicate acknowledgements across re-batching. Test a receiver holding no current group label key and receiving its first piece through a stranger.

## Route counts and metadata that remain acceptable only with explicit limits

The hop observations in v3.1 Part 2 are substantially correct. A direct sender's stored envelope leaves with eight; each carrier-store forward decrements it, and the TLS relay test expects seven. The reported value measures that mechanism, not a verified physical route. A carrier can set eight on the **first** arriving copy just as it could on a later one; retaining the first is a consistent display rule, not protection against false “Direct”. Use wording such as “Reported direct; route unverified” alongside the value. Never let it imply proximity, trust, confidentiality, an internet-free path or a delivery guarantee. No authenticated hop-chain design is supplied here.

An authenticated receipt proves what the recipient reported, not that the network report was true. Validate receipt hop values, including rejecting 9–255, rather than letting Kotlin's `>= MAX_HOPS` turn them into Direct. Zero is a valid terminal arrival: `forwarded()` permits 1 → 0 and endpoints can receive it, although a carrier rejects storing zero. A receipt's own mutable hop count describes the return path and must remain separate.

Returning the forward count gives the sender information it previously lacked about the recipient's route. Encryption hides the count from strangers, but the changed receipt length reveals the new format, and a malicious carrier able to vary the forward byte can try to encode a marker for a cooperating sender. The count exposes no relay identities and is coarse; describe this disclosure rather than claiming it adds no metadata. Keep the exact first receipt on duplicate delivery so later hop changes cannot rewrite it. Group sent-message routes being unavailable is consistent with the proposed absence of ordinary group receipts.

Marking group traffic reveals more than an isolated group bit: observers can correlate size, bursts, unchanged relay keys, retention and repeated movement to estimate group activity or likely key-change events. The frame alone does not identify a group or prove whether it contains keys. Large multi-piece batches can be distinguishable statistically, and clear piece-label sets may associate pairwise traffic within an hour. Fixed random sender hints and the outer encryption do not hide these patterns. State this residual exposure beside §1.1's trade-off.

## Checks against today's code

| Proposal claim | Result of source inspection |
| --- | --- |
| Binding has 68 bytes and excludes hops; relay digest excludes hops | Correct. The digest covers immutable encoding and ciphertext after zeroing byte 68, rather than literally hashing every byte unchanged. It also does not include mesh frame type. |
| Sender uses literal eight; relay-store sends call `forwarded()` | Correct in `engine.rs::encrypt()` and `relay.rs::pending_for()`. `tls_relay.rs` asserts eight becomes seven. |
| Incoming hop is dropped; history has no hop field | Correct in current `StoredMessage` and incoming text construction. Group receive remains future work. |
| Second message copy is ignored | Qualified: an exact immutable digest match returns cached custody and can schedule the stored receipt; it neither appends history nor recomputes the route. Arbitrary changed bytes are not silently accepted as the duplicate. |
| Nearby carrier holds 10,000 envelopes/64 MiB and evicts oldest | Correct in native carrier configuration and relay admission. Oldest is local acceptance time, with key tie-breaking. Byte limit excludes database/index overhead. Server limits are 1,024/8 MiB. |
| Offer tracking already means once per link | Incorrect; see finding 3. Tracking lives principally in `SecureModel.kt`, with separate `Peer` in-flight state. |
| Flood slow mode is the only admission bottleneck for removal bursts | Incomplete. Native carrier byte/envelope budgets apply as well. No ACK means retry remains possible, not guaranteed eventual delivery. |
| A 2,048-byte PROBE fits `MAX_FRAME` | Correct: frame length including type is 2,049, below 65,537. Types 9/10 are currently rejected by the parser. |
| Group envelopes can start at 32 with only the listed host changes | Incomplete. Rust `MAX_COPIES` and Kotlin `Spray.MAX_COPIES` are both 12; admission clamps and frame decoding enforce them. Class-specific limits need coordinated changes. |
| Native JSON pass-through needs no route schema change | Plausible for the JSON bridge; not demonstrated by building. All constructors, snapshot loading and Kotlin mapping still need checks. |
| More than 128 contacts can be sampled | Hypothetical under today's 64-contact endpoint limit. |

## Evidence, coverage and file boundary

Read both target proposals, local mesh v2, the specified group-encryption sections, the endpoint contract, audit finding 1b, repository security/architecture/group guidance and the requested source files. Findings answer request questions 1–15: group classification/bounds/custody in 1–3 and 6/14; routes and receipts in 7 and the route discussion; recognition in 4 and 8–11; label lifecycle, clocks and relay integration in 5 and 10–13. The group marker's metadata implications are stated above.

A read-only Python check using standard-library HMAC confirmed that ordered sender/receiver transcripts distinguish an echoed local tag, that a fresh local nonce changes the expected tag, and that an unchanged transcript through a transparent tunnel produces identical genuine tags. It also checked the subset and store-fill arithmetic quoted above. This is reasoning support, not an implemented handshake test or an independent validation of libsignal. RFC references support the KDF/DH observations; they do not certify this protocol.

No build, full test suite, radio test or simulation was run: these proposals are not implemented, and such runs would not establish their claimed properties. The code claims above are source-inspection results. Only this new report was written; existing modified proposals and untracked audit/simulation work were left intact.
