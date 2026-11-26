# yap-groups: group rules, stage one

**Experimental and not audited.** This crate puts the decisions in [GROUP_MECHANICS.md](../docs/GROUP_MECHANICS.md) into code that can be tested without phones. It has no transport, no encryption and no Android code. Nothing in the app uses it yet.

```sh
cargo test --manifest-path groups/Cargo.toml
```

## What it covers

- **Signing** (`keys.rs`): members sign with their libsignal identity key, the same key the endpoint uses for pairing, through libsignal's own signing API (pinned to the endpoint's revision). Every signed message starts with a label for its purpose, so a group signature cannot pass as a pairing signature. Because libsignal signatures are randomised, an event's hash leaves the signature out. Linking libsignal makes this crate AGPL-3.0-only, like `endpoint/`.
- **Signed events** (`event.rs`): genesis with two founders, propose, approve, object (with a reason), admit, propose revoke, vote revoke, add device, revoke own device. Every signature covers the group ID. Each event lists the events its author had seen (its parents), which gives an order without trusting any clock.
- **The log and state** (`state.rs`): each event is checked only against its own history, so the result never depends on the order events arrive in. Two devices with the same events always end up with the same group state.
- **Admission rules:** at least one approval, any objection blocks, and the window is timed by the proposal's age counter. "Independent approval" stops the sponsor's own approval counting from 5 members, and it can be switched off.
- **Revocation:** a majority of the other members in groups of up to 5, and 3 approvals from 6. The target is not counted and cannot vote. The whole thing can be switched off at creation. A revoked member can come back through a new proposal, under the normal admission rules.
- **Join history:** founders see every join; later members see joins from their own onwards.
- **Age counter** (`age.rs`): signed hops, where only members' time counts.
- **Password guard** (`password.rs`): at least 8 characters, Argon2id hash, 5 tries then a 30-minute cooldown that grows by 30 minutes each lockout, changeable, no recovery.
- **Scramble decision** (`scramble.rs`): an owner trigger with the password wipes all Yap data. A group trigger needs a revocation the device can check itself in a group created with "group scramble" on, and wipes that group only. There is no individual opt-out. Commands expire at 24 hours of age.

## Not decided here

Where the specification is still open, the crate either leaves the feature out or reports a `Warning` instead of picking a rule:

- `LateObjection` (decided): an objection from the other side of a split mesh that arrives after the member was admitted. The admission stands; `notices_for` gives the objector a notice, and `can_challenge` says whether they can start a removal vote against the new member.
- `ActedWhileRevoked` (decided): a member revoked on one side keeps acting on the other side. What they did before the sync stands, including their messages and governance actions; once the revocation reaches a device, they are out.

## Provisional choices made while building (need confirming)

- **Group size for a revocation vote** is counted when the vote starts.
- **Coming back after revocation** (decided: through a new proposal) keeps the earlier membership on record, keeps every old device revoked, and refuses a proposal made before a later revocation, so old approvals cannot be reused.
- **Owner scramble** is available whenever the device has a password set; there is no separate opt-in.

## Known weakness (accepted)

Anyone forwarding an aged item (a proposal or a scramble command) can **drop the last hops**, making it look younger. The remaining hops still verify. This has the same effect as a member under-reporting its time, but a non-member relay can do it too. The test `dropping_the_last_hops_is_not_detected` records it. Accepted as a known weakness (user, 26 November 2026).

## Not in stage one

Encryption and key rotation, the mesh transport and envelope, confiscation reporting (§7), the temporary room for isolated members (§9), per-device signing keys, group revocation of a single device, persistence, and any Android UI. The owner's password travels as a plain field here; before real use it must be encrypted to the target device.
