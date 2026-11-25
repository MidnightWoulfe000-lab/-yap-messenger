# Yap message core

This experimental library now implements canonical opaque envelopes, bounded
record framing/stream I/O and a transactional relay queue. Android does not yet
call it. It does not encrypt, authenticate identities, store endpoint secrets or
issue recipient receipts.

## Check it

Use Rust 1.100.0 for the current locked checks:

```sh
cargo test --locked --manifest-path core/Cargo.toml
cargo clippy --locked --manifest-path core/Cargo.toml --all-targets -- -D warnings
```

`envelope` validates length, version, lifetime and hop bounds before copying a
payload. Its public sender hint is a session lookup hint, not authentication.
`Header::binding()` gives the exact 68 immutable bytes a crypto adapter must copy
inside authenticated plaintext and verify before committing any staged state.
The wire layout is recorded in the [implementation notes](../docs/messaging-implementation.md).

`relay::Relay` opens a redb database with immediate-durability commits. Accepted
custody survives restart. A database failure returns an error instead of an
acknowledgement. The caller supplies every operational quota and clock-skew
allowance explicitly through `RelayConfig`; there are no hidden global defaults.

Only `Admission::Stored` and `AlreadyStored` confirm current durable custody.
`RecentlyForwarded` describes a cache hit and must not be acknowledged as custody.
`pending_for` preserves records, excludes their incoming connection and decrements
the outgoing hop budget without changing ciphertext. `transfer_custody` releases
intermediary custody only after a next-link durable acknowledgement. It must never
be used to clear an endpoint's outgoing-message store after a transport ack.

Relays deduplicate on ID plus a SHA-256 digest of every immutable envelope byte.
Pending-record indexing survives cache eviction and restart. The recent cache
has bounded entries, local retention and LRU eviction. Replays can be forwarded
again after cache eviction; endpoint authenticated deduplication is still required.

Running residence time uses an advancing monotonic anchor as well as wall time.
The persisted time high-water mark rejects reopening with an older wall clock.
This conservative availability trade-off is not malicious-backup rollback
protection. The relay queue holds opaque data, not cryptographic endpoint state.

Quotas count logical encoded bytes, not total redb file size or allocator overhead.
Database page allocation and compaction need deployment measurements and disk
budgets before production use. Recent/rate caches reset on process restart, while
pending custody remains durable. Global budgets and connection caps bound work;
they do not establish fair scheduling or Sybil resistance.

Tests include forged-ID injection, altered metadata, cache flooding, queue and
byte quotas, rate/connection limits, expiry, clock rollback, split/coalesced reads,
restart and abrupt process exit after a completed commit. They do not simulate
power loss during a disk write or prove operating-system storage guarantees.
