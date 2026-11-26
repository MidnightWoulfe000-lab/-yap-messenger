//! Rejection review in collectives (§3).

mod common;

use common::*;
use yap_groups::*;

fn reject(log: &mut Log, who: &Identity, proposal: Hash, reason: &str) -> Hash {
    log.append(
        who,
        Body::Object {
            electorate: everyone(log),
            proposal,
            age_ms: EARLY,
            reason: reason.into(),
        },
    )
    .unwrap()
}

fn override_vote(log: &mut Log, who: &Identity, proposal: Hash, rejection: Hash) -> Hash {
    log.append(
        who,
        Body::Override {
            proposal,
            rejection,
            age_ms: EARLY,
        },
    )
    .unwrap()
}

fn admit_after(log: &mut Log, sponsor: &Identity, proposal: Hash, rejections: &[Hash]) -> Hash {
    let mut rejection_ages: Vec<(Hash, u64)> = rejections.iter().map(|r| (*r, WINDOW_MS)).collect();
    rejection_ages.sort();
    log.append(
        sponsor,
        Body::Admit {
            proposal,
            age_ms: WINDOW_MS,
            rejection_ages,
        },
    )
    .unwrap()
}

#[test]
fn a_rejection_keeps_them_out_until_the_group_overrides_it() {
    // Five members: overriding needs 3 of the 4 who did not reject.
    let (mut log, p) = group_of(5, true, true);
    let x = id(20);
    let proposal = propose(&mut log, &p[0], &x);
    approve(&mut log, &p[2], proposal);
    let r = reject(&mut log, &p[1], proposal, "Saw them with the police");

    let blocked = admit_after(&mut log, &p[0], proposal, &[r]);
    assert_eq!(log.is_valid(&blocked), Some(false));

    // The rejecter cannot override their own rejection.
    let own = override_vote(&mut log, &p[1], proposal, r);
    assert_eq!(log.is_valid(&own), Some(false));

    override_vote(&mut log, &p[0], proposal, r);
    override_vote(&mut log, &p[2], proposal, r);
    let two = admit_after(&mut log, &p[0], proposal, &[r]);
    assert_eq!(log.is_valid(&two), Some(false), "2 of 4 is not enough");

    override_vote(&mut log, &p[3], proposal, r);
    let admitted = admit_after(&mut log, &p[0], proposal, &[r]);
    assert_eq!(log.is_valid(&admitted), Some(true));
    let state = log.state().unwrap();
    assert!(state.is_active(&x.key()));
    assert_eq!(
        state.join_proposals[&proposal].objections[0].reason,
        "Saw them with the police"
    );
}

#[test]
fn the_override_has_its_own_fresh_window() {
    let (mut log, p) = group_of(3, true, true);
    let x = id(20);
    let proposal = propose(&mut log, &p[0], &x);
    approve(&mut log, &p[2], proposal);
    let r = reject(&mut log, &p[1], proposal, "No");

    let late = log
        .append(
            &p[2],
            Body::Override {
                proposal,
                rejection: r,
                age_ms: WINDOW_MS,
            },
        )
        .unwrap();
    assert_eq!(log.is_valid(&late), Some(false));

    override_vote(&mut log, &p[0], proposal, r);
    override_vote(&mut log, &p[2], proposal, r);
    // Overridden, but the rejection's window has not passed yet, so others
    // could still reject again.
    let early = log
        .append(
            &p[0],
            Body::Admit {
                proposal,
                age_ms: WINDOW_MS,
                rejection_ages: vec![(r, WINDOW_MS - 1)],
            },
        )
        .unwrap();
    assert_eq!(log.is_valid(&early), Some(false));
    let admitted = admit_after(&mut log, &p[0], proposal, &[r]);
    assert_eq!(log.is_valid(&admitted), Some(true));
}

#[test]
fn rejecting_again_needs_its_own_override() {
    let (mut log, p) = group_of(3, true, true);
    let x = id(20);
    let proposal = propose(&mut log, &p[0], &x);
    approve(&mut log, &p[2], proposal);
    let first = reject(&mut log, &p[1], proposal, "No");
    override_vote(&mut log, &p[0], proposal, first);
    override_vote(&mut log, &p[2], proposal, first);
    let again = reject(&mut log, &p[1], proposal, "Still no, and here is why");

    let only_first = admit_after(&mut log, &p[0], proposal, &[first]);
    assert_eq!(log.is_valid(&only_first), Some(false));
    let both = admit_after(&mut log, &p[0], proposal, &[first, again]);
    assert_eq!(log.is_valid(&both), Some(false));

    override_vote(&mut log, &p[0], proposal, again);
    override_vote(&mut log, &p[2], proposal, again);
    let admitted = admit_after(&mut log, &p[0], proposal, &[first, again]);
    assert_eq!(log.is_valid(&admitted), Some(true));
}

#[test]
fn normal_groups_have_no_objections() {
    let (mut log, a, b) = normal_group(true);
    let proposal = propose(&mut log, &a, &id(3));
    let r = reject(&mut log, &b, proposal, "No");
    assert_eq!(log.is_valid(&r), Some(false));
    let overriding = override_vote(&mut log, &a, proposal, r);
    assert_eq!(log.is_valid(&overriding), Some(false));
    // The sponsor approves and admits.
    approve(&mut log, &a, proposal);
    let admitted = admit(&mut log, &a, proposal);
    assert_eq!(log.is_valid(&admitted), Some(true));
}
