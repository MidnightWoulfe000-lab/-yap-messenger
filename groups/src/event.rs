//! Signed governance events and their canonical encoding.

use crate::keys::{context, Identity, MemberKey};
use crate::wire::{Reader, Writer};
use crate::{Error, Result};
use rand_core::{OsRng, RngCore};
use sha2::{Digest, Sha256};

pub type Hash = [u8; 32];

pub const MAX_PARENTS: usize = 16;
/// Most rejections one admission can answer.
pub const MAX_REJECTIONS: usize = 16;
pub const MAX_REASON_BYTES: usize = 1000;
pub const MAX_EVENT_BYTES: usize = 8192;
/// Most members a group can have (user, 26 November 2026).
pub const MAX_MEMBERS: usize = 128;
/// Most members one vote's electorate can list: the whole group.
pub const MAX_ELECTORATE: usize = MAX_MEMBERS;
pub const MIN_OBJECTION_WINDOW_MINUTES: u64 = 1;
pub const MAX_OBJECTION_WINDOW_MINUTES: u64 = 60;

const MINUTE_MS: u64 = 60_000;
const VERSION: u8 = 1;
const EVENT_CONTEXT: &str = "yap-groups/v1/event";
const HASH_CONTEXT: &str = "yap-groups/v1/event-hash";
const JOIN_CONTEXT: &str = "yap-groups/v1/join-request";

/// Random group identifier, chosen by the founder. Every signature covers it,
/// so events cannot be replayed into another group.
#[derive(Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash, Debug)]
pub struct GroupId(pub [u8; 16]);

impl GroupId {
    pub fn random() -> Self {
        let mut bytes = [0u8; 16];
        OsRng.fill_bytes(&mut bytes);
        Self(bytes)
    }
}

/// Settings chosen once, at group creation (§3, §5).
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub struct Settings {
    objection_window_ms: u64,
    /// "Independent approval": from 5 members, the sponsor's approval does not count.
    pub independent_approval: bool,
    /// When false, the group cannot revoke anyone by vote.
    pub group_revoking: bool,
    /// "Group scramble": when true, a member revoked by vote is asked to wipe
    /// this group from their devices. It applies to every member, with no
    /// individual opt-out; joining a group with it on is the consent (§6).
    /// It has no effect while `group_revoking` is off.
    pub group_scramble: bool,
    /// "Rejection review": on makes the group a collective, with no admins;
    /// off makes it a normal group, with admins and a SuperAdmin (§3).
    pub rejection_review: bool,
}

/// The two kinds of group (§3). Set by [`Settings::rejection_review`].
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum GroupType {
    /// Admins and a SuperAdmin, and no objections.
    Normal,
    /// No admins; new members go through rejection review.
    Collective,
}

impl Settings {
    pub fn new(
        objection_window_minutes: u64,
        independent_approval: bool,
        group_revoking: bool,
        group_scramble: bool,
        rejection_review: bool,
    ) -> Result<Self> {
        if !(MIN_OBJECTION_WINDOW_MINUTES..=MAX_OBJECTION_WINDOW_MINUTES)
            .contains(&objection_window_minutes)
        {
            return Err(Error::Malformed("objection window must be 1 to 60 minutes"));
        }
        Ok(Self {
            objection_window_ms: objection_window_minutes * MINUTE_MS,
            independent_approval,
            group_revoking,
            group_scramble,
            rejection_review,
        })
    }

    pub fn objection_window_ms(&self) -> u64 {
        self.objection_window_ms
    }

    pub fn group_type(&self) -> GroupType {
        if self.rejection_review {
            GroupType::Collective
        } else {
            GroupType::Normal
        }
    }

    fn encode(&self, w: &mut Writer) {
        w.u64(self.objection_window_ms / MINUTE_MS);
        w.u8(self.independent_approval as u8
            | (self.group_revoking as u8) << 1
            | (self.group_scramble as u8) << 2
            | (self.rejection_review as u8) << 3);
    }

    fn decode(r: &mut Reader) -> Result<Self> {
        let minutes = r.u64()?;
        let flags = r.u8()?;
        if flags & !0b1111 != 0 {
            return Err(Error::Malformed("unknown settings flag"));
        }
        Self::new(
            minutes,
            flags & 1 != 0,
            flags & 2 != 0,
            flags & 4 != 0,
            flags & 8 != 0,
        )
    }
}

/// The candidate's own signed consent to join. The sponsor embeds it in a
/// proposal.
#[derive(Clone, PartialEq, Eq, Debug)]
pub struct JoinRequest {
    pub group: GroupId,
    pub candidate: MemberKey,
    pub signature: [u8; 64],
}

impl JoinRequest {
    pub fn sign(identity: &Identity, group: GroupId) -> Self {
        let candidate = identity.key();
        let signature = identity.sign(&Self::message(group, candidate));
        Self {
            group,
            candidate,
            signature,
        }
    }

    pub fn verify(&self) -> bool {
        self.candidate
            .verify(&Self::message(self.group, self.candidate), &self.signature)
    }

    fn message(group: GroupId, candidate: MemberKey) -> Vec<u8> {
        let mut w = Writer::default();
        w.fixed(&group.0);
        w.fixed(&candidate.0);
        context(JOIN_CONTEXT, &w.0)
    }

    fn encode(&self, w: &mut Writer) {
        w.fixed(&self.group.0);
        w.fixed(&self.candidate.0);
        w.fixed(&self.signature);
    }

    fn decode(r: &mut Reader) -> Result<Self> {
        Ok(Self {
            group: GroupId(r.array()?),
            candidate: MemberKey(r.array()?),
            signature: r.array()?,
        })
    }
}

/// What an event says. Ages are the signer's reading of the proposal's age
/// counter when it signed (§3, timed like scramble expiry in §6).
#[derive(Clone, PartialEq, Eq, Debug)]
pub enum Body {
    /// Creates the group with its two founders, who skip approval (§2).
    Genesis {
        settings: Settings,
        second: JoinRequest,
    },
    /// The author sponsors a new member.
    Propose {
        request: JoinRequest,
    },
    Approve {
        proposal: Hash,
        age_ms: u64,
    },
    /// A rejection (collectives only). It must give a reason, and keeps the
    /// person out unless the other members override it (§3).
    Object {
        proposal: Hash,
        age_ms: u64,
        reason: String,
        /// Members active in the last 30 days by the author's phone, sorted.
        /// Majorities count from this list, and only its members may vote.
        /// Empty means every current member, which is never easier to pass.
        electorate: Vec<MemberKey>,
    },
    /// A vote to let the person in despite one rejection. `age_ms` is the
    /// rejection's own age counter: the override has a fresh window.
    Override {
        proposal: Hash,
        rejection: Hash,
        age_ms: u64,
    },
    /// Issued by the sponsor once the window has passed on its own copy.
    /// `rejection_ages` gives the age of every rejection the sponsor has
    /// seen, each of whose override windows must also have passed.
    Admit {
        proposal: Hash,
        age_ms: u64,
        rejection_ages: Vec<(Hash, u64)>,
    },
    /// An admin of a normal group lets someone in directly.
    AdminAdd {
        request: JoinRequest,
    },
    /// An admin of a normal group removes a member.
    AdminRemove {
        target: MemberKey,
    },
    /// An admin makes someone an admin, or the SuperAdmin takes it away.
    SetAdmin {
        target: MemberKey,
        admin: bool,
    },
    /// The SuperAdmin changes the toolkit settings.
    ChangeSettings {
        settings: Settings,
    },
    /// A collective's members vote on new settings. The author supports it.
    ProposeSettings {
        settings: Settings,
        /// Members active in the last 30 days by the author's phone, sorted.
        /// Majorities count from this list, and only its members may vote.
        /// Empty means every current member, which is never easier to pass.
        electorate: Vec<MemberKey>,
    },
    VoteSettings {
        proposal: Hash,
    },
    /// A vote for the next SuperAdmin. `vacancy` is the event that left the
    /// seat empty.
    ElectSuperAdmin {
        vacancy: Hash,
        candidate: MemberKey,
    },
    /// Starts a group vote to revoke a member. The proposer's support counts.
    ProposeRevoke {
        target: MemberKey,
        /// Members active in the last 30 days by the author's phone, sorted.
        /// Majorities count from this list, and only its members may vote.
        /// Empty means every current member, which is never easier to pass.
        electorate: Vec<MemberKey>,
    },
    VoteRevoke {
        proposal: Hash,
    },
}

impl Body {
    fn encode(&self, w: &mut Writer) {
        match self {
            Body::Genesis { settings, second } => {
                w.u8(1);
                settings.encode(w);
                second.encode(w);
            }
            Body::Propose { request } => {
                w.u8(2);
                request.encode(w);
            }
            Body::Approve { proposal, age_ms } => {
                w.u8(3);
                w.fixed(proposal);
                w.u64(*age_ms);
            }
            Body::Object {
                proposal,
                age_ms,
                reason,
                electorate,
            } => {
                w.u8(4);
                w.fixed(proposal);
                w.u64(*age_ms);
                w.var(reason.as_bytes());
                encode_electorate(w, electorate);
            }
            Body::Admit {
                proposal,
                age_ms,
                rejection_ages,
            } => {
                w.u8(5);
                w.fixed(proposal);
                w.u64(*age_ms);
                w.u8(rejection_ages.len() as u8);
                for (rejection, age) in rejection_ages {
                    w.fixed(rejection);
                    w.u64(*age);
                }
            }
            Body::ProposeRevoke { target, electorate } => {
                w.u8(6);
                w.fixed(&target.0);
                encode_electorate(w, electorate);
            }
            Body::VoteRevoke { proposal } => {
                w.u8(7);
                w.fixed(proposal);
            }
            Body::Override {
                proposal,
                rejection,
                age_ms,
            } => {
                w.u8(10);
                w.fixed(proposal);
                w.fixed(rejection);
                w.u64(*age_ms);
            }
            Body::AdminAdd { request } => {
                w.u8(11);
                request.encode(w);
            }
            Body::AdminRemove { target } => {
                w.u8(12);
                w.fixed(&target.0);
            }
            Body::SetAdmin { target, admin } => {
                w.u8(13);
                w.fixed(&target.0);
                w.u8(*admin as u8);
            }
            Body::ChangeSettings { settings } => {
                w.u8(14);
                settings.encode(w);
            }
            Body::ProposeSettings {
                settings,
                electorate,
            } => {
                w.u8(15);
                settings.encode(w);
                encode_electorate(w, electorate);
            }
            Body::VoteSettings { proposal } => {
                w.u8(16);
                w.fixed(proposal);
            }
            Body::ElectSuperAdmin { vacancy, candidate } => {
                w.u8(17);
                w.fixed(vacancy);
                w.fixed(&candidate.0);
            }
        }
    }

    fn decode(r: &mut Reader) -> Result<Self> {
        Ok(match r.u8()? {
            1 => Body::Genesis {
                settings: Settings::decode(r)?,
                second: JoinRequest::decode(r)?,
            },
            2 => Body::Propose {
                request: JoinRequest::decode(r)?,
            },
            3 => Body::Approve {
                proposal: r.array()?,
                age_ms: r.u64()?,
            },
            4 => Body::Object {
                proposal: r.array()?,
                age_ms: r.u64()?,
                reason: String::from_utf8(r.var(MAX_REASON_BYTES)?.to_vec())
                    .map_err(|_| Error::Malformed("reason is not UTF-8"))?,
                electorate: decode_electorate(r)?,
            },
            5 => {
                let proposal = r.array()?;
                let age_ms = r.u64()?;
                let count = r.u8()? as usize;
                if count > MAX_REJECTIONS {
                    return Err(Error::Malformed("too many rejections"));
                }
                let mut rejection_ages: Vec<(Hash, u64)> = Vec::with_capacity(count);
                for _ in 0..count {
                    let rejection: Hash = r.array()?;
                    if rejection_ages
                        .last()
                        .is_some_and(|(last, _)| *last >= rejection)
                    {
                        return Err(Error::Malformed("rejections not in canonical order"));
                    }
                    rejection_ages.push((rejection, r.u64()?));
                }
                Body::Admit {
                    proposal,
                    age_ms,
                    rejection_ages,
                }
            }
            6 => Body::ProposeRevoke {
                target: MemberKey(r.array()?),
                electorate: decode_electorate(r)?,
            },
            7 => Body::VoteRevoke {
                proposal: r.array()?,
            },
            10 => Body::Override {
                proposal: r.array()?,
                rejection: r.array()?,
                age_ms: r.u64()?,
            },
            11 => Body::AdminAdd {
                request: JoinRequest::decode(r)?,
            },
            12 => Body::AdminRemove {
                target: MemberKey(r.array()?),
            },
            13 => Body::SetAdmin {
                target: MemberKey(r.array()?),
                admin: match r.u8()? {
                    0 => false,
                    1 => true,
                    _ => return Err(Error::Malformed("bad admin flag")),
                },
            },
            14 => Body::ChangeSettings {
                settings: Settings::decode(r)?,
            },
            15 => Body::ProposeSettings {
                settings: Settings::decode(r)?,
                electorate: decode_electorate(r)?,
            },
            16 => Body::VoteSettings {
                proposal: r.array()?,
            },
            17 => Body::ElectSuperAdmin {
                vacancy: r.array()?,
                candidate: MemberKey(r.array()?),
            },
            _ => return Err(Error::Malformed("unknown event type")),
        })
    }

    /// The electorate a vote names, if this event starts one.
    pub fn electorate(&self) -> Option<&[MemberKey]> {
        match self {
            Body::Object { electorate, .. }
            | Body::ProposeRevoke { electorate, .. }
            | Body::ProposeSettings { electorate, .. } => Some(electorate),
            _ => None,
        }
    }

    fn check(&self) -> Result<()> {
        if let Some(electorate) = self.electorate() {
            if electorate.len() > MAX_ELECTORATE {
                return Err(Error::Malformed("electorate too large"));
            }
            if electorate.is_empty() {
                return Err(Error::Malformed("electorate empty"));
            }
            if electorate.windows(2).any(|w| w[0] >= w[1]) {
                return Err(Error::Malformed("electorate not in canonical order"));
            }
        }
        if let Body::Admit { rejection_ages, .. } = self {
            if rejection_ages.len() > MAX_REJECTIONS {
                return Err(Error::Malformed("too many rejections"));
            }
            if rejection_ages.windows(2).any(|w| w[0].0 >= w[1].0) {
                return Err(Error::Malformed("rejections not in canonical order"));
            }
        }
        if let Body::Object { reason, .. } = self {
            if reason.trim().is_empty() {
                return Err(Error::Malformed("an objection needs a reason"));
            }
            if reason.len() > MAX_REASON_BYTES {
                return Err(Error::Malformed("reason too long"));
            }
        }
        Ok(())
    }
}

fn encode_electorate(w: &mut Writer, electorate: &[MemberKey]) {
    w.u8(electorate.len() as u8);
    for member in electorate {
        w.fixed(&member.0);
    }
}

fn decode_electorate(r: &mut Reader) -> Result<Vec<MemberKey>> {
    let count = r.u8()? as usize;
    if count > MAX_ELECTORATE {
        return Err(Error::Malformed("electorate too large"));
    }
    let mut out: Vec<MemberKey> = Vec::with_capacity(count);
    for _ in 0..count {
        let member = MemberKey(r.array()?);
        if out.last().is_some_and(|last| *last >= member) {
            return Err(Error::Malformed("electorate not in canonical order"));
        }
        out.push(member);
    }
    Ok(out)
}

/// A signed governance event. `parents` are the latest events the author had
/// seen; they give causal order without trusting any clock (§8).
#[derive(Clone, PartialEq, Eq, Debug)]
pub struct Event {
    pub group: GroupId,
    pub author: MemberKey,
    pub parents: Vec<Hash>,
    pub body: Body,
    pub signature: [u8; 64],
}

impl Event {
    pub fn create(
        identity: &Identity,
        group: GroupId,
        mut parents: Vec<Hash>,
        body: Body,
    ) -> Result<Self> {
        parents.sort_unstable();
        parents.dedup();
        if parents.len() > MAX_PARENTS {
            return Err(Error::Malformed("too many parents"));
        }
        body.check()?;
        let mut event = Self {
            group,
            author: identity.key(),
            parents,
            body,
            signature: [0; 64],
        };
        event.signature = identity.sign(&context(EVENT_CONTEXT, &event.unsigned()));
        if event.encode().len() > MAX_EVENT_BYTES {
            return Err(Error::Malformed("event too large"));
        }
        Ok(event)
    }

    fn unsigned(&self) -> Vec<u8> {
        let mut w = Writer::default();
        w.u8(VERSION);
        w.fixed(&self.group.0);
        w.fixed(&self.author.0);
        w.u8(self.parents.len() as u8);
        for parent in &self.parents {
            w.fixed(parent);
        }
        self.body.encode(&mut w);
        w.0
    }

    pub fn encode(&self) -> Vec<u8> {
        let mut out = self.unsigned();
        out.extend_from_slice(&self.signature);
        out
    }

    /// Decodes and checks structure, limits and the author's signature.
    pub fn decode(bytes: &[u8]) -> Result<Self> {
        if bytes.len() > MAX_EVENT_BYTES {
            return Err(Error::Malformed("event too large"));
        }
        let mut r = Reader::new(bytes);
        if r.u8()? != VERSION {
            return Err(Error::Malformed("unknown version"));
        }
        let group = GroupId(r.array()?);
        let author = MemberKey(r.array()?);
        let count = r.u8()? as usize;
        if count > MAX_PARENTS {
            return Err(Error::Malformed("too many parents"));
        }
        let mut parents = Vec::with_capacity(count);
        for _ in 0..count {
            let parent: Hash = r.array()?;
            // Strictly ascending, so each event has exactly one encoding.
            if parents.last().is_some_and(|last: &Hash| *last >= parent) {
                return Err(Error::Malformed("parents not in canonical order"));
            }
            parents.push(parent);
        }
        let body = Body::decode(&mut r)?;
        let signature = r.array()?;
        r.finish()?;
        body.check()?;
        let event = Self {
            group,
            author,
            parents,
            body,
            signature,
        };
        if !event.verify_signature() {
            return Err(Error::BadSignature);
        }
        Ok(event)
    }

    pub fn verify_signature(&self) -> bool {
        self.author
            .verify(&context(EVENT_CONTEXT, &self.unsigned()), &self.signature)
    }

    /// Covers everything except the signature. libsignal's signatures are
    /// randomised, so the same event signed twice must still have one hash.
    pub fn hash(&self) -> Hash {
        let mut h = Sha256::new();
        h.update(context(HASH_CONTEXT, &self.unsigned()));
        h.finalize().into()
    }
}
