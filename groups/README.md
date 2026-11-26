# yap-groups: group rules, stage one

**Experimental and not audited.** This crate puts the decisions in [GROUP_MECHANICS.md](../docs/GROUP_MECHANICS.md) into code that can be tested without phones. It has no transport, no encryption and no Android code. Nothing in the app uses it yet.

```sh
cargo test --manifest-path groups/Cargo.toml
```

## What it covers

- **Signing** (`keys.rs`): members sign with their libsignal identity key, the same key the endpoint uses for pairing, through libsignal's own signing API (pinned to the endpoint's revision). Every signed message starts with a label for its purpose, so a group signature cannot pass as a pairing signature. Because libsignal signatures are randomised, an event's hash leaves the signature out. Linking libsignal makes this crate AGPL-3.0-only, like `endpoint/`.
- **Signed events** (`event.rs`): genesis with two founders, propose, approve, reject (with a reason), override a rejection, admit, propose revoke, vote revoke, add device, revoke own device, and for admins: add, remove, make or unmake admin, change settings; for collectives: propose and vote on settings; and SuperAdmin election votes. Every signature covers the group ID. Each event lists the events its author had seen (its parents), which gives an order without trusting any clock.
- **The log and state** (`state.rs`): each event is checked only against its own history, so the result never depends on the order events arrive in. Two devices with the same events always end up with the same group state.
- **Two group types** (rejection review on or off):
  - **Normal group:** the creator is the SuperAdmin. Admins add and remove members and make admins; only the SuperAdmin removes or demotes an admin, changes the toolkit settings, or switches the group to a collective (which ends every admin role). There are no objections and no window: the sponsor approves and admits straight away.
  - **Collective:** no admins. Members vote on any settings change by majority; switching rejection review off makes a normal group, which then elects a SuperAdmin.
- **Admission rules:** at least one approval, and the window is timed by the proposal's age counter. "Independent approval" stops the sponsor's own approval counting from 5 members, and it can be switched off.
- **Rejection review** (collectives, and normal groups while they have no SuperAdmin): a rejection needs a reason and keeps the person out until enough other members override it (the same number as a removal vote, the rejecter not counted). The override has a fresh window, timed by the rejection's own age, and anyone can reject again during it; the sponsor's Admit has to answer every rejection it has seen.
- **SuperAdmin:** can always be voted out, even with group revoking off, by a majority of the other members in any size of group. The seat then opens an election straight away: anyone can stand, each member votes once, and a majority wins. Until then the group works as a collective, and every admin goes back to being a normal member who must be made admin again.
- **Active in the last 30 days** (`activity.rs`): each phone keeps its own record of when it last received a message or group event from each member, by its own clock. Whoever starts a majority vote (a removal, a rejection, a settings vote) puts the list of members their phone saw as active into it. Majorities count from that list and only people on it can vote; a SuperAdmin election uses the list of the vote that emptied the seat. `check_electorate` compares someone else's list with this phone's records so the app can warn about differences. The vote's list always wins (user, 26 November 2026): a difference never changes the count.
- **Runoff:** if two parts of a split group each vote the SuperAdmin out and elect someone different, the two meet in a new vote on reconnecting. The voters are everyone on either side's list, and only those two can win.
- **Clashes:** when an admin's decision about a member meets a SuperAdmin decision about the same member that neither had seen, the SuperAdmin's wins and the other is reported as `OverruledBySuperAdmin`. Between ordinary admins, removal wins.
- **Revocation:** a majority of the other members in groups of up to 5, and 3 approvals from 6. The target is not counted and cannot vote. The whole thing can be switched off at creation. A revoked member can come back through a new proposal, under the normal admission rules.
- **Join history:** founders see every join; later members see joins from their own onwards.
- **Age counter** (`age.rs`): signed hops, where only members' time counts.
- **Password guard** (`password.rs`): at least 8 characters, Argon2id hash, 5 tries then a 30-minute cooldown that grows by 30 minutes each lockout, changeable, no recovery.
- **Scramble decision** (`scramble.rs`): an owner trigger with the password wipes all Yap data. A group trigger needs a revocation the device can check itself in a group created with "group scramble" on, and wipes that group only. There is no individual opt-out. Removal by an admin never triggers it: scramble is a toolkit tool and follows only a group vote. Commands expire at 24 hours of age.

## Not decided here

Where the specification is still open, the crate either leaves the feature out or reports a `Warning` instead of picking a rule:

- `LateObjection` (decided): an objection from the other side of a split mesh that arrives after the member was admitted. The admission stands; `notices_for` gives the objector a notice, and `can_challenge` says whether they can start a removal vote against the new member.
- `ActedWhileRevoked` (decided): a member revoked on one side keeps acting on the other side. What they did before the sync stands, including their messages and governance actions; once the revocation reaches a device, they are out.

## Choices made while building (proposed by Claude, accepted by user, 26 November 2026)

- **Group size for a vote** is counted when the vote starts.
- **Coming back after revocation** (through a new proposal) keeps the earlier membership on record, lets their old devices work again except any they revoked themselves, and refuses a proposal made before a later revocation, so old approvals cannot be reused.
- **Owner scramble** is available whenever the device has a password set; there is no separate opt-in.
- **Every vote's voters are its list.** Only members on a vote's list may vote on it, including in groups of 6 or more where removal needs a fixed 3 votes.
- **Every vote must carry a list.** It always names at least the person who started the vote, so it is never empty.
- **A vote always counts at least half of all current members** (user, 26 November 2026), leaving out the person it is about, even if its list names fewer (`counted`). This stops one member listing only themselves to take over the group.
- **A group holds at most 128 members** (user, 26 November 2026), so a vote's list can always name everyone active.

## Known weakness (accepted)

Anyone forwarding an aged item (a proposal or a scramble command) can **drop the last hops**, making it look younger. The remaining hops still verify. This has the same effect as a member under-reporting its time, but a non-member relay can do it too. The test `dropping_the_last_hops_is_not_detected` records it. Accepted as a known weakness (user, 26 November 2026).

## Not in stage one

Encryption and key rotation, the mesh transport and envelope, confiscation reporting (§7), the temporary room for isolated members (§9), per-device signing keys, group revocation of a single device, persistence, and any Android UI. The owner's password travels as a plain field here; before real use it must be encrypted to the target device.
