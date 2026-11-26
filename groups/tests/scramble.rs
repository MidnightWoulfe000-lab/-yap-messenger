mod common;

use common::*;
use yap_groups::password::{COOLDOWN_STEP_MS, MIN_PASSWORD_CHARS};
use yap_groups::scramble::SCRAMBLE_LIFETIME_MS;
use yap_groups::*;

const PASSWORD: &str = "river42stone";

#[test]
fn password_needs_eight_characters() {
    assert_eq!(MIN_PASSWORD_CHARS, 8);
    assert!(PasswordGuard::new("short12").is_err());
    assert!(PasswordGuard::new("longer12").is_ok());
}

#[test]
fn five_wrong_tries_then_growing_cooldown() {
    let mut guard = PasswordGuard::new(PASSWORD).unwrap();
    let mut now = 0;
    for remaining in (1..5).rev() {
        assert_eq!(
            guard.check("wrong-one", now),
            Check::Wrong {
                remaining_attempts: remaining
            }
        );
    }
    assert_eq!(
        guard.check("wrong-one", now),
        Check::LockedOut {
            retry_after_ms: COOLDOWN_STEP_MS
        }
    );
    // Even the right password waits out the cooldown.
    now += COOLDOWN_STEP_MS - 1;
    assert!(matches!(
        guard.check(PASSWORD, now),
        Check::LockedOut { .. }
    ));

    now += 1;
    for _ in 0..4 {
        guard.check("wrong-two", now);
    }
    assert_eq!(
        guard.check("wrong-two", now),
        Check::LockedOut {
            retry_after_ms: 2 * COOLDOWN_STEP_MS
        }
    );
    now += 2 * COOLDOWN_STEP_MS;
    assert_eq!(guard.check(PASSWORD, now), Check::Correct);
    // Success resets the count.
    assert_eq!(
        guard.check("wrong", now),
        Check::Wrong {
            remaining_attempts: 4
        }
    );
}

#[test]
fn password_changes_on_the_device_and_state_persists() {
    let mut guard = PasswordGuard::new(PASSWORD).unwrap();
    assert!(guard.change(PASSWORD, "short", 0).is_err());
    assert_eq!(
        guard.change("not-it-at-all", "newpass123", 0).unwrap(),
        Check::Wrong {
            remaining_attempts: 4
        }
    );
    assert_eq!(
        guard.change(PASSWORD, "newpass123", 0).unwrap(),
        Check::Correct
    );

    let saved = serde_json::to_string(&guard).unwrap();
    assert!(!saved.contains("newpass123"));
    let mut restored: PasswordGuard = serde_json::from_str(&saved).unwrap();
    assert_eq!(restored.check("newpass123", 0), Check::Correct);
}

#[test]
fn age_counts_member_hops_only_and_detects_tampering() {
    let (member, stranger) = (id(1), id(99));
    let mut item = AgedItem::new([3; 32]);
    item.add_hop(&member, 1_000).unwrap();
    item.add_hop(&stranger, 50_000).unwrap();
    item.add_hop(&member, 2_000).unwrap();
    let counts = |k: &MemberKey| *k == member.key();
    assert_eq!(item.verified_age_ms(counts).unwrap(), 3_000);

    let mut edited = item.clone();
    edited.hops[0].held_ms = 1;
    assert_eq!(edited.verified_age_ms(counts), Err(Error::BadSignature));

    let mut reordered = item.clone();
    reordered.hops.swap(0, 2);
    assert_eq!(reordered.verified_age_ms(counts), Err(Error::BadSignature));
}

#[test]
fn dropping_the_last_hops_is_not_detected() {
    // Known flaw recorded in groups/README.md: truncation makes an item look
    // younger and still verifies.
    let member = id(1);
    let mut item = AgedItem::new([3; 32]);
    item.add_hop(&member, 1_000).unwrap();
    item.add_hop(&member, 5_000).unwrap();
    item.hops.pop();
    assert_eq!(item.verified_age_ms(|_| true).unwrap(), 1_000);
}

fn guard_with_password() -> ScrambleGuard {
    ScrambleGuard::new(
        DeviceKey([4; 32]),
        id(3).key(),
        Some(PasswordGuard::new(PASSWORD).unwrap()),
    )
}

#[test]
fn owner_scramble_wipes_everything() {
    let mut guard = guard_with_password();
    let command = ScrambleCommand::owner(guard.device, PASSWORD);
    assert_eq!(
        guard.handle(&command, 0, 0, None),
        Decision::Wipe(Scope::AllYapData)
    );
    assert_eq!(
        guard.handle(&command, 0, 0, None),
        Decision::Reject(Rejection::AlreadyHandled)
    );
}

#[test]
fn owner_scramble_checks_password_and_device() {
    let mut guard = guard_with_password();
    let wrong = ScrambleCommand::owner(guard.device, "guess1234");
    assert_eq!(
        guard.handle(&wrong, 0, 0, None),
        Decision::Reject(Rejection::WrongPassword {
            remaining_attempts: 4
        })
    );
    let elsewhere = ScrambleCommand::owner(DeviceKey([8; 32]), PASSWORD);
    assert_eq!(
        guard.handle(&elsewhere, 0, 0, None),
        Decision::Reject(Rejection::NotThisDevice)
    );

    let mut no_password = ScrambleGuard::new(guard.device, guard.owner, None);
    let command = ScrambleCommand::owner(guard.device, PASSWORD);
    assert_eq!(
        no_password.handle(&command, 0, 0, None),
        Decision::Reject(Rejection::NoPassword)
    );
}

#[test]
fn commands_expire_after_24_hours_of_age() {
    let mut guard = guard_with_password();
    let command = ScrambleCommand::owner(guard.device, PASSWORD);
    assert_eq!(
        guard.handle(&command, SCRAMBLE_LIFETIME_MS, 0, None),
        Decision::Reject(Rejection::Expired)
    );
    assert_eq!(
        guard.handle(&command, SCRAMBLE_LIFETIME_MS - 1, 0, None),
        Decision::Wipe(Scope::AllYapData)
    );
}

#[test]
fn group_scramble_needs_a_verified_revocation() {
    let (mut log, p) = group_of(3, true, true);
    let target = &p[2];
    let mut guard = ScrambleGuard::new(DeviceKey([4; 32]), target.key(), None);
    let fake = ScrambleCommand::group(guard.device, log.group(), [6; 32]);
    let state = log.state().unwrap();
    assert_eq!(
        guard.handle(&fake, 0, 0, Some(&state)),
        Decision::Reject(Rejection::NotRevoked)
    );

    let r = log
        .append(
            &p[0],
            Body::ProposeRevoke {
                target: target.key(),
            },
        )
        .unwrap();
    log.append(&p[1], Body::VoteRevoke { proposal: r }).unwrap();
    let state = log.state().unwrap();
    let real = ScrambleCommand::group(guard.device, log.group(), r);
    assert_eq!(
        guard.handle(&real, 0, 0, None),
        Decision::Reject(Rejection::UnknownGroup)
    );
    assert_eq!(
        guard.handle(&real, 0, 0, Some(&state)),
        Decision::Wipe(Scope::Group(log.group()))
    );
}

/// With "group scramble" on, every revoked member is wiped; nobody can opt
/// out individually. With it off, nobody is.
#[test]
fn group_scramble_follows_the_group_setting() {
    for setting in [true, false] {
        let (mut log, a, b) = new_group_with(Settings::new(1, true, true, setting).unwrap());
        let c = id(3);
        add(&mut log, &a, &b, &c);
        let r = log
            .append(&a, Body::ProposeRevoke { target: c.key() })
            .unwrap();
        log.append(&b, Body::VoteRevoke { proposal: r }).unwrap();

        let mut guard = ScrambleGuard::new(DeviceKey([4; 32]), c.key(), None);
        let command = ScrambleCommand::group(guard.device, log.group(), r);
        let expected = if setting {
            Decision::Wipe(Scope::Group(log.group()))
        } else {
            Decision::Reject(Rejection::GroupScrambleOff)
        };
        assert_eq!(
            guard.handle(&command, 0, 0, Some(&log.state().unwrap())),
            expected
        );
    }
}

#[test]
fn old_group_scramble_does_not_apply_after_coming_back() {
    let (mut log, p) = group_of(3, true, true);
    let c = &p[2];
    let r = log
        .append(&p[0], Body::ProposeRevoke { target: c.key() })
        .unwrap();
    log.append(&p[1], Body::VoteRevoke { proposal: r }).unwrap();
    add(&mut log, &p[0], &p[1], c);

    let mut guard = ScrambleGuard::new(DeviceKey([4; 32]), c.key(), None);
    let command = ScrambleCommand::group(guard.device, log.group(), r);
    assert_eq!(
        guard.handle(&command, 0, 0, Some(&log.state().unwrap())),
        Decision::Reject(Rejection::NotRevoked)
    );
}

#[test]
fn debug_output_never_shows_the_password() {
    let command = ScrambleCommand::owner(DeviceKey([4; 32]), PASSWORD);
    assert!(!format!("{command:?}").contains(PASSWORD));
    let guard = PasswordGuard::new(PASSWORD).unwrap();
    assert!(!format!("{guard:?}").contains("argon2"));
}
