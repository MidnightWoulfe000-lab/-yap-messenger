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
- the HKDF library against RFC 5869 test case 1;
- the XChaCha20-Poly1305 library against the published example in
  draft-irtf-cfrg-xchacha-03, appendix A.3.1;
- that tampering with the ciphertext, associated data or format tag is refused;
- that the two directions of a pair, and group and pair messages, get
  different keys;
- that an hour boundary changes the key;
- that a relay lowering the hop count does not break an envelope, but a
  changed creation time does.

The vectors fix the nonce so the output is repeatable. In use the nonce is 24
random bytes from a cryptographically secure source, new for every message.
