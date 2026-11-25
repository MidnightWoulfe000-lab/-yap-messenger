//! Experimental native endpoint. Never expose a staged result before its
//! containing snapshot has been encrypted and atomically committed by the host.
pub mod bytes;
pub mod engine;
pub mod pairing;
pub mod store;
