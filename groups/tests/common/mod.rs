#![allow(dead_code)]

use yap_groups::*;

pub const WINDOW_MS: u64 = 60_000;
pub const EARLY: u64 = 1_000;

pub fn id(n: u8) -> Identity {
    Identity::from_seed([n; 32])
}

pub fn group_id() -> GroupId {
    GroupId([7; 16])
}

/// A new group with founders `a` and `b`, a 1-minute objection window and
/// group scramble on.
pub fn new_group(independent_approval: bool, group_revoking: bool) -> (Log, Identity, Identity) {
    new_group_with(Settings::new(1, independent_approval, group_revoking, true).unwrap())
}

pub fn new_group_with(settings: Settings) -> (Log, Identity, Identity) {
    let (a, b) = (id(1), id(2));
    let group = group_id();
    let genesis = Event::create(
        &a,
        group,
        vec![],
        Body::Genesis {
            settings,
            second: JoinRequest::sign(&b, group),
        },
    )
    .unwrap();
    let mut log = Log::new(group);
    log.insert(genesis).unwrap();
    (log, a, b)
}

pub fn propose(log: &mut Log, sponsor: &Identity, candidate: &Identity) -> Hash {
    let request = JoinRequest::sign(candidate, log.group());
    log.append(sponsor, Body::Propose { request }).unwrap()
}

pub fn approve(log: &mut Log, who: &Identity, proposal: Hash) -> Hash {
    log.append(
        who,
        Body::Approve {
            proposal,
            age_ms: EARLY,
        },
    )
    .unwrap()
}

pub fn admit(log: &mut Log, sponsor: &Identity, proposal: Hash) -> Hash {
    log.append(
        sponsor,
        Body::Admit {
            proposal,
            age_ms: WINDOW_MS,
        },
    )
    .unwrap()
}

/// Full admission: `sponsor` proposes, `approver` approves, sponsor admits.
pub fn add(log: &mut Log, sponsor: &Identity, approver: &Identity, candidate: &Identity) {
    let p = propose(log, sponsor, candidate);
    approve(log, approver, p);
    let admitted = admit(log, sponsor, p);
    assert_eq!(
        log.is_valid(&admitted),
        Some(true),
        "admission should be valid"
    );
}

/// A group of `n` members (seeds 1..=n), each admitted with approval from
/// founder b.
pub fn group_of(n: u8, independent_approval: bool, group_revoking: bool) -> (Log, Vec<Identity>) {
    let (mut log, a, b) = new_group(independent_approval, group_revoking);
    for seed in 3..=n {
        let candidate = id(seed);
        add(&mut log, &a, &b, &candidate);
    }
    let mut people = vec![a, b];
    people.extend((3..=n).map(id));
    (log, people)
}

/// Copies every event `from` has that `to` lacks.
pub fn sync(from: &Log, to: &mut Log) {
    for event in from.events() {
        to.insert(event.clone()).unwrap();
    }
}
