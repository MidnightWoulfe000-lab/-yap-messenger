# Fourth review: group encryption v1

**Reviewer:** Codex, 27 November 2026, at the project owner's request.  
**Scope:** the “Third review” section of the [response](2026-11-26-group-encryption-v1-response.md), the [proposal](../proposals/group-encryption-v1.md), and changes at commit `40d719ba2fa07a8a6f8179061ac4413067e23361`.  
**Verdict:** the previous four findings have been addressed in code, measurements or the written design, as detailed below. Two medium-severity protocol gaps remain. Neither is a reproduced takeover or confidentiality break. Keep the implementation hold while these, the outer-layer test vectors and the acknowledged batch custody questions remain open. This is an AI-assisted review, not an independent professional security audit.

## 1. Medium — re-wrapped duplicates need a durable re-acknowledgement path

**Locations:** proposal §3.4, lines 269–274; §3.9, lines 433–434; §5 item 3, lines 603–608. Related response row: third review #1, line 95.

The proposal now refreshes an old piece's outer layer while keeping its libsignal ciphertext unchanged. It says the receiver ignores a repeat of a generation it already has. However, that generation is inside the libsignal plaintext. After successful acceptance, libsignal rejects the same inner ciphertext as a duplicate rather than returning that plaintext again.

**Library behaviour reproduced:** establish a pairwise session, encrypt a hand-over, decrypt it successfully, then decrypt the identical inner message again. The second call returns `SignalProtocolError::DuplicatedMessage`. A temporary integration test against the pinned library passed with those assertions. Re-wrapping does not change this result because it leaves the input to libsignal unchanged.

If the first hand-over was committed but its acknowledgement was lost, a later re-wrapped retry therefore cannot reach the proposed generation check through normal decryption. Ignoring it also does not stop the sender's “retry until acknowledged” loop. The existing [endpoint receipt contract](../protocol-proposal.md) caches acknowledgements for an exact accepted envelope digest; a newly wrapped piece has different bytes, and the group proposal does not specify a stable inner-message lookup.

**Required change:** specify durable duplicate handling for hand-over pieces. One option for review is to commit a record binding the authenticated peer, exact inner ciphertext digest, group, generation and acknowledgement together with acceptance. After authenticating a fresh outer wrapper and verifying the exact stored inner digest, the receiver can schedule a bounded re-acknowledgement without advancing libsignal again. Do not treat an arbitrary decryption error or a merely claimed generation as proof of prior acceptance.

Test lost acknowledgements, receiver restart after committing the hand-over, re-wrapping after 20 hours, rebatching and tampered retries. The executable check establishes the library behaviour; the missing end-to-end acknowledgement path remains a design finding.

## 2. Medium — receiver rate limits need a rule for delayed valid generations

**Locations:** proposal §3.6, lines 305–322; §5 item 6, lines 634–638. Related response row: third review #2, line 96.

The stale daily cap is gone, but the checklist now explicitly tells receivers to ignore changes beyond one recovery per sender per hour or one weekly change in six days. It does not specify how those limits apply when valid sender changes arrive together after mesh delay.

For example, a sender produces recovery generations 10 and 11 at 10:00 and 11:00. A receiver gets both at 14:00, with generation 10 first. If it measures the limit by local acceptance time, generation 10 uses its hourly allowance and the current generation 11 is ignored. Messages sent using generation 11 remain unreadable during that imposed wait even though the sender obeyed the rate limit. A reception-time six-day weekly limit can impose a much longer delay after reconnection.

Measuring by the outer wrapping hour is not a complete alternative: re-wrapping intentionally replaces that hour without creating a new sender-key generation. The hand-over currently has no separate generation-creation time. The rule that only a higher generation becomes current prevents rollback but does not resolve a rate-limit rejection of that higher generation.

**Required change:** specify sender generation budgets separately from receiver work scheduling. Define how a receiver retains or requests the newest pending generation and eventually installs it under bounded work, rather than permanently discarding it or making reconnection wait six days. Replays and re-wrapped copies of an already accepted generation must not consume another generation allowance. State which clocks and persistent records the policy uses.

Test compliant changes sent an hour apart but delivered together in both orders, separated weekly changes delivered together, restart during deferral and an old generation arriving immediately before the latest hand-over. This is a specification gap; no receiver implementation exists to reproduce it end to end.

## Disposition of the third review's four findings

| Previous finding | Result |
| --- | --- |
| 1: two-hour receive-key window | Addressed at proposal level. Envelope creation time or an authenticated piece-hour field selects the key; the window now covers delayed traffic and clock skew. Re-wrapping prevents the outer epoch alone from ageing pending pieces out. Duplicate acknowledgement handling still needs new finding 1 above. |
| 2: conflicting daily cap | Addressed. Both §3.6 and §5 now list the separate weekly and recovery limits. Their receiver-side catch-up semantics need new finding 2 above. |
| 3: stale public threshold helper | Fixed in code. The public helpers take electorate information and are called by the actual removal path. The two-member helper/state-machine regression passes. |
| 4: outdated measurement input | Addressed. The program encodes hand-overs with 0, 1 and 16 event references and counts the exact 70 bytes of outer/piece framing. Its results reproduce the revised tables. This remains a size experiment, not validation of the cipher composition or mesh custody. |

## Verification and changes

- Ran `cargo test --offline --locked` in `groups/`: **75 integration tests passed**, with no failing unit or documentation tests.
- Reran the size program using `cargo run --offline --locked --target-dir '../../groups/target'` from `experiments/sender-key-sizes/`. The debug build reused cached library dependencies. Plaintexts were **204 / 236 / 716 bytes**; new-session pieces **2,020 / 2,052 / 2,532 bytes**; maximum open-session pieces **371 / 403 / 883 bytes**. These match the current proposal. Counting fixed AEAD overhead without running the outer cipher is sufficient for this size calculation; it does not test encryption correctness.
- The initially attempted release build failed because the installed `protoc` could not launch (Windows error 623). The successful cached debug run supplied the measurement evidence above.
- Ran one temporary integration test verifying successful initial decryption followed by `DuplicatedMessage` for the same inner hand-over. Removed its source afterwards. No end-to-end group-encryption implementation was added.
- Added only this report. Production code, the proposal, the response and unrelated untracked mesh audit/simulation files remain unchanged. Normal ignored Cargo artefacts remain in the target directories.
