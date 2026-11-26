//! Normal groups: admins, the SuperAdmin, elections and switching group type
//! (§3).

mod common;

use common::*;
use yap_groups::*;

/// A normal group of `n` members (seeds 1..=n), added directly by the creator.
fn normal_of(n: u8, group_revoking: bool) -> (Log, Vec<Identity>) {
    let (mut log, a, b) = normal_group(group_revoking);
    for seed in 3..=n {
        let request = JoinRequest::sign(&id(seed), log.group());
        let added = log.append(&a, Body::AdminAdd { request }).unwrap();
        assert_eq!(log.is_valid(&added), Some(true));
    }
    let mut people = vec![a, b];
    people.extend((3..=n).map(id));
    (log, people)
}

fn make_admin(log: &mut Log, by: &Identity, target: &Identity) -> Hash {
    log.append(
        by,
        Body::SetAdmin {
            target: target.key(),
            admin: true,
        },
    )
    .unwrap()
}

fn remove(log: &mut Log, by: &Identity, target: &Identity) -> Hash {
    log.append(
        by,
        Body::AdminRemove {
            target: target.key(),
        },
    )
    .unwrap()
}

fn vote_out(log: &mut Log, voters: &[&Identity], target: &Identity) -> Hash {
    let r = log
        .append(
            voters[0],
            Body::ProposeRevoke {
                electorate: everyone(log),
                target: target.key(),
            },
        )
        .unwrap();
    for voter in &voters[1..] {
        log.append(voter, Body::VoteRevoke { proposal: r }).unwrap();
    }
    r
}

#[test]
fn the_creator_is_super_admin() {
    let (log, a, b) = normal_group(true);
    let state = log.state().unwrap();
    assert_eq!(state.group_type(), GroupType::Normal);
    assert_eq!(state.super_admin, Some(a.key()));
    assert!(state.is_admin(&a.key()) && !state.is_admin(&b.key()));

    let (log, _, _) = new_group(true, true);
    let state = log.state().unwrap();
    assert_eq!(state.group_type(), GroupType::Collective);
    assert_eq!(state.super_admin, None);
    assert!(state.admins.is_empty());
}

#[test]
fn admins_add_members_directly() {
    let (mut log, p) = normal_of(3, true);
    let state = log.state().unwrap();
    assert!(state.is_active(&p[2].key()));
    assert_eq!(state.members[&p[2].key()].sponsor, Some(p[0].key()));

    let request = JoinRequest::sign(&id(9), log.group());
    let not_admin = log.append(&p[1], Body::AdminAdd { request }).unwrap();
    assert_eq!(log.is_valid(&not_admin), Some(false));
}

#[test]
fn normal_groups_admit_without_waiting_for_a_window() {
    let (mut log, _, b) = normal_group(true);
    let c = id(3);
    let proposal = propose(&mut log, &b, &c);
    log.append(
        &b,
        Body::Approve {
            proposal,
            age_ms: WINDOW_MS * 5,
        },
    )
    .unwrap();
    let at_once = log
        .append(
            &b,
            Body::Admit {
                proposal,
                age_ms: 0,
                rejection_ages: vec![],
            },
        )
        .unwrap();
    assert_eq!(log.is_valid(&at_once), Some(true));
    assert_eq!(
        log.state().unwrap().members[&c.key()].sponsor,
        Some(b.key())
    );
}

#[test]
fn only_the_super_admin_removes_an_admin() {
    let (mut log, p) = normal_of(4, true);
    let (a, b, c, d) = (&p[0], &p[1], &p[2], &p[3]);
    make_admin(&mut log, a, b);
    let promoted = make_admin(&mut log, b, d);
    assert_eq!(log.is_valid(&promoted), Some(true), "admins make admins");

    let ordinary = remove(&mut log, b, c);
    assert_eq!(log.is_valid(&ordinary), Some(true), "admins remove members");

    let by_admin = remove(&mut log, b, d);
    assert_eq!(log.is_valid(&by_admin), Some(false));
    let demote = log
        .append(
            b,
            Body::SetAdmin {
                target: d.key(),
                admin: false,
            },
        )
        .unwrap();
    assert_eq!(log.is_valid(&demote), Some(false));

    let by_super = remove(&mut log, a, d);
    assert_eq!(log.is_valid(&by_super), Some(true));
}

#[test]
fn admins_cannot_touch_the_super_admin_or_the_settings() {
    let (mut log, p) = normal_of(3, true);
    let (a, b) = (&p[0], &p[1]);
    make_admin(&mut log, a, b);
    let kick = remove(&mut log, b, a);
    assert_eq!(log.is_valid(&kick), Some(false));
    let demote = log
        .append(
            b,
            Body::SetAdmin {
                target: a.key(),
                admin: false,
            },
        )
        .unwrap();
    assert_eq!(log.is_valid(&demote), Some(false));

    let mut settings = log.state().unwrap().settings;
    settings.group_scramble = false;
    let by_admin = log.append(b, Body::ChangeSettings { settings }).unwrap();
    assert_eq!(log.is_valid(&by_admin), Some(false));
    let by_super = log.append(a, Body::ChangeSettings { settings }).unwrap();
    assert_eq!(log.is_valid(&by_super), Some(true));
    assert!(!log.state().unwrap().settings.group_scramble);
}

#[test]
fn the_super_admin_can_be_voted_out_even_with_revoking_off() {
    let (mut log, p) = normal_of(4, false);
    let (a, b, c, d) = (&p[0], &p[1], &p[2], &p[3]);
    make_admin(&mut log, a, b);

    // Group revoking is off, so ordinary members cannot be voted out.
    let ordinary = log
        .append(
            b,
            Body::ProposeRevoke {
                electorate: everyone(&log),
                target: d.key(),
            },
        )
        .unwrap();
    assert_eq!(log.is_valid(&ordinary), Some(false));

    // A majority of the 3 others: 2.
    let r = vote_out(&mut log, &[b, c], a);
    let state = log.state().unwrap();
    assert!(!state.is_active(&a.key()));
    assert_eq!(state.super_admin, None);
    assert_eq!(state.open_election().unwrap().vacancy, r);

    // Until a new SuperAdmin is chosen, the group works as a collective, and
    // every admin goes back to being a normal member.
    assert!(state.uses_rejection_review());
    assert!(state.admins.is_empty());
    let request = JoinRequest::sign(&id(9), log.group());
    let added = log.append(b, Body::AdminAdd { request }).unwrap();
    assert_eq!(log.is_valid(&added), Some(false));
}

#[test]
fn removing_the_super_admin_needs_a_majority_in_large_groups_too() {
    // Seven members: a majority of the other 6 is 4, not the usual 3.
    let (mut log, p) = normal_of(7, false);
    assert_eq!(super_admin_removal_threshold(7), 4);
    vote_out(&mut log, &[&p[1], &p[2], &p[3]], &p[0]);
    assert!(log.state().unwrap().is_active(&p[0].key()));
    let r = log
        .state()
        .unwrap()
        .revocations
        .keys()
        .next()
        .copied()
        .unwrap();
    log.append(&p[4], Body::VoteRevoke { proposal: r }).unwrap();
    assert!(!log.state().unwrap().is_active(&p[0].key()));
}

#[test]
fn members_elect_a_new_super_admin() {
    let (mut log, p) = normal_of(4, true);
    let (a, b, c, d) = (&p[0], &p[1], &p[2], &p[3]);
    let vacancy = vote_out(&mut log, &[b, c], a);
    let vote = |log: &mut Log, who: &Identity, candidate: &Identity| {
        log.append(
            who,
            Body::ElectSuperAdmin {
                vacancy,
                candidate: candidate.key(),
            },
        )
        .unwrap()
    };
    vote(&mut log, b, c);
    let twice = vote(&mut log, b, c);
    assert_eq!(log.is_valid(&twice), Some(false), "one vote each");
    assert_eq!(log.state().unwrap().super_admin, None);

    // Three active members: a majority is 2.
    vote(&mut log, d, c);
    let state = log.state().unwrap();
    assert_eq!(state.super_admin, Some(c.key()));
    assert!(state.is_admin(&c.key()) && state.open_election().is_none());
}

#[test]
fn switching_rejection_review_on_makes_a_collective() {
    let (mut log, p) = normal_of(3, true);
    let (a, b) = (&p[0], &p[1]);
    make_admin(&mut log, a, b);
    let mut settings = log.state().unwrap().settings;
    settings.rejection_review = true;
    log.append(a, Body::ChangeSettings { settings }).unwrap();

    let state = log.state().unwrap();
    assert_eq!(state.group_type(), GroupType::Collective);
    assert!(state.admins.is_empty() && state.super_admin.is_none());
    let request = JoinRequest::sign(&id(9), log.group());
    let added = log.append(a, Body::AdminAdd { request }).unwrap();
    assert_eq!(log.is_valid(&added), Some(false));
}

#[test]
fn a_collective_votes_to_become_normal_then_elects() {
    let (mut log, p) = group_of(3, true, true);
    let (a, b, c) = (&p[0], &p[1], &p[2]);
    let mut settings = log.state().unwrap().settings;
    settings.rejection_review = false;

    let by_one = log.append(a, Body::ChangeSettings { settings }).unwrap();
    assert_eq!(
        log.is_valid(&by_one),
        Some(false),
        "no SuperAdmin to decide"
    );

    let proposal = log
        .append(
            a,
            Body::ProposeSettings {
                electorate: everyone(&log),
                settings,
            },
        )
        .unwrap();
    assert_eq!(log.state().unwrap().group_type(), GroupType::Collective);
    log.append(b, Body::VoteSettings { proposal }).unwrap();
    let state = log.state().unwrap();
    assert_eq!(state.group_type(), GroupType::Normal);
    assert_eq!(state.open_election().unwrap().vacancy, proposal);

    for voter in [a, c] {
        log.append(
            voter,
            Body::ElectSuperAdmin {
                vacancy: proposal,
                candidate: b.key(),
            },
        )
        .unwrap();
    }
    assert_eq!(log.state().unwrap().super_admin, Some(b.key()));
}

#[test]
fn in_a_clash_the_super_admins_decision_wins() {
    // B (an admin) removes C on one side while A (SuperAdmin) makes C an
    // admin on the other.
    let (mut log, p) = normal_of(3, true);
    let (a, b, c) = (&p[0], &p[1], &p[2]);
    make_admin(&mut log, a, b);
    let mut left = log.clone();
    let mut right = log;
    let removal = remove(&mut left, b, c);
    let promotion = make_admin(&mut right, a, c);
    sync(&right, &mut left);

    let state = left.state().unwrap();
    assert!(state.is_active(&c.key()) && state.is_admin(&c.key()));
    assert!(state.warnings.contains(&Warning::OverruledBySuperAdmin {
        event: removal,
        by: promotion
    }));
}

#[test]
fn in_a_clash_between_admins_the_removal_wins() {
    let (mut log, p) = normal_of(4, true);
    let (a, b, c, d) = (&p[0], &p[1], &p[2], &p[3]);
    make_admin(&mut log, a, b);
    make_admin(&mut log, a, d);
    let mut left = log.clone();
    let mut right = log;
    remove(&mut left, b, c);
    make_admin(&mut right, d, c);
    sync(&right, &mut left);
    let snapshot = left.clone();
    sync(&snapshot, &mut right);

    for log in [&left, &right] {
        let state = log.state().unwrap();
        assert!(!state.is_active(&c.key()) && !state.is_admin(&c.key()));
    }
}

#[test]
fn new_events_round_trip_through_bytes() {
    let (mut log, p) = normal_of(3, true);
    make_admin(&mut log, &p[0], &p[1]);
    let mut settings = log.state().unwrap().settings;
    settings.rejection_review = true;
    log.append(&p[0], Body::ChangeSettings { settings })
        .unwrap();
    let x = propose(&mut log, &p[1], &id(9));
    let r = log
        .append(
            &p[2],
            Body::Object {
                electorate: everyone(&log),
                proposal: x,
                age_ms: EARLY,
                reason: "No".into(),
            },
        )
        .unwrap();
    log.append(
        &p[0],
        Body::Override {
            proposal: x,
            rejection: r,
            age_ms: EARLY,
        },
    )
    .unwrap();
    for event in log.events() {
        let decoded = Event::decode(&event.encode()).unwrap();
        assert_eq!(&decoded, event);
    }
}
