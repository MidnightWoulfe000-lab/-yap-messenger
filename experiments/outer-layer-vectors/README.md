# Outer layer test vectors

Reference test vectors for the outer encryption layer in the
[group encryption proposal](../../docs/proposals/group-encryption-v1.md), §3.4.
The code here exists only to produce and check them. **It has not been reviewed
and is not linked into the app.**

[`vectors.txt`](vectors.txt) lists fixed inputs and their exact outputs: a group
envelope, the same just before an hour boundary, a one-to-one piece in each
direction, and an empty piece. Any implementation of the layer must reproduce
them byte for byte.

```
cargo test             # checks vectors.txt and the rules below
cargo run > vectors.txt  # regenerates it after a reviewed change to the rules
```

The tests also check:
- the HKDF library against RFC 5869 test case 1 (all 42 bytes);
- the XChaCha20-Poly1305 library against the published example in
  draft-irtf-cfrg-xchacha-03, appendix A.3.1 (all 130 bytes of ciphertext and
  tag);
- that changing the format tag, nonce, ciphertext or tag, or cutting or
  lengthening the output, is refused;
- that each envelope header field (ID, label, sender hint, creation time,
  expiry) is bound on its own, and a piece's label and hour too;
- that the two directions of a pair, and group and pair messages, get
  different keys, and an hour boundary changes the key;
- that a relay lowering the hop count does not break an envelope.

[`src/policy.rs`](src/policy.rs) adds the receive and re-wrapping rules, with
the clock and nonce passed in, and [`tests/policy.rs`](tests/policy.rs) checks
them: hours 25 back and 1 ahead accepted, 26 back and 2 ahead refused, no
underflow near zero, pieces opening after 3 and 23 hours, re-wrapping starting
at exactly 20 hours, a re-wrapped piece keeping its inner bytes while its old
label and hour are refused, and random nonces differing.

The vectors fix the nonce so the output is repeatable. In use the nonce is 24
random bytes from the operating system's secure random source
(`policy::random_nonce`), new for every message. Fixed nonces must never be
used outside tests.
