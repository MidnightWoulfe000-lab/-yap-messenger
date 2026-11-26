# Yap group mechanics specification

**Status:** Planning decisions and open design questions. **Not implemented or security-audited.**  
**Purpose:** Preserve the group behaviour discussed for Yap so future Codex sessions do not silently replace it with conventional administrator-controlled chat groups.

## 1. Principles

Yap rooms are intended for people organising across an unreliable, sometimes disconnected Bluetooth/Wi-Fi mesh. Group governance should not depend on a permanent central server or a single all-powerful administrator. Membership and governance events need to be verifiable even when devices receive them later through relays.

These are **product decisions**, not a complete consensus, security or cryptographic protocol. Details marked **Open** must not be invented during implementation.

## 2. Creating a room

**Agreed direction**
- A member creates a room and invites another person through a QR code.
- The QR invitation carries room-joining information, discussed as a **room key**.
- The **first two members are exempt** from the later approval/objection process. This allows the room to be established.
- Room membership must subsequently be verified rather than inferred from physical proximity or a Bluetooth connection.

**Security qualification / open**
- The phrase “room key” was used in discussion. A QR code that contains a shared decryption secret may give anyone who photographs it access to the room. The final QR format, invitation expiry, one-use semantics, authenticated key exchange and whether it contains a secret or only a capability are **not yet decided**.
- The founder's exact powers, room recovery rules and whether a room can ever have a sole member after creation remain **open**.

## 3. Inviting a third or later member

**Agreed direction**
1. An existing member proposes a new member, acting as their **sponsor/inviter**.
2. The proposal is broadcast through the room, including across the mesh as peers reconnect.
3. Existing members have an **objection window** in which to respond.
4. **At least one approval** during that window is needed for admission.
5. **Any objection overrides an approval** under the discussed rule; the proposed member is not admitted if an objection is raised.
6. An objection must have a **reason**, and the reason is displayed to the group.
7. The first two members are exempt from this mechanism; it applies to later joins.

**Open**
- The duration and starting event of the objection window.
- Whether the sponsor's own approval counts toward the one-approval requirement.
- Whether silence after one approval results in admission automatically at the window's end.
- Whether the objector must be a current member when the objection is signed or when it arrives.
- What happens if a late objection arrives after admission during a network partition.
- Whether an objection can be withdrawn, appealed or overruled.
- How to prevent fake objections, spam proposals and repeated targeting.
- Whether the proposed member receives the objection reason, or only existing members do.

**Implementation requirement:** Membership proposals, approvals, objections and final decisions must be authenticated and replay-resistant. Do not treat a relay's assertion as a vote.

## 4. Membership history and accountability

**Agreed direction**
- Members can see **who invited whom**: each member's sponsor/inviter is recorded.
- Members can see **when someone joined**.
- A newly admitted member **cannot see the earlier join history** from before they entered the room.
- This limits retrospective disclosure while retaining accountability for subsequent admissions.

**Open**
- Whether existing members retain the full historical log, and whether the group creator is a special case.
- Whether “join time” means device time, verified logical order or a consensus event.
- What a member sees about pre-existing participants at join time (current roster vs earlier invitation chain).
- Whether past messages and historical governance events are accessible to newcomers.
- How the history restriction is enforced cryptographically, rather than merely hidden by the interface.

## 5. Multiple devices and revocation

**Agreed direction**
- A person may have **secondary devices** associated with their identity.
- A member may **revoke their own secondary device at any time**, without requiring a group vote.
- A **group-initiated revocation** of a device or member requires a threshold of approvals rather than one person's unilateral action.
- **Three or four approvals** were discussed as a possible threshold, **not a final fixed number**.

**Open**
- The exact threshold, whether it is fixed or proportional to group size, and how small groups operate.
- Whether the target can vote, and whether a sponsor has any special responsibility.
- Distinction between removing a device, suspending a member, and expelling a member.
- Whether the affected person can appeal, rejoin or recover a replacement device.
- How revocation rotates group encryption keys and protects subsequent messages.
- How revoked devices are treated during offline periods and network partitions.

## 6. Scramble on revocation

**Agreed direction**
- An optional **scramble** feature was discussed: a queued command intended to erase local room chat on a revoked device when that device next reconnects.
- The feature is **opt-in when joining the room**, rather than secretly imposed later.
- The intention is to reduce exposure if a device is lost, confiscated or revoked.

**Critical security limitation**
- A remote wipe command **cannot be guaranteed** to run on an offline, compromised, modified or attacker-controlled device. A device can refuse the command, preserve backups, copy data or never reconnect.
- Therefore, scramble is a **best-effort deletion request**, never a confidentiality guarantee.
- Future encryption key rotation can prevent access to **future** messages but cannot retroactively erase messages already copied.

**Open**
- Who may issue a scramble command and whether it is automatic upon valid revocation.
- How the command is authenticated, queued, expired and acknowledged.
- Whether the command deletes one room or all Yap data.
- Interaction with backups, screenshots, exported content and multiple devices.
- Consent language and user expectations.

## 7. Confiscated-device reporting

**Agreed direction**
- A member can report that another member's device has been **confiscated**, using a friend's phone if necessary.
- The room receives the report and **votes** on the appropriate status/action.
- The resulting status is propagated through the mesh, including to peers that reconnect later.
- This is distinct from ordinary device self-revocation, since the person may no longer control their phone.

**Open**
- How the reporter proves their identity, or how a third party is authorised to report on someone else's behalf.
- Whether a report merely marks the device as suspect, suspends it temporarily, or triggers full revocation.
- Vote threshold, duration, evidence, appeal, false-report protection and emergency behaviour.
- How to avoid exposing the reporter or targeted person through public metadata.
- Whether a confiscation status applies to one device or all devices of an identity.

## 8. Split networks and conflicting decisions

**Agreed direction discussed**
- A room may split into disconnected subnets and receive governance decisions at different times.
- **Timestamps were proposed as the way to resolve conflicting decisions**, with the **latest decision prevailing** after reconnection.
- Governance changes and statuses must be propagated as connectivity returns.

**Important unresolved security issue**
- “Latest timestamp wins” is **not yet a safe complete rule**. Offline phones can have incorrect clocks, malicious members can forge timestamps, and a late-arriving event is not necessarily a later-authorised event.
- A robust rule must define which signed event is authoritative, how causal order is established, how concurrent valid decisions are handled, and whether revoked participants may still cast votes.
- **Do not implement last-writer-wins solely using device wall-clock time** without a reviewed protocol.

**Open**
- Whether conflicting votes can be merged, must be replayed or require a fresh vote.
- Whether membership changes during a partition affect eligibility and quorum.
- Whether decisions are tentative until enough participants have seen them.
- How to converge deterministically without allowing an attacker to manipulate time.
- What users see while the group's governance state is uncertain.

## 9. Privacy and metadata

**Agreed direction**
- Minimise unnecessary exposure of neighbours and device identities.
- Use **rotating link identifiers** for nearby transport interactions rather than static discoverable IDs where practical.
- Group membership and governance must still be verifiable despite changing transport identifiers.
- Avoid broadcasting more group history than a member is entitled to receive.

**Open**
- What membership and sponsor metadata is visible to relays, nearby non-members and invited candidates.
- How to authenticate changing transport identifiers without making users trivially trackable.
- Retention periods for join records, objections, confiscation reports and revocation events.

## 10. Retention and constrained devices

**Agreed direction**
- Storage pressure on low-end phones matters.
- Use message compression where safe and beneficial.
- Support **configurable message retention windows** so local history and queued messages do not grow indefinitely.
- Mesh relaying should use bounded queues and deduplication.

**Open**
- Whether retention is chosen per user, per room or by room vote.
- Whether governance records require longer retention than chat messages.
- How compression interacts with encryption and sensitive-data side channels.
- How offline nodes learn enough history to validate current membership without receiving disallowed past history.

## 11. Relationship to the mesh

- Group messages may be carried by members or intermediary devices over Bluetooth, Wi-Fi Direct and optional internet bridges.
- **Transporting a packet does not make the transporter a room member.**
- A relay must not gain the ability to read group messages or approve membership simply because it forwards traffic.
- The message envelope must carry sufficient authenticated information to distinguish routing, membership and governance state.
- Network topology and neighbour sharing are a separate design area; group governance must not depend on a single permanently connected node.

## 12. Required design work before implementation

1. Define identities, device keys, room identifiers and signed event formats.
2. Specify invitation QR contents and safe key distribution.
3. Define the admission state machine, approval/objection timing and late-event handling.
4. Define device self-revocation, group revocation and confiscation-vote state machines.
5. Define deterministic convergence for partitions without trusting device clocks.
6. Define key rotation, access to past/future messages and metadata visibility.
7. Threat-model malicious insiders, forged events, replay, denial of service and compromised devices.
8. Write tests for two-member bootstrap, concurrent invitations, objections, partitioned votes, conflicting revocations, late arrivals and reconnection.
9. Validate behaviour on real Android devices before expanding to iOS.

## 13. Implementation status

As of November 2026, these group mechanics are **design decisions only**. The existing Android proof of concept exchanges short, unauthenticated BLE messages between two phones. It does **not** implement encrypted rooms, signed governance, group voting, reliable relay, confiscation reporting or remote deletion.

**Instruction to Codex:** Treat the **Agreed direction** sections as product intent, the **Open** sections as decisions requiring the user's input, and the security qualifications as non-negotiable engineering cautions. Do not silently invent or claim implementation of missing rules.
