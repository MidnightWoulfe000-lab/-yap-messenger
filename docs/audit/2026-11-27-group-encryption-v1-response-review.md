# Second review: group encryption v1 response

**Reviewer:** Codex, at the project owner's request.  
**Scope:** the [response](2026-11-26-group-encryption-v1-response.md), its [original review](2026-11-26-group-encryption-v1-codex-review.md), the [revised proposal](../proposals/group-encryption-v1.md), and supporting code and measurements.  
**Verdict: keep the implementation hold.** Finding 4 is only partly fixed: a three-member group still permits unilateral takeover. The revised recovery and temporary-group rules also need changes. This is an AI-assisted review, not an independent professional security audit.

## 1. Critical — unilateral takeover still works in a three-member group

**Original finding:** 4.  
**Locations:** response line 36; `groups/src/state.rs` lines 47–58, 983–989 and 1137–1142.

The new rule computes `majority(max(listed, ceil(everyone / 2)))`, with the target excluded from `everyone`. In a three-member normal group, removing SuperAdmin A leaves two possible voters. B lists only B: `counted(1, 2) = 1`, and `majority(1) = 1`. B's proposal removes A immediately. The election inherits that one-person electorate, and again needs only B's vote.

**Reproduced against the current crate:** create normal group {A, B, C}, with group revoking off; B proposes removing A with `[B]`; B elects B; B uses `AdminRemove` on C. Every event is valid and only B remains active. A temporary integration test asserted these results and passed. The response's five-member regression test passes too; it does not cover this case.

The implementation follows the newly accepted numerical floor, but that floor does not establish the claimed protection. Do not silently change the owner's policy: record this remaining consequence and obtain a reviewed rule that prevents unilateral takeover in groups of three or more. Keep the intentionally unilateral two-member case separate. Add boundary tests across group sizes and electorate lengths.

## 2. High — the recovery fix runs out after two changes per day

**Original findings:** 8 and 10.  
**Locations:** response lines 40 and 42; proposal §3.6, lines 267–290.

Excessive-gap recovery creates a fresh generation, counts as routine, and is limited to two routine generations per member per day. The separate once-an-hour limit does not override that daily cap.

For example, receivers held more than 2,000 steps behind can request recovery at three successive hours. The first two requests can cause changes; the third is ignored. A weekly rotation can consume one of those two slots as well. Resending the current distribution cannot advance a receiver that already holds that chain, which is the reason this recovery mechanism was introduced.

Specify how legitimate recovery remains available after the routine budget is exhausted. Separate weekly rotation from recovery scheduling, or define another reviewed recovery mechanism with bounded work. Test more than two recoveries in one day, including a weekly rotation and staggered requests from different receivers.

## 3. High — the outer encryption layer lacks a nonce and key-use contract

**Original finding:** 5.  
**Locations:** response line 37; proposal §3.4, lines 231–242.

The response correctly labels this a new construction requiring review. It is not yet specified sufficiently to approve: the proposal leaves the cipher undecided and supplies no nonce format, allocation rule, restart behaviour, or exact domain-separated key derivation. The pairwise case derives from a shared label key and an hour without explicitly separating directions. Both phones could therefore use one key and overlapping counters in an otherwise plausible implementation.

ChaCha20-Poly1305 requires nonce uniqueness under each key, including across multiple senders; see [RFC 8439 §2.3 and §2.8](https://www.rfc-editor.org/rfc/rfc8439.html#section-2.8). An hourly key alone does not supply it. This is a specification gap, not evidence that an implemented cipher currently reuses nonces.

Define the cipher, complete key schedule, direction separation, nonce allocation and persistence, encoded format, and retry rules. Bind the canonical immutable envelope fields: `core/src/envelope.rs` deliberately excludes the mutable hop byte from `Header::binding()`. Also explain how per-piece outer authentication works when unacknowledged pieces are placed in a different batch. Provide test vectors before approval. Correct the proposal's opening claim that no new encryption construction is introduced.

## 4. High — organiser failover stops working once an offer is sent

**Original finding:** 16.  
**Locations:** response line 48; proposal §3.10, lines 428–429 and 485–490.

The two-minute failover applies only if no offer arrives. A hostile smallest-key organiser can send an offer within that period, obtain an acceptance, and then withhold the signed founding material. Formation requires two founders, yet the accepted attempt cannot be displaced under the stated rule. This leaves the original finding's second stalling case unresolved.

Add progress deadlines through completion, including consent collection, founding signatures and initial key delivery. Specify whether another phone can finish the identical consent-bound attempt or must obtain fresh consent for a replacement. Test an organiser that sends an offer promptly and then disappears.

## 5. Medium — heartbeats do not establish that nobody has internet access

**Original finding:** 15.  
**Locations:** response line 47; proposal choice 9 and §3.10, lines 406–414 and 481–484.

The revised text still treats lack of recent group messages as proof that no phone in the local mesh has internet access. A working relay connection does not guarantee delivery of majority heartbeats: remote phones may be offline, traffic may be delayed or withheld, and a daily heartbeat has no margin against a 24-hour threshold.

The fresh challenge improves evidence of recent responsiveness and the tunnelling limitation is honestly stated. It does not repair the internet inference. Define internet/relay availability as a separate observation, or seek an explicit product decision to use suspected isolation as the formation condition. Do not describe the current trigger as enforcing the existing “nobody has internet” requirement.

## 6. Medium — the proposed hand-over layout omits fields required by the fixes

**Original findings:** 7, 9 and 10.  
**Locations:** proposal §3.6, §3.9 lines 381–384, and §5 lines 548–555.

The proposed `YGH1` layout contains the group ID, distribution ID, label key and distribution message. It omits the recipient, generation number and governance-event references that the revised rules require for independent piece checks, generation acknowledgements and justified rotations.

A random distribution ID does not encode a monotonic generation or explain why a routine limit may be bypassed. The encoded contract must carry these fields, or define authenticated context that supplies them. Specify how delayed older generations may be retained for reading without replacing the current generation, and how repeated references to the same governance event are prevented from authorising unlimited churn. Test reordered hand-overs, acknowledgement mismatches and repeated justifications.

## What the response gets right

- Full-log replay removes the checkpoint shortcuts behind original findings 2 and 3. The sponsor bootstrap limitation in finding 1 is now explicit; replay does not prove that a sponsor has supplied every branch that exists elsewhere.
- The pinned libsignal source confirms that sender-key identifiers are outside the ciphertext and that `add_sender_key_state` preserves an existing matching chain instead of advancing it.
- Batching by actual encoded size replaces the invalid single-envelope assumption. Relay queue occupancy and retransmission costs correctly remain open under finding 7.
- Finding 11's weekly compromise-recovery claim and finding 12's stored-history claim are narrowed appropriately. The outer layer is correctly marked as awaiting review.
- The local-removal veto and consent binding improve findings 14 and 16, even though organiser progress remains incomplete.

## Verification and boundaries

- Ran `cargo test --offline --locked --test activity` in `groups/`: **9 passed**.
- Ran a temporary integration test reproducing the three-member takeover: **1 passed**, meaning the attack succeeded. Removed that temporary source after the check.
- Inspected the pinned libsignal revision `257105c55a7389ca6b1e85185e2769465e6729f1`, the retained size-measurement program, the envelope binding and relevant governance code.
- Attempted `cargo run --offline --locked --release` in `experiments/sender-key-sizes/`. It could not complete: after supplying an explicit `PROTOC` path, both installed compiler binaries failed to launch with Windows error 623 (illegal system DLL relocation). The numerical size results were therefore not independently rerun. Normal ignored Cargo build artefacts remain under the target directories.
- Findings 2–6 concern the written design; they were not reproduced in a group-encryption implementation, which does not yet exist.
- No production code, proposal, response or existing unrelated work was changed by this review.
