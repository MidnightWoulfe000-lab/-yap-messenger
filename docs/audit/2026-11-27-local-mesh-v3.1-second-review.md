# Second review of local mesh v3.1, rotating labels, emergency wipe and copy counts

**Reviewer:** Codex, at the project owner's request.  
**Date:** 27 November 2026.  
**Revision:** HEAD `a2bd5b871f7a8052e94b0c495ca3d90c0a3b586e`, with the working tree as read. The proposals are designs, not implemented features.

**Verdict:** nine High and six Medium findings; no Critical finding. The revisions resolve the proximity wording, receipt compatibility decision and several earlier specification errors. They do not yet establish a copy bound under hostile peers, a complete custody protocol, confidential pairing, or a working pair-secret lifecycle for contacts and group-only sessions. The wipe draft needs a complete storage inventory and an interruption-safe execution contract. The simulator is reproducible for the seed rerun here, but differs from the proposed wire protocol and today's hop and admission rules; it supports investigating higher copy counts, not treating 96 and 255 as validated operating limits. Further specification and review are needed. Nothing in this report is approved for building.

**This is an AI-assisted review, not a professional security audit.** Suggested fixes identify requirements and choices for review; they do not settle new cryptographic or group-governance policy.

## Findings

### 1. High — the claimed carrier bound depends on honest membership and copy claims

**Locations:** local mesh v3.1 §§1.3–1.4, 1.8; local mesh v2, “Spray and wait”; `core/src/relay.rs::admit_carried()`; `EffortStamp.kt::meets()`; simulator `Run.offer()`.

With honest peers, serial transfers and conserved allocations, splitting limits stranger carriers. Giving additional singleton copies exclusively to genuine members does not by itself create further stranger allocations: those members can pass their singleton only to other members. The simulator's exact `member[]` knowledge models that restricted case. The bound is not a bound on all copies, since member copies are additional, and its historical-carrier interpretation also assumes no renewed source allocations or re-admission after forgetting.

A stranger can answer `mine` without knowing a group key. There is no membership proof in WANT. A singleton holder retains its copy while giving the liar another, and the liar can solicit the same envelope from other holders or under new link IDs. This does not reveal the encrypted content, but defeats the claimed bound on strangers holding it and consumes delivery resources. If malicious holders propagate the lie, eight hops constrain depth rather than breadth. Even one malicious holder can change its own remaining count to 255 and inject large allocations into fresh honest regions, using the captured envelope's existing stamp. The same unauthenticated-count issue affects one-to-one envelopes at 96. Clamping bounds one advertised allocation, not the sum of allocations across the network. The v2 assertion that copy lies spread only an attacker's *own* messages is false: captured messages work too.

**Suggested fix:** state the honest-participant assumptions beside the bounds and simulate lying `mine` answers, count resets, churn and re-admission. Keep independent storage, ingress and egress limits. Decide whether private recipient evidence is required or whether the disclosure and abuse costs of self-declaration are accepted; do not invent a membership proof during implementation. Retain “recognised” as display-only: a contact probe does not prove group membership.

Copy-priced work could raise the cost of minting high-allowance junk. It cannot prove that a carrier has conserved copies. Specify an immutable initial allowance/work tier, its validation and its relation to mutable remaining counts. Hashing the *current* count into a reusable stamp would invalidate the stamp at the first split; merely checking work against a lowered count does not prevent later reuse at the original allowance. Binding the initial allowance into the immutable envelope is one option to review. Include size and measured phone cost in the evaluation; an extra four bits means 16 times expected hash work, not a demonstrated few-second bound. Do not describe this undecided idea as fixing malicious replication.

### 2. High — OFFER cannot distinguish a last copy from a one-copy spray allocation

**Locations:** local mesh v3.1 §§1.3–1.4; `EffortStamp.kt::Spray.give()` and `Spray.decode()`; `SecureModel.kt::receive()`; simulator `Run.offer()` around lines 325–368.

OFFER carries the count the sender would *give*, not its remaining count or an eligibility flag. A holder with two or three copies offers one when splitting; a holder with one also offers one, but only to a recipient/member. The receiver is told to omit a one-copy offer when it is not a member. It therefore refuses the final spray allocations as well as genuine wait-phase deliveries. This changes propagation and invalidates direct application of the simulator's results: its `offer()` sees the sender's true count and successfully transfers a one-copy split to a stranger. A focused check reproduced that transfer, leaving one copy at each phone.

The OFFER also lacks routing class. An opaque destination label does not reveal whether it is a one-to-one recipient label, group-chat label or eventually a batch requiring a piece scan. The envelope class can be checked on arrival, but it does not supply the missing pre-send decision.

Today's code already declines a non-recipient's received count of one, even if it was half of two or three. That is a current-code/model mismatch, not evidence that the revised rule is implemented correctly.

**Suggested fix:** define class and transfer eligibility in the offer contract, separately from the allocated count, and require the eventual envelope to agree. Define zero and invalid counts explicitly. Add two- and three-copy splits to strangers, singleton wait offers, stale offers and class mismatches to protocol tests, and make the simulator use the same decision inputs available on the wire.

### 3. High — reservations and uncertain custody remain outside the retry state machine

**Locations:** local mesh v3.1 §1.4; `SecureModel.kt::pump()`, `offer()` and `handedOff()`; `MeshHub.kt::Peer.send()` and `consumeAck()`; `core/src/relay.rs::hand_off()`.

Paging and explicit settled state address the previous queue-starvation problem, but the three states do not define how copies are reserved or committed. Today's pump reads a source count once and offers the same half to every local peer before ACKs reduce it. With 96 copies and 12 links that can advertise 12 allocations of 48. Carriers also debit only on ACK. Sequential execution on one worker does not make these asynchronous transfers serial reservations. Subtracting later, or clamping at one, cannot undo copies already accepted.

Suppose B durably accepts A's allocation but its ACK is lost. On retry B omits the entry because it now has it; A marks it settled. There is no stated rule for whether A spends the outstanding allocation, releases it, or distinguishes B's earlier possession from acceptance of this transfer. Releasing an uncertain reservation can mint copies; keeping every unanswered offer permanently reserved can strand them. Disconnects, process death and source-count reset on restart have the same problem. Today's source counters are memory-only and refill after restart; the revision does not explicitly replace that accepted development limitation.

There is also no state for “offered, awaiting WANT”. A lost WANT can leave an entry neither wanted/in flight nor settled. Omission is overloaded between already held, recently forwarded and ineligible singleton; it is not proof of current durable custody. “No phone is sent what it already has” cannot be absolute when a second link delivers it between WANT and data arrival.

**Suggested fix:** write the transition and reservation contract before coding. Cover outstanding OFFER/WANT exchanges, a bounded response timeout, transfer identifiers, ACK correlation, atomic reservation/debit, duplicate replies, uncertain completion, concurrent peers, eviction between WANT and data, disconnect and restart. Persist the state needed for the chosen honest-peer copy guarantee. Separate custody evidence from a peer's unverified assertion. Require cursor-based paging stable across inserts/expiry, and bound per-link offer state for the whole store. Test loss at every transition; backoff values alone do not solve accounting.

### 4. High — WANT creates an unstamped inventory and recipient-label oracle

**Locations:** local mesh v3.1 §1.4 and “How the parts fit together”; rotating labels §4; `MeshHub.kt` frame admission.

The revision correctly identifies an unresolved inventory leak. The new `mine` distinction adds a more direct one: an attacker with an observed hourly label can offer that label under a chosen relay key and ask whether a phone recognises it. OFFER has no envelope or valid stamp to prove that the offered key, destination and count belong together. A bounded but repeated list of chosen queries can test possession and label ownership without supplying authentic ciphertext. `mine` identifies a likely one-to-one recipient or group member for that label; `carry` distinguishes it from an ordinary willing carrier.

Omission alone is not a reliable “has it” answer, because the rules also omit ineligible or recently refused entries. Repeated comparisons and `mine` still give useful information. Stable relay keys and repeated possession sets can follow phones across link-ID changes. Existing offers already disclose the *offering* phone's inventory, but they do not justify saying that active, chosen-label queries against the answering phone add nothing. A per-link frame budget does not bound aggregate queries across reconnects.

**Suggested fix:** decide and document the privacy contract before this update. Evaluate hiding the `mine` distinction, possession-query resistance and a phone-wide query/work budget, while resolving findings 1–2. Random false wants may blur omission-based inference but do not hide a distinct `mine` response. A one-hour answering limit still permits queries within that hour and can strand older mail. Evaluate the actual attack and delivery trade-off for each option; do not claim fixed-size or random-looking labels conceal an explicit response about them.

### 5. High — pairing-link confinement does not make initial identity traffic confidential

**Locations:** rotating labels §2; local mesh v2, “Wi-Fi transport” and pairing; `MeshBluetooth.kt` socket creation; `MeshWifi.kt`; `MeshWifiAware.kt`; `SecureModel.kt::localRecord()` and `pump()`.

Keeping bootstrap off carrier paths is necessary, but the local link is not a reviewed confidential, authenticated channel. Bluetooth uses `createInsecureL2capChannel()` and `listenUsingInsecureL2capChannel()`. Shared-network Wi-Fi uses ordinary TCP sockets, and Aware first tries an open data path, then a public hard-coded passphrase explicitly documented as providing no secrecy. Any underlying radio/network protection must be assessed per transport; there is no common application-layer pairing encryption. At least a peer on the path can inspect these streams. The direct pairing peer may also be a transparent tunnel rather than the physically scanned phone.

Bundles and signed confirmations themselves contain permanent public identity material. Libsignal protects the pair secret in its first encrypted message, but its prekey framing exposes the sender's identity key. Restricting that frame to a socket does not hide it from an observer of that socket. Fixing only the first libsignal frame would also leave the bundle and confirmation exposure.

Today's host confines confirmations using `pairingLinks`, but sends other endpoint pending packets, including encrypted bootstrap, to every local link and the configured internet relay. The new confinement must be an enforced packet-routing property, not an inference from a signed confirmation: its signature authenticates the transcript, not ownership of the transport endpoint.

**Suggested fix:** specify and review confidential pairing for the complete transcript, including bundles, confirmations and initial prekey traffic, with verification bound to the QR exchange. Do not choose a new handshake in implementation. Define a path-restricted bootstrap queue and cancellation/re-pairing semantics on link loss; test complete captures on every supported transport and via an active tunnel. Until that work is settled, retain the explicit pairing metadata limitation and do not claim strangers cannot observe permanent identifiers.

### 6. High — pair-secret activation and replacement can split the two phones' key state

**Locations:** rotating labels §§1–2, 4–5; endpoint contract, bootstrap and exact retries; `endpoint/src/engine.rs::confirm()` and `receive()` kinds 3–4; group encryption §3.6, “Copied phones”.

Choosing the smaller identity as initiator, using fresh randomness and acknowledging installation are improvements. The proposal does not specify the control encoding, initial number, acknowledgement binding, durable installation, activation point, duplicate handling or rollback rules. Today's first message is an empty kind-03 bootstrap with an exact cached kind-04 response; a secret-bearing first message changes that contract rather than fitting it unchanged. If both phones send secrets, “the smaller one wins” must apply before readiness, including an ACK or user message arriving before the winning secret. No losing secret may briefly become active.

For replacement, B can install the new secret and send its ACK while A never receives that ACK. The text does not say which secret wraps B's ACK, which secret either phone uses to send, or whether A retains and accepts the pending secret. An ACK wrapped under a key A has not activated can create a deadlock. Repeated application messages cannot simply be re-decrypted by libsignal; its duplicate behaviour requires exact cached acknowledgement handling. A lost ACK or restart must not reset the 48-hour timer or reinstall an older numbered secret. A replacement created now but delivered weeks later also needs a rule for when the next seven-day interval begins and how many pending/previous roots may exist.

The promise about copied phones needs qualification. Replacing a copied pair secret can exclude a holder of that secret alone. A complete plaintext endpoint snapshot also contains libsignal session state, which may let a passive copier decrypt replacements until the session has actually recovered from compromise. Group encryption §3.6 already states that recovery condition. An offline pair cannot complete replacement on a seven-day schedule; overlap cannot start at a mutually observed instant merely by saying “acknowledged”.

**Suggested fix:** specify and review a crash-safe per-contact secret state machine with canonical sequence numbers, authorised initiator, pending/current/retired roots, monotonic acceptance, exact retry/ACK records and explicit wrapping/activation rules. Bound pending retention and define offline recovery without silently reverting to identity-derived keys. Test simultaneous initial sends, lost proposal/ACK, duplicate libsignal messages, expiry, sleep and crashes at each commit. State separately what an old pair-secret leak and a complete endpoint compromise permit.

### 7. High — group-only sessions cannot obtain the new pair secret by the stated route

**Locations:** rotating labels §§1–2 and “labelKey … group encryption's pairwise pieces”; group encryption §§3.2–3.4, 3.7, 3.9; local mesh v3.1 §§1.5, 3.5 and “How the parts fit together”.

Group members need not be paired contacts. Group encryption opens pairwise sessions remotely from prekey bundles in the log, including hand-overs when no sessions are open. The random `pairSecret` is only installed after mutual contact pairing, and its first message may travel only on that pairing link. Two existing members meeting indirectly have no such link or shared random root. They cannot compute the pairwise piece label/outer key before receiving the first secret, and cannot receive that secret through the specified route. Wrapping a first message under the as-yet-unshared root is circular.

This remains even if the batch container is completed. Requiring every group pair to meet again would change the owner's offline group design and cannot be silently inferred. The group proposal's clear-identity cheap prekey admission check also needs reconciliation with a wrapper intended to hide that identity. Group-only sessions must not automatically consume paired-contact recognition slots.

**Suggested fix:** define a separately reviewed bootstrap and addressing contract for group-only pairs, including first prekey messages, missing secrets, resynchronisation and membership admission. Reconcile all three proposals and the existing outer-layer vectors. Do not fall back to exposed identities, static identity DH or an assumed shared root without an explicit design decision.

### 8. High — interrupted wipe has no durable ordering or restart barrier

**Locations:** emergency wipe, “What it deletes”, “After the wipe” and tests; `EndpointRepository.kt::open()` and `EncryptedEndpointStore`; `SecureModel.kt` worker/pump and `close()`; `MeshService.kt::onStartCommand()`; native `relay_close`.

“Finishes on next start” is currently only a test requirement. There is no operation sequence or durable marker that makes it true. If the snapshot is deleted first, ordinary startup sees no state and can create a fresh identity while old carrier/profile data remains. If the Keystore key goes first, a surviving snapshot fails closed and ordinary startup cannot open it to discover a wipe request stored inside it. If deletion races a queued operation, that operation can write old state again or publish old packets. `SecureModel.close()` does not by itself provide a durable wipe transaction or close the native relay handle.

The foreground service returns `START_STICKY` and can recreate the runtime after process death. Killing the process as the final step does not stop that restart policy. A marker read only after repository opening, or only by the activity, is too late. Clock lockout or corrupt storage must not prevent an already requested wipe from continuing.

**Suggested fix:** write an interruption-safe contract: durably record the request outside the snapshot/key being destroyed; block every entry point before repository opening and radio/service startup; stop transport publication and drain or cancel old worker activity; close the native relay handle; delete the old key and the complete data inventory; verify completion; then retire the marker and permit a deliberate fresh start. Specify filesystem sync, Keystore/deletion failure behaviour and process termination without claiming one atomic transaction across both systems. Test power loss after every durable step, service restart, queued callbacks, storage errors and an unreadable old snapshot. Never show success or start a new identity while old data may still be live.

### 9. High — wipe omits an identifying store and overstates what deletion proves

**Locations:** emergency wipe, introduction, deletion list and limitations; `EndpointRepository.kt::EncryptedEndpointStore`; `Profile.kt::ProfileStore`; `SecureModel.kt::myProfileName()`; `core/src/relay.rs`; audit finding 6.

The default snapshot is `noBackupFilesDir/messaging/endpoint-v1.bin`, encrypted with Keystore alias `org.yapmessenger.endpoint-v1`. AtomicFile recovery/new-write files must be included, not just the base filename. Separately, `ProfileStore` keeps `name`, `colour` and `onboarded` in `yap_profile` shared preferences. `SecureModel` reads that name for pairing. Deleting the snapshot and its key does not remove that name or the in-memory profile object, so a supposedly anonymous fresh identity can retain and later send the old display name.

The relay database at `noBackupFilesDir/messaging/relay-v1.redb` is separate and has no endpoint-snapshot encryption layer. Keystore deletion does not cryptographically erase its retained envelope headers, ciphertext and routing records. Close and delete it explicitly, while acknowledging recoverable flash remnants. Future group stores and scramble password/counter state need named destinations before claiming “all Yap data”.

Deleting the AES key is a reasonable way to make leftover *encrypted snapshot copies* unusable through that key, assuming deletion succeeds, the key cannot be recovered and plaintext/usable decryptions were not already copied. A file copy is not the same as an earlier decrypted snapshot. The current code does not establish hardware-backed key storage on every phone. Android distinguishes non-exportability from optional hardware binding in its [Keystore documentation](https://developer.android.com/privacy-and-security/keystore). Key deletion cannot erase previous plaintext extraction, the process's Java strings, screen captures or another device's data.

The snapshot's exclusion from ordinary Android Auto Backup is correct: see [Android's backup exclusions](https://developer.android.com/identity/data/autobackup). This build additionally disables backup and excludes cloud/device-transfer data in its XML rules. That does not rule out privileged or manually made copies. The draft honestly acknowledges flash, immutable Java state and friends' copies, but “nothing left to find or use” and the name “Go anonymous” still promise more than these limits support. Starting a new identity does not prevent observers correlating the same phone by timing and radio presence.

**Suggested fix:** name every persistent store, alias, recovery file and in-memory owner; explicitly include the profile and pending settings writes. Qualify cryptographic erasure to the encrypted snapshot and successful key deletion. Describe the action as deleting local Yap identity/data with stated limits, rather than guaranteeing anonymity. Test a retained old ciphertext copy after key deletion and after fresh-key creation, profile removal, relay closure/removal and memory/UI reset. Process termination is a reduction in exposure, not verified RAM erasure.

Local wipe and owner-triggered scramble can share this full-device deletion executor after their separate authorisation checks. Group-triggered scramble must remain group-scoped: deleting the shared snapshot key or whole carrier store for a group removal would wipe unrelated contacts and groups, contrary to GROUP_MECHANICS §6. Keep remote password/age/replay checks separate from the local button, and bind old remote commands to the old device/identity so a fresh installation state is not a new target. The draft's choice to leave old group membership in place is explicit and does not itself conflict with scramble; local deletion is not remote revocation.

### 10. Medium — authenticated routing class conflicts with the fixed 68-byte binding descriptions

**Locations:** local mesh v3.1 §1.1 and “One update”; rotating labels §5; group encryption §3.4; `core/src/envelope.rs::Header::binding()`, encoding and relay key; `SecureModel.kt::envelopeKey()` and receive offsets.

The class-in-binding design fixes free frame reclassification in principle. The current 68-byte binding is already fully allocated: magic/version 4, ID 16, destination 16, hint 16, creation 8 and expiry 8. Adding a class byte makes it 69 unless the layout is explicitly redesigned. Both outer-layer specifications still require exactly 68 bytes of associated data. An implementer following those literal lengths could omit an immutable field or disagree with other endpoints. Merely increasing the version does not define offsets, authenticated plaintext boundaries or digest canonicalisation.

**Suggested fix:** publish the exact new versioned wire layout and binding/AD bytes, update both proposals and vectors together, and require frame/class validation before class-dependent actions. Update the Rust decoder, Kotlin hard-coded hop/destination/ID handling and size checks together. Reject old persisted/outstanding envelopes under the stated incompatible upgrade, not just old incoming frames. A class-changing immutable edit should produce a different relay key; same-key/different-class rejection is defence against inconsistent metadata, not the main binding mechanism. Carriers still verify work and structure, not end-to-end authorship.

For kind `0x03`, the stated fixed-width HKDF info is otherwise unambiguous: 1 + 16 + 32 + 32 + 8 = 89 bytes, distinct from kinds `0x01` and `0x02`, with sender/recipient order separating directions. A random 24-byte nonce and the `YGO1` envelope format match group encryption. Use explicit conversion between canonical 33-byte identity encodings and the 32-byte key fields, match label root and wrapping root, and test the new binding. No new cryptographic primitive is called for by this finding.

### 11. Medium — recognition timing, generation rate and overlap remain underspecified

**Locations:** local mesh v3.1 §§3.2–3.5, 3.8; rotating labels §1; `MeshHub.kt::Peer.readLoop()` and `SecureModel.kt` admission/worker.

The probe transcript is now well defined: the 17-byte ASCII domain followed by 16 + 16 + 8 + 8 raw bytes gives 65 bytes, with tagger-first role separation and 16-byte output tags. Reversing the expected roles rejects an echoed local probe; a fresh honest nonce prevents ordinary stale-link reuse. Eight-byte nonce collisions are possible, so “never matches” should not be literal, but no practical replay attack follows merely from that length. The explicit tunnel limitation and “recognised” wording are appropriate.

Checking every contact against every slot removes match-dependent early exit, but the work still scales with the actual number of contacts, including HMAC generation. A stranger observing when PROBE is sent or later serial-worker traffic can potentially infer contact count. Constant-time equality is not constant-time whole-protocol work. The cap says making and checking are bounded, but defines only “30 probes … matched”. It does not clearly limit outgoing generation, concurrent work, queue depth, per-minute boundary bursts or malicious reconnects that never send a probe. Attackers can also exhaust this unauthenticated shared allowance and leave genuine friends anonymous; that is a bounded degradation to document.

During the 48-hour root overlap, a phone can need to recognise a friend still using the previous root. Exactly 128 slots already hold one tag per contact at maximum capacity. Sending both roots for every contact would require 256 slots. The receive side could instead check bounded retained roots, but that selection and the revised comparison bound are absent. Simply using the current root can make recognition asymmetric during a lost-ACK replacement.

**Suggested fix:** state whether contact-count timing privacy is required; if so, pad generation and matching work to a documented capacity and measure complete observable exchanges. Specify separate generation/matching admission, concurrency and overload rules before JNI queueing. Define old/current/pending root use during recognition without silently exceeding the slot limit, and test replacement disagreement. Define whether an invalid second probe clears an existing label, rather than relying on “stays anonymous”. Recognition must remain display-only under overload and tunnelling.

### 12. Medium — the larger contact and label tables need lifecycle and capacity rules

**Locations:** rotating labels §§3–4; local mesh v3.1 §3.5; `endpoint/src/engine.rs::check()`, pairing admission and bootstrap capacity; `SecureModel.kt::rememberLink()`.

For 128 paired contacts, 27 incoming epochs mean exactly 3,456 current-root entries, or 6,912 with one retained root for every contact. Those raw tags take about 54 KiB and 108 KiB respectively before map values/overhead: the proposal's “about 3,500” is reasonable, not a capacity defect by itself. Group labels, old generations and group-only sessions are additional work and state, not included in that number.

An hourly rebuild alone misses a newly paired contact or an installed replacement until the next hour. Deleted/retired contacts must stop matching immediately, and erased old roots must not survive in cached label or recognition tables. A label match must select the associated contact, direction, epoch and root; collisions or overlapping generations need bounded candidate handling rather than silently overwriting a map entry. Saturating epoch arithmetic must deduplicate its clamped values near zero. The recomputation rule is sound if it uses the same selected root and authenticated creation time before committing inner ratchet state.

Raising the contact check alone is insufficient. Today there are separate 64-contact admission/validation checks, 68-offer and 64-bootstrap bounds, a host pairing-link map capped at 64, 64 outgoing control slots and fixed snapshot/session limits. These require coordinated review; not every limit should simply double. At the stated 512 KiB per-session ceiling, 128 sessions cannot all fit in an 8 MiB snapshot. Group-only sessions add a separate capacity problem.

**Suggested fix:** specify immediate cache insertion/removal/rebuild events and bounded lookup failure handling; retain exact expiry and effective-time/rollback checks separately from the coarse epoch window. Review related capacities and backpressure, including 128 simultaneous pending pairings and large sessions. Test replacement mid-hour, deletion, restart, hour boundaries and maximum supported contacts/groups. Do not turn group-only members into recognised contacts as a capacity workaround.

### 13. Medium — the batch container is still an explicit delivery blocker

**Locations:** local mesh v3.1 §1.5 and OFFER/WANT; group encryption §§3.4, 3.7, 3.9; rotating labels §4; `SecureModel.kt::receive()`.

The revision correctly says that consuming a piece also retains the whole batch. It explicitly defers the outer container, visible addressing, scan and newcomer path. Consequently the main part of earlier finding 14 remains unresolved. An OFFER with one destination label cannot let a newcomer decide `mine` from labels hidden in pieces it has not received. Calling the outer envelope group traffic does not provide the missing key. Behind members also need a generation exchange that works without the label key they missed.

**Suggested fix:** complete the container and pre-send eligibility contract in the group proposal, with bounded visible piece discovery, authentication contexts and newcomer/behind-member routing. Define endpoint piece acceptance separately from durable carrier acceptance and their ACKs. Reject bad data within limits while retaining valid pieces and whole-envelope digest semantics as specified. Test a newcomer with no current group root receiving its first piece through a stranger; resolve finding 7 as part of that exercise.

### 14. Medium — simulator hop, expiry and budget rules differ from the target system

**Locations:** `CopyCountSim.java` constants at lines 22–23, `Run.offer()` at 325, `Run.exchange()` at 375 and `Run.run()` at 429; `core/src/envelope.rs::forwarded()`; relay admission; `FloodGuard.kt`; `SecureModel.kt::EndpointAdmission`.

The split arithmetic is conservative for serial honest spray transfers, and group singleton transfers correctly keep the holder's copy and add only real members under the model's membership oracle. `has[]` prevents resend to anyone who ever received a message, including after custody deletion. Group envelopes never run the one-to-one DELIVERED branch. The group sender is excluded from delivery denominators, and sample standard deviation is correctly labelled as spread, not a confidence interval. Those parts are internally sensible.

There are material differences:

- The simulator counts each radio edge, including the sender's first send, against eight. Today's sender transmits eight unchanged; only a carrier forward decrements it. An endpoint can accept zero after eight carrier forwards, or nine radio edges. `offer()` refuses every send at simulated depth eight, including delivery to the recipient. A focused check returned zero for that case. Correct the representation and separately prohibit storage of a true zero-hop carrier copy.
- Expiry is processed after exchange. A still-alive message can be delivered on the step at or just after expiry, normally an error of less than ten seconds. A focused call also confirmed `offer()` itself has no deadline check. Prune before transfer and check the exact deadline.
- Seven envelopes per ten seconds permits 42 per minute, not 40; 17 permits 102, not 100. Neither models FloodGuard's slow-mode intervals/durations. Budgets are directional per link and there is no phone-wide outgoing cap, byte accounting or native/endpoint admission work. The native carrier byte limits and the host's 120-envelope/2 MiB global and 60-envelope/256 KiB per-source gates cannot be inferred from these counters.
- Contact with a recipient already holding a one-to-one message drops a carrier before the data-budget check, charging no control exchange. Newly reached recipients instantly stop the source, as documented. Neither models encrypted receipt propagation, frame loss, acknowledgement processing or its bandwidth. Under revised OFFER, a holder omitting a known key does not even send today's DELIVERED unless a new rule says so.

**Suggested fix:** align hop and deadline semantics and add explicit control/data budgets. Compare the wire-state machine, not a global `has[]` oracle, under delayed receipts, lost ACKs/WANTs, finite dedup retention and concurrency. Report the idealised and realistic variants separately. These changes may alter both absolute delivery and the ranking of copy counts; the current figures do not establish their direction or size.

### 15. Medium — the experiments do not establish the operating cost or optimum copy count

**Locations:** simulator README, FINDINGS §§1–4 and results; `World.step()`, `Run.makeMessages()`, `pickGroup()` and storage metrics; local mesh v3.1 §§1.3, 1.6–1.8.

The six worlds, common movement/seeds and comparison with unlimited forwarding are useful exploratory evidence. The published 97–99% one-to-one and 79–96% group-of-30 all-member figures are fairly quoted as model results. They neither guarantee full group delivery nor select a unique optimum: 255 is the largest tested byte value, and the one-to-one/group counts change together between configurations. Greater group load can influence one-to-one results. Five seeds and three fixed memberships per group size give limited independent evidence; their ten messages are correlated.

Storage is measured in envelope counts, with no byte sizes, eviction, large batches or sustained adversarial load. A peak around a hundred small messages does not show that 56 KiB hand-over traffic fits the 64 MiB store or its ingress budget. The experiment has ten junk messages, no lying members, and no effort work, battery cost or control-frame traffic. It does not run the required removal burst, lost key recovery or sustained eviction tests merely because the proposal now lists them.

Movement is also more favourable or different than some prose suggests. Phones randomly reposition within a hotspot about every five minutes, creating new contacts without walking that distance. At night a walker finishes its current trip before the night branch sends it home; phones walk home after 23:00 rather than all being home from 23:00. A focused check at 23:00 left a walker heading to a hotspot. Morning departure is a repeated random trial with roughly a one-hour mean, not a fixed departure appointment. Existing in-range links persist rather than being replaced by the nearest twelve each step. These are modelling choices to describe and vary, not evidence of actual phone movement.

The claim that hand-on four reached *every* phone in *every* scenario is slightly stronger than the output. For the rerun seed its scattered-junk reach averaged 118.90 of 119 possible other phones. Whole-number tables round that to 119. This does not rescue hand-on four as a copy bound: its branching rule can still flood widely. Distinguish a lack of a fixed network replication bound from this finite, hop-limited experiment.

**Suggested fix:** correct the movement and universality wording, then vary copy counts independently, add intermediate values and use more independent worlds/groups with uncertainty for differences. Run disconnected/weakly bridged communities, random and geographically scattered members, churn, sleeping/offline phones, realistic radio setup/range/loss, high chat load, 128-member removal batches, malicious `mine`, count inflation, eviction and lost recovery traffic. Measure bytes stored/transmitted, unique historical and simultaneous carriers, excess replicas, offered/control frames, fairness, worst waits, unreached recipients, delivery conditional on an actual temporal path, hash work and energy. Preserve loss rates alongside reached-only delay percentiles. These scenarios could change the copy-count conclusion; new real-phone measurements are still required.

## A. Disposition of the fourteen earlier findings

Statuses below concern the revised **design**, not completed code or tests. “Fixes” means the earlier design issue is answered; it is not approval of the combined update.

| Earlier finding | Status | Reason |
| --- | --- | --- |
| 1. High — frame type not bound to policy | Partly fixes | Authenticated class, matching frame and stored-class DELIVERED decisions answer the attack. The new wire layout conflicts with both fixed 68-byte AD descriptions; second-review finding 10. |
| 2. High — singleton group flooding | Partly fixes | Genuine member-only singleton expansion limits honest stranger allocations. Unverified `mine`, mutable counts and incomplete accounting defeat an unconditional bound; findings 1–3. |
| 3. High — offer/hand-off machinery | Partly fixes | Paging, settled state, retry backoff and member retention are improvements. One-copy ambiguity, reservations and lost-WANT/ACK/restart states remain; findings 2–3. |
| 4. High — tunnel implies false proximity | Fixes | “Recognised”, the explicit two-site relay limitation, a screen note and display-only use answer this finding. Ordinary application authentication still cannot prove distance. |
| 5. High — one-to-one libsignal metadata | Partly fixes | Kind `0x03` covers established chat and receipts. Pairing confinement does not encrypt the transport, and group-only bootstrap has no shared root; findings 5, 7 and 10. |
| 6. Medium — eviction/recovery claims | Fixes | Native limits, conditional repair, expiry and permanent loss are honestly stated. Required attack/recovery simulations remain unperformed by this simulator. |
| 7. Medium — receipt compatibility | Fixes | Owner chose an incompatible update and re-pairing; strict 17-byte receipts, 0–8 validation and persisted first-result semantics answer the compatibility issue. None is built yet. |
| 8. Medium — probe transcript/state | Fixes | Exact raw widths, tagger order, fixed HELLO values, one sized post-HELLO probe and ambiguous-match rejection answer the original encoding problem. Remaining timing/rotation integration is in finding 11. |
| 9. Medium — probe work/behaviour | Partly fixes | Full comparisons and no match-dependent wire behaviour help. Contact-count work, generation admission, queue/concurrency and overlap are unresolved; finding 11. |
| 10. Medium — static identity-derived parent | Partly fixes | A fresh random root removes deterministic identity-only recomputation. Installation, replacement recovery and copied-session limits are incomplete; findings 6–7. |
| 11. Medium — over-128 sampling | Fixes | Owner explicitly caps paired contacts at 128 and removes sampling; the original probabilistic omission issue is gone. Related capacities and two-root recognition still need work; findings 11–12. |
| 12. Medium — epoch/label validation | Fixes | Fixed epoch encoding, canonical direction, saturating bounds, post-authentication recomputation and corrected retry/linkability claims answer the earlier errors. Cache lifecycle and new-root selection need the additional rules in finding 12. |
| 13. Medium — internet fallback | Fixes | Owner explicitly disables all sending/fetching and defers the server design. This is a clear design decision, not a description of today's active configured-relay code; see the code-claims table. |
| 14. Medium — hand-over container | Partly fixes | Whole-batch retention after reading a piece is now explicit. Container, newcomer discovery and pre-send eligibility are explicitly still unwritten; findings 7 and 13. |

## Code claims: what is right, wrong or still future work

Android filenames below are under `android/app/src/main/java/org/yapmessenger/app/`.

| Claim | Source-inspection result |
| --- | --- |
| Today's binding is 68 bytes, excludes mutable hops, and relay keys ignore hops | Correct. `core/src/envelope.rs` zeroes encoded byte 68 before digesting the immutable envelope. The new class needs a new exact format; Kotlin duplicates those offsets and digest rules. |
| Current sender starts at eight and carrier forwarding decrements it | Correct. Endpoint `encrypt()` uses literal eight; relay `pending_for()` calls `forwarded()`. Endpoint zero-hop arrivals are valid; carrier storage rejects zero. |
| Incoming route is discarded and StoredMessage has no hop field | Correct in today's endpoint. First-route persistence and strict new receipt parsing are proposed changes. Current receipts require exactly 16 bytes. |
| Exact duplicates can reuse the first receipt | Correct for authenticated accepted text with matching immutable digest and retained cache, subject to cached-response scheduling limits. Changed immutable bytes are not an accepted duplicate just because the message ID matches. |
| Phone carrier store is 10,000 envelopes/64 MiB with oldest eviction | Correct in the native configuration and core admission. Byte accounting excludes database overhead. Native admission also caps 60 envelopes/256 KiB per source and 240/1 MiB globally per minute; earlier host gates can be stricter. |
| Current code has 96/255 copies and frame types 9–12 | False if stated as present behaviour. Rust and Kotlin copy caps remain 12; MeshHub rejects types 9 and above. The proposals generally label these as future changes. |
| Current offer tracking gives successful once-per-link delivery and safe serial copy splits | False. Host offers have a 60-second suppression, Peer has a separate 10-second in-flight suppression, and ACK removes host offer state. There are no concurrent reservations; native hand-off at one copy removes custody. |
| A 2,048-byte PROBE and 64-entry OFFER fit the frame cap | Correct as size arithmetic: PROBE is 2,049 including type; the stated OFFER entries total 4,160 bytes before type and any exchange header. Parser support, state and admission are new work. |
| Pairing links are confidential and prove a physical peer | Unsupported. Clear TCP, insecure Bluetooth APIs and open/public-passphrase Aware supply no common confidential authenticated link. QR/signature checks authenticate pairing data, not physical distance or the socket endpoint. |
| Bootstrap is already confined like signed confirmations | Incorrect. In `SecureModel.pump()`, confirmation packets use `linkFor()`, but other pending packets enter all-local-link and internet paths. `receive()` selects today's endpoint by fixed route, otherwise carrier custody; it has no batch dual-acceptance path. |
| Internet relay is off in today's code | Incorrect. `pump()` reads saved `relay_url`, reconnects, calls `poll()`, offers source outbox packets and offers carried relay packets. InternetRelay callbacks still receive and acknowledge traffic. The update must gate connection, polling, both send loops, callbacks and retries, including saved configuration; a “Coming soon” screen alone does not disable them. |
| Contacts are already 128 | Incorrect. Endpoint validation and pairing admission remain 64, with related limits discussed in finding 12. Group-only sessions are additional future integration. |
| Snapshot and carrier store share the Keystore wrapper | Incorrect. The snapshot is `messaging/endpoint-v1.bin` under noBackupFilesDir with alias `org.yapmessenger.endpoint-v1`; the carrier redb is separate. Profile preferences are separate again. |
| Keystore deletion makes any old copy unreadable | Correct only for encrypted snapshot ciphertext dependent on that successfully deleted, unrecoverable key, without a prior plaintext/key compromise. It says nothing equivalent about relay storage or profile preferences. |
| noBackupFilesDir excludes the snapshot from ordinary Auto Backup | Correct. Manifest/XML add exclusions as well. It is not a guarantee against earlier privileged extraction. |
| Native JSON pass-through avoids changing the bridge for route fields | Plausible at the JSON transport layer, but no build was run to establish all constructors/mappings. New probe and label operations, state schema and versioned envelope handling still require coordinated implementation. |

## Evidence, runs and boundaries

Read AGENTS.md, the first review and response; all three target proposals; simulator README, FINDINGS, complete Java source and stored summary; local mesh v2; group encryption §§3.4, 3.7, 3.9 and relevant bootstrap/compromise sections; the endpoint contract; GROUP_MECHANICS §6; audit findings 1b and 6; architecture/security guidance; and the requested envelope, relay, endpoint, native and Android source. Additional targeted reads covered ProfileStore, MeshService, local transport socket setup and backup exclusions. External primary-source checks were limited to the Android Keystore and Auto Backup documentation linked above. No claim here relies on the unrelated untracked mesh-v4 audit or topology experiment.

Ran the bundled JDK 17.0.21.1 compiler against the unmodified simulator source, writing only to temporary `experiments/copy-count-sim/review-build/`, then:

```text
javac -encoding UTF-8 -d review-build src/CopyCountSim.java
java -cp review-build CopyCountSim --quick --only scattered --out review-build/out
```

All six configurations completed for the first scattered seed (`-1634525243`). Their 24 output CSV rows matched the corresponding committed rows. For that seed, one-to-one reached 79.17% at 12 and 98.33% at 96; group-of-30 all-member delivery was 40.00% at 32 and 76.67% at 255. This verifies reproducibility of that slice, not the validity of its model or the full five-seed run.

A temporary Java harness against the same unmodified classes exercised six cases: two-copy split to a stranger (one copy retained and one received); singleton member expansion (three singleton holders and no stranger allocation); refusal to deliver after eight simulated edges; free custody deletion when meeting an already-reached recipient even with data budget exhausted; acceptance of an expired-but-alive message by `offer()`; and a walker still heading to a hotspot at 23:00. It also printed the 42/102 six-step budgets. These are focused model checks, not tests of an implemented mesh protocol.

The stored six-scenario/five-seed report was read, not rerun in full. No Android/Rust build, full test suite, cryptographic implementation test, radio capture, actual key deletion, crash/power-loss test or physical multi-hop test was run. The proposals are unbuilt, and the source-inspection findings must not be presented as reproduced app failures of v3.1.

Temporary harness, class files and generated results were removed after checking. Only this report was added; the committed results and all other files were left unchanged. Local Markdown links in this report were checked for existing targets; no Mermaid diagrams were added.
