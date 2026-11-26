//! The age counter (§3 window timing, §6 scramble expiry).
//!
//! An item's age starts at 0. Each member device that holds it adds how long
//! it held it, timed with its own elapsed-time stopwatch (on Android,
//! `SystemClock.elapsedRealtime`), and signs the new total. No device needs a
//! correct clock. The same idea is used by delay-tolerant networking (the
//! Bundle Age block in RFC 9171).
//!
//! Accepted flaws, also listed in the specification:
//! - a dishonest member can under-report how long it held an item;
//! - time while a phone is switched off, or while a non-member carries the
//!   item, is not counted.
//!
//! Found while building: anyone who forwards an item can **drop the last
//! hops**, making it look younger. Each remaining hop still verifies. This has
//! the same effect as under-reporting, but a non-member relay can do it too.
//! It needs a decision; see `groups/README.md`.

use crate::event::Hash;
use crate::keys::{context, Identity, MemberKey};
use crate::wire::Writer;
use crate::{Error, Result};
use sha2::{Digest, Sha256};

pub const MAX_HOPS: usize = 64;
const HOP_CONTEXT: &str = "yap-groups/v1/age-hop";
const CHAIN_CONTEXT: &str = "yap-groups/v1/age-chain";

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Hop {
    pub holder: MemberKey,
    pub held_ms: u64,
    pub signature: [u8; 64],
}

/// An item (identified by its hash) together with its signed age counter.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct AgedItem {
    pub item: Hash,
    pub hops: Vec<Hop>,
}

impl AgedItem {
    pub fn new(item: Hash) -> Self {
        Self {
            item,
            hops: Vec::new(),
        }
    }

    /// Records that `identity` held the item for `held_ms`, measured with its
    /// own stopwatch, before passing it on.
    pub fn add_hop(&mut self, identity: &Identity, held_ms: u64) -> Result<()> {
        if self.hops.len() >= MAX_HOPS {
            return Err(Error::Malformed("too many hops"));
        }
        let holder = identity.key();
        let signature = identity.sign(&self.hop_message(self.hops.len(), holder, held_ms));
        self.hops.push(Hop {
            holder,
            held_ms,
            signature,
        });
        Ok(())
    }

    /// Checks every hop's signature and returns the age, counting only hops by
    /// holders for whom `counts` is true (group members). Strangers can carry
    /// the item but their time counts for nothing.
    pub fn verified_age_ms(&self, counts: impl Fn(&MemberKey) -> bool) -> Result<u64> {
        if self.hops.len() > MAX_HOPS {
            return Err(Error::Malformed("too many hops"));
        }
        let mut age: u64 = 0;
        for (i, hop) in self.hops.iter().enumerate() {
            if !hop.holder.verify(
                &self.hop_message(i, hop.holder, hop.held_ms),
                &hop.signature,
            ) {
                return Err(Error::BadSignature);
            }
            if counts(&hop.holder) {
                age = age.saturating_add(hop.held_ms);
            }
        }
        Ok(age)
    }

    /// Each hop signs the item and every earlier hop, so hops cannot be
    /// reordered, edited or removed from the middle.
    fn hop_message(&self, index: usize, holder: MemberKey, held_ms: u64) -> Vec<u8> {
        let mut chain = Sha256::new();
        chain.update(context(CHAIN_CONTEXT, &self.item));
        for hop in &self.hops[..index] {
            chain.update(hop.holder.0);
            chain.update(hop.held_ms.to_be_bytes());
            chain.update(hop.signature);
        }
        let chain: [u8; 32] = chain.finalize().into();
        let mut w = Writer::default();
        w.fixed(&self.item);
        w.fixed(&chain);
        w.u16(index as u16);
        w.fixed(&holder.0);
        w.u64(held_ms);
        context(HOP_CONTEXT, &w.0)
    }
}
