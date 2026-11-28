# Noise pairing handshake check (gate 4)

Feasibility check for the handshake in [encrypted-pairing §1–§3](../../docs/proposals/encrypted-pairing.md),
gate 4 of the [eighth review](../../docs/audit/2026-11-28-v3.1-update-eighth-review.md).
**Not linked into the app, not reviewed, not an audit.**

## What was run, and on what

- **Machine:** Windows 11, `rustc 1.100.0`, native `x86_64-pc-windows-msvc`. The tests *ran* there, both
  sides of every exchange in one process.
- **Android:** `build-android.ps1` cross-compiles the library and test binaries for `aarch64-linux-android`
  and `x86_64-linux-android` with NDK 28.2.13676358. Both **built**. **Not run on a phone**; see the emulator run below.
- `cargo test`: **12 passed, 0 failed** (`results/test-output.txt`).

## Pinned versions (`Cargo.lock` is committed)

| Crate | Version |
| --- | --- |
| `snow` | `=0.10.0`, `default-features = false`, features `use-chacha20poly1305`, `use-sha2`, `use-curve25519`, `use-getrandom` |
| (via snow) `chacha20poly1305` 0.10.1, `curve25519-dalek` 4.1.3, `sha2` 0.10.9, `getrandom` 0.3.4, `subtle` 2.6.1 | |
| `hkdf` `=0.12.4`, `sha2` `=0.10.9` | for the section 1 `psk` |

Choosing features this way gives exactly the suite `Noise_NNpsk0_25519_ChaChaPoly_SHA256` and leaves out AES-GCM,
BLAKE2 and `ring`. The `chacha20poly1305` it pulls in is **0.10.1**, the same as the outer layer's. snow tracks
Noise **revision 34**, the revision the proposal names. The dependency graph builds for both Android ABIs with
no native code.

## What was checked

Pattern, prologue and `psk` exactly as the proposal words them (readings listed below). Initiator is the phone with the
smaller identity key.

| Check | Result |
| --- | --- |
| Full **original** exchange (`-> psk, e` / `<- e, ee`, empty payloads) | pass |
| Both handshake messages **exactly 48 bytes** (32-byte ephemeral key + 16-byte tag) | pass, both messages, original and resume |
| Same 32-byte handshake hash on both sides (the value §3 signs) | pass |
| Transport both ways, in order, including empty payloads | pass |
| Full **resume** exchange (same `psk`, same roles, prologue `"Yap pairing v2 resume"` ‖ original handshake hash ‖ both identity keys) | pass; its hash differs from the original (fresh ephemeral keys) |
| Resume with a wrong original hash, with the original prologue on one side, or with the identity order swapped | all fail |
| **Wrong PSK** (either side) | fails; the responder cannot even read message 1 |
| **Wrong prologue** (one byte; QR order reversed; one QR missing; empty; a third phone's QR) | all fail, in both directions |
| Message 1 **truncated** to 0, 1, 31, 32, 33, 47 bytes; **lengthened** to 49 and 149 bytes | all refused |
| Message 1: **every bit of every byte flipped** (384 cases) | none accepted |
| Message 2: truncated, or one byte changed (48 positions) | all refused |
| Message 1 read twice on one responder state | second read refused |
| Transport messages out of order or replayed | refused; a refused read does **not** advance the counter (the in-order message still opens afterwards) |
| **Maximum transport message:** 65,519 bytes plaintext → exactly **65,535** bytes with the tag, round-trips | pass |
| 65,520 bytes plaintext | write refused; a failed write does not burn a send counter |
| A 65,536-byte message | read refused |
| A 1,883-byte bundle (section 3) in one transport message | 1,899 bytes, pass |

`psk` vector from the test data in `tests/noise.rs` (identities `02 0a…` / `03 15…` patterns, keys and nonces
as written there): `fb574fe0abfae561de6fb3e1359eaa3431cff45bceef6cb0232579893b335984`. The handshake hash is not
recorded because it changes every run.

## Findings

1. **`snow` does not enforce "exactly 48 bytes".** A valid initiator holding the `psk` can send message 1
   with a 1-byte payload (49 bytes); `snow` accepts it and returns the payload (test
   `snow_does_not_enforce_the_48_byte_rule_itself`). The proposal's "any other length is dropped" is therefore
   a rule the endpoint must apply **before** calling `read_message` (the harness has `length_gate`). The
   proposal already says "dropped without any cryptographic work"; this confirms it has to be hand-written.
2. **"QR payload" and "identity keys in order" are ambiguous.** The prologue says "both QR payloads in
   identity order". I read this as the decoded 133 bytes (`YPR2` ‖ identity 33 ‖ nonce 16 ‖ bundle hash 32 ‖
   token 16 ‖ pairing key 32), not the `yap:v2:` text or its base64url. Any other reading (text form, length
   prefixes) gives a different prologue and phones would not agree. The proposal should say which, and give
   one prologue vector. The resume prologue's "both identity keys" I read as the raw 33-byte keys, smaller
   first.
3. **A wrong `psk` or prologue gives no signal to the sender.** It fails on the responder's first read, so the
   initiator just gets silence. That matches the "drop" rule but means the UI cannot tell "other phone not
   there" from "wrong QR" without a timer.
4. **Rounding of numbers in §3 holds:** 65,535 is the cap on a whole Noise message; plaintext capacity is
   65,519; a 1,883-byte bundle fits with room to spare.

## What this does not show

- Not run: the **signed confirmation** binding (the handshake hash is available and equal on both sides, but
  the signatures over it are not built here), **Ready-responder loss and restart**, **bounded dispatch**
  (at most 3 lookup values, 4 bound links, one start per 10 s), `PAIR_FIND` handling. Those are endpoint state
  machines, not properties of `snow`.
- No third-party Noise test vectors were run here (snow ships its own vector tests; they were not re-run).
- Not run on Android (see above). Not a timing or side-channel review.

## Emulator run

Run on the existing `yap_test` emulator (`emulator-5556`, Android 9, API 28, ABI `x86_64`, "Android SDK built for x86_64"), by pushing the cross-compiled x86_64 test binary with `adb push` into `/data/local/tmp` and running it there. **An emulator is not a real phone**: it is x86_64 (the arm64-v8a build was not run), uses the host's CPU and disk, and says nothing about real-device timing, flash behaviour or vendor changes.

Result: **12 passed, 0 failed** (same as desktop).

## Run it

```
cargo test
powershell -File build-android.ps1   # builds both Android ABIs (needs the NDK)
```
