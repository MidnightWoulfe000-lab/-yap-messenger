//! The receive window and re-wrapping rules (proposal §3.4), added after the
//! Codex outer-layer review of 27 November 2026.

use yap_outer_layer_vectors::policy::*;
use yap_outer_layer_vectors::*;

const HOUR: u64 = HOUR_MS;

fn ctx() -> PairContext<'static> {
    PairContext {
        label_key: &LABEL_KEY,
        group: &GROUP,
        sender: &ALICE,
        recipient: &BOB,
    }
}

#[test]
fn the_window_runs_from_25_hours_back_to_1_ahead() {
    let now = NOON_MS;
    let h = hour_of(now);
    assert!(accepts_hour(now, h - 25), "-25 accepted");
    assert!(accepts_hour(now, h + 1), "+1 accepted");
    assert!(accepts_hour(now, h), "now accepted");
    assert!(!accepts_hour(now, h - 26), "-26 refused");
    assert!(!accepts_hour(now, h + 2), "+2 refused");
}

#[test]
fn low_hours_do_not_underflow() {
    assert!(accepts_hour(0, 0));
    assert!(accepts_hour(3 * HOUR, 0));
    assert!(!accepts_hour(0, 2));
}

#[test]
fn delayed_pieces_open_after_3_and_23_hours() {
    let inner = b"inner libsignal message";
    let piece = wrap(&ctx(), [0x55; 16], NOON_MS, &random_nonce(), inner);
    for delay in [3, 23] {
        assert_eq!(
            open_piece(&ctx(), &piece, NOON_MS + delay * HOUR),
            Some(inner.to_vec()),
            "{delay} hours"
        );
    }
    assert_eq!(
        open_piece(&ctx(), &piece, NOON_MS + 26 * HOUR),
        None,
        "26 hours"
    );
}

#[test]
fn a_clock_one_hour_ahead_is_tolerated() {
    let inner = b"made by a phone whose clock runs ahead";
    let piece = wrap(&ctx(), [0x55; 16], NOON_MS + HOUR, &random_nonce(), inner);
    assert_eq!(open_piece(&ctx(), &piece, NOON_MS), Some(inner.to_vec()));
    let far = wrap(
        &ctx(),
        [0x55; 16],
        NOON_MS + 2 * HOUR,
        &random_nonce(),
        inner,
    );
    assert_eq!(open_piece(&ctx(), &far, NOON_MS), None);
}

#[test]
fn rewrapping_starts_at_exactly_20_hours() {
    let h = hour_of(NOON_MS);
    assert!(!needs_rewrap(h, NOON_MS + 19 * HOUR));
    assert!(needs_rewrap(h, NOON_MS + 20 * HOUR));
    assert!(!needs_rewrap(h + 1, NOON_MS), "a later hour never needs it");
}

#[test]
fn a_rewrapped_piece_keeps_its_inner_bytes_and_drops_the_old_context() {
    let inner = b"inner libsignal message".to_vec();
    let old = wrap(&ctx(), [0x55; 16], NOON_MS, &random_nonce(), &inner);
    let later = NOON_MS + 21 * HOUR;
    assert!(needs_rewrap(old.hour, later));
    let new = wrap(&ctx(), [0x56; 16], later, &random_nonce(), &inner);
    assert_ne!(new.sealed, old.sealed);
    assert_eq!(new.hour, old.hour + 21);
    assert_eq!(open_piece(&ctx(), &new, later), Some(inner.clone()));

    // The new wrapper under the old label or hour is refused.
    let mut stale_label = new.clone();
    stale_label.label = old.label;
    assert_eq!(open_piece(&ctx(), &stale_label, later), None);
    let mut stale_hour = new.clone();
    stale_hour.hour = old.hour;
    assert_eq!(open_piece(&ctx(), &stale_hour, later), None);

    // The piece is the same bytes whichever batch carries it.
    let moved = new.clone();
    assert_eq!(open_piece(&ctx(), &moved, later), Some(inner));
}

#[test]
fn random_nonces_differ() {
    let a = wrap(&ctx(), [0x55; 16], NOON_MS, &random_nonce(), b"same");
    let b = wrap(&ctx(), [0x55; 16], NOON_MS, &random_nonce(), b"same");
    assert_ne!(a.sealed[4..28], b.sealed[4..28]);
    assert_ne!(a.sealed, b.sealed);
}
