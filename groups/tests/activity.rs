//! Majorities counted from members active in the last 30 days, and the
//! SuperAdmin runoff after a split (§3, §5).

mod common;

use common::*;
use yap_groups::*;

const DAY: u64 = 24 * 60 * 60 * 1000;

fn keys(people: &[&Identity]) -> Vec<MemberKey> {
    let mut list: Vec<MemberKey> = people.iter().map(|p| p.key()).collect();
    list.sort();
    list
}

#[test]
fn activity_is_dated_by_when_this_phone_received_it() {
    let (log, p) = group_of(3, true, true);
    let state = log.state().unwrap();
    let mut activity = ActivityLog::new();
    activity.record(p[1].key(), 10 * DAY);
    activity.record(p[1].key(), 2 * DAY); // an older copy arriving late
    activity.record(p[2].key(), 0);

    let now = 31 * DAY;
    assert!(activity.is_active(&p[1].key(), now));
    assert!(!activity.is_active(&p[2].key(), now), "heard 31 days ago");
    // This phone's own member is always on its list.
    assert_eq!(
        activity.electorate(&state, p[0].key(), now),
        keys(&[&p[0], &p[1]])
    );

    let check = activity.check_electorate(&keys(&[&p[0], &p[2]]), &state, p[0].key(), now);
    assert_eq!(check.missing, vec![p[1].key()]);
    assert_eq!(check.extra, vec![p[2].key()]);
    assert!(!check.agrees());

    let saved = serde_json::to_string(&activity).unwrap();
    assert_eq!(
        serde_json::from_str::<ActivityLog>(&saved).unwrap(),
        activity
    );
}

#[test]
fn majorities_count_only_the_active_members_listed() {
    // Five members, but only A, B and C have been active. Removing E needs
    // a majority of those three, not of all four others.
    let (mut log, p) = group_of(5, true, true);
    let electorate = keys(&[&p[0], &p[1], &p[2]]);
    let r = log
        .append(
            &p[0],
            Body::ProposeRevoke {
                target: p[4].key(),
                electorate,
            },
        )
        .unwrap();
    assert_eq!(log.is_valid(&r), Some(true));

    // D was not on the list, so D cannot vote.
    let outsider = log.append(&p[3], Body::VoteRevoke { proposal: r }).unwrap();
    assert_eq!(log.is_valid(&outsider), Some(false));
    assert!(log.state().unwrap().is_active(&p[4].key()));

    log.append(&p[1], Body::VoteRevoke { proposal: r }).unwrap();
    assert!(!log.state().unwrap().is_active(&p[4].key()));
}

#[test]
fn every_vote_must_carry_a_list() {
    let (log, p) = group_of(3, true, true);
    let empty = Event::create(
        &p[0],
        log.group(),
        log.heads(),
        Body::ProposeRevoke {
            target: p[2].key(),
            electorate: vec![],
        },
    );
    assert!(empty.is_err());
}

#[test]
fn a_group_holds_at_most_128_members() {
    let (mut log, a, b) = normal_group(false);
    for n in 0..(MAX_MEMBERS - 2) as u32 {
        let request = JoinRequest::sign(&big_id(n), log.group());
        log.append(&a, Body::AdminAdd { request }).unwrap();
    }
    assert_eq!(log.state().unwrap().active_count(), MAX_MEMBERS);
    let request = JoinRequest::sign(&big_id(999), log.group());
    let full = log.append(&a, Body::AdminAdd { request }).unwrap();
    assert_eq!(log.is_valid(&full), Some(false));

    // Once someone leaves, there is room again.
    log.append(&a, Body::AdminRemove { target: b.key() })
        .unwrap();
    let request = JoinRequest::sign(&big_id(1000), log.group());
    let added = log.append(&a, Body::AdminAdd { request }).unwrap();
    assert_eq!(log.is_valid(&added), Some(true));
}

/// Distinct identities beyond the 255 that `id` can make.
fn big_id(n: u32) -> Identity {
    let mut seed = [7u8; 32];
    seed[1..5].copy_from_slice(&n.to_le_bytes()); // byte 0 loses bits to key clamping
    Identity::from_seed(seed)
}

#[test]
fn the_list_must_name_current_members_and_the_proposer() {
    let (mut log, p) = group_of(4, true, true);
    let stranger = id(50);
    let with_stranger = log
        .append(
            &p[0],
            Body::ProposeRevoke {
                target: p[3].key(),
                electorate: keys(&[&p[0], &p[1], &stranger]),
            },
        )
        .unwrap();
    assert_eq!(log.is_valid(&with_stranger), Some(false));

    let without_proposer = log
        .append(
            &p[0],
            Body::ProposeRevoke {
                target: p[3].key(),
                electorate: keys(&[&p[1], &p[2]]),
            },
        )
        .unwrap();
    assert_eq!(log.is_valid(&without_proposer), Some(false));

    let mut unsorted = keys(&[&p[0], &p[1]]);
    unsorted.reverse();
    let bad = Event::create(
        &p[0],
        log.group(),
        log.heads(),
        Body::ProposeRevoke {
            target: p[3].key(),
            electorate: unsorted,
        },
    );
    assert!(bad.is_err());
}

#[test]
fn a_rejection_override_counts_its_own_list() {
    // Five members; the rejecter B lists only A, B and C as active, so
    // overriding needs a majority of A and C: both.
    let (mut log, p) = group_of(5, true, true);
    let x = id(20);
    let proposal = propose(&mut log, &p[0], &x);
    approve(&mut log, &p[2], proposal);
    let r = log
        .append(
            &p[1],
            Body::Object {
                proposal,
                age_ms: EARLY,
                reason: "No".into(),
                electorate: keys(&[&p[0], &p[1], &p[2]]),
            },
        )
        .unwrap();
    let outsider = log
        .append(
            &p[3],
            Body::Override {
                proposal,
                rejection: r,
                age_ms: EARLY,
            },
        )
        .unwrap();
    assert_eq!(log.is_valid(&outsider), Some(false));
    for who in [&p[0], &p[2]] {
        log.append(
            who,
            Body::Override {
                proposal,
                rejection: r,
                age_ms: EARLY,
            },
        )
        .unwrap();
    }
    let admitted = log
        .append(
            &p[0],
            Body::Admit {
                proposal,
                age_ms: WINDOW_MS,
                rejection_ages: vec![(r, WINDOW_MS)],
            },
        )
        .unwrap();
    assert_eq!(log.is_valid(&admitted), Some(true));
}

/// A normal group of five, added directly by the SuperAdmin A.
fn normal_five() -> (Log, Vec<Identity>) {
    let (mut log, a, b) = normal_group(false);
    for seed in 3..=5 {
        let request = JoinRequest::sign(&id(seed), log.group());
        log.append(&a, Body::AdminAdd { request }).unwrap();
    }
    let mut people = vec![a, b];
    people.extend((3..=5).map(id));
    (log, people)
}

fn vote_out_super(log: &mut Log, side: [&Identity; 2], target: &Identity) -> Hash {
    let r = log
        .append(
            side[0],
            Body::ProposeRevoke {
                target: target.key(),
                electorate: keys(&side),
            },
        )
        .unwrap();
    log.append(side[1], Body::VoteRevoke { proposal: r })
        .unwrap();
    r
}

fn elect(log: &mut Log, voter: &Identity, vacancy: Hash, candidate: &Identity) -> Hash {
    log.append(
        voter,
        Body::ElectSuperAdmin {
            vacancy,
            candidate: candidate.key(),
        },
    )
    .unwrap()
}

#[test]
fn two_superadmins_elected_apart_face_a_runoff() {
    // The group splits into {B, C} and {D, E}. Each side has only heard from
    // its own members for 30 days, so each votes A out and elects its own
    // SuperAdmin.
    let (log, p) = normal_five();
    let (a, b, c, d, e) = (&p[0], &p[1], &p[2], &p[3], &p[4]);
    let mut left = log.clone();
    let mut right = log;

    let vl = vote_out_super(&mut left, [b, c], a);
    elect(&mut left, b, vl, b);
    elect(&mut left, c, vl, b);
    assert_eq!(left.state().unwrap().super_admin, Some(b.key()));

    let vr = vote_out_super(&mut right, [d, e], a);
    elect(&mut right, d, vr, d);
    elect(&mut right, e, vr, d);
    assert_eq!(right.state().unwrap().super_admin, Some(d.key()));

    sync(&right, &mut left);
    let snapshot = left.clone();
    sync(&snapshot, &mut right);
    let state = left.state().unwrap();
    assert_eq!(state, right.state().unwrap(), "both sides agree");
    assert_eq!(state.super_admin, None);
    let runoff = state.open_election().unwrap().clone();
    assert_eq!(
        runoff.candidates,
        Some([b.key(), d.key()].into_iter().collect())
    );
    assert_eq!(runoff.electorate, keys(&[b, c, d, e]));

    // Only the two can win; a majority of the four decides.
    let other = elect(&mut left, c, runoff.vacancy, c);
    assert_eq!(left.is_valid(&other), Some(false));
    for voter in [b, c, e] {
        elect(&mut left, voter, runoff.vacancy, d);
    }
    let state = left.state().unwrap();
    assert_eq!(state.super_admin, Some(d.key()));
    assert!(state.open_election().is_none());
}

#[test]
fn one_member_cannot_take_over_by_listing_only_themselves() {
    // Five members with A as SuperAdmin. B claims nobody else is active.
    // Every vote counts at least half of the others, so B alone is not
    // enough to remove A (found by the Codex review, 26 November 2026).
    let (log, p) = normal_five();
    let (a, b, c) = (&p[0], &p[1], &p[2]);
    let mut log = log;
    let r = log
        .append(
            b,
            Body::ProposeRevoke {
                target: a.key(),
                electorate: keys(&[b]),
            },
        )
        .unwrap();
    assert!(log.state().unwrap().is_active(&a.key()), "B alone");
    assert_eq!(log.state().unwrap().revocations[&r].threshold, 2);

    // A second listed member is needed, and C is not on B's list.
    let c_vote = log.append(c, Body::VoteRevoke { proposal: r }).unwrap();
    assert_eq!(log.is_valid(&c_vote), Some(false));
    assert!(log.state().unwrap().is_active(&a.key()));
}

#[test]
fn half_the_members_are_always_counted() {
    assert_eq!(counted(1, 4), 2);
    assert_eq!(counted(3, 4), 3);
    assert_eq!(counted(2, 9), 5);
    assert_eq!(counted(0, 0), 0);
}

/// A normal group of three, added directly by the SuperAdmin A.
fn normal_three() -> (Log, Vec<Identity>) {
    let (mut log, a, b) = normal_group(false);
    let request = JoinRequest::sign(&id(3), log.group());
    log.append(&a, Body::AdminAdd { request }).unwrap();
    (log, vec![a, b, id(3)])
}

fn propose_removal(log: &mut Log, by: &Identity, target: &Identity, list: &[&Identity]) -> Hash {
    log.append(
        by,
        Body::ProposeRevoke {
            target: target.key(),
            electorate: keys(list),
        },
    )
    .unwrap()
}

#[test]
fn no_one_takes_over_a_group_of_three_alone() {
    // Found by the second Codex review (27 November 2026): with only the
    // half-of-members floor, B could remove A here by listing only B.
    let (mut log, p) = normal_three();
    let (a, b, c) = (&p[0], &p[1], &p[2]);
    let alone = propose_removal(&mut log, b, a, &[b]);
    assert!(log.state().unwrap().is_active(&a.key()));
    assert_eq!(log.state().unwrap().revocations[&alone].threshold, 2);

    // With C on the list and voting, A is removed, and the election also
    // needs both.
    let r = propose_removal(&mut log, b, a, &[b, c]);
    log.append(c, Body::VoteRevoke { proposal: r }).unwrap();
    let state = log.state().unwrap();
    assert!(!state.is_active(&a.key()));
    let vacancy = state.open_election().unwrap().vacancy;
    log.append(
        b,
        Body::ElectSuperAdmin {
            vacancy,
            candidate: b.key(),
        },
    )
    .unwrap();
    assert_eq!(
        log.state().unwrap().super_admin,
        None,
        "B's own vote is not enough"
    );
    log.append(
        c,
        Body::ElectSuperAdmin {
            vacancy,
            candidate: b.key(),
        },
    )
    .unwrap();
    assert_eq!(log.state().unwrap().super_admin, Some(b.key()));
}

#[test]
fn nobody_can_remove_the_superadmin_of_a_group_of_two() {
    let (mut log, a, b) = normal_group(false);
    let r = propose_removal(&mut log, &b, &a, &[&b]);
    let state = log.state().unwrap();
    assert!(state.is_active(&a.key()));
    assert_eq!(state.revocations[&r].threshold, 2);
}

#[test]
fn no_member_of_a_collective_of_three_removes_another_alone() {
    let (mut log, p) = group_of(3, true, true);
    propose_removal(&mut log, &p[0], &p[1], &[&p[0]]);
    assert!(log.state().unwrap().is_active(&p[1].key()));
}

#[test]
fn votes_needed_across_sizes() {
    // (listed, everyone) -> votes needed
    for (listed, everyone, want) in [
        (0, 0, 1), // nobody could vote, so it can never pass
        (1, 1, 1),
        (1, 2, 2),
        (2, 2, 2),
        (1, 3, 2),
        (3, 4, 2),
        (2, 9, 3),
        (9, 9, 5),
    ] {
        assert_eq!(needed(listed, everyone), want, "{listed} of {everyone}");
    }
}
