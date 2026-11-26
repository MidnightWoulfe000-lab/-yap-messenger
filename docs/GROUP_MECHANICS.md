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

**Decided (user, 26 November 2026)**
- **Objection window length:** chosen by the group's creator **at group creation**, anywhere from **1 to 60 minutes**.
- **Sponsor's own approval:** counts toward the one-approval requirement **only while the group has 4 or fewer members**. From 5 members upwards, someone other than the sponsor must approve. This rule is a **setting chosen at group creation** (working name "Independent approval"; the user suggested something milder than "infiltration deterrent"). When it is switched off, the sponsor's approval always counts.
- **Window timing (user, 26 November 2026):** the window is timed with the same **age counter** as scramble expiry (§6), not by any device's clock. The proposal starts at age 0 when the sponsor signs it, and each member device that holds or forwards it adds its own stopwatch time and signs the new age. An approval or objection counts only if it was signed while that member's copy was younger than the window. The same accepted flaws apply: a dishonest member forwarder can under-report the age, and time a phone spends switched off is not counted.

**Open**
- What happens to an objection that reaches the sponsor's device only after the window closed and the member was admitted (for example, sent from the other side of a split mesh). Linked to the late-objection question below.
- How the 1-minute minimum interacts with members who are out of range: in a split mesh, a short window can close before some members have seen the proposal at all.
- Whether the window length and the "Independent approval" setting can ever be changed after creation, and by what process.
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

**Decided (user, 26 November 2026)**
- **Existing members keep the full history** they were entitled to see. Newcomers still only see history from their own admission onwards.

**Open**
- Whether the group creator is a special case.
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

**Decided (user, 26 November 2026)**
- **Groups of up to 5 members** need a **majority** to revoke a device or member.
- **Groups of 6 or more** need **3 approvals**.
- **Group revoking is a setting chosen at group creation.** It can be switched off, in which case the group cannot revoke anyone by vote. Members can still revoke their own secondary devices (above).
- **Accepted risk:** with group revoking switched off, the group has no way to cut off a confiscated or compromised device. Only the owner's own actions (self-revocation, owner-triggered scramble in §6) remain. This is a hazard users accept by their choice at creation, and the creation screen must say so plainly.

- In groups of 2–3 members a majority can be just 2 people. **Confirmed as intended.**
- **Majority means a majority of all current members, not just of those who vote** (user, 26 November 2026). Not voting counts the same as voting no.
- **The person being removed is not counted and cannot vote.** Example: in a 5-member group, revoking one member needs 3 of the other 4. In a 2-member group, the other member alone can revoke.

**Open**
- Which membership list counts when the group is split (§8). A majority of all members cannot be reached inside a small isolated subset, which fails safe but means that subset cannot revoke anyone.
- Whether the threshold uses the group's size when the vote starts or when it ends, if members join or leave during the vote.
- With group revoking switched off, how a confiscated device (§7) is dealt with.
- Whether a sponsor has any special responsibility. (The target cannot vote: decided above.)
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

**Decided (user, 26 November 2026)**
- A scramble can be triggered in two ways, both only for devices whose owner opted in when joining:
  - **Automatically**, when a valid group revocation of that device takes effect.
  - **By the device's owner**, for example from another of their devices or a friend's phone if theirs is lost or taken.

- **Owner identity:** each device gets an **alphanumeric password**, set up with the profile. The owner uses it to prove who they are when triggering a scramble from a device other than the lost one, such as a friend's phone. This replaces the earlier idea of a PIN.
- **Guess limit:** **5 wrong attempts**, then a **cooldown** before more attempts are allowed. The first cooldown is **30 minutes**, and each further lockout adds **30 minutes** (30, 60, 90 minutes and so on).
- **Who checks and counts:** the **target device itself** (the one being scrambled) checks the password and keeps the count of wrong tries. There is one counter, so a split mesh cannot give an attacker 5 tries per partition.
- **Password rules:** at least **8 characters**, alphanumeric. It can be **changed on the device**. There is **no recovery**: if the owner forgets it, the owner-triggered scramble will not work for that device. (Automatic scramble on group revocation does not need the password.)
- **What gets wiped:** an **owner-triggered** scramble wipes **all Yap data** on the device. A **group-triggered** (automatic) scramble wipes **only that group's** data.
- **Expiry:** a scramble command is valid for **24 hours**. If the device has not reconnected and received it in that time, it is dropped.
  - Consequence to show users: a device kept offline for more than 24 hours will never receive the command. The owner would need to issue it again.
  - The 24 hours cannot rely on the target device's own clock, which its holder can change.
- **Who checks the time (decided, user, 26 November 2026):** the **forwarding devices** authenticate the time. The issuing device signs the time it issued the command; each forwarding device checks that against its own clock, **drops** a command older than 24 hours, and signs that it was still valid when it passed it on. The target device accepts the forwarders' signed check instead of trusting its own clock.
  - Only **group members' devices** (or the owner's own other devices) may vouch for the time. Under §12, a stranger relaying the packet carries it but its time check counts for nothing.
  - Forwarders' clocks can be wrong too, so checking the issue time against each forwarder's clock is not enough on its own.
- **Clock errors: decided (proposed by Claude, accepted by user, 26 November 2026):** measure the command's **age**, not the time of day.
  - The command carries an age counter, starting at 0 when issued.
  - Each member device that holds it adds how long it held it, measured with the phone's **elapsed-time stopwatch** (on Android, `SystemClock.elapsedRealtime`), which keeps counting correctly even when the phone's date and time are set wrong. It signs the new age before passing it on.
  - Any device drops the command once its age passes 24 hours.
  - No device needs a correct clock, only a working stopwatch for the time it held the packet. Delay-tolerant networking uses the same idea for nodes without accurate clocks (the Bundle Age block in RFC 9171).
  - Time spent in transit over a single radio link is not counted, but that is seconds, not hours.
- **Known flaws (accepted, since scramble is opt-in):**
  - A dishonest **member** forwarder can under-report how long it held the command, stretching its life. Each forwarder signs its own addition, so this can be traced to it afterwards but not prevented.
  - The stopwatch resets when a phone restarts. A forwarder must save the age before shutting down; time spent switched off is lost, so the command lives a little longer than 24 hours of real time.
  - None of this stops an attacker-controlled target device from refusing the command.
  - What this protects against: an honest target device with a wrong clock, and an old command being replayed against a device the owner has since got back. It does not stop an attacker-controlled device from refusing the command, which remains the best-effort limit above.

**Security qualification (password)**
- The check happens only when the target device next reconnects, which is also the only time a scramble can run, so this adds no extra delay.
- Password guesses travel across the mesh and must be **encrypted to the target device**, so relays and other members never see them.
- A confiscated device holds whatever it uses to check the password, and its holder can attack that offline without the 5-try limit. The password must therefore be stored only as a slow, salted hash (for example Argon2id), and it should **authorise actions on that one device only**, so learning it gives an attacker nothing else. Users should be told not to reuse it.
- A device the attacker controls can ignore the count or refuse the scramble entirely. This is the same best-effort limit as scramble itself.

**Open**
- Whether the same password also authorises confiscation reports (§7) on the owner's own behalf.
- Whether an automatic scramble also follows a self-revocation of a secondary device.
- How the command is authenticated, queued and acknowledged.
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

## 9. Move to Secure New Group: protection during mesh isolation

**Confirmed user clarification (26 November 2026).** This is **not** primarily a procedure for evacuating a compromised group. It addresses a **small part of an existing group becoming isolated from the larger mesh**, so its members can no longer know whether somebody has been revoked elsewhere.

### Confirmed product behaviour
- A room may be split into a **larger connected network** and a **small isolated subset**.
- The isolated members may miss governance updates, including a member or device being revoked on the larger side.
- Because the subset **cannot confirm the current revocation state**, Yap should **prompt those isolated members to create a new, secure group** for their local conversations.
- That secure group is a **temporary way to continue communicating during isolation**, until they can reconnect with the larger network and reconcile what happened.
- The aim is to avoid treating a stale copy of the original group's membership as definitely trustworthy.

This feature is separate from voting to revoke someone, remote scramble, confiscation reporting and simply rotating the keys of the original group.

### Security properties the implementation must provide (design requirements, not settled mechanics)
1. Clearly show **membership/revocation state is uncertain** while partitioned; do not suggest that the isolated phones know who has or has not been revoked elsewhere.
2. Create the new group with a **different group identity and fresh cryptographic keys**, not a copy of the original group's room key.
3. Give access to the new group only to participants **explicitly enrolled and authenticated for that new group**. Do not copy the old membership list as proof of current authorisation.
4. Do not silently forward keys, messages, attachments or governance records between the original and temporary rooms.
5. When connectivity to the larger network returns, synchronise and verify signed governance updates before treating the original room as current. **Reconnection is not an automatic security guarantee**.
6. Keep the temporary room distinguishable from the original room; users must understand which room receives a message.

### Security limitation
A disconnected subset **cannot discover revocations that happened elsewhere until information reaches it**. A newly formed group reduces reliance on *stale old-group membership*, but it cannot prove that each participant is still trusted by the wider group. If a revoked or compromised device is physically present in the isolated subset and admitted to the new room, the new room is not safe from it. The feature must be described as a risk-reduction option, **not a guarantee that isolation is secure**.

### Open questions requiring user decisions and protocol review
- What should trigger the prompt: a measured isolation period, loss of a quorum, loss of particular peers, or another signal? A phone does not necessarily know whether it is in the minority partition.
- Should Yap only **offer** the move, or should it block sending in the original room after some threshold? The clarified direction explicitly says **prompt**.
- Who may create the temporary group, who is invited, and how each person's device/identity is authenticated during isolation?
- Does the new temporary group use the standard first-two-member exemption and approval/objection rules, or a special isolated-room bootstrap?
- How can the subset check for a locally known revocation, and how does it handle suspects or conflicting local governance information?
- ~~On reconnect, does the temporary group remain as a separate conversation, get archived or offer a carefully verified return to the original room?~~ **Decided (user, 26 November 2026):** on reconnect, the temporary group can be **kept** (as a separate conversation) or **dissolved**. It is never merged into the original room. **Do not silently merge members, keys or histories.**
- Who makes the keep/dissolve choice: each member for their own copy, the creator of the temporary group, or a group vote. What "dissolve" does to its local history.
- What happens if several isolated subsets each create a different temporary group?
- How are pending messages and room history handled during the split and after reconciliation?

### Required tests before shipping
- Small subgroup becomes isolated while the main network revokes a device: show the **uncertainty prompt** without pretending the subgroup saw the revocation.
- Isolated members establish a separate new room with fresh keys; original group membership does **not** grant automatic access.
- Reconnected devices learn valid signed revocation updates, and the UI updates without implicitly merging the temporary room.
- A compromised/revoked device in the isolated subset must not be described as safe merely because a new group was created.

**Instruction to Codex:** Implement none of these unconfirmed state-machine details without a reviewed specification. Preserve the confirmed user intent: **prompt isolated members to establish a separate secure group because they lack up-to-date revocation information, then reconcile when the wider mesh reconnects**.

## 10. Privacy and metadata

**Agreed direction**
- Minimise unnecessary exposure of neighbours and device identities.
- Use **rotating link identifiers** for nearby transport interactions rather than static discoverable IDs where practical.
- Group membership and governance must still be verifiable despite changing transport identifiers.
- Avoid broadcasting more group history than a member is entitled to receive.

**Open**
- What membership and sponsor metadata is visible to relays, nearby non-members and invited candidates.
- How to authenticate changing transport identifiers without making users trivially trackable.
- Retention periods for join records, objections, confiscation reports and revocation events.

## 11. Retention and constrained devices

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

## 12. Relationship to the mesh

- Group messages may be carried by members or intermediary devices over Bluetooth, Wi-Fi Direct and optional internet bridges.
- **Transporting a packet does not make the transporter a room member.**
- A relay must not gain the ability to read group messages or approve membership simply because it forwards traffic.
- The message envelope must carry sufficient authenticated information to distinguish routing, membership and governance state.
- Network topology and neighbour sharing are a separate design area; group governance must not depend on a single permanently connected node.

## 13. Required design work before implementation

1. Define identities, device keys, room identifiers and signed event formats.
2. Specify invitation QR contents and safe key distribution.
3. Define the admission state machine, approval/objection timing and late-event handling.
4. Define device self-revocation, group revocation and confiscation-vote state machines.
5. Define deterministic convergence for partitions without trusting device clocks.
6. Define key rotation, access to past/future messages and metadata visibility.
7. Specify and review the isolation fallback: how to detect uncertain revocation state, prompt the isolated subset to form a separately keyed temporary group, and safely reconcile on reconnection.
8. Threat-model malicious insiders, forged events, replay, denial of service and compromised devices.
9. Write tests for two-member bootstrap, concurrent invitations, objections, partitioned votes, conflicting revocations, late arrivals and reconnection.
10. Validate behaviour on real Android devices before expanding to iOS.

## 14. Implementation status

As of November 2026, these group mechanics are **design decisions only**. The existing Android proof of concept exchanges short, unauthenticated BLE messages between two phones. It does **not** implement encrypted rooms, signed governance, group voting, reliable relay, confiscation reporting, isolation fallback groups or remote deletion.

**Instruction to Codex:** Treat the **Agreed direction** sections as product intent, the **Open** sections as decisions requiring the user's input, and the security qualifications as non-negotiable engineering cautions. Do not silently invent or claim implementation of missing rules.
