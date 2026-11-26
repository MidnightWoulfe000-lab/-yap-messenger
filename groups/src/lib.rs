//! Group governance rules for Yap, stage one.
//!
//! This crate turns the decisions in `docs/GROUP_MECHANICS.md` into code that
//! can be tested without phones: signed governance events, a causal event log,
//! deterministic state derivation, the age counter, the device password guard
//! and the scramble decision on the target device.
//!
//! It has **no transport and no encryption** and it has **not been audited**.
//! Rules the specification still marks as open are either left out or surfaced
//! as [`Warning`]s rather than decided here. See `groups/README.md`.

pub mod activity;
mod error;
mod wire;

pub mod age;
pub mod event;
pub mod keys;
pub mod password;
pub mod scramble;
pub mod state;

pub use activity::{ActivityLog, ElectorateCheck, ACTIVE_WINDOW_MS};
pub use age::{AgedItem, Hop};
pub use error::{Error, Result};
pub use event::{Body, Event, GroupId, GroupType, Hash, JoinRequest, Settings, MAX_MEMBERS};
pub use keys::{DeviceKey, Identity, MemberKey};
pub use password::{Check, PasswordGuard};
pub use scramble::{Decision, Rejection, Scope, ScrambleCommand, ScrambleGuard, Trigger};
pub use state::{
    counted, majority, revocation_threshold, super_admin_removal_threshold, EarlierTerm, Election,
    GroupState, JoinProposal, JoinRecord, Log, Member, MemberStatus, Objection, Revocation,
    SettingsVote, Warning,
};
