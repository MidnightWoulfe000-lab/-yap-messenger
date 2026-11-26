//! Split-mesh behaviour: devices on different sides make decisions without
//! hearing each other, then reconnect.

mod common;

use common::*;
use yap_groups::*;

/// Replays events into a fresh log, picking among ready events by largest
/// hash first: a different (but parent-respecting) arrival order.
fn replay_reversed(events: &[Event], group: GroupId) -> Log {
    let mut log = Log::new(group);
    let mut pending: Vec<Event> = events.to_vec();
    while !pending.is_empty() {
        pending.sort_by_key(|e| std::cmp::Reverse(e.hash()));
        let index = pending
            .iter()
            .position(|e| e.parents.iter().all(|p| log.contains(p)))
            .expect("some event is always ready");
        log.insert(pending.remove(index)).unwrap();
    }
    log
}

#[test]
fn arrival_order_does_not_change_the_result() {
    let (mut log, p) = group_of(5, true, true);
    let r = log
        .append(&p[0], Body::ProposeRevoke { target: p[3].key() })
        .unwrap();
    let mut left = log.clone();
    let mut right = log.clone();
    left.append(&p[1], Body::VoteRevoke { proposal: r })
        .unwrap();
    let x = propose(&mut right, &p[4], &id(20));
    approve(&mut right, &p[2], x);
    right
        .append(&p[2], Body::VoteRevoke { proposal: r })
        .unwrap();
    sync(&right, &mut left);

    let events: Vec<Event> = left.events().into_iter().cloned().collect();
    let other = replay_reversed(&events, left.group());
    assert_eq!(left.state().unwrap(), other.state().unwrap());
    assert_eq!(left.heads(), other.heads());
}

#[test]
fn both_sides_converge_after_reconnecting() {
    let (log, p) = group_of(5, true, true);
    let mut left = log.clone();
    let mut right = log;
    left.append(
        &p[0],
        Body::AddDevice {
            device: DeviceKey([1; 32]),
        },
    )
    .unwrap();
    right
        .append(
            &p[4],
            Body::AddDevice {
                device: DeviceKey([2; 32]),
            },
        )
        .unwrap();
    let snapshot = left.clone();
    sync(&right, &mut left);
    sync(&snapshot, &mut right);
    assert_eq!(left.state().unwrap(), right.state().unwrap());
}

#[test]
fn late_objection_admission_stands_and_objector_is_told() {
    // A proposes X. B approves and A admits on one side; D objects on the
    // other side without either seeing the other.
    let (mut log, p) = group_of(4, true, true);
    let x = id(20);
    let proposal = propose(&mut log, &p[0], &x);
    let mut left = log.clone();
    let mut right = log;
    approve(&mut left, &p[1], proposal);
    let admitted = admit(&mut left, &p[0], proposal);
    let objection = right
        .append(
            &p[3],
            Body::Object {
                proposal,
                age_ms: 30_000,
                reason: "Saw them with the police".into(),
            },
        )
        .unwrap();
    assert_eq!(right.is_valid(&objection), Some(true));

    sync(&right, &mut left);
    assert_eq!(left.is_valid(&admitted), Some(true));
    let state = left.state().unwrap();
    assert!(state.is_active(&x.key()), "the admission stands");
    let notice = Warning::LateObjection {
        proposal,
        objection,
        objector: p[3].key(),
        admitted: x.key(),
    };
    assert_eq!(state.notices_for(&p[3].key()), vec![&notice]);
    assert!(state.notices_for(&p[0].key()).is_empty());

    // The objector can act on it by starting a normal removal vote.
    assert!(state.can_challenge(&p[3].key(), &x.key()));
    let challenge = left
        .append(&p[3], Body::ProposeRevoke { target: x.key() })
        .unwrap();
    assert_eq!(left.is_valid(&challenge), Some(true));
    assert!(
        left.state().unwrap().is_active(&x.key()),
        "needs a majority"
    );
}

#[test]
fn revoked_elsewhere_is_out_after_sync_but_earlier_actions_stand() {
    // Left side (A, B, C) revokes E. Right side (D, E) carries on, and E keeps
    // acting because it has not heard.
    let (log, p) = group_of(5, true, true);
    let e = &p[4];
    let mut left = log.clone();
    let mut right = log;
    let r = left
        .append(&p[0], Body::ProposeRevoke { target: e.key() })
        .unwrap();
    left.append(&p[1], Body::VoteRevoke { proposal: r })
        .unwrap();
    left.append(&p[2], Body::VoteRevoke { proposal: r })
        .unwrap();
    assert!(!left.state().unwrap().is_active(&e.key()));

    let mut last = [0; 32];
    for n in 0..4 {
        last = right
            .append(
                e,
                Body::AddDevice {
                    device: DeviceKey([n; 32]),
                },
            )
            .unwrap();
    }
    sync(&right, &mut left);
    let state = left.state().unwrap();
    assert!(matches!(
        state.members[&e.key()].status,
        MemberStatus::Revoked { .. }
    ));
    // What E did before the sync stands.
    assert_eq!(left.is_valid(&last), Some(true));
    assert!(state.members[&e.key()]
        .devices
        .contains(&DeviceKey([3; 32])));
    // Once E's side has the revocation, E is out.
    sync(&left, &mut right);
    let after = right
        .append(
            e,
            Body::AddDevice {
                device: DeviceKey([9; 32]),
            },
        )
        .unwrap();
    assert_eq!(right.is_valid(&after), Some(false));
    assert!(state.warnings.contains(&Warning::ActedWhileRevoked {
        event: last,
        author: e.key()
    }));
}

#[test]
fn small_isolated_subset_cannot_reach_a_majority() {
    // Five members; two are cut off and try to revoke a third.
    let (mut log, p) = group_of(5, true, true);
    let r = log
        .append(&p[3], Body::ProposeRevoke { target: p[0].key() })
        .unwrap();
    log.append(&p[4], Body::VoteRevoke { proposal: r }).unwrap();
    assert!(log.state().unwrap().is_active(&p[0].key()));
}

#[test]
fn large_group_subset_of_three_can_revoke() {
    let (mut log, p) = group_of(7, true, true);
    let r = log
        .append(&p[4], Body::ProposeRevoke { target: p[0].key() })
        .unwrap();
    log.append(&p[5], Body::VoteRevoke { proposal: r }).unwrap();
    assert!(log.state().unwrap().is_active(&p[0].key()));
    log.append(&p[6], Body::VoteRevoke { proposal: r }).unwrap();
    assert!(!log.state().unwrap().is_active(&p[0].key()));
}
