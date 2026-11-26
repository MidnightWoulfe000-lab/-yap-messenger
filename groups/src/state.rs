//! The event log and the group state derived from it.
//!
//! Every event is judged only against its own causal history (its ancestors),
//! which is fixed by hashes. So whether an event is valid never depends on
//! arrival order, and two devices holding the same set of events derive the
//! same state, whatever order they received them in.
//!
//! When events from different sides of a split mesh meet, some combinations
//! are not yet settled by the specification (§3 late objections, §8 votes by
//! members revoked elsewhere). Those are reported as [`Warning`]s and not
//! resolved here.

use crate::event::{Body, Event, GroupId, Hash, Settings, MAX_PARENTS};
use crate::keys::{DeviceKey, Identity, MemberKey};
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
/// `group_size` is the number of active members when the vote **starts**.
/// That choice is provisional: the specification still lists start-or-end as
/// open.
pub fn revocation_threshold(group_size: usize) -> usize {
    let others = group_size.saturating_sub(1);
    if group_size <= SMALL_GROUP_MAX {
        others / 2 + 1
    } else {
        LARGE_GROUP_REVOKE_APPROVALS
    }
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
    pub devices: BTreeSet<DeviceKey>,
    pub revoked_devices: BTreeSet<DeviceKey>,
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
    pub supporters: BTreeSet<MemberKey>,
    pub carried: bool,
}

/// Situations the specification has not resolved yet. The UI should show them;
/// this crate does not decide them.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Warning {
    /// A valid objection that the sponsor had not seen when it admitted the
    /// member, for example because it came from the other side of a split
    /// mesh. The admission stands for now (§3 open question).
    LateObjection { proposal: Hash, objection: Hash },
    /// A valid event whose author was revoked elsewhere without knowing it.
    /// It still took effect (§8 open question).
    ActedWhileRevoked { event: Hash, author: MemberKey },
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct GroupState {
    pub group: GroupId,
    pub settings: Settings,
    /// Everyone who has ever been a member, including revoked members.
    pub members: BTreeMap<MemberKey, Member>,
    pub join_proposals: BTreeMap<Hash, JoinProposal>,
    pub revocations: BTreeMap<Hash, Revocation>,
    pub warnings: Vec<Warning>,
}

impl GroupState {
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
        self.events.insert(
            hash,
            Stored {
                event,
                depth,
                valid,
                active_before: state.active_count(),
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
            devices: BTreeSet::new(),
            revoked_devices: BTreeSet::new(),
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
            warnings: Vec::new(),
        };
        for hash in self.ordered(set.iter().copied().collect()) {
            let stored = &self.events[&hash];
            if hash != genesis && stored.valid {
                apply(&mut state, hash, stored);
            }
        }
        Ok(state)
    }
}

/// Whether `event` follows the rules, judged against the state of its own
/// causal history.
fn check(state: &GroupState, event: &Event) -> bool {
    if !state.is_active(&event.author) {
        return false;
    }
    let window = state.settings.objection_window_ms();
    let open = |proposal: &Hash| {
        state.join_proposals.get(proposal).filter(|p| {
            p.admitted.is_none() && state.revoked_by(&p.candidate) == p.candidate_revoked_by
        })
    };
    match &event.body {
        Body::Genesis { .. } => false,
        // Anyone not currently active can be proposed, including a revoked
        // member coming back (decided, §5). They go through the normal rules.
        Body::Propose { request } => {
            request.group == state.group
                && request.verify()
                && request.candidate != event.author
                && !state.is_active(&request.candidate)
        }
        Body::Approve { proposal, age_ms }
        | Body::Object {
            proposal, age_ms, ..
        } => open(proposal).is_some() && *age_ms < window,
        Body::Admit { proposal, age_ms } => open(proposal).is_some_and(|p| {
            p.sponsor == event.author
                && *age_ms >= window
                && p.objections.is_empty()
                && state.eligible_approvals(p) >= 1
        }),
        Body::ProposeRevoke { target } => {
            state.settings.group_revoking && *target != event.author && state.is_active(target)
        }
        Body::VoteRevoke { proposal } => state
            .revocations
            .get(proposal)
            .is_some_and(|r| !r.carried && r.target != event.author && state.is_active(&r.target)),
        Body::AddDevice { device } => {
            let me = &state.members[&event.author];
            !me.devices.contains(device) && !me.revoked_devices.contains(device)
        }
        Body::RevokeOwnDevice { device } => state.members[&event.author].devices.contains(device),
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
                    });
                }
                p.objections.push(Objection {
                    event: hash,
                    objector: author,
                    reason: reason.clone(),
                });
            }
        }
        Body::Admit { proposal, .. } => {
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
            // Objections applied earlier in the order but not in the Admit's
            // own view: concurrent, so late.
            for o in &p.objections {
                state.warnings.push(Warning::LateObjection {
                    proposal: *proposal,
                    objection: o.event,
                });
            }
            let mut member = Member {
                sponsor: Some(p.sponsor),
                joined_at: hash,
                devices: BTreeSet::new(),
                revoked_devices: BTreeSet::new(),
                status: MemberStatus::Active,
                earlier_terms: Vec::new(),
            };
            // Coming back: keep the record of the earlier membership, and
            // keep every old device revoked. They must add devices afresh.
            if let Some(old) = state.members.remove(&p.candidate) {
                if let MemberStatus::Revoked { revocation } = old.status {
                    member.earlier_terms = old.earlier_terms;
                    member.earlier_terms.push(EarlierTerm {
                        sponsor: old.sponsor,
                        joined_at: old.joined_at,
                        revocation,
                    });
                }
                member.revoked_devices = old.revoked_devices;
                member.revoked_devices.extend(old.devices);
            }
            state.members.insert(p.candidate, member);
        }
        Body::ProposeRevoke { target } => {
            state.revocations.insert(
                hash,
                Revocation {
                    proposer: author,
                    target: *target,
                    group_size_at_start: stored.active_before,
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
        Body::AddDevice { device } => {
            if let Some(m) = state.members.get_mut(&author) {
                m.devices.insert(*device);
            }
        }
        Body::RevokeOwnDevice { device } => {
            if let Some(m) = state.members.get_mut(&author) {
                m.devices.remove(device);
                m.revoked_devices.insert(*device);
            }
        }
    }
}

fn carry(state: &mut GroupState, proposal: Hash) {
    let Some(r) = state.revocations.get_mut(&proposal) else {
        return;
    };
    if r.carried || r.supporters.len() < revocation_threshold(r.group_size_at_start) {
        return;
    }
    r.carried = true;
    let target = r.target;
    if let Some(m) = state.members.get_mut(&target) {
        if m.status == MemberStatus::Active {
            m.status = MemberStatus::Revoked {
                revocation: proposal,
            };
        }
    }
}
