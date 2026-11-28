# Attachment streaming check (gate 5, first part)

Feasibility check for the segmenting in [attachments-v1 §1](../../docs/proposals/attachments-v1.md),
the first item of gate 5 in the [eighth review](../../docs/audit/2026-11-28-v3.1-update-eighth-review.md).
**Not linked into the app, not reviewed, not an audit.**

## What was run, and on what

- **Machine:** Windows 11, `rustc 1.100.0`, native `x86_64-pc-windows-msvc`. Everything *ran* there.
- **Android:** `build-android.ps1` cross-compiles the library, binary and the test binaries for
  `aarch64-linux-android` (arm64-v8a) and `x86_64-linux-android` with NDK 28.2.13676358 (same linker and
  16 KiB page-size flags as `scripts/build-native.ps1`). Both builds **succeeded**. **Not run on a phone**; see the emulator run below.
- `cargo test`: **15 passed, 0 failed** (output in `results/test-output.txt`).

## Pinned versions (`Cargo.lock` is committed)

| Crate | Version | Why |
| --- | --- | --- |
| `aead-stream` | `=0.6.0` (feature `alloc`) | the crate the proposal names |
| `chacha20poly1305` | `=0.11.0` | the release `aead-stream` 0.6.0 pairs with (both use `aead` 0.6) |
| `chacha20poly1305` (renamed `chacha20poly1305_old`) | `=0.10.1` (feature `stream`) | what `experiments/outer-layer-vectors` pins today; used only to cross-check bytes |
| `sha2` | `=0.10.9` | as elsewhere in the repo |

## Finding: the proposal's crate pairing cannot be met literally

The proposal says `aead-stream` is "pinned in the same crate family as the outer layer's
`chacha20poly1305`". The outer layer pins `chacha20poly1305 =0.10.1`, which sits on `aead` 0.5. There is
**no** `aead-stream` for `aead` 0.5: STREAM lives inside `aead` 0.5 (`aead::stream`, behind the
`chacha20poly1305` feature `stream`). The standalone `aead-stream` crate only exists as 0.6.0, which needs
`aead` 0.6 and so `chacha20poly1305` 0.11.0. The design has to pick one:

1. keep the outer layer on 0.10.1 and use its built-in `stream` module (no `aead-stream` crate), or
2. move the outer layer to 0.11.0 and re-check `experiments/outer-layer-vectors/vectors.txt`.

Whichever is chosen, **the bytes are the same**: the test `both_crate_families_agree` shows the two
families produce identical ciphertext for 0, 1, 65,536, 65,537 and 262,080 bytes, so the choice does not
change stored files. (Having both in one build, as here, pulls in two `aead` versions; the app would use
only one.)

## What was checked

Segmenting as the proposal words it: XChaCha20-Poly1305 under `StreamBE32`, 19-byte stream nonce, 65,536-byte
plaintext segments, an empty file as one empty last segment, exact multiples with no extra empty segment.

| Plaintext bytes | Segments | Encrypted bytes | Fits 262,144 |
| ---: | ---: | ---: | --- |
| 0 | 1 | 16 | yes |
| 1 | 1 | 17 | yes |
| 65,535 | 1 | 65,551 | yes |
| 65,536 | 1 | 65,552 | yes |
| 65,537 | 2 | 65,569 | yes |
| 262,080 | 4 | 262,144 | yes (exactly) |
| 262,081 | 4 | 262,145 | **no** |

These match the review's table, and now also match **real encryption output lengths** (not only arithmetic):
`size_table` encrypts each size and compares the length.

| Check | Result |
| --- | --- |
| Round trip at every size above, plus 131,072 | pass |
| Manifest equations (`count = max(1, ceil(n/65,536))`, `encrypted = n + 16 × count`), and each broken on its own | pass; broken equations refused before any decryption |
| Wrong size / wrong SHA-256 | refused |
| Cut on a segment boundary | refused by the size check; with size and hash bypassed, refused because the segments run out, and, with the count also wrong, because a non-final segment decrypted as final fails (the final flag working alone) |
| Cut mid-segment | refused |
| Two segments swapped (same length) | refused; with the hash bypassed, segment 0 fails |
| Last segment swapped with an earlier one | refused |
| A non-final segment presented as the last | refused |
| A segment appended | refused (size; and count mismatch with size bypassed) |
| One flipped bit in the first, a middle or the last segment | refused |
| Wrong key, wrong nonce | refused |
| Hand-built nonce `stream nonce (19) ‖ big-endian counter (4) ‖ flag (1)` opens each segment with the plain XChaCha20-Poly1305, and flipping the flag stops it | pass: this is an independent check of "the final flag is part of the nonce" |

Fixed vector (recorded in the test, key `07` × 32, stream nonce `09` × 19, plaintext `(i*31+7) mod 256` for
65,537 bytes): encrypted length 65,569, SHA-256 of the ciphertext
`0af344ba2afd5c6339c4b883736c889217dfeb0a24dd4068ddb557958ffba491`. It was produced by this code and
matches across both crate families, so it is a regression pin, **not** an independent known-answer test.

## What this does not show

- No third-party STREAM test vector was available offline, so nothing here checks interoperability with
  another implementation. The nonce-layout test above is the nearest independent check.
- The rest of gate 5 was **not run**: outer-wrapper vectors for the new header/inbox inputs; chunk/range
  ACK, reset/publication/cleanup crash tests and quota reconciliation (these need gate 1's store).
- The `n ≤ 262,080` rule is right only because 262,080 = 4 × 65,520 and four tags fit exactly; a change
  to the segment size or small limit needs the table redone.
- STREAM here uses empty associated data. Nothing binds a segment to the attachment ID; the manifest
  hash and the authenticated carrier message do that. Not a defect, but worth stating in the proposal.

## Emulator run

Run on the existing `yap_test` emulator (`emulator-5556`, Android 9, API 28, ABI `x86_64`, "Android SDK built for x86_64"), by pushing the cross-compiled x86_64 test binary with `adb push` into `/data/local/tmp` and running it there. **An emulator is not a real phone**: it is x86_64 (the arm64-v8a build was not run), uses the host's CPU and disk, and says nothing about real-device timing, flash behaviour or vendor changes.

Result: **15 passed, 0 failed** (same as desktop).

## Run it

```
cargo test
cargo run                          # prints the size table
powershell -File build-android.ps1 # builds both Android ABIs (needs the NDK)
```
