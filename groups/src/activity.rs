//! Who has been active in the last 30 days (§3, §5).
//!
//! Every majority vote counts only members active in the last 30 days.
//! Sending a message or signing a group event counts as activity. Each phone
//! dates it by **when it received it**, on its own clock, never by the time
//! the sender's phone put on it, which can be wrong or faked.
//!
//! Phones hear different things, so their lists can differ. That is why the
//! list is not worked out inside the group log: whoever starts a vote puts
//! the list their phone sees into the vote (its *electorate*), every phone
//! counts from that one list, and each phone checks it against its own
//! records with [`ActivityLog::check_electorate`] so the app can warn when
//! they disagree. The vote's list always wins (user, 26 November 2026): a
//! difference never changes the count.

use crate::keys::MemberKey;
use crate::state::GroupState;
use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap, BTreeSet};

pub const ACTIVE_WINDOW_MS: u64 = 30 * 24 * 60 * 60 * 1000;

/// This phone's own record of when it last heard from each member.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct ActivityLog {
    /// Member key bytes to the latest receipt time on this phone's clock.
    #[serde(with = "as_pairs")]
    last_heard: BTreeMap<[u8; 32], u64>,
}

/// Saves the map as a list of pairs, so formats that need text keys (such as
/// JSON) can hold it.
mod as_pairs {
    use serde::{Deserialize, Deserializer, Serialize, Serializer};
    use std::collections::BTreeMap;

    pub fn serialize<S: Serializer>(
        map: &BTreeMap<[u8; 32], u64>,
        s: S,
    ) -> Result<S::Ok, S::Error> {
        map.iter().collect::<Vec<_>>().serialize(s)
    }

    pub fn deserialize<'de, D: Deserializer<'de>>(
        d: D,
    ) -> Result<BTreeMap<[u8; 32], u64>, D::Error> {
        Ok(Vec::<([u8; 32], u64)>::deserialize(d)?
            .into_iter()
            .collect())
    }
}

/// How a vote's electorate compares with this phone's own records.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct ElectorateCheck {
    /// Active by this phone's records but left off the list.
    pub missing: Vec<MemberKey>,
    /// On the list but not heard from by this phone in the last 30 days.
    pub extra: Vec<MemberKey>,
}

impl ElectorateCheck {
    pub fn agrees(&self) -> bool {
        self.missing.is_empty() && self.extra.is_empty()
    }
}

impl ActivityLog {
    pub fn new() -> Self {
        Self::default()
    }

    /// Records a message or group event from `member`, received at
    /// `received_at_ms` by this phone's clock.
    pub fn record(&mut self, member: MemberKey, received_at_ms: u64) {
        let entry = self.last_heard.entry(member.0).or_insert(received_at_ms);
        *entry = (*entry).max(received_at_ms);
    }

    pub fn is_active(&self, member: &MemberKey, now_ms: u64) -> bool {
        self.last_heard
            .get(&member.0)
            .is_some_and(|t| now_ms.saturating_sub(*t) < ACTIVE_WINDOW_MS)
    }

    /// The electorate to put into a new vote: current members this phone has
    /// heard from in the last 30 days, plus this phone's own member, sorted.
    pub fn electorate(&self, state: &GroupState, me: MemberKey, now_ms: u64) -> Vec<MemberKey> {
        let mut list: BTreeSet<MemberKey> = state
            .active_members()
            .into_iter()
            .filter(|m| self.is_active(m, now_ms))
            .collect();
        if state.is_active(&me) {
            list.insert(me);
        }
        list.into_iter().collect()
    }

    /// Compares someone else's electorate with this phone's own records.
    pub fn check_electorate(
        &self,
        electorate: &[MemberKey],
        state: &GroupState,
        me: MemberKey,
        now_ms: u64,
    ) -> ElectorateCheck {
        let mine: BTreeSet<MemberKey> = self.electorate(state, me, now_ms).into_iter().collect();
        let theirs: BTreeSet<MemberKey> = electorate.iter().copied().collect();
        ElectorateCheck {
            missing: mine.difference(&theirs).copied().collect(),
            extra: theirs.difference(&mine).copied().collect(),
        }
    }
}
