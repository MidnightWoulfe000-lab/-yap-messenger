//! Scramble: a best-effort request for a device to wipe its data (§6).
//!
//! This is the decision the **target device** makes when a command reaches
//! it. A device that is offline, modified or in an attacker's hands can simply
//! ignore it, so this is never a guarantee.
//!
//! - Owner-triggered: needs the device's password; wipes **all Yap data**.
//! - Group-triggered: needs a revocation the device can verify in its own
//!   synced copy of the group log, in a group created with "group scramble"
//!   on; wipes **that group only**. Removal by an admin never triggers it. There is no individual opt-out: joining
//!   a group with the setting on is the consent.
//! - Either way the command is dropped once its age reaches 24 hours.
//!
//! Stage one carries the owner's password as a plain field. Before any real
//! use it must be encrypted to the target device (stage three), so relays and
//! other members never see it.

use crate::event::{GroupId, Hash};
use crate::keys::{DeviceKey, MemberKey};
use crate::password::{Check, PasswordGuard};
use crate::state::{GroupState, MemberStatus};
use rand_core::{OsRng, RngCore};
use sha2::{Digest, Sha256};
use std::collections::BTreeSet;

pub const SCRAMBLE_LIFETIME_MS: u64 = 24 * 60 * 60 * 1000;

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Scope {
    AllYapData,
    Group(GroupId),
}

#[derive(Clone, PartialEq, Eq)]
pub enum Trigger {
    Owner { password: String },
    GroupRevocation { group: GroupId, revocation: Hash },
}

impl std::fmt::Debug for Trigger {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Trigger::Owner { .. } => write!(f, "Owner {{ password: <redacted> }}"),
            Trigger::GroupRevocation { group, revocation } => f
                .debug_struct("GroupRevocation")
                .field("group", group)
                .field("revocation", &revocation[..4].to_vec())
                .finish(),
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ScrambleCommand {
    pub id: [u8; 16],
    pub target: DeviceKey,
    pub trigger: Trigger,
}

impl ScrambleCommand {
    pub fn owner(target: DeviceKey, password: &str) -> Self {
        Self {
            id: random_id(),
            target,
            trigger: Trigger::Owner {
                password: password.to_owned(),
            },
        }
    }

    pub fn group(target: DeviceKey, group: GroupId, revocation: Hash) -> Self {
        Self {
            id: random_id(),
            target,
            trigger: Trigger::GroupRevocation { group, revocation },
        }
    }

    /// Identifies the command for its age counter. The password is left out,
    /// so the hash cannot be used to test password guesses.
    pub fn hash(&self) -> Hash {
        let mut h = Sha256::new();
        h.update(b"yap-groups/v1/scramble\0");
        h.update(self.id);
        h.update(self.target.0);
        match &self.trigger {
            Trigger::Owner { .. } => h.update([1]),
            Trigger::GroupRevocation { group, revocation } => {
                h.update([2]);
                h.update(group.0);
                h.update(revocation);
            }
        }
        h.finalize().into()
    }
}

fn random_id() -> [u8; 16] {
    let mut id = [0u8; 16];
    OsRng.fill_bytes(&mut id);
    id
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Decision {
    Wipe(Scope),
    Reject(Rejection),
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Rejection {
    NotThisDevice,
    Expired,
    AlreadyHandled,
    NoPassword,
    WrongPassword {
        remaining_attempts: u32,
    },
    LockedOut {
        retry_after_ms: u64,
    },
    UnknownGroup,
    NotRevoked,
    /// The group was created with "group scramble" off.
    GroupScrambleOff,
    /// The member was removed by an admin, not by a group vote.
    NotAVote,
}

/// Lives on each device and decides what to do with incoming commands.
#[derive(Debug)]
pub struct ScrambleGuard {
    pub device: DeviceKey,
    pub owner: MemberKey,
    pub password: Option<PasswordGuard>,
    handled: BTreeSet<[u8; 16]>,
}

impl ScrambleGuard {
    pub fn new(device: DeviceKey, owner: MemberKey, password: Option<PasswordGuard>) -> Self {
        Self {
            device,
            owner,
            password,
            handled: BTreeSet::new(),
        }
    }

    /// `age_ms` is the command's verified age plus this device's own holding
    /// time (see [`crate::AgedItem::verified_age_ms`]). `group` is this
    /// device's own synced state for the group a group-trigger names.
    pub fn handle(
        &mut self,
        command: &ScrambleCommand,
        age_ms: u64,
        now_ms: u64,
        group: Option<&GroupState>,
    ) -> Decision {
        use Decision::Reject;
        if command.target != self.device {
            return Reject(Rejection::NotThisDevice);
        }
        if self.handled.contains(&command.id) {
            return Reject(Rejection::AlreadyHandled);
        }
        if age_ms >= SCRAMBLE_LIFETIME_MS {
            return Reject(Rejection::Expired);
        }
        let scope = match &command.trigger {
            Trigger::Owner { password } => {
                let Some(guard) = self.password.as_mut() else {
                    return Reject(Rejection::NoPassword);
                };
                match guard.check(password, now_ms) {
                    Check::Correct => Scope::AllYapData,
                    Check::Wrong { remaining_attempts } => {
                        return Reject(Rejection::WrongPassword { remaining_attempts })
                    }
                    Check::LockedOut { retry_after_ms } => {
                        return Reject(Rejection::LockedOut { retry_after_ms })
                    }
                }
            }
            Trigger::GroupRevocation {
                group: id,
                revocation,
            } => {
                let Some(state) = group.filter(|s| s.group == *id) else {
                    return Reject(Rejection::UnknownGroup);
                };
                let Some(me) = state.members.get(&self.owner) else {
                    return Reject(Rejection::UnknownGroup);
                };
                if me.status
                    != (MemberStatus::Revoked {
                        revocation: *revocation,
                    })
                {
                    return Reject(Rejection::NotRevoked);
                }
                if !state.settings.group_scramble {
                    return Reject(Rejection::GroupScrambleOff);
                }
                // Scramble is a toolkit tool: it follows a group vote, never
                // an admin's decision on their own (§6).
                if !state.revocations.get(revocation).is_some_and(|r| r.carried) {
                    return Reject(Rejection::NotAVote);
                }
                Scope::Group(*id)
            }
        };
        self.handled.insert(command.id);
        Decision::Wipe(scope)
    }
}
