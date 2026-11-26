//! The per-device password that proves the owner when they trigger a scramble
//! from another phone (§6).
//!
//! Rules from the specification: at least 8 characters, changeable on the
//! device, no recovery, 5 wrong tries then a cooldown of 30 minutes that grows
//! by 30 minutes with each further lockout. The target device itself checks
//! and counts, so a split mesh cannot give an attacker extra tries.
//!
//! Only a salted Argon2id hash is kept. Whoever holds a confiscated phone can
//! still attack that hash offline without the try limit, which is why the
//! password should authorise actions on this one device only.

use crate::{Error, Result};
use argon2::password_hash::{PasswordHash, PasswordHasher, PasswordVerifier, SaltString};
use argon2::Argon2;
use rand_core::OsRng;
use serde::{Deserialize, Serialize};

pub const MIN_PASSWORD_CHARS: usize = 8;
pub const MAX_PASSWORD_BYTES: usize = 256;
pub const ATTEMPTS_BEFORE_COOLDOWN: u32 = 5;
pub const COOLDOWN_STEP_MS: u64 = 30 * 60 * 1000;

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Check {
    Correct,
    Wrong { remaining_attempts: u32 },
    LockedOut { retry_after_ms: u64 },
}

/// Persist this whole struct (it is serialisable): the counters must survive
/// restarts. `now_ms` arguments come from the device's own clock that does not
/// go backwards across restarts; a device the attacker controls can ignore all
/// of this anyway.
#[derive(Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct PasswordGuard {
    hash: String,
    failures: u32,
    lockouts: u32,
    locked_until_ms: u64,
}

impl std::fmt::Debug for PasswordGuard {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("PasswordGuard")
            .field("failures", &self.failures)
            .field("lockouts", &self.lockouts)
            .field("locked_until_ms", &self.locked_until_ms)
            .finish_non_exhaustive()
    }
}

impl PasswordGuard {
    pub fn new(password: &str) -> Result<Self> {
        Ok(Self {
            hash: hash(password)?,
            failures: 0,
            lockouts: 0,
            locked_until_ms: 0,
        })
    }

    pub fn check(&mut self, guess: &str, now_ms: u64) -> Check {
        if now_ms < self.locked_until_ms {
            return Check::LockedOut {
                retry_after_ms: self.locked_until_ms - now_ms,
            };
        }
        if guess.len() <= MAX_PASSWORD_BYTES && verify(&self.hash, guess) {
            self.failures = 0;
            self.lockouts = 0;
            return Check::Correct;
        }
        self.failures += 1;
        if self.failures >= ATTEMPTS_BEFORE_COOLDOWN {
            self.failures = 0;
            self.lockouts += 1;
            let cooldown = COOLDOWN_STEP_MS.saturating_mul(self.lockouts as u64);
            self.locked_until_ms = now_ms.saturating_add(cooldown);
            return Check::LockedOut {
                retry_after_ms: cooldown,
            };
        }
        Check::Wrong {
            remaining_attempts: ATTEMPTS_BEFORE_COOLDOWN - self.failures,
        }
    }

    /// Changes the password on the device. The current password is checked
    /// with the same try limit.
    pub fn change(&mut self, current: &str, new: &str, now_ms: u64) -> Result<Check> {
        validate(new)?;
        let check = self.check(current, now_ms);
        if check == Check::Correct {
            self.hash = hash(new)?;
        }
        Ok(check)
    }
}

fn validate(password: &str) -> Result<()> {
    if password.chars().count() < MIN_PASSWORD_CHARS {
        return Err(Error::Password("must be at least 8 characters"));
    }
    if password.len() > MAX_PASSWORD_BYTES {
        return Err(Error::Password("too long"));
    }
    Ok(())
}

fn hash(password: &str) -> Result<String> {
    validate(password)?;
    let salt = SaltString::generate(&mut OsRng);
    Argon2::default()
        .hash_password(password.as_bytes(), &salt)
        .map(|h| h.to_string())
        .map_err(|_| Error::Password("hashing failed"))
}

fn verify(stored: &str, guess: &str) -> bool {
    PasswordHash::new(stored)
        .map(|parsed| {
            Argon2::default()
                .verify_password(guess.as_bytes(), &parsed)
                .is_ok()
        })
        .unwrap_or(false)
}
