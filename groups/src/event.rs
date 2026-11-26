//! Signed governance events and their canonical encoding.

use crate::keys::{context, DeviceKey, Identity, MemberKey};
use crate::wire::{Reader, Writer};
use crate::{Error, Result};
use rand_core::{OsRng, RngCore};
use sha2::{Digest, Sha256};

pub type Hash = [u8; 32];

pub const MAX_PARENTS: usize = 16;
pub const MAX_REASON_BYTES: usize = 1000;
pub const MAX_EVENT_BYTES: usize = 4096;
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
}

impl Settings {
    pub fn new(
        objection_window_minutes: u64,
        independent_approval: bool,
        group_revoking: bool,
        group_scramble: bool,
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
        })
    }

    pub fn objection_window_ms(&self) -> u64 {
        self.objection_window_ms
    }

    fn encode(&self, w: &mut Writer) {
        w.u64(self.objection_window_ms / MINUTE_MS);
        w.u8(self.independent_approval as u8
            | (self.group_revoking as u8) << 1
            | (self.group_scramble as u8) << 2);
    }

    fn decode(r: &mut Reader) -> Result<Self> {
        let minutes = r.u64()?;
        let flags = r.u8()?;
        if flags & !0b111 != 0 {
            return Err(Error::Malformed("unknown settings flag"));
        }
        Self::new(minutes, flags & 1 != 0, flags & 2 != 0, flags & 4 != 0)
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
    /// An objection blocks admission and must give a reason (§3).
    Object {
        proposal: Hash,
        age_ms: u64,
        reason: String,
    },
    /// Issued by the sponsor once the window has passed on its own copy.
    Admit {
        proposal: Hash,
        age_ms: u64,
    },
    /// Starts a group vote to revoke a member. The proposer's support counts.
    ProposeRevoke {
        target: MemberKey,
    },
    VoteRevoke {
        proposal: Hash,
    },
    AddDevice {
        device: DeviceKey,
    },
    /// A member may revoke their own device at any time, without a vote (§5).
    RevokeOwnDevice {
        device: DeviceKey,
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
            } => {
                w.u8(4);
                w.fixed(proposal);
                w.u64(*age_ms);
                w.var(reason.as_bytes());
            }
            Body::Admit { proposal, age_ms } => {
                w.u8(5);
                w.fixed(proposal);
                w.u64(*age_ms);
            }
            Body::ProposeRevoke { target } => {
                w.u8(6);
                w.fixed(&target.0);
            }
            Body::VoteRevoke { proposal } => {
                w.u8(7);
                w.fixed(proposal);
            }
            Body::AddDevice { device } => {
                w.u8(8);
                w.fixed(&device.0);
            }
            Body::RevokeOwnDevice { device } => {
                w.u8(9);
                w.fixed(&device.0);
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
            },
            5 => Body::Admit {
                proposal: r.array()?,
                age_ms: r.u64()?,
            },
            6 => Body::ProposeRevoke {
                target: MemberKey(r.array()?),
            },
            7 => Body::VoteRevoke {
                proposal: r.array()?,
            },
            8 => Body::AddDevice {
                device: DeviceKey(r.array()?),
            },
            9 => Body::RevokeOwnDevice {
                device: DeviceKey(r.array()?),
            },
            _ => return Err(Error::Malformed("unknown event type")),
        })
    }

    fn check(&self) -> Result<()> {
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
