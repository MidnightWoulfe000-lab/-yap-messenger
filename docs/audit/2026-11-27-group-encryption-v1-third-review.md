# Third review: group encryption v1

**Reviewer:** Codex, 27 November 2026, at the project owner's request.  
**Scope:** the “Second review” section of the [response](2026-11-26-group-encryption-v1-response.md), the [proposal](../proposals/group-encryption-v1.md), and the supporting changes at commit `1774808eb6db3d87261eb03cccd741c264b3b17f`. Finding numbers below are new; the table at the end maps the preceding review's six findings.  
**Verdict: keep the implementation hold.** The three-member takeover is fixed in the current voting path. One high-severity availability problem and three medium-severity inconsistencies remain. The outer-layer test vectors and batch custody review are also still outstanding. This is an AI-assisted review, not an independent professional security audit.

## 1. High — two hourly decryption keys cannot receive 24-hour mesh traffic

**Location:** proposal §3.4, lines 248–261; §3.6, lines 304–309; §3.7, lines 347–355. Related response row: second review #3, line 81.

The outer key includes the sending hour, but receivers try only the current and previous hour's keys. The envelope lifetime is 24 hours (`core/src/envelope.rs`, `MAX_LIFETIME_MS`), and the referenced [address-label design](../proposals/rotating-address-labels.md) recognises labels from `now-25h` through `now+1h`.

For example, a message encrypted during hour 10 and delivered during hour 13 can have a recognised label, an unexpired envelope and a retained sender-key generation. It still fails outer decryption because the receiver tries hours 13 and 12. The inner libsignal message is never reached. Clock skew can also make a fresh message use the next hour's key, which the stated rule omits.

The same conflict affects stored hand-over pieces. Retries must resend their bytes unchanged, and pending hand-overs are retained until acknowledged; neither putting an old piece in a fresh batch nor resending it changes its encryption epoch. The two-key rule can therefore defeat the recovery traffic intended to restore a disconnected member.

**Required change:** define epoch selection over the supported lifetime and clock-skew window, checked against authenticated context after decryption. For envelopes, the canonical creation time supplies a candidate epoch; for pieces, specify authenticated epoch/expiry context independent of the enclosing batch. Resolve how long-lived pending pieces are refreshed without reusing nonces or breaking the underlying session. Test delivery after 3 and 23 hours, an hour boundary with clock skew, and a delayed hand-over moved between batches.

This is a contradiction in the proposed algorithm, not a reproduced failure in implemented group encryption.

## 2. Medium — the old daily cap remains a competing protocol rule

**Location:** proposal §3.6, lines 292–302, versus §5 item 6, lines 613–617. Related response row: second review #2, line 80.

Section 3.6 now separates weekly rotation from recovery and permits one recovery per sender per hour. Section 5 still says routine changes are limited to two per member per day, explicitly referring back to §3.6. There is no rule removing that cap or resolving precedence.

An implementation following the resource-limit checklist could retain the exact daily recovery failure the response says it fixed. This is a stale normative requirement, rather than evidence that the revised hourly rule itself still imposes the daily limit.

**Required change:** replace the old checklist entry with the separate weekly and recovery budgets, including their enforcement scope and treatment of justified changes. The existing proposed test for more than two recoveries in a day should exercise the same acceptance policy used by receivers.

## 3. Medium — the exported SuperAdmin threshold helper still returns the old answer

**Location:** `groups/src/state.rs` lines 68–70 and 994–1000; export in `groups/src/lib.rs` lines 30–34. Related response row: second review #1, line 79.

The actual `ProposeRevoke` path correctly applies `needed(others, everyone).max(2)` to the SuperAdmin. However, the public `super_admin_removal_threshold(2)` still returns `majority(1)`, which is **1**. The revised rule and actual state machine require **2**, making removal impossible with only the other member available.

This helper remains re-exported as part of the crate's public interface. Current repository callers are tests, so this does not reopen the reproduced takeover in the present state machine. It does give a future UI, validator or encryption integration an incorrect security-policy answer. Its group-size-only interface also cannot express proposer-selected electorates.

**Required change:** remove or clearly restrict the legacy helper, or replace it with an interface that represents the current policy and shares the state machine's calculation. Add a test comparing its two-member answer with the actual removal threshold. Review the related `revocation_threshold` helper's documented scope at the same time.

## 4. Medium — the retained size experiment measures the superseded hand-over

**Location:** `experiments/sender-key-sizes/src/main.rs`, `HAND_OVER`; proposal §3.9, lines 371–403, and §5, lines 595–599. Related response rows: second review #3 and #6, lines 81 and 84.

The program still encrypts a 164-byte dummy plaintext: `96 + 32 + 4 + 16 + 16`. The revised `YGH1` carries the recipient key, generation, change kind and governance-event references as well. With a 96-byte distribution message and one justification hash, the listed fields require at least 235 bytes, even using one byte each for the count and distribution length. Sixteen justification hashes require at least 715 bytes.

The experiment also does not apply the new outer layer, whose `YGO1` format adds 44 bytes before any routing label or batch framing. Its recorded maxima therefore describe the earlier message shape, not the complete current hand-over. The unchanged removal/join traffic estimates cannot serve as measurements of this revision.

Actual encoded-size batching still protects the envelope cap; this finding concerns the cost evidence and the claims based on it.

**Required change:** measure the canonical revised encoding, covering no justification, one event and the maximum event count; include outer encryption and piece/batch framing. Refresh the traffic table and distinguish measured cases from a proven maximum. Keep the batch storage/retransmission question open until measured.

## Disposition of the previous six findings

| Previous finding | Result of this review |
| --- | --- |
| 1: three-member takeover | Fixed in the current voting path. New two- and three-member regression tests pass. The public helper inconsistency is new finding 3 above. |
| 2: recovery exhausts its daily allowance | Addressed in §3.6; the conflicting §5 rule remains as new finding 2. No implemented recovery path exists to test yet. |
| 3: outer-layer contract | Substantially improved: a cipher, nonce size, direction-separated key context, header binding and retry format are now named. The receive epoch window fails delayed traffic (new finding 1); test vectors are still explicitly outstanding. |
| 4: organiser sends an offer then stalls | Addressed at proposal level by deadlines for founding material and initial keys, abandonment and fresh consent. The listed failure tests remain required before implementation is considered validated. |
| 5: internet inferred from heartbeats | Addressed by the recorded product decision: internet access is no longer a formation condition. The six-hour heartbeat is correctly described as supporting an uncertain isolation judgement. |
| 6: missing hand-over fields | Addressed at proposal level: recipient, generation, change kind and justification references are listed, with rollback and repeated-justification rules. The size experiment has not followed the format change (new finding 4). |

The 24-byte nonce choice is consistent with the extended-nonce construction documented by [RustCrypto](https://docs.rs/chacha20poly1305/0.11.0/chacha20poly1305/#xchacha20poly1305). That supports the choice of primitive, not approval of Yap's complete composition. The implementation must use a cryptographically secure random source and the exact reviewed encodings and derivations.

## Verification and changes

- Ran `cargo test --offline --locked` in `groups/`: **75 integration tests passed**, with no failing unit or documentation tests. This includes all 13 activity tests and the new takeover regressions.
- Inspected the current voting paths, exported helpers, envelope lifetime and binding, revised proposal, response section and retained size program. Checked the changes against commit `1774808`.
- Findings 1, 2 and 4 are design/documentation findings. Group encryption and the new outer layer are not implemented, so no end-to-end reproduction is claimed. No fresh size benchmark was run; its input format was inspected directly.
- Added only this report. The response, proposal, governance code and unrelated untracked mesh audit/simulation work were left unchanged. Normal ignored Cargo test artefacts remain in the existing target directory.
