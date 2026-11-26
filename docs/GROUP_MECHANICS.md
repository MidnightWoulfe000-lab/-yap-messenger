# Yap group mechanics specification

**Status:** Planning decisions and open design questions. **Not implemented or security-audited.**  
**Purpose:** Preserve the group behaviour discussed for Yap so future Codex sessions do not silently replace it with conventional administrator-controlled chat groups.

## 1. Principles

Yap rooms are intended for people organising across an unreliable, sometimes disconnected Bluetooth/Wi-Fi mesh. Group governance should not depend on a permanent central server or a single all-powerful administrator. Membership and governance events need to be verifiable even when devices receive them later through relays.

These are **product decisions**, not a complete consensus, security or cryptographic protocol. Details marked **Open** must not be invented during implementation.

**Design intent (user, 26 November 2026):** the group settings chosen at creation (Independent approval, rejection review, group revoking, group scramble) are meant to add up to a **toolkit against authority**: each group picks how much protection it wants. Some things are left to people's judgement on purpose; the software should not try to settle every dispute, because rules that punish too eagerly invite witch hunts.

## 2. Creating a room

**Agreed direction**
- A member creates a room and invites another person through a QR code.
- The QR invitation carries room-joining information, discussed as a **room key**.
- **Group invite QRs change over time, as personal QRs do** (user, 26 November 2026), so an old photo of an invite stops working. How often they change, and whether an invite is one-use, is still open.
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
- **Late objection (user, 26 November 2026):** if a valid objection was signed in time but the sponsor had not seen it when they admitted the member (for example because the group was split), **the admission stands**. When the objection and the admission meet, the person who objected gets a **notice** saying what happened, with the option of **raising a rejection**.
  - ~~Stage one reads "raising a rejection" as starting an ordinary removal vote.~~ **Corrected (user):** "raising a rejection" is a rejection under **rejection review** (below). Once the person has already been admitted, the rejecter can **start a removal vote** (§5); their reason is shown to everyone and the normal removal thresholds apply.
- **Rejection review (user, 26 November 2026), described but not yet specified in full:**
  - A **setting chosen at group creation**, like Independent approval.
  - When a sponsor proposes someone, **every member gets an approve/reject message**.
  - A member who rejects **must give a reason**, which the other members see.
  - The other members read it and either **accept the rejection** (the person is not admitted) or **carry on with approval through a 3-vote process**.
  - **Override threshold:** the same as removal (§5): a majority of the other members in groups of up to 5, and 3 approvals from 6 members. Neither the rejecter nor the candidate is counted.
  - **Override time:** a **fresh window** of the group's chosen length starts when the rejection is signed, timed by the age counter. If the override has not passed by the end, the rejection stands.
  - **With rejection review switched off:** there is **no objection** step at all, and **the sponsor can approve** the person themselves. Instead the group has **admins**: the creator, plus members the creator deputises, with admin powers comparable to WhatsApp and similar apps. This is the conventional model, for groups that choose it.
  - This replaces point 5 above ("any objection overrides an approval"): with review on, a rejection can be overridden; with review off, there are no objections.
  - Built in `groups/` (stage one), with the provisional choices listed in its README.
- **Admins (user, 26 November 2026)**, only in groups with rejection review switched off:
  - **Powers:** every admin can **add members**, **remove members**, **make or unmake admins** and **change group settings**.
  - **The creator is protected:** other admins cannot remove the creator or take away their admin role. The only way to remove the creator is a **normal removal vote** (§5), and only if the group has **group revoking** switched on. The same applies to a later SuperAdmin (below).
  - **No admin left:** if the creator leaves or is removed and no other admin remains, **members vote a new admin in**, using the same threshold as a removal vote (§5).
  - **Conflicts across a split mesh:** when admins on different sides make decisions that clash (for example one removes X while another makes X an admin), **the SuperAdmin's decision wins**. Between other admins, the removal wins.
  - **What counts as a clash (proposed by Claude, accepted by user, 26 November 2026):** two decisions clash when neither admin had seen the other's and both concern the same member.
  - **Only the SuperAdmin can remove an admin** (user, 26 November 2026). Ordinary admins cannot remove each other, so two admins removing each other cannot happen.
- **Measuring "active in the last 30 days" (proposed by Claude, accepted by user, 26 November 2026):**
  - **Sending a message or signing a group event** counts as activity (the user suggested messages).
  - Each phone dates it by **when it received it**, not by the time the sender's phone put on it, because the sender's clock can be wrong or faked (for example dated 60 days ahead to look active for longer).
  - The removal vote carries the list of members its proposer's phone saw as active, and every phone counts from that list, so every phone counts the same people. **If a phone's own records disagree with the list, the vote's list wins** (user, 26 November 2026): the phone still counts from the list and may only warn its user.
  - **SuperAdmin (user, 26 November 2026):** the creator is the **SuperAdmin**. If the creator leaves or is removed, the admin the members vote in becomes the new SuperAdmin, with **the same protection** as the creator. There is one SuperAdmin at a time.
  - **Only the SuperAdmin can change the toolkit settings** (rejection review, Independent approval, group revoking, group scramble, and the objection window). Other admins cannot touch them. Normal groups have no objections, so the objection window is a toolkit setting too.
  - **Only the SuperAdmin can overrule another admin's decision.**
  - **Removing the SuperAdmin (user, 26 November 2026):** always possible, **even with group revoking switched off**. Any member can propose a removal vote, and it passes if a **majority of the members active in the last 30 days** accepts it. If it passes, the members must **vote in a new SuperAdmin straight away**.
- **Two group types (user, 26 November 2026):**
  - **Normal group:** admins and a SuperAdmin, as above, and no objections. The sponsor or an admin admits new members, with **no window to wait for** (user, 26 November 2026).
  - **Removal by an admin never triggers group scramble** (user, 26 November 2026). Scramble is a toolkit tool and follows only a removal by group vote.
  - **Collective** (name proposed by Claude, needs confirming): no admins and no SuperAdmin. New members go through rejection review, and removals go through the removal vote (§5).
  - Switching rejection review on turns a normal group into a collective, and every admin role ends.
  - **Back to a normal group (user, 26 November 2026):** a collective has no SuperAdmin, so it turns back into a normal group by a **member vote to switch rejection review off**. A SuperAdmin is then elected as below.
  - **A collective votes on any settings change** this way, not only switching rejection review off (user, 26 November 2026).
- **Electing a SuperAdmin (user, 26 November 2026):**
  - When the SuperAdmin is removed, **every admin goes back to being a normal member** while the group works as a collective, and must be made admin again afterwards (user, 26 November 2026).
  - **Anyone** in the group can stand, and the winner needs a **majority**.
  - Until a new SuperAdmin is chosen, the group **works as a collective**.
  - If two isolated parts of the group each elect a SuperAdmin and then reconnect, there is a **new vote with those two as the only candidates**.
- **Rejection stands until the group decides (confirmed, user):** a rejection must give a reason. It keeps the person out unless the other members, having read the reason, vote to let them in through the override above.
- **Open (rejection review and admins):**
  - ~~Whether members can overrule an admin's decision other than by the removal vote.~~ Decided above: only the SuperAdmin can overrule an admin.

  - How the new SuperAdmin is voted in after a removal: who can stand, the threshold, and what happens to the group until one is chosen.
  - Whether a collective can turn back into a normal group, and who would become SuperAdmin. A collective has no SuperAdmin to change its settings, so who can change them at all.
  - What counts as a clash between two admin decisions, so every phone resolves it the same way.
  - How admins interact with group scramble and Independent approval.
  - ~~Whether the rejecter, or anyone else, can reject again during the override window.~~ **Decided (user):** yes.
- **Window timing (user, 26 November 2026):** the window is timed with the same **age counter** as scramble expiry (§6), not by any device's clock. The proposal starts at age 0 when the sponsor signs it, and each member device that holds or forwards it adds its own stopwatch time and signs the new age. An approval or objection counts only if it was signed while that member's copy was younger than the window. The same accepted flaws apply: a dishonest member forwarder can under-report the age, and time a phone spends switched off is not counted.

**Open**
- ~~What happens to an objection that reaches the sponsor's device only after the window closed and the member was admitted.~~ Decided below.
- How the 1-minute minimum interacts with members who are out of range: in a split mesh, a short window can close before some members have seen the proposal at all.
- Whether the window length and the "Independent approval" setting can ever be changed after creation, and by what process.
- Whether silence after one approval results in admission automatically at the window's end.
- Whether the objector must be a current member when the objection is signed or when it arrives.
- ~~What happens if a late objection arrives after admission during a network partition.~~ Decided below.
- With group revoking switched off, the objector's notice has no vote to offer. What, if anything, they can do then.
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
  - **Updated (user, 26 November 2026): every majority vote counts only members active in the last 30 days** (measured as in §3). This applies to removal votes in groups of up to 5, overriding a rejection, removing or electing a SuperAdmin, and switching a collective back to a normal group. Members who have not been active are left out of the count. The fixed threshold of 3 approvals in groups of 6 or more is unchanged.
  - Built in `groups/`: each vote carries the list of members its proposer's phone saw as active, majorities count from that list, and only members on it can vote (accepted by user, 26 November 2026). Every vote must carry a list, naming at least whoever started it. If a phone's own records disagree with the list, **the vote's list wins** (user, 26 November 2026); the phone can show a warning but counts from the list.
- **The person being removed is not counted and cannot vote.** Example: in a 5-member group, revoking one member needs 3 of the other 4. In a 2-member group, the other member alone can revoke.
- **A revoked member can come back through a new proposal** (user, 26 November 2026). It goes through the normal rules in §3: a sponsor, the objection window, approvals, and any objection blocks it.
  - **They can come back on their old devices** (user, 26 November 2026); they only need a new invite. A device they revoked themselves, for example because it was lost, stays revoked (proposed by Claude, accepted by user, 26 November 2026).
  - Details stage one chose (need confirming): the earlier membership stays on record, and a proposal made before a later revocation cannot be used to bring them back, so approvals given in an earlier absence are not reused. After coming back they see joins from their new join onwards (§4).

**Open**
- Which membership list counts when the group is split (§8). A majority of all members cannot be reached inside a small isolated subset, which fails safe but means that subset cannot revoke anyone.
- ~~Whether the threshold uses the group's size when the vote starts or when it ends, if members join or leave during the vote.~~ **Decided (proposed by Claude, accepted by user, 26 November 2026):** the size when the vote starts.
- **A group holds at most 128 members for now** (user, 26 November 2026).
- With group revoking switched off, how a confiscated device (§7) is dealt with.
- Whether a sponsor has any special responsibility. (The target cannot vote: decided above.)
- Distinction between removing a device, suspending a member, and expelling a member.
- Whether the affected person can appeal or recover a replacement device. (Rejoining: decided above.)
- How revocation rotates group encryption keys and protects subsequent messages.
- How revoked devices are treated during offline periods and network partitions.

## 6. Scramble on revocation

**Agreed direction**
- An optional **scramble** feature was discussed: a queued command intended to erase local room chat on a revoked device when that device next reconnects.
- ~~The feature is opt-in when joining the room.~~ **Replaced (user, 26 November 2026):** group scramble is a **group setting**, switched on or off when the group is created, not a choice each member makes. With it on, a member revoked by vote has that group wiped from their devices whether or not they would have chosen it. Joining a group with the setting on is the consent, so the app must show the setting clearly before anyone joins; it is never imposed later. With group revoking switched off it has no effect.
- The intention is to reduce exposure if a device is lost, confiscated or revoked.

**Critical security limitation**
- A remote wipe command **cannot be guaranteed** to run on an offline, compromised, modified or attacker-controlled device. A device can refuse the command, preserve backups, copy data or never reconnect.
- Therefore, scramble is a **best-effort deletion request**, never a confidentiality guarantee.
- Future encryption key rotation can prevent access to **future** messages but cannot retroactively erase messages already copied.

**Decided (user, 26 November 2026)**
- A scramble can be triggered in two ways:
  - **Automatically**, when a valid group revocation of that device takes effect, in a group created with group scramble on.
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
- **Known flaws (accepted, since group scramble is a setting chosen at group creation):**
  - A dishonest **member** forwarder can under-report how long it held the command, stretching its life. Each forwarder signs its own addition, so this can be traced to it afterwards but not prevented.
  - The stopwatch resets when a phone restarts. A forwarder must save the age before shutting down; time spent switched off is lost, so the command lives a little longer than 24 hours of real time.
  - None of this stops an attacker-controlled target device from refusing the command.
  - **Found while building stage one, accepted as a known weakness (user, 26 November 2026):** anyone forwarding an aged item, including a non-member relay, can drop the last hops so the item looks younger. The remaining hops still verify. This applies to objection-window proposals as well as scramble commands.
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

**Decided (user, 26 November 2026)**
- **Acting while revoked elsewhere:** if a member is revoked on one side of a split and keeps acting on the other, they are **removed as soon as the revocation reaches each device**. What they did before that **stands**, and **their messages are not deleted**.
  - **This includes governance actions** (confirmed, user): an approval, vote or proposal they made before the sync still counts. The risk is accepted: a member revoked on one side can still help admit someone on the other until the sides reconnect. Cancelling those actions afterwards is the road to witch hunts; people have to use their judgement here rather than rely on the software.

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

**Decided (user, 26 November 2026)**
- **Temporary groups are a toolkit setting**, chosen when the group is created like the others. A collective such as a protest group has to fear infiltrators; a normal group may not, and can leave it off.
- **It is automatic.** No one picks members and no one person starts it. When the conditions are met, the temporary group forms on its own and every member in reach gets a notification asking whether they want to move to it.
- **No new QR codes.** Members were already checked in person when they joined the original group.
- **It only happens when at least 2 people from the same group are nearby.**
- **A phone counts as cut off** when it has not heard from more than half of the group's active members for **24 hours** (user, 26 November 2026).
- **"Nearby" means reachable through the local mesh right now**, and a temporary group **only forms if nobody in the local mesh has internet access** (user, 26 November 2026). One phone reaching an internet relay connects the whole local mesh to the rest of the group, so the phones keep hearing from members and the cut-off rule is never met; no separate internet check is needed. Yap's internet path today is the user-configured relay in the protocol proposal; Tor is not designed yet.
- **Two temporary groups from the same original group that come into reach** each get a notification offering to merge (user, 26 November 2026). This is separate from the rule that a temporary group is never merged into the original group.
- **A temporary group runs as a collective** with no admins, whatever type the original group was, and keeps the original's other toolkit settings (user, 26 November 2026).
- **Someone from the original group who comes into reach later:** the temporary group's members get a notification asking whether to invite them, and **one member's tap is enough** (user, 26 November 2026). No vote, because everyone in the temporary group is cut off and a vote would have no more information than the person tapping. Accepted risk: the late arrival may have been removed elsewhere.
- How it would work is proposed in [group-encryption-v1.md §3.10](proposals/group-encryption-v1.md#310-temporary-groups-for-cut-off-members).

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

Group encryption (items 2 and 6) has a proposal: [group-encryption-v1.md](proposals/group-encryption-v1.md). Its open points were reviewed by Claude, its author, on 26 November 2026 (not an independent review), and its new choice 11 is decided (user, 26 November 2026): a newcomer can see who has voted in a vote that is still open. Multiple devices per member are a planned future feature (user, 26 November 2026); for now, one device per member, and group encryption version 1 is built for that. Its choices were accepted by the user on 26 November 2026:
- Newcomers cannot read messages from before they joined.
- Once a phone knows a member is out, it drops their messages that arrive after that.
- An invite QR works once, for 10 minutes at most.
- Joining a group does not make the joiner and the inviter contacts.
- Keys change on every removal, whenever a member marks a device as lost, and weekly for each member's own sending key.
- The cost of a removal in a full group (about 16,000 private hand-overs) is accepted for now, to be measured on real phones.

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

As of 26 November 2026, **stage one** exists in [`groups/`](../groups/README.md): an unaudited Rust crate with the signed governance events, the rules decided above, the age counter, the password guard and the scramble decision, tested with simulated split meshes. It has no transport, no encryption and no app integration, and it lists the provisional choices it made. Governance events are signed with each member's **libsignal identity key**, the same key used for pairing (decided, user, 26 November 2026), through libsignal's own signing API. It also covers the two group types, rejection review, admins, the SuperAdmin, SuperAdmin removal and election with the runoff after a split, the clash rule, and majorities counted from members active in the last 30 days. Everything else here is still **design decisions only**. The existing Android proof of concept exchanges short, unauthenticated BLE messages between two phones. It does **not** implement encrypted rooms, signed governance, group voting, reliable relay, confiscation reporting, isolation fallback groups or remote deletion.

**Instruction to Codex:** Treat the **Agreed direction** sections as product intent, the **Open** sections as decisions requiring the user's input, and the security qualifications as non-negotiable engineering cautions. Do not silently invent or claim implementation of missing rules.
