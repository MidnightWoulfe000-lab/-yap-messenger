mod common;

use common::*;
use yap_groups::*;

#[test]
fn founders_join_without_approval() {
    let (log, a, b) = new_group(true, true);
    let state = log.state().unwrap();
    assert_eq!(state.active_count(), 2);
    assert_eq!(state.members[&a.key()].sponsor, None);
    assert_eq!(state.members[&b.key()].sponsor, Some(a.key()));
}

#[test]
fn window_must_be_one_to_sixty_minutes() {
    assert!(Settings::new(0, true, true, true, true).is_err());
    assert!(Settings::new(61, true, true, true, true).is_err());
    assert_eq!(
        Settings::new(60, true, true, true, true)
            .unwrap()
            .objection_window_ms(),
        3_600_000
    );
}

#[test]
fn third_member_needs_an_approval_and_the_window() {
    let (mut log, a, b) = new_group(true, true);
    let c = id(3);
    let p = propose(&mut log, &a, &c);

    // No approval yet: admission is invalid.
    let early = admit(&mut log, &a, p);
    assert_eq!(log.is_valid(&early), Some(false));

    approve(&mut log, &b, p);
    // Window not over on the sponsor's copy.
    let too_soon = log
        .append(
            &a,
            Body::Admit {
                proposal: p,
                age_ms: WINDOW_MS - 1,
                rejection_ages: vec![],
            },
        )
        .unwrap();
    assert_eq!(log.is_valid(&too_soon), Some(false));

    let ok = admit(&mut log, &a, p);
    assert_eq!(log.is_valid(&ok), Some(true));
    let state = log.state().unwrap();
    assert!(state.is_active(&c.key()));
    assert_eq!(state.members[&c.key()].sponsor, Some(a.key()));
}

#[test]
fn only_the_sponsor_can_admit() {
    let (mut log, a, b) = new_group(true, true);
    let p = propose(&mut log, &a, &id(3));
    approve(&mut log, &b, p);
    let wrong = admit(&mut log, &b, p);
    assert_eq!(log.is_valid(&wrong), Some(false));
}

#[test]
fn any_objection_blocks_admission() {
    let (mut log, a, b) = new_group(true, true);
    let c = id(3);
    let p = propose(&mut log, &a, &c);
    approve(&mut log, &a, p);
    let o = log
        .append(
            &b,
            Body::Object {
                electorate: everyone(&log),
                proposal: p,
                age_ms: EARLY,
                reason: "Not someone I know".into(),
            },
        )
        .unwrap();
    assert_eq!(log.is_valid(&o), Some(true));
    let admitted = admit(&mut log, &a, p);
    assert_eq!(log.is_valid(&admitted), Some(false));
    let state = log.state().unwrap();
    assert!(!state.members.contains_key(&c.key()));
    assert_eq!(
        state.join_proposals[&p].objections[0].reason,
        "Not someone I know"
    );
}

#[test]
fn objection_needs_a_reason() {
    let (log, _, b) = new_group(true, true);
    let result = Event::create(
        &b,
        log.group(),
        log.heads(),
        Body::Object {
            electorate: everyone(&log),
            proposal: [0; 32],
            age_ms: EARLY,
            reason: "   ".into(),
        },
    );
    assert!(result.is_err());
}

#[test]
fn votes_after_the_window_do_not_count() {
    let (mut log, a, b) = new_group(true, true);
    let p = propose(&mut log, &a, &id(3));
    let late = log
        .append(
            &b,
            Body::Object {
                electorate: everyone(&log),
                proposal: p,
                age_ms: WINDOW_MS,
                reason: "late".into(),
            },
        )
        .unwrap();
    assert_eq!(log.is_valid(&late), Some(false));
    approve(&mut log, &a, p);
    let admitted = admit(&mut log, &a, p);
    assert_eq!(log.is_valid(&admitted), Some(true));
}

#[test]
fn sponsor_approval_counts_up_to_four_members() {
    let (mut log, people) = group_of(4, true, true);
    let p = propose(&mut log, &people[0], &id(5));
    approve(&mut log, &people[0], p);
    let admitted = admit(&mut log, &people[0], p);
    assert_eq!(log.is_valid(&admitted), Some(true));
}

#[test]
fn independent_approval_from_five_members() {
    let (mut log, people) = group_of(5, true, true);
    let p = propose(&mut log, &people[0], &id(6));
    approve(&mut log, &people[0], p);
    let sponsor_only = admit(&mut log, &people[0], p);
    assert_eq!(log.is_valid(&sponsor_only), Some(false));
    approve(&mut log, &people[2], p);
    let admitted = admit(&mut log, &people[0], p);
    assert_eq!(log.is_valid(&admitted), Some(true));
}

#[test]
fn independent_approval_switched_off() {
    let (mut log, people) = group_of(5, false, true);
    let p = propose(&mut log, &people[0], &id(6));
    approve(&mut log, &people[0], p);
    let admitted = admit(&mut log, &people[0], p);
    assert_eq!(log.is_valid(&admitted), Some(true));
}

#[test]
fn revocation_thresholds() {
    // Up to 5: majority of everyone except the target. From 6: three.
    let expected = [(2, 1), (3, 2), (4, 2), (5, 3), (6, 3), (10, 3), (50, 3)];
    for (size, needed) in expected {
        assert_eq!(revocation_threshold(size), needed, "group of {size}");
    }
}

#[test]
fn five_member_group_needs_three_of_the_other_four() {
    let (mut log, p) = group_of(5, true, true);
    let target = p[4].key();
    let r = log
        .append(
            &p[0],
            Body::ProposeRevoke {
                electorate: everyone(&log),
                target,
            },
        )
        .unwrap();
    log.append(&p[1], Body::VoteRevoke { proposal: r }).unwrap();
    assert!(log.state().unwrap().is_active(&target));

    // The target cannot vote.
    let own = log.append(&p[4], Body::VoteRevoke { proposal: r }).unwrap();
    assert_eq!(log.is_valid(&own), Some(false));

    log.append(&p[2], Body::VoteRevoke { proposal: r }).unwrap();
    let state = log.state().unwrap();
    assert_eq!(
        state.members[&target].status,
        MemberStatus::Revoked { revocation: r }
    );
}

#[test]
fn two_member_group_other_member_alone_revokes() {
    let (mut log, a, b) = new_group(true, true);
    let r = log
        .append(
            &a,
            Body::ProposeRevoke {
                electorate: everyone(&log),
                target: b.key(),
            },
        )
        .unwrap();
    assert!(!log.state().unwrap().is_active(&b.key()));
    assert!(log.state().unwrap().revocations[&r].carried);
}

#[test]
fn group_revoking_switched_off() {
    let (mut log, a, b) = new_group(true, false);
    let r = log
        .append(
            &a,
            Body::ProposeRevoke {
                electorate: everyone(&log),
                target: b.key(),
            },
        )
        .unwrap();
    assert_eq!(log.is_valid(&r), Some(false));
    assert!(log.state().unwrap().is_active(&b.key()));
}

fn revoke(log: &mut Log, proposer: &Identity, voter: &Identity, target: &Identity) -> Hash {
    let r = log
        .append(
            proposer,
            Body::ProposeRevoke {
                electorate: everyone(log),
                target: target.key(),
            },
        )
        .unwrap();
    log.append(voter, Body::VoteRevoke { proposal: r }).unwrap();
    assert!(!log.state().unwrap().is_active(&target.key()));
    r
}

#[test]
fn revoked_member_cannot_act() {
    let (mut log, p) = group_of(3, true, true);
    revoke(&mut log, &p[0], &p[1], &p[2]);
    let after = propose(&mut log, &p[2], &id(9));
    assert_eq!(log.is_valid(&after), Some(false));
}

#[test]
fn active_member_cannot_be_proposed() {
    let (mut log, p) = group_of(3, true, true);
    let again = propose(&mut log, &p[0], &p[2]);
    assert_eq!(log.is_valid(&again), Some(false));
}

#[test]
fn revoked_member_can_come_back_through_a_new_proposal() {
    let (mut log, p) = group_of(3, true, true);
    let target = &p[2];
    let old_phone = DeviceKey([5; 32]);
    let lost_phone = DeviceKey([6; 32]);
    for device in [old_phone, lost_phone] {
        log.append(target, Body::AddDevice { device }).unwrap();
    }
    log.append(target, Body::RevokeOwnDevice { device: lost_phone })
        .unwrap();
    let r = revoke(&mut log, &p[0], &p[1], target);

    // The normal rules apply: an objection still blocks.
    let blocked = propose(&mut log, &p[1], target);
    log.append(
        &p[0],
        Body::Object {
            electorate: everyone(&log),
            proposal: blocked,
            age_ms: EARLY,
            reason: "Not yet".into(),
        },
    )
    .unwrap();
    let refused = admit(&mut log, &p[1], blocked);
    assert_eq!(log.is_valid(&refused), Some(false));

    add(&mut log, &p[1], &p[0], target);
    let state = log.state().unwrap();
    let member = &state.members[&target.key()];
    assert!(state.is_active(&target.key()));
    assert_eq!(member.sponsor, Some(p[1].key()));
    assert_eq!(member.earlier_terms.len(), 1);
    assert_eq!(member.earlier_terms[0].revocation, r);
    assert_eq!(member.earlier_terms[0].sponsor, Some(p[0].key()));

    // Their old phone works again; the one they revoked themselves does not.
    assert!(member.devices.contains(&old_phone));
    assert!(member.revoked_devices.contains(&lost_phone));
    let reuse = log
        .append(target, Body::AddDevice { device: lost_phone })
        .unwrap();
    assert_eq!(log.is_valid(&reuse), Some(false));
}

#[test]
fn a_proposal_from_an_earlier_absence_cannot_be_reused() {
    // While C was out, A proposed C twice and B approved both. C came back
    // through the first, then was revoked again. The second proposal belongs
    // to the earlier absence and must not let C straight back in.
    let (mut log, p) = group_of(3, true, true);
    let c = &p[2];
    revoke(&mut log, &p[0], &p[1], c);
    let first = propose(&mut log, &p[0], c);
    approve(&mut log, &p[1], first);
    let second = propose(&mut log, &p[0], c);
    approve(&mut log, &p[1], second);
    admit(&mut log, &p[0], first);
    assert!(log.state().unwrap().is_active(&c.key()));

    revoke(&mut log, &p[0], &p[1], c);
    let stale = admit(&mut log, &p[0], second);
    assert_eq!(log.is_valid(&stale), Some(false));
    assert!(!log.state().unwrap().is_active(&c.key()));
}

#[test]
fn self_device_revocation_needs_no_vote() {
    let (mut log, a, b) = new_group(true, true);
    let phone = DeviceKey([5; 32]);
    log.append(&a, Body::AddDevice { device: phone }).unwrap();
    let theirs = log
        .append(&b, Body::RevokeOwnDevice { device: phone })
        .unwrap();
    assert_eq!(log.is_valid(&theirs), Some(false));
    let mine = log
        .append(&a, Body::RevokeOwnDevice { device: phone })
        .unwrap();
    assert_eq!(log.is_valid(&mine), Some(true));
    let me = &log.state().unwrap().members[&a.key()];
    assert!(me.devices.is_empty() && me.revoked_devices.contains(&phone));
}

#[test]
fn newcomers_do_not_see_earlier_joins() {
    let (log, p) = group_of(4, true, true);
    let founder_view = log.visible_joins(&p[0].key()).unwrap();
    assert_eq!(founder_view.len(), 4);
    // Member 3 sees themselves and member 4, not the founders.
    let third_view: Vec<MemberKey> = log
        .visible_joins(&p[2].key())
        .unwrap()
        .iter()
        .map(|r| r.member)
        .collect();
    assert_eq!(third_view, vec![p[2].key(), p[3].key()]);
    let last_view = log.visible_joins(&p[3].key()).unwrap();
    assert_eq!(last_view.len(), 1);
}

#[test]
fn forged_tampered_and_foreign_events_are_rejected() {
    let (mut log, a, b) = new_group(true, true);
    let mut event = Event::create(
        &b,
        log.group(),
        log.heads(),
        Body::ProposeRevoke {
            electorate: everyone(&log),
            target: a.key(),
        },
    )
    .unwrap();

    let mut forged = event.clone();
    forged.author = a.key();
    assert_eq!(log.insert(forged), Err(Error::BadSignature));

    let mut bytes = event.encode();
    let last = bytes.len() - 70;
    bytes[last] ^= 1;
    assert!(Event::decode(&bytes).is_err());

    let foreign = Event::create(
        &b,
        GroupId([9; 16]),
        log.heads(),
        Body::ProposeRevoke {
            electorate: everyone(&log),
            target: a.key(),
        },
    )
    .unwrap();
    assert_eq!(log.insert(foreign), Err(Error::WrongGroup));

    event.parents = vec![[1; 32]];
    assert!(log.insert(event).is_err());
}

#[test]
fn unknown_parents_are_reported() {
    let (log, a, b) = new_group(true, true);
    let mut other = log.clone();
    let p = propose(&mut other, &a, &id(3));
    let child = Event::create(
        &b,
        log.group(),
        vec![p],
        Body::Approve {
            proposal: p,
            age_ms: EARLY,
        },
    )
    .unwrap();
    let mut log = log;
    assert_eq!(log.insert(child), Err(Error::MissingParents(vec![p])));
}

#[test]
fn events_round_trip_through_bytes() {
    let (log, _) = group_of(4, true, true);
    for event in log.events() {
        let decoded = Event::decode(&event.encode()).unwrap();
        assert_eq!(&decoded, event);
        assert_eq!(decoded.hash(), event.hash());
    }
}

#[test]
fn signing_twice_gives_one_event_hash() {
    // libsignal signatures are randomised; the hash must not depend on them.
    let (log, a, b) = new_group(true, true);
    let body = Body::ProposeRevoke {
        electorate: everyone(&log),
        target: b.key(),
    };
    let first = Event::create(&a, log.group(), log.heads(), body.clone()).unwrap();
    let second = Event::create(&a, log.group(), log.heads(), body).unwrap();
    assert_ne!(first.signature, second.signature);
    assert_eq!(first.hash(), second.hash());
}

#[test]
fn members_sign_with_their_libsignal_identity() {
    let pair = libsignal_protocol::IdentityKeyPair::generate(&mut rand::rng());
    let identity = Identity::from_key_pair(pair);
    assert_eq!(&identity.key().0[..], pair.public_key().public_key_bytes());
    let group = GroupId::random();
    assert!(JoinRequest::sign(&identity, group).verify());
}

#[test]
fn a_second_genesis_is_refused() {
    let (mut log, a, b) = new_group(true, true);
    let again = Event::create(
        &a,
        log.group(),
        vec![],
        Body::Genesis {
            settings: Settings::new(5, true, true, false, true).unwrap(),
            second: JoinRequest::sign(&b, log.group()),
        },
    )
    .unwrap();
    assert_eq!(log.insert(again), Err(Error::BadRoot));
}
