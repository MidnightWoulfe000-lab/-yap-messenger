use std::fmt;

/// Errors from decoding, inserting or authoring governance data.
///
/// A signed event that is well formed but breaks a governance rule is **not**
/// an error: it is stored and ignored, because other events may name it as a
/// parent. See [`crate::Log::is_valid`].
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Error {
    /// Bytes or fields that do not follow the encoding or its limits.
    Malformed(&'static str),
    /// A signature that does not verify.
    BadSignature,
    /// An event for a different group.
    WrongGroup,
    /// Parents that this log has not seen yet. Fetch them and retry.
    MissingParents(Vec<[u8; 32]>),
    /// The log has no genesis event yet.
    NoGenesis,
    /// A second genesis, or a parentless event that is not a genesis.
    BadRoot,
    /// A password that does not meet the rules, or a wrong current password.
    Password(&'static str),
}

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Error::Malformed(what) => write!(f, "malformed: {what}"),
            Error::BadSignature => write!(f, "signature does not verify"),
            Error::WrongGroup => write!(f, "event belongs to another group"),
            Error::MissingParents(p) => write!(f, "{} parent event(s) not yet known", p.len()),
            Error::NoGenesis => write!(f, "group has no genesis event"),
            Error::BadRoot => write!(f, "invalid root event"),
            Error::Password(what) => write!(f, "password: {what}"),
        }
    }
}

impl std::error::Error for Error {}

pub type Result<T> = std::result::Result<T, Error>;
