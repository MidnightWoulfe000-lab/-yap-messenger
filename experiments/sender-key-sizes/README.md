# Sender-key size measurement

Measures the libsignal message sizes used in the
[group encryption proposal](../../docs/proposals/group-encryption-v1.md), with
the same pinned libsignal revision as `endpoint/` and `groups/`. It is not
linked into the app.

```
cargo run --release
```

It prints the largest size seen in each case. A largest seen value is
evidence, not a proven maximum.
