//! Experimental message routing primitives. Not encrypted or production-ready.
use std::collections::{HashSet, VecDeque};

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Message {
    pub id: [u8; 16],
    pub payload: Vec<u8>,
    pub hops_remaining: u8,
}

pub struct Relay {
    seen: HashSet<[u8; 16]>,
    queue: VecDeque<Message>,
    capacity: usize,
}

impl Relay {
    pub fn new(capacity: usize) -> Self {
        Self { seen: HashSet::new(), queue: VecDeque::new(), capacity }
    }

    /// Accept a new message once, rejecting duplicates and full queues.
    /// The caller must provide a cryptographically random ID in production.
    pub fn receive(&mut self, message: Message) -> bool {
        if self.seen.contains(&message.id) || self.queue.len() >= self.capacity { return false; }
        self.seen.insert(message.id);
        self.queue.push_back(message);
        true
    }

    /// Return the next eligible message, consuming one hop.
    pub fn next_forward(&mut self) -> Option<Message> {
        while let Some(mut message) = self.queue.pop_front() {
            if message.hops_remaining == 0 { continue; }
            message.hops_remaining -= 1;
            return Some(message);
        }
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn relays_two_hops() {
        let msg = Message { id: [7; 16], payload: b"hello".to_vec(), hops_remaining: 3 };
        let mut a = Relay::new(8);
        let mut b = Relay::new(8);
        let mut c = Relay::new(8);
        assert!(a.receive(msg.clone()));
        assert!(!a.receive(msg));
        assert!(b.receive(a.next_forward().unwrap()));
        assert!(c.receive(b.next_forward().unwrap()));
        assert_eq!(c.next_forward().unwrap().payload, b"hello");
    }
    #[test]
    fn bounded_queue() {
        let mut relay = Relay::new(1);
        assert!(relay.receive(Message { id: [1; 16], payload: vec![], hops_remaining: 1 }));
        assert!(!relay.receive(Message { id: [2; 16], payload: vec![], hops_remaining: 1 }));
    }
}
