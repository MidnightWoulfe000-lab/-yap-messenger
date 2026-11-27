//! The receive window and re-wrapping rules of proposal §3.4, written as a
//! small policy reference with the clock and nonce passed in, so the rules can
//! be tested. **Not reviewed and not for use in the app.**
//!
//! - A receiver accepts an outer hour from 25 hours before its own current
//!   hour to 1 hour after it, inclusive. This window is coarse: an envelope
//!   must also pass its own exact expiry and lifetime checks.
//! - A piece is re-wrapped once it has waited **20 hours or more** since it was
//!   last wrapped, counted in whole hours. Re-wrapping keeps the inner
//!   libsignal message byte for byte, and uses the current hour, the label for
//!   that hour and a fresh random nonce.

use crate::{KIND_PAIR, derive_key, hour_of, info, piece_ad, seal};
use chacha20poly1305::aead::OsRng;
use chacha20poly1305::aead::rand_core::RngCore;

pub const WINDOW_BACK_HOURS: u64 = 25;
pub const WINDOW_AHEAD_HOURS: u64 = 1;
pub const REWRAP_AFTER_HOURS: u64 = 20;

/// Whether a receiver whose clock reads `now_ms` accepts `hour`.
pub fn accepts_hour(now_ms: u64, hour: u64) -> bool {
    let now = hour_of(now_ms);
    hour >= now.saturating_sub(WINDOW_BACK_HOURS) && hour <= now + WINDOW_AHEAD_HOURS
}

/// Whether a piece wrapped in `wrapped_hour` must be re-wrapped before it is
/// sent again at `now_ms`.
pub fn needs_rewrap(wrapped_hour: u64, now_ms: u64) -> bool {
    hour_of(now_ms).saturating_sub(wrapped_hour) >= REWRAP_AFTER_HOURS
}

/// A one-to-one piece ready to send: its label, the hour it was wrapped in,
/// and the outer layer around the unchanged libsignal message.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Piece {
    pub label: [u8; 16],
    pub hour: u64,
    pub sealed: Vec<u8>,
}

/// Everything that names a one-to-one piece's outer key.
pub struct PairContext<'a> {
    pub label_key: &'a [u8; 32],
    pub group: &'a [u8; 16],
    pub sender: &'a [u8; 32],
    pub recipient: &'a [u8; 32],
}

/// Wraps `inner` for the hour of `now_ms`, with `label` (the pair's label for
/// that hour) and `nonce`.
pub fn wrap(
    ctx: &PairContext,
    label: [u8; 16],
    now_ms: u64,
    nonce: &[u8; 24],
    inner: &[u8],
) -> Piece {
    let hour = hour_of(now_ms);
    let key = derive_key(
        ctx.label_key,
        &info(KIND_PAIR, ctx.group, ctx.sender, ctx.recipient, hour),
    );
    Piece {
        label,
        hour,
        sealed: seal(&key, nonce, &piece_ad(&label, hour), inner),
    }
}

/// Opens a piece as a receiver whose clock reads `now_ms`: refuses an hour
/// outside the window before trying the key.
pub fn open_piece(ctx: &PairContext, piece: &Piece, now_ms: u64) -> Option<Vec<u8>> {
    if !accepts_hour(now_ms, piece.hour) {
        return None;
    }
    let key = derive_key(
        ctx.label_key,
        &info(KIND_PAIR, ctx.group, ctx.sender, ctx.recipient, piece.hour),
    );
    crate::open(&key, &piece_ad(&piece.label, piece.hour), &piece.sealed)
}

/// A fresh nonce from the operating system's secure random source.
pub fn random_nonce() -> [u8; 24] {
    let mut nonce = [0u8; 24];
    OsRng.fill_bytes(&mut nonce);
    nonce
}
