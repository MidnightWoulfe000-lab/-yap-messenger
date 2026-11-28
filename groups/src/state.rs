//! The event log and the group state derived from it.
//!
//! Every event is judged only against its own causal history (its ancestors),
//! which is fixed by hashes. So whether an event is valid never depends on
//! arrival order, and two devices holding the same set of events derive the
//! same state, whatever order they received them in.
//!
//! When events from different sides of a split mesh meet, the result is
//! reported as a [`Warning`]. A late objection (§3) is decided: the admission
//! stands and the objector is told. A member revoked elsewhere (§8) is out
//! as soon as the revocation reaches a device; what they did before that
//! stands.

use crate::event::{Body, Event, GroupId, GroupType, Hash, Settings, MAX_MEMBERS, MAX_PARENTS};
use crate::keys::{Identity, MemberKey};
use crate::{Error, Result};
use std::collections::{BTreeMap, BTreeSet, HashMap, HashSet};

/// Groups up to this size revoke by majority (§5).
pub const SMALL_GROUP_MAX: usize = 5;
/// Approvals needed to revoke in groups larger than [`SMALL_GROUP_MAX`].
pub const LARGE_GROUP_REVOKE_APPROVALS: usize = 3;
/// From this many members, "Independent approval" stops the sponsor's own
/// approval counting (§3: "not after there are more than 4 members").
pub const INDEPENDENT_APPROVAL_FROM: usize = 5;

/// Supporters needed to revoke someone from a group of `group_size` members.
///
/// The target is not counted and cannot vote. Up to 5 members it is a majority
/// of everyone else (not voting counts as no); from 6 it is 3.
///
/// This is the calculation the state machine uses. `listed` is the vote's
/// list and `everyone` every active member, both leaving out the target;
/// `group_size` is the active members, target included, when the vote
/// **starts** (decided, user, 26 November 2026).
pub fn revocation_threshold(listed: usize, everyone: usize, group_size: usize) -> usize {
    if group_size <= SMALL_GROUP_MAX {
        needed(listed, everyone)
    } else {
        LARGE_GROUP_REVOKE_APPROVALS
    }
}

/// More than half of `n`. Every majority vote counts only members active in
/// the last 30 days (§5): `n` is the size of the vote's electorate (see
/// [`crate::ActivityLog`]).
pub fn majority(n: usize) -> usize {
    n / 2 + 1
}

/// How many members a majority vote counts: its list, but never fewer than
/// half of everyone who could have been on it (user, 26 November 2026).
/// `listed` and `everyone` both leave out the person the vote is about. This
/// stops one member naming only themselves and passing a vote alone.
pub fn counted(listed: usize, everyone: usize) -> usize {
    listed.max(everyone.div_ceil(2))
}

/// Votes a majority vote needs: a majority of [`counted`], and never fewer
/// than 2 when at least 2 people could vote (user, 27 November 2026), so no
/// one carries a vote alone in a group of 3 or more.
pub fn needed(listed: usize, everyone: usize) -> usize {
    majority(counted(listed, everyone)).max(everyone.min(2))
}

/// Votes needed to remove the SuperAdmin, in any size of group, even with
/// group revoking off (§3): [`needed`], and always at least 2, so in a group
/// of 2 nobody can remove them (user, 27 November 2026). This is the
/// calculation the state machine uses; arguments as for
/// [`revocation_threshold`].
pub fn super_admin_removal_threshold(listed: usize, everyone: usize) -> usize {
    needed(listed, everyone).max(2)
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum MemberStatus {
    Active,
    Revoked { revocation: Hash },
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Member {
    /// Who invited them (§4). The first founder has none.
    pub sponsor: Option<MemberKey>,
    /// The event that admitted them: genesis for founders, otherwise an Admit.
    /// This is their logical join time; no clock is involved.
    pub joined_at: Hash,
    pub status: MemberStatus,
    /// Earlier memberships that ended in revocation, oldest first. A revoked
    /// member can come back through a new proposal (§5).
    pub earlier_terms: Vec<EarlierTerm>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct EarlierTerm {
    pub sponsor: Option<MemberKey>,
    pub joined_at: Hash,
    pub revocation: Hash,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Objection {
    pub event: Hash,
    pub objector: MemberKey,
    pub reason: String,
    /// Active members in the rejection's own view; decides whether the
    /// override needs a majority (up to 5) or 3 votes (from 6).
    pub members_at: usize,
    /// Who may vote on the override: members active in the last 30 days.
    pub electorate: Vec<MemberKey>,
    /// Members who voted to let the person in despite this rejection.
    pub overriders: BTreeSet<MemberKey>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct JoinProposal {
    pub sponsor: MemberKey,
    pub candidate: MemberKey,
    /// The revocation that removed the candidate, if they were a member
    /// before. The proposal only stays open while that is still true, so
    /// approvals given before a later revocation cannot be reused.
    pub candidate_revoked_by: Option<Hash>,
    /// Active members in the proposal's own view, excluding the candidate.
    pub members_at_proposal: usize,
    pub approvals: BTreeSet<MemberKey>,
    pub objections: Vec<Objection>,
    pub admitted: Option<Hash>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Revocation {
    pub proposer: MemberKey,
    pub target: MemberKey,
    pub group_size_at_start: usize,
    /// Who may vote: members active in the last 30 days.
    pub electorate: Vec<MemberKey>,
    /// Supporters needed, fixed when the vote starts.
    pub threshold: usize,
    pub supporters: BTreeSet<MemberKey>,
    pub carried: bool,
}

/// A collective's vote on new settings (§3).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SettingsVote {
    pub settings: Settings,
    pub electorate: Vec<MemberKey>,
    pub threshold: usize,
    pub supporters: BTreeSet<MemberKey>,
    pub carried: bool,
}

/// A SuperAdmin election (§3). Each member of the electorate votes once.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Election {
    /// The event that left the seat empty.
    pub vacancy: Hash,
    /// Who may vote, taken from the vote that left the seat empty.
    pub electorate: Vec<MemberKey>,
    /// In a runoff, the only people who can win. Otherwise anyone can stand.
    pub candidates: Option<BTreeSet<MemberKey>>,
    /// Voter to candidate.
    pub votes: BTreeMap<MemberKey, MemberKey>,
    pub winner: Option<MemberKey>,
    /// Superseded by a runoff.
    pub closed: bool,
    /// Active members when the seat became empty; the count never drops below
    /// half of them (see [`counted`]).
    pub members_at: usize,
}

impl Election {
    fn new(vacancy: Hash, electorate: Vec<MemberKey>, members_at: usize) -> Self {
        Self {
            vacancy,
            electorate,
            members_at,
            candidates: None,
            votes: BTreeMap::new(),
            winner: None,
            closed: false,
        }
    }

    pub fn is_open(&self) -> bool {
        self.winner.is_none() && !self.closed
    }

    /// Votes one candidate needs to win.
    pub fn threshold(&self) -> usize {
        needed(self.electorate.len(), self.members_at)
    }
}

/// Things that happened across a split mesh which people need to be told
/// about. See [`GroupState::notices_for`].
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Warning {
    /// A valid objection that the sponsor had not seen when it admitted the
    /// member, for example because it came from the other side of a split
    /// mesh. Decided (§3): the admission stands, and the objector gets a
    /// notice saying what happened, with the option of starting a vote to
    /// remove the member.
    LateObjection {
        proposal: Hash,
        objection: Hash,
        objector: MemberKey,
        admitted: MemberKey,
    },
    /// A valid event whose author was revoked elsewhere without knowing it.
    /// Decided (§8): it stands, and so do their messages from before the
    /// sync. Once the revocation is in an event's own history, that author's
    /// events are refused, so they are out from the sync onwards.
    ActedWhileRevoked { event: Hash, author: MemberKey },
    /// An admin's decision about a member clashed with a SuperAdmin decision
    /// about the same member that neither had seen, so it was set aside
    /// (§3: the SuperAdmin's decision wins).
    OverruledBySuperAdmin { event: Hash, by: Hash },
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct GroupState {
    pub group: GroupId,
    pub settings: Settings,
    /// Everyone who has ever been a member, including revoked members.
    pub members: BTreeMap<MemberKey, Member>,
    pub join_proposals: BTreeMap<Hash, JoinProposal>,
    pub revocations: BTreeMap<Hash, Revocation>,
    /// Admins of a normal group. Empty in a collective.
    pub admins: BTreeSet<MemberKey>,
    /// The SuperAdmin of a normal group: the creator, or whoever the members
    /// elected after them. `None` in a collective or during an election.
    pub super_admin: Option<MemberKey>,
    /// Every SuperAdmin election, by the event that left the seat empty.
    pub elections: BTreeMap<Hash, Election>,
    pub settings_votes: BTreeMap<Hash, SettingsVote>,
    pub warnings: Vec<Warning>,
}

impl GroupState {
    pub fn group_type(&self) -> GroupType {
        self.settings.group_type()
    }

    /// Admin powers work only in a normal group that has a SuperAdmin. While
    /// the seat is empty the group works as a collective (§3).
    pub fn admin_powers_active(&self) -> bool {
        self.group_type() == GroupType::Normal && self.super_admin.is_some()
    }

    /// Whether admissions go through rejection review right now.
    pub fn uses_rejection_review(&self) -> bool {
        !self.admin_powers_active()
    }

    pub fn is_admin(&self, key: &MemberKey) -> bool {
        self.admin_powers_active() && self.admins.contains(key) && self.is_active(key)
    }

    pub fn is_super_admin(&self, key: &MemberKey) -> bool {
        self.admin_powers_active() && self.super_admin == Some(*key)
    }

    /// Votes needed to let someone in despite `rejection`: the same as a
    /// removal vote, the rejecter not counted.
    pub fn override_threshold(&self, rejection: &Objection) -> usize {
        let others = rejection
            .electorate
            .iter()
            .filter(|m| **m != rejection.objector)
            .count();
        if rejection.members_at <= SMALL_GROUP_MAX {
            needed(others, rejection.members_at.saturating_sub(1))
        } else {
            LARGE_GROUP_REVOKE_APPROVALS
        }
    }

    /// The SuperAdmin election that is still open, if any.
    pub fn open_election(&self) -> Option<&Election> {
        self.elections.values().find(|e| e.is_open())
    }

    /// Notices for one member: late objections they raised. The app shows
    /// each with the option of starting a removal vote against the admitted
    /// member, which is only possible while they are still active and the
    /// group has group revoking on.
    pub fn notices_for(&self, member: &MemberKey) -> Vec<&Warning> {
        self.warnings
            .iter()
            .filter(|w| matches!(w, Warning::LateObjection { objector, .. } if objector == member))
            .collect()
    }

    /// Whether `objector` can act on a late-objection notice now.
    pub fn can_challenge(&self, objector: &MemberKey, admitted: &MemberKey) -> bool {
        self.settings.group_revoking
            && self.is_active(objector)
            && self.is_active(admitted)
            && objector != admitted
    }

    /// The revocation behind a member's current removal, if any.
    pub fn revoked_by(&self, key: &MemberKey) -> Option<Hash> {
        match self.members.get(key)?.status {
            MemberStatus::Revoked { revocation } => Some(revocation),
            MemberStatus::Active => None,
        }
    }

    pub fn is_active(&self, key: &MemberKey) -> bool {
        self.members
            .get(key)
            .is_some_and(|m| m.status == MemberStatus::Active)
    }

    pub fn active_members(&self) -> Vec<MemberKey> {
        self.members
            .iter()
            .filter(|(_, m)| m.status == MemberStatus::Active)
            .map(|(k, _)| *k)
            .collect()
    }

    pub fn active_count(&self) -> usize {
        self.members
            .values()
            .filter(|m| m.status == MemberStatus::Active)
            .count()
    }

    /// Approvals that count toward admission: from active members, and not
    /// the sponsor's own once "Independent approval" applies.
    pub fn eligible_approvals(&self, proposal: &JoinProposal) -> usize {
        let exclude_sponsor = self.settings.independent_approval
            && proposal.members_at_proposal >= INDEPENDENT_APPROVAL_FROM;
        proposal
            .approvals
            .iter()
            .filter(|a| self.is_active(a))
            .filter(|a| !(exclude_sponsor && **a == proposal.sponsor))
            .count()
    }
}

/// One line of the join history (§4).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct JoinRecord {
    pub member: MemberKey,
    pub sponsor: Option<MemberKey>,
    pub joined_at: Hash,
    /// Logical position in the history; larger means later.
    pub depth: u64,
}

#[derive(Clone, Debug)]
struct Stored {
    event: Event,
    depth: u64,
    valid: bool,
    /// Active members in this event's own view (before it applies).
    active_before: usize,
    /// Whether the author was the SuperAdmin in this event's own view.
    by_super: bool,
    /// For an event that starts a vote: its electorate, with an empty list
    /// replaced by every active member in the event's own view.
    electorate: Vec<MemberKey>,
}

/// All governance events this device holds for one group.
#[derive(Clone, Debug)]
pub struct Log {
    group: GroupId,
    genesis: Option<Hash>,
    events: HashMap<Hash, Stored>,
}

impl Log {
    pub fn new(group: GroupId) -> Self {
        Self {
            group,
            genesis: None,
            events: HashMap::new(),
        }
    }

    pub fn group(&self) -> GroupId {
        self.group
    }

    pub fn genesis(&self) -> Option<Hash> {
        self.genesis
    }

    pub fn len(&self) -> usize {
        self.events.len()
    }

    pub fn is_empty(&self) -> bool {
        self.events.is_empty()
    }

    pub fn contains(&self, hash: &Hash) -> bool {
        self.events.contains_key(hash)
    }

    pub fn get(&self, hash: &Hash) -> Option<&Event> {
        self.events.get(hash).map(|s| &s.event)
    }

    /// Whether a stored event passed the governance rules in its own view.
    pub fn is_valid(&self, hash: &Hash) -> Option<bool> {
        self.events.get(hash).map(|s| s.valid)
    }

    /// Events no other event names as a parent: what a new event should cite.
    pub fn heads(&self) -> Vec<Hash> {
        let cited: HashSet<&Hash> = self
            .events
            .values()
            .flat_map(|s| s.event.parents.iter())
            .collect();
        let mut heads: Vec<Hash> = self
            .events
            .keys()
            .filter(|h| !cited.contains(h))
            .copied()
            .collect();
        heads.sort_unstable();
        heads
    }

    /// All events, parents before children, in a fixed order. Use it to send
    /// the log to another device.
    pub fn events(&self) -> Vec<&Event> {
        self.ordered(self.events.keys().copied().collect())
            .into_iter()
            .map(|h| &self.events[&h].event)
            .collect()
    }

    /// Adds a signed event. Its parents must already be here.
    ///
    /// An event that is well formed but breaks a rule is stored anyway and
    /// marked invalid, because later events may cite it.
    pub fn insert(&mut self, event: Event) -> Result<Hash> {
        if event.group != self.group {
            return Err(Error::WrongGroup);
        }
        if !event.verify_signature() {
            return Err(Error::BadSignature);
        }
        let hash = event.hash();
        if self.events.contains_key(&hash) {
            return Ok(hash);
        }

        if let Body::Genesis { second, .. } = &event.body {
            if self.genesis.is_some() || !event.parents.is_empty() {
                return Err(Error::BadRoot);
            }
            if second.group != self.group || !second.verify() || second.candidate == event.author {
                return Err(Error::Malformed("bad second founder"));
            }
            self.events.insert(
                hash,
                Stored {
                    event,
                    depth: 0,
                    valid: true,
                    active_before: 0,
                    by_super: false,
                    electorate: Vec::new(),
                },
            );
            self.genesis = Some(hash);
            return Ok(hash);
        }

        if event.parents.is_empty() {
            return Err(Error::BadRoot);
        }
        let missing: Vec<Hash> = event
            .parents
            .iter()
            .filter(|p| !self.events.contains_key(*p))
            .copied()
            .collect();
        if !missing.is_empty() {
            return Err(Error::MissingParents(missing));
        }

        let depth = 1 + event
            .parents
            .iter()
            .map(|p| self.events[p].depth)
            .max()
            .unwrap_or(0);
        let mut view = HashSet::new();
        for parent in &event.parents {
            view.insert(*parent);
            view.extend(self.ancestors(parent));
        }
        let state = self.derive(&view)?;
        let valid = check(&state, &event);
        let by_super = state.is_super_admin(&event.author);
        let electorate = event
            .body
            .electorate()
            .map(<[MemberKey]>::to_vec)
            .unwrap_or_default();
        self.events.insert(
            hash,
            Stored {
                event,
                depth,
                valid,
                active_before: state.active_count(),
                by_super,
                electorate,
            },
        );
        Ok(hash)
    }

    /// Signs a new event citing the current heads, and adds it.
    pub fn append(&mut self, identity: &Identity, body: Body) -> Result<Hash> {
        let mut parents = self.heads();
        parents.truncate(MAX_PARENTS);
        let event = Event::create(identity, self.group, parents, body)?;
        self.insert(event)
    }

    /// Every event `hash` descends from, not including itself.
    pub fn ancestors(&self, hash: &Hash) -> HashSet<Hash> {
        let mut seen = HashSet::new();
        let mut stack: Vec<Hash> = self
            .events
            .get(hash)
            .map(|s| s.event.parents.clone())
            .unwrap_or_default();
        while let Some(h) = stack.pop() {
            if seen.insert(h) {
                if let Some(s) = self.events.get(&h) {
                    stack.extend(s.event.parents.iter().copied());
                }
            }
        }
        seen
    }

    /// The state from everything this device holds.
    pub fn state(&self) -> Result<GroupState> {
        self.derive(&self.events.keys().copied().collect())
    }

    /// The state as the author of `hash` saw it, including `hash` itself.
    pub fn state_at(&self, hash: &Hash) -> Result<GroupState> {
        let mut view = self.ancestors(hash);
        view.insert(*hash);
        self.derive(&view)
    }

    /// Join history `viewer` may see (§4): founders see everything, later
    /// members see joins from their own admission onwards.
    ///
    /// This is a display rule only. Hiding history cryptographically, so the
    /// newcomer's device never receives it, is still open in the specification.
    pub fn visible_joins(&self, viewer: &MemberKey) -> Result<Vec<JoinRecord>> {
        let state = self.state()?;
        let me = state
            .members
            .get(viewer)
            .ok_or(Error::Malformed("viewer is not a member"))?;
        let hidden = if Some(me.joined_at) == self.genesis {
            HashSet::new()
        } else {
            self.ancestors(&me.joined_at)
        };
        let mut out: Vec<JoinRecord> = state
            .members
            .iter()
            .filter(|(_, m)| !hidden.contains(&m.joined_at))
            .map(|(k, m)| JoinRecord {
                member: *k,
                sponsor: m.sponsor,
                joined_at: m.joined_at,
                depth: self.events[&m.joined_at].depth,
            })
            .collect();
        out.sort_by_key(|r| (r.depth, r.member));
        Ok(out)
    }

    /// Deterministic topological order: by depth, then hash.
    fn ordered(&self, set: Vec<Hash>) -> Vec<Hash> {
        let mut list = set;
        list.sort_unstable_by_key(|h| (self.events[h].depth, *h));
        list
    }

    fn derive(&self, set: &HashSet<Hash>) -> Result<GroupState> {
        let genesis = self.genesis.ok_or(Error::NoGenesis)?;
        if !set.contains(&genesis) {
            return Err(Error::NoGenesis);
        }
        let root = &self.events[&genesis].event;
        let Body::Genesis { settings, second } = &root.body else {
            return Err(Error::BadRoot);
        };
        let founder = |sponsor| Member {
            sponsor,
            joined_at: genesis,
            status: MemberStatus::Active,
            earlier_terms: Vec::new(),
        };
        let mut state = GroupState {
            group: self.group,
            settings: *settings,
            members: BTreeMap::from([
                (root.author, founder(None)),
                (second.candidate, founder(Some(root.author))),
            ]),
            join_proposals: BTreeMap::new(),
            revocations: BTreeMap::new(),
            admins: BTreeSet::new(),
            super_admin: None,
            elections: BTreeMap::new(),
            settings_votes: BTreeMap::new(),
            warnings: Vec::new(),
        };
        if settings.group_type() == GroupType::Normal {
            state.super_admin = Some(root.author);
            state.admins.insert(root.author);
        }
        let order = self.ordered(set.iter().copied().collect());
        let overruled = self.overruled(&order);
        for hash in order {
            let stored = &self.events[&hash];
            if hash == genesis || !stored.valid {
                continue;
            }
            if let Some(by) = overruled.get(&hash) {
                state.warnings.push(Warning::OverruledBySuperAdmin {
                    event: hash,
                    by: *by,
                });
                continue;
            }
            apply(&mut state, hash, stored);
        }
        Ok(state)
    }

    /// Admin decisions set aside because a SuperAdmin decision about the same
    /// member was made without either having seen the other (§3).
    fn overruled(&self, order: &[Hash]) -> HashMap<Hash, Hash> {
        let decisions: Vec<(Hash, MemberKey, bool)> = order
            .iter()
            .filter_map(|h| {
                let stored = &self.events[h];
                let about = stored
                    .valid
                    .then(|| admin_decision_about(&stored.event.body))??;
                Some((*h, about, stored.by_super))
            })
            .collect();
        let mut ancestors: HashMap<Hash, HashSet<Hash>> = HashMap::new();
        let mut out = HashMap::new();
        for (event, about, by_super) in &decisions {
            if *by_super {
                continue;
            }
            for (other, other_about, other_super) in &decisions {
                if !other_super || other_about != about {
                    continue;
                }
                let event_saw = ancestors
                    .entry(*event)
                    .or_insert_with(|| self.ancestors(event))
                    .contains(other);
                let other_saw = ancestors
                    .entry(*other)
                    .or_insert_with(|| self.ancestors(other))
                    .contains(event);
                if !event_saw && !other_saw {
                    out.insert(*event, *other);
                    break;
                }
            }
        }
        out
    }
}

/// The member an admin decision is about, for the clash rule.
fn admin_decision_about(body: &Body) -> Option<MemberKey> {
    match body {
        Body::AdminAdd { request } => Some(request.candidate),
        Body::AdminRemove { target } | Body::SetAdmin { target, .. } => Some(*target),
        _ => None,
    }
}

/// Whether `event` follows the rules, judged against the state of its own
/// causal history.
fn check(state: &GroupState, event: &Event) -> bool {
    let author = event.author;
    if !state.is_active(&author) {
        return false;
    }
    // A vote's electorate may only list current members.
    if let Some(list) = event.body.electorate() {
        if !list.iter().all(|m| state.is_active(m)) {
            return false;
        }
    }
    let lists_author = |list: &[MemberKey]| list.contains(&author);
    let window = state.settings.objection_window_ms();
    let open = |proposal: &Hash| {
        state.join_proposals.get(proposal).filter(|p| {
            p.admitted.is_none() && state.revoked_by(&p.candidate) == p.candidate_revoked_by
        })
    };
    // A group holds at most MAX_MEMBERS members (user, 26 November 2026).
    let room = state.active_count() < MAX_MEMBERS;
    let valid_request = |request: &crate::event::JoinRequest| {
        request.group == state.group
            && request.verify()
            && request.candidate != author
            && !state.is_active(&request.candidate)
    };
    match &event.body {
        Body::Genesis { .. } => false,
        // Anyone not currently active can be proposed, including a revoked
        // member coming back (decided, §5). They go through the normal rules.
        Body::Propose { request } => valid_request(request),
        // With no objections there is nothing to wait for: in a normal group
        // with a SuperAdmin, approvals and admission have no window (§3).
        Body::Approve { proposal, age_ms } => {
            open(proposal).is_some() && (!state.uses_rejection_review() || *age_ms < window)
        }
        // Normal groups have no objections (§3).
        Body::Object {
            proposal, age_ms, ..
        } => state.uses_rejection_review() && open(proposal).is_some() && *age_ms < window,
        Body::Override {
            proposal,
            rejection,
            age_ms,
        } => {
            state.uses_rejection_review()
                && *age_ms < window
                && open(proposal).is_some_and(|p| {
                    p.objections.iter().any(|o| {
                        o.event == *rejection
                            && o.objector != author
                            && o.electorate.contains(&author)
                    })
                })
        }
        Body::Admit {
            proposal,
            age_ms,
            rejection_ages,
        } => open(proposal).is_some_and(|p| {
            // Every rejection the sponsor has seen must be answered: named
            // here, its fresh window over, and overridden by enough members.
            let answered = rejection_ages.len() == p.objections.len()
                && p.objections.iter().all(|o| {
                    let overriders = o.overriders.iter().filter(|m| state.is_active(m)).count();
                    rejection_ages
                        .iter()
                        .any(|(r, age)| *r == o.event && *age >= window)
                        && overriders >= state.override_threshold(o)
                });
            let waited = !state.uses_rejection_review() || *age_ms >= window;
            room && p.sponsor == author && waited && answered && state.eligible_approvals(p) >= 1
        }),
        Body::AdminAdd { request } => room && state.is_admin(&author) && valid_request(request),
        // Ordinary admins remove ordinary members; only the SuperAdmin removes
        // an admin; nobody removes the SuperAdmin this way.
        Body::AdminRemove { target } => {
            state.is_admin(&author)
                && *target != author
                && state.is_active(target)
                && state.super_admin != Some(*target)
                && (!state.is_admin(target) || state.is_super_admin(&author))
        }
        Body::SetAdmin { target, admin } => {
            state.is_admin(&author)
                && state.is_active(target)
                && state.super_admin != Some(*target)
                && if *admin {
                    !state.admins.contains(target)
                } else {
                    state.is_super_admin(&author) && state.admins.contains(target)
                }
        }
        // Only the SuperAdmin touches the toolkit settings.
        Body::ChangeSettings { settings } => {
            state.is_super_admin(&author) && *settings != state.settings
        }
        // A collective has no SuperAdmin, so its members vote instead.
        Body::ProposeSettings {
            settings,
            electorate,
        } => {
            state.group_type() == GroupType::Collective
                && *settings != state.settings
                && lists_author(electorate)
        }
        Body::VoteSettings { proposal } => state.settings_votes.get(proposal).is_some_and(|v| {
            !v.carried && !v.supporters.contains(&author) && v.electorate.contains(&author)
        }),
        Body::ElectSuperAdmin { vacancy, candidate } => {
            state.group_type() == GroupType::Normal
                && state.super_admin.is_none()
                && state.is_active(candidate)
                && state.elections.get(vacancy).is_some_and(|e| {
                    e.is_open()
                        && e.electorate.contains(&author)
                        && !e.votes.contains_key(&author)
                        && e.candidates.as_ref().is_none_or(|c| c.contains(candidate))
                })
        }
        // The SuperAdmin can always be voted out, even with group revoking
        // off (§3).
        Body::ProposeRevoke { target, electorate } => {
            *target != author
                && state.is_active(target)
                && (state.settings.group_revoking || state.is_super_admin(target))
                && lists_author(electorate)
        }
        Body::VoteRevoke { proposal } => state.revocations.get(proposal).is_some_and(|r| {
            !r.carried
                && r.target != author
                && state.is_active(&r.target)
                && r.electorate.contains(&author)
        }),
    }
}

/// Applies a valid event to a state being built in topological order.
fn apply(state: &mut GroupState, hash: Hash, stored: &Stored) {
    let event = &stored.event;
    let author = event.author;
    if !state.is_active(&author) {
        state.warnings.push(Warning::ActedWhileRevoked {
            event: hash,
            author,
        });
    }
    match &event.body {
        Body::Genesis { .. } => {}
        Body::Propose { request } => {
            state.join_proposals.insert(
                hash,
                JoinProposal {
                    sponsor: author,
                    candidate: request.candidate,
                    candidate_revoked_by: state.revoked_by(&request.candidate),
                    members_at_proposal: stored.active_before,
                    approvals: BTreeSet::new(),
                    objections: Vec::new(),
                    admitted: None,
                },
            );
        }
        Body::Approve { proposal, .. } => {
            if let Some(p) = state.join_proposals.get_mut(proposal) {
                p.approvals.insert(author);
            }
        }
        Body::Object {
            proposal, reason, ..
        } => {
            if let Some(p) = state.join_proposals.get_mut(proposal) {
                if p.admitted.is_some() {
                    state.warnings.push(Warning::LateObjection {
                        proposal: *proposal,
                        objection: hash,
                        objector: author,
                        admitted: p.candidate,
                    });
                }
                p.objections.push(Objection {
                    event: hash,
                    objector: author,
                    reason: reason.clone(),
                    members_at: stored.active_before,
                    electorate: stored.electorate.clone(),
                    overriders: BTreeSet::new(),
                });
            }
        }
        Body::Override {
            proposal,
            rejection,
            ..
        } => {
            if let Some(p) = state.join_proposals.get_mut(proposal) {
                if let Some(o) = p.objections.iter_mut().find(|o| o.event == *rejection) {
                    o.overriders.insert(author);
                }
            }
        }
        Body::Admit {
            proposal,
            rejection_ages,
            ..
        } => {
            let Some(candidate) = state.join_proposals.get(proposal).map(|p| p.candidate) else {
                return;
            };
            if state.is_active(&candidate) {
                return;
            }
            let p = state
                .join_proposals
                .get_mut(proposal)
                .expect("looked up above");
            if p.admitted.is_some() {
                return;
            }
            p.admitted = Some(hash);
            let sponsor = p.sponsor;
            // Rejections applied earlier in the order but not answered in the
            // Admit's own view: concurrent, so late.
            let late: Vec<Warning> = p
                .objections
                .iter()
                .filter(|o| !rejection_ages.iter().any(|(r, _)| *r == o.event))
                .map(|o| Warning::LateObjection {
                    proposal: *proposal,
                    objection: o.event,
                    objector: o.objector,
                    admitted: candidate,
                })
                .collect();
            state.warnings.extend(late);
            admit_member(state, candidate, sponsor, hash);
        }
        Body::AdminAdd { request } => {
            if !state.is_active(&request.candidate) {
                admit_member(state, request.candidate, author, hash);
            }
        }
        Body::AdminRemove { target } => remove_member(state, *target, hash, Vec::new()),
        Body::SetAdmin { target, admin } => {
            if *admin {
                if state.is_active(target) {
                    state.admins.insert(*target);
                }
            } else {
                state.admins.remove(target);
            }
        }
        Body::ChangeSettings { settings } => {
            change_settings(state, *settings, hash, state.active_members())
        }
        Body::ProposeSettings { settings, .. } => {
            state.settings_votes.insert(
                hash,
                SettingsVote {
                    settings: *settings,
                    electorate: stored.electorate.clone(),
                    threshold: needed(stored.electorate.len(), stored.active_before),
                    supporters: BTreeSet::from([author]),
                    carried: false,
                },
            );
            carry_settings(state, hash);
        }
        Body::VoteSettings { proposal } => {
            if let Some(v) = state.settings_votes.get_mut(proposal) {
                v.supporters.insert(author);
            }
            carry_settings(state, *proposal);
        }
        Body::ElectSuperAdmin { vacancy, candidate } => {
            vote_super_admin(state, hash, *vacancy, author, *candidate)
        }
        Body::ProposeRevoke { target, .. } => {
            let others = stored.electorate.iter().filter(|m| *m != target).count();
            let everyone = stored.active_before.saturating_sub(1);
            let threshold = if state.super_admin == Some(*target) {
                super_admin_removal_threshold(others, everyone)
            } else {
                revocation_threshold(others, everyone, stored.active_before)
            };
            state.revocations.insert(
                hash,
                Revocation {
                    proposer: author,
                    target: *target,
                    group_size_at_start: stored.active_before,
                    electorate: stored.electorate.clone(),
                    threshold,
                    supporters: BTreeSet::from([author]),
                    carried: false,
                },
            );
            carry(state, hash);
        }
        Body::VoteRevoke { proposal } => {
            if let Some(r) = state.revocations.get_mut(proposal) {
                r.supporters.insert(author);
            }
            carry(state, *proposal);
        }
    }
}

fn vote_super_admin(
    state: &mut GroupState,
    hash: Hash,
    vacancy: Hash,
    voter: MemberKey,
    candidate: MemberKey,
) {
    let Some(e) = state.elections.get_mut(&vacancy).filter(|e| e.is_open()) else {
        return;
    };
    e.votes.insert(voter, candidate);
    let votes = e.votes.values().filter(|c| **c == candidate).count();
    if votes < e.threshold() {
        return;
    }
    e.winner = Some(candidate);
    let electorate = e.electorate.clone();
    let e_members_at = e.members_at;
    if !state.is_active(&candidate) {
        return;
    }
    match state.super_admin {
        None => {
            state.super_admin = Some(candidate);
            state.admins.insert(candidate);
        }
        Some(current) if current == candidate => {}
        // Two parts of a split group each elected someone. Neither win had
        // seen the other, so there is a new vote between just those two (§3).
        Some(current) => {
            let mut both: BTreeSet<MemberKey> = electorate.into_iter().collect();
            let mut members_at = e_members_at;
            for other in state.elections.values() {
                if other.winner == Some(current) {
                    both.extend(other.electorate.iter().copied());
                    members_at = members_at.max(other.members_at);
                }
            }
            for e in state.elections.values_mut() {
                e.closed = true;
            }
            let mut runoff = Election::new(
                hash,
                both.into_iter().filter(|m| state.is_active(m)).collect(),
                members_at,
            );
            runoff.candidates = Some(BTreeSet::from([current, candidate]));
            state.elections.insert(hash, runoff);
            state.super_admin = None;
            state.admins.clear();
        }
    }
}

fn admit_member(state: &mut GroupState, candidate: MemberKey, sponsor: MemberKey, hash: Hash) {
    let mut member = Member {
        sponsor: Some(sponsor),
        joined_at: hash,
        status: MemberStatus::Active,
        earlier_terms: Vec::new(),
    };
    // Coming back: keep the record of the earlier membership.
    if let Some(old) = state.members.remove(&candidate) {
        if let MemberStatus::Revoked { revocation } = old.status {
            member.earlier_terms = old.earlier_terms;
            member.earlier_terms.push(EarlierTerm {
                sponsor: old.sponsor,
                joined_at: old.joined_at,
                revocation,
            });
        }
    }
    state.members.insert(candidate, member);
}

/// Removes a member, by vote or by an admin. Removing the SuperAdmin empties
/// the seat and opens an election straight away, with `electorate` (the
/// removal vote's) as the voters.
fn remove_member(state: &mut GroupState, target: MemberKey, by: Hash, electorate: Vec<MemberKey>) {
    let Some(m) = state.members.get(&target) else {
        return;
    };
    let electorate: Vec<MemberKey> = electorate.into_iter().filter(|e| *e != target).collect();
    if m.status != MemberStatus::Active {
        // Already removed by a vote that left the seat empty on another side
        // of a split: this removal left it empty too, so it opens its own
        // election. If both sides elect someone, a runoff follows.
        if let MemberStatus::Revoked { revocation } = m.status {
            if state.elections.contains_key(&revocation) && !state.elections.contains_key(&by) {
                state
                    .elections
                    .insert(by, Election::new(by, electorate, state.active_count()));
            }
        }
        return;
    }
    state
        .members
        .get_mut(&target)
        .expect("looked up above")
        .status = MemberStatus::Revoked { revocation: by };
    state.admins.remove(&target);
    // Losing the SuperAdmin makes the group work as a collective: every admin
    // goes back to being a normal member and must be made admin again (§3).
    if state.super_admin == Some(target) {
        state.super_admin = None;
        state.admins.clear();
        state
            .elections
            .insert(by, Election::new(by, electorate, state.active_count()));
    }
}

/// Switching rejection review on makes a collective and ends every admin
/// role. Switching it off makes a normal group, which elects a SuperAdmin
/// with `electorate` as the voters.
fn change_settings(
    state: &mut GroupState,
    settings: Settings,
    by: Hash,
    electorate: Vec<MemberKey>,
) {
    let before = state.group_type();
    state.settings = settings;
    match (before, settings.group_type()) {
        (GroupType::Normal, GroupType::Collective) => {
            state.admins.clear();
            state.super_admin = None;
            for e in state.elections.values_mut() {
                e.closed = true;
            }
        }
        (GroupType::Collective, GroupType::Normal) => {
            state
                .elections
                .insert(by, Election::new(by, electorate, state.active_count()));
        }
        _ => {}
    }
}

fn carry_settings(state: &mut GroupState, proposal: Hash) {
    let Some(v) = state.settings_votes.get(&proposal) else {
        return;
    };
    let supporters = v.supporters.iter().filter(|m| state.is_active(m)).count();
    if v.carried || supporters < v.threshold || state.group_type() != GroupType::Collective {
        return;
    }
    let settings = v.settings;
    let electorate = v.electorate.clone();
    state
        .settings_votes
        .get_mut(&proposal)
        .expect("looked up above")
        .carried = true;
    change_settings(state, settings, proposal, electorate);
}

fn carry(state: &mut GroupState, proposal: Hash) {
    let Some(r) = state.revocations.get_mut(&proposal) else {
        return;
    };
    if r.carried || r.supporters.len() < r.threshold {
        return;
    }
    r.carried = true;
    let target = r.target;
    let electorate = r.electorate.clone();
    remove_member(state, target, proposal, electorate);
}
