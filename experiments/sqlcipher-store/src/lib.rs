//! Gate 1 feasibility check for `docs/proposals/local-storage-v1.md` section 5.
//!
//! Opens SQLCipher with a raw 32-byte key, applies the pinned settings, and measures
//! frames appended and peak extra space per operation against the estimate rule:
//! "the sum of the maximum encoded sizes of the rows it will write or delete, each
//! rounded up to whole pages of the pinned page size, doubled, plus 16 pages".
//!
//! Test harness only. Not linked into the app. Not reviewed.

use std::fs::{self, File};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicI64, AtomicU64, Ordering};
use std::sync::Arc;
use std::time::Duration;

use rusqlite::Connection;

// LibTomCrypt's Windows random source (CryptGenRandom) lives in advapi32.
#[cfg(windows)]
#[link(name = "advapi32")]
unsafe extern "C" {}

pub const PAGE: usize = 4096;
/// WAL file header, and per-frame header, in bytes (SQLite file format).
pub const WAL_HEADER: u64 = 32;
pub const FRAME_HEADER: u64 = 24;
pub const FRAME_BYTES: u64 = PAGE as u64 + FRAME_HEADER;

pub const KIB: usize = 1024;
pub const SESSION_MAX: usize = 512 * KIB;
pub const ENVELOPE_MAX: usize = 64 * KIB;

pub fn hex(b: &[u8]) -> String {
    b.iter().map(|x| format!("{x:02x}")).collect()
}

/// Pages needed for `bytes`, rounded up.
pub fn pages(bytes: usize) -> usize {
    bytes.div_ceil(PAGE)
}

/// The estimate rule under test: sum of each row's maximum rounded up to pages,
/// doubled, plus 16 pages for indexes and accounting.
pub fn estimate(row_maxima: &[usize]) -> usize {
    2 * row_maxima.iter().map(|&b| pages(b)).sum::<usize>() + 16
}

/// Opens (or creates) the database with a raw key and the pinned settings.
/// `PRAGMA key = "x'<64 hex>'"` is SQLCipher's raw-key form: no passphrase KDF.
pub fn open(path: &Path, key: &[u8; 32]) -> rusqlite::Result<Connection> {
    let conn = Connection::open(path)?;
    conn.execute_batch(&format!("PRAGMA key = \"x'{}'\";", hex(key)))?;
    // Must come after the key and before the first read or write of the file.
    conn.execute_batch(&format!("PRAGMA cipher_page_size = {PAGE};"))?;
    conn.execute_batch("PRAGMA cipher_memory_security = ON;")?;
    quiet(&conn);
    // auto_vacuum only takes effect on a database that has no tables yet.
    conn.execute_batch("PRAGMA auto_vacuum = INCREMENTAL;")?;
    let mode: String = conn.query_row("PRAGMA journal_mode = WAL;", [], |r| r.get(0))?;
    assert_eq!(mode.to_lowercase(), "wal", "journal_mode did not switch to WAL");
    conn.execute_batch("PRAGMA synchronous = FULL; PRAGMA secure_delete = ON;")?;
    Ok(conn)
}

/// Opens an existing database the same way but without changing its mode.
pub fn reopen(path: &Path, key: &[u8; 32]) -> rusqlite::Result<Connection> {
    let conn = Connection::open(path)?;
    conn.execute_batch(&format!("PRAGMA key = \"x'{}'\";", hex(key)))?;
    quiet(&conn);
    // With a wrong key or a damaged first page, some of these can already fail; the failure
    // is then reported by the caller's first real read, which is what the tests look at.
    let _ = conn.execute_batch(&format!("PRAGMA cipher_page_size = {PAGE};"));
    let _ = conn.execute_batch("PRAGMA cipher_memory_security = ON;");
    let _ = conn.execute_batch("PRAGMA synchronous = FULL; PRAGMA secure_delete = ON;");
    Ok(conn)
}

/// SQLCipher logs every failed page check to stderr, which floods the tamper tests.
/// Set YAP_SQLCIPHER_LOG=1 to see it.
fn quiet(conn: &Connection) {
    if std::env::var_os("YAP_SQLCIPHER_LOG").is_none() {
        let _ = conn.query_row("PRAGMA cipher_log_level = NONE", [], |_| Ok(()));
    }
}

fn pragma_text(conn: &Connection, sql: &str) -> String {
    match conn.query_row(sql, [], |r| r.get::<_, rusqlite::types::Value>(0)) {
        Ok(rusqlite::types::Value::Text(t)) => t,
        Ok(rusqlite::types::Value::Integer(i)) => i.to_string(),
        Ok(v) => format!("{v:?}"),
        Err(e) => format!("error: {e}"),
    }
}

/// Every setting the gate asks to be recorded, read back from the open connection.
pub fn settings_report(conn: &Connection) -> Vec<(String, String)> {
    let mut rows = Vec::new();
    for (label, sql) in [
        ("sqlite_version()", "SELECT sqlite_version()"),
        ("cipher_version", "PRAGMA cipher_version"),
        ("cipher_provider", "PRAGMA cipher_provider"),
        ("cipher_provider_version", "PRAGMA cipher_provider_version"),
        ("cipher_page_size", "PRAGMA cipher_page_size"),
        ("page_size", "PRAGMA page_size"),
        ("cipher_memory_security", "PRAGMA cipher_memory_security"),
        ("journal_mode", "PRAGMA journal_mode"),
        ("synchronous (2 = FULL)", "PRAGMA synchronous"),
        ("auto_vacuum (2 = INCREMENTAL)", "PRAGMA auto_vacuum"),
        ("secure_delete", "PRAGMA secure_delete"),
        ("cipher_hmac_algorithm", "PRAGMA cipher_hmac_algorithm"),
        ("cipher_kdf_algorithm", "PRAGMA cipher_kdf_algorithm"),
        ("cipher_kdf_iter", "PRAGMA cipher_kdf_iter"),
        ("cipher_use_hmac", "PRAGMA cipher_use_hmac"),
        ("cipher_plaintext_header_size", "PRAGMA cipher_plaintext_header_size"),
        ("cache_spill", "PRAGMA cache_spill"),
        ("cache_size", "PRAGMA cache_size"),
        ("wal_autocheckpoint", "PRAGMA wal_autocheckpoint"),
        ("temp_store (0 default, 1 file, 2 memory)", "PRAGMA temp_store"),
    ] {
        rows.push((label.to_string(), pragma_text(conn, sql)));
    }
    let mut stmt = conn.prepare("PRAGMA compile_options").unwrap();
    let opts: Vec<String> = stmt.query_map([], |r| r.get(0)).unwrap().map(|r| r.unwrap()).collect();
    for o in opts.iter().filter(|o| {
        ["THREADSAFE", "TEMP_STORE", "HAS_CODEC", "SECURE_DELETE", "DEFAULT_WAL", "OMIT_", "MEMSTATUS"]
            .iter()
            .any(|k| o.contains(k))
    }) {
        rows.push(("compile_option".to_string(), o.clone()));
    }
    rows
}

// ---------------------------------------------------------------------------
// Measurement
// ---------------------------------------------------------------------------

/// Deterministic bytes that do not compress or repeat in a way that matters.
pub fn blob(len: usize, seed: u64) -> Vec<u8> {
    let mut x = seed.wrapping_mul(0x9E37_79B9_7F4A_7C15) | 1;
    (0..len)
        .map(|_| {
            x ^= x << 13;
            x ^= x >> 7;
            x ^= x << 17;
            (x >> 24) as u8
        })
        .collect()
}

fn file_len(p: &Path) -> u64 {
    File::open(p).and_then(|f| f.metadata()).map(|m| m.len()).unwrap_or(0)
}

/// Sizes of the database, its log and wal-index, and any other file next to them.
#[derive(Clone, Copy, Debug, Default)]
pub struct Sizes {
    pub db: u64,
    pub wal: u64,
    pub shm: u64,
    pub other: u64,
}

impl Sizes {
    pub fn total(&self) -> u64 {
        self.db + self.wal + self.shm + self.other
    }
}

pub fn sizes(dir: &Path, db: &Path) -> Sizes {
    let name = db.file_name().unwrap().to_string_lossy().to_string();
    let mut s = Sizes { db: file_len(db), ..Default::default() };
    s.wal = file_len(&dir.join(format!("{name}-wal")));
    s.shm = file_len(&dir.join(format!("{name}-shm")));
    if let Ok(rd) = fs::read_dir(dir) {
        for e in rd.flatten() {
            let n = e.file_name().to_string_lossy().to_string();
            if n != name && n != format!("{name}-wal") && n != format!("{name}-shm") {
                s.other += file_len(&e.path());
            }
        }
    }
    s
}

/// Records the largest size seen of each file, from samples taken by the harness (after
/// each statement and at commit) and by a poller thread (every ~100 microseconds, to catch
/// peaks inside one statement). The two are kept separate so the report can say which saw
/// the peak.
#[derive(Default)]
pub struct Peaks {
    pub db: AtomicU64,
    pub wal: AtomicU64,
    pub shm: AtomicU64,
    pub other: AtomicU64,
    pub total: AtomicU64,
}

impl Peaks {
    pub fn note(&self, s: &Sizes) {
        self.db.fetch_max(s.db, Ordering::Relaxed);
        self.wal.fetch_max(s.wal, Ordering::Relaxed);
        self.shm.fetch_max(s.shm, Ordering::Relaxed);
        self.other.fetch_max(s.other, Ordering::Relaxed);
        self.total.fetch_max(s.total(), Ordering::Relaxed);
    }
}

/// How a run is configured. `cache_size_pages` forces spilling when small.
#[derive(Clone, Copy, Debug)]
pub struct Config {
    pub cache_spill: bool,
    /// `None` leaves SQLite's default (-2000 = about 2 MB).
    pub cache_size_pages: Option<i64>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Variant {
    /// A new database: the rows used by the case, a few small rows, no free pages.
    Fresh,
    /// The same, after many inserts and deletes, so there are free pages to reuse.
    Used,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Op {
    InsertSession,
    UpdateSession,
    DeleteSession,
    InsertEnvelope,
    TwoSessionsTxn,
    InsertTwoSessionsTxn,
    RolledBack,
}

impl Op {
    pub const ALL: [Op; 7] = [
        Op::InsertSession,
        Op::UpdateSession,
        Op::DeleteSession,
        Op::InsertEnvelope,
        Op::TwoSessionsTxn,
        Op::InsertTwoSessionsTxn,
        Op::RolledBack,
    ];
    pub fn label(self) -> &'static str {
        match self {
            Op::InsertSession => "insert 512 KiB blob row",
            Op::UpdateSession => "update 512 KiB blob row",
            Op::DeleteSession => "delete 512 KiB blob row",
            Op::InsertEnvelope => "insert 64 KiB envelope row",
            Op::TwoSessionsTxn => "txn: update two 512 KiB rows",
            Op::InsertTwoSessionsTxn => "txn: insert two 512 KiB rows",
            Op::RolledBack => "txn: update two 512 KiB rows, ROLLBACK",
        }
    }
    /// Maximum encoded size of each row the operation writes or deletes.
    pub fn row_maxima(self) -> Vec<usize> {
        match self {
            Op::InsertSession | Op::UpdateSession | Op::DeleteSession => vec![SESSION_MAX],
            Op::InsertEnvelope => vec![ENVELOPE_MAX],
            Op::TwoSessionsTxn | Op::InsertTwoSessionsTxn | Op::RolledBack => vec![SESSION_MAX; 2],
        }
    }
    pub fn expects_commit(self) -> bool {
        self != Op::RolledBack
    }
}

#[derive(Debug, Clone)]
pub struct Outcome {
    pub op: Op,
    pub variant: Variant,
    pub config: Config,
    pub estimate_pages: usize,
    /// Number of times the WAL hook ran (commit cases need exactly 1).
    pub hook_calls: i64,
    /// WAL frames the hook reported after the commit (cumulative; the log began empty).
    pub frames_by_hook: i64,
    /// The same figure worked out from the log file's length, as a cross-check.
    pub frames_by_file: i64,
    /// Peak of (database + log + wal-index + other files) above the starting total, in pages,
    /// from samples taken after each statement and at commit.
    pub peak_extra_pages_sampled: i64,
    /// The same with the poller thread's samples too.
    pub peak_extra_pages_polled: i64,
    pub peak_wal_bytes: u64,
    pub peak_other_bytes: u64,
    pub log_len_at_start: u64,
    pub checkpoint_result: (i64, i64, i64),
    pub frames_ok: bool,
    pub peak_ok: bool,
    pub hook_ok: bool,
    pub cross_check_ok: bool,
}

impl Outcome {
    pub fn pass(&self) -> bool {
        self.frames_ok && self.peak_ok && self.hook_ok && self.cross_check_ok
    }
}

const SCHEMA: &str = "
    CREATE TABLE kv(id INTEGER PRIMARY KEY, kind INTEGER NOT NULL, data BLOB NOT NULL);
    CREATE INDEX kv_kind ON kv(kind);
";

/// Builds the starting database for a run (ids 1 and 2 are the two 512 KiB rows).
fn prepare(path: &Path, key: &[u8; 32], variant: Variant) {
    let conn = open(path, key).unwrap();
    conn.execute_batch(SCHEMA).unwrap();
    for (id, seed) in [(1i64, 11u64), (2, 12)] {
        conn.execute(
            "INSERT INTO kv(id, kind, data) VALUES (?1, 1, ?2)",
            rusqlite::params![id, blob(SESSION_MAX, seed)],
        )
        .unwrap();
    }
    for i in 0..5 {
        conn.execute(
            "INSERT INTO kv(kind, data) VALUES (9, ?1)",
            rusqlite::params![blob(300, 100 + i)],
        )
        .unwrap();
    }
    if variant == Variant::Used {
        // Churn: add and remove envelope-sized and session-sized rows so that the file has
        // a freelist of several hundred pages and used pages are scattered.
        for i in 0..24 {
            conn.execute(
                "INSERT INTO kv(id, kind, data) VALUES (?1, 2, ?2)",
                rusqlite::params![1000 + i as i64, blob(ENVELOPE_MAX, 200 + i)],
            )
            .unwrap();
        }
        for i in 0..3 {
            conn.execute(
                "INSERT INTO kv(id, kind, data) VALUES (?1, 3, ?2)",
                rusqlite::params![2000 + i as i64, blob(SESSION_MAX, 300 + i)],
            )
            .unwrap();
        }
        conn.execute("DELETE FROM kv WHERE kind = 2 AND id % 3 != 0", []).unwrap();
        conn.execute("DELETE FROM kv WHERE kind = 3 AND id = 2001", []).unwrap();
    }
    let _ = conn.query_row("PRAGMA wal_checkpoint(TRUNCATE)", [], |_| Ok(()));
    conn.close().map_err(|(_, e)| e).unwrap();
}

/// One run: its own directory, its own connection, a truncating checkpoint first.
pub fn run_case(work: &Path, key: &[u8; 32], op: Op, variant: Variant, config: Config) -> Outcome {
    let dir = work.join(format!(
        "{:?}-{:?}-spill{}-cache{:?}",
        op, variant, config.cache_spill as u8, config.cache_size_pages
    ));
    let _ = fs::remove_dir_all(&dir);
    fs::create_dir_all(&dir).unwrap();
    let path = dir.join("store.db");
    prepare(&path, key, variant);

    let mut conn = reopen(&path, key).unwrap();
    conn.execute_batch(&format!("PRAGMA cache_spill = {};", if config.cache_spill { "ON" } else { "OFF" }))
        .unwrap();
    if let Some(n) = config.cache_size_pages {
        conn.execute_batch(&format!("PRAGMA cache_size = {n};")).unwrap();
    }
    // The truncating checkpoint, on this very connection, so the log starts empty.
    let checkpoint_result = conn
        .query_row("PRAGMA wal_checkpoint(TRUNCATE)", [], |r| {
            Ok((r.get::<_, i64>(0)?, r.get::<_, i64>(1)?, r.get::<_, i64>(2)?))
        })
        .unwrap();
    let log_len_at_start = sizes(&dir, &path).wal;

    // The hook is an observer that always returns success.
    let calls = Arc::new(AtomicI64::new(0));
    let frames = Arc::new(AtomicI64::new(0));
    {
        let (c, f) = (calls.clone(), frames.clone());
        conn.wal_hook(Some(move |_: &mut rusqlite::hooks::Wal, n: std::ffi::c_int| {
            c.fetch_add(1, Ordering::SeqCst);
            f.store(n as i64, Ordering::SeqCst);
            Ok(())
        }))
        .unwrap();
    }

    let baseline = sizes(&dir, &path);
    let sampled = Arc::new(Peaks::default());
    let polled = Arc::new(Peaks::default());
    sampled.note(&baseline);
    polled.note(&baseline);

    let stop = Arc::new(AtomicBool::new(false));
    let poller = {
        let (stop, polled, dir, path) = (stop.clone(), polled.clone(), dir.clone(), path.clone());
        std::thread::spawn(move || {
            while !stop.load(Ordering::Relaxed) {
                polled.note(&sizes(&dir, &path));
                std::thread::sleep(Duration::from_micros(100));
            }
        })
    };
    let sample = |conn_label: &str| {
        let _ = conn_label;
        let s = sizes(&dir, &path);
        sampled.note(&s);
        polled.note(&s);
    };

    let next_seed = 900u64;
    match op {
        Op::InsertSession => {
            conn.execute("INSERT INTO kv(kind, data) VALUES (1, ?1)", [blob(SESSION_MAX, next_seed)]).unwrap();
            sample("statement");
        }
        Op::UpdateSession => {
            conn.execute("UPDATE kv SET data = ?1 WHERE id = 1", [blob(SESSION_MAX, next_seed)]).unwrap();
            sample("statement");
        }
        Op::DeleteSession => {
            conn.execute("DELETE FROM kv WHERE id = 1", []).unwrap();
            sample("statement");
        }
        Op::InsertEnvelope => {
            conn.execute("INSERT INTO kv(kind, data) VALUES (2, ?1)", [blob(ENVELOPE_MAX, next_seed)]).unwrap();
            sample("statement");
        }
        Op::TwoSessionsTxn | Op::RolledBack => {
            conn.execute_batch("BEGIN IMMEDIATE").unwrap();
            sample("begin");
            for id in [1, 2] {
                conn.execute("UPDATE kv SET data = ?1 WHERE id = ?2", rusqlite::params![blob(SESSION_MAX, next_seed + id as u64), id]).unwrap();
                sample("statement");
            }
            if op == Op::RolledBack {
                conn.execute_batch("ROLLBACK").unwrap();
            } else {
                conn.execute_batch("COMMIT").unwrap();
            }
            sample("end");
        }
        Op::InsertTwoSessionsTxn => {
            conn.execute_batch("BEGIN IMMEDIATE").unwrap();
            sample("begin");
            for i in 0..2u64 {
                conn.execute("INSERT INTO kv(kind, data) VALUES (1, ?1)", [blob(SESSION_MAX, next_seed + i)]).unwrap();
                sample("statement");
            }
            conn.execute_batch("COMMIT").unwrap();
            sample("end");
        }
    }
    sample("after");
    stop.store(true, Ordering::Relaxed);
    poller.join().unwrap();

    let end = sizes(&dir, &path);
    let hook_calls = calls.load(Ordering::SeqCst);
    let frames_by_hook = frames.load(Ordering::SeqCst);
    let frames_by_file = if end.wal >= WAL_HEADER { ((end.wal - WAL_HEADER) / FRAME_BYTES) as i64 } else { 0 };
    let est = estimate(&op.row_maxima());
    let to_pages = |bytes: u64| ((bytes as i64 + PAGE as i64 - 1) / PAGE as i64).max(0);
    let extra = |p: &Peaks| to_pages(p.total.load(Ordering::Relaxed).saturating_sub(baseline.total()));
    let peak_extra_pages_sampled = extra(&sampled);
    let peak_extra_pages_polled = extra(&polled);

    let hook_ok = if op.expects_commit() { hook_calls == 1 } else { hook_calls == 0 };
    // For a rolled-back transaction nothing was committed, so the hook gives no frame count
    // and the bound that matters is the peak. For a commit, the hook count and the log length
    // must agree, which also shows no checkpoint reset the log in between.
    let cross_check_ok = if op.expects_commit() { frames_by_hook == frames_by_file } else { true };
    let frames_ok = if op.expects_commit() { frames_by_hook as usize <= est } else { true };
    let peak_ok = (peak_extra_pages_polled as usize) <= est && (peak_extra_pages_sampled as usize) <= est;

    drop(conn);
    let _ = fs::remove_dir_all(&dir);
    Outcome {
        op,
        variant,
        config,
        estimate_pages: est,
        hook_calls,
        frames_by_hook,
        frames_by_file,
        peak_extra_pages_sampled,
        peak_extra_pages_polled,
        peak_wal_bytes: polled.wal.load(Ordering::Relaxed),
        peak_other_bytes: polled.other.load(Ordering::Relaxed),
        log_len_at_start,
        checkpoint_result,
        frames_ok,
        peak_ok,
        hook_ok,
        cross_check_ok,
    }
}

pub fn scratch(name: &str) -> PathBuf {
    let p = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("target").join("gate1-work").join(name);
    let _ = fs::remove_dir_all(&p);
    fs::create_dir_all(&p).unwrap();
    p
}

/// Informational, no pass/fail rule: frames written by `PRAGMA incremental_vacuum(256)` after a
/// 512 KiB row is deleted (the "checkpoint and reclamation" class in section 5).
pub fn incremental_vacuum_run(work: &Path, key: &[u8; 32]) -> (i64, i64, i64) {
    let dir = work.join("incvac");
    let _ = fs::remove_dir_all(&dir);
    fs::create_dir_all(&dir).unwrap();
    let path = dir.join("store.db");
    prepare(&path, key, Variant::Used);
    {
        let conn = reopen(&path, key).unwrap();
        conn.execute_batch("DELETE FROM kv WHERE id IN (1, 2, 2000, 2002)").unwrap();
        conn.query_row("PRAGMA wal_checkpoint(TRUNCATE)", [], |_| Ok(())).unwrap();
    }
    let mut conn = reopen(&path, key).unwrap();
    let free_before: i64 = conn.query_row("PRAGMA freelist_count", [], |r| r.get(0)).unwrap();
    conn.query_row("PRAGMA wal_checkpoint(TRUNCATE)", [], |_| Ok(())).unwrap();
    let frames = Arc::new(AtomicI64::new(0));
    let f = frames.clone();
    conn.wal_hook(Some(move |_: &mut rusqlite::hooks::Wal, n: std::ffi::c_int| {
        f.store(n as i64, Ordering::SeqCst);
        Ok(())
    }))
    .unwrap();
    {
        // The pragma frees one page per step, so it has to be stepped to the end.
        let mut stmt = conn.prepare("PRAGMA incremental_vacuum(256)").unwrap();
        let mut rows = stmt.query([]).unwrap();
        while rows.next().unwrap().is_some() {}
    }
    let free_after: i64 = conn.query_row("PRAGMA freelist_count", [], |r| r.get(0)).unwrap();
    (free_before, free_after, frames.load(Ordering::SeqCst))
}
