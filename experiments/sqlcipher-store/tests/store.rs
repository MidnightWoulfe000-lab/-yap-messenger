use std::fs;
use std::io::{Read, Seek, SeekFrom, Write};

use yap_sqlcipher_store::*;

const KEY: [u8; 32] = [0x42; 32];

fn setting(rows: &[(String, String)], name: &str) -> String {
    rows.iter().find(|(k, _)| k == name).map(|(_, v)| v.clone()).unwrap_or_else(|| panic!("no setting {name}"))
}

#[test]
fn pinned_settings_are_applied() {
    let dir = scratch("settings");
    let conn = open(&dir.join("s.db"), &KEY).unwrap();
    conn.execute_batch("CREATE TABLE t(x)").unwrap();
    let rows = settings_report(&conn);
    for (k, v) in &rows {
        println!("SETTING {k} = {v}");
    }
    assert_eq!(setting(&rows, "cipher_page_size"), "4096");
    assert_eq!(setting(&rows, "page_size"), "4096");
    assert_eq!(setting(&rows, "journal_mode"), "wal");
    assert_eq!(setting(&rows, "synchronous (2 = FULL)"), "2");
    assert_eq!(setting(&rows, "auto_vacuum (2 = INCREMENTAL)"), "2");
    assert_eq!(setting(&rows, "secure_delete"), "1");
    assert_eq!(setting(&rows, "cipher_memory_security"), "1");
    assert_eq!(setting(&rows, "cipher_provider"), "libtomcrypt");
}

#[test]
fn raw_key_round_trip_wrong_key_and_no_plaintext() {
    let dir = scratch("roundtrip");
    let path = dir.join("r.db");
    {
        let conn = open(&path, &KEY).unwrap();
        conn.execute_batch("CREATE TABLE t(x TEXT); INSERT INTO t VALUES ('needle-in-a-haystack-0123456789')").unwrap();
        conn.query_row("PRAGMA wal_checkpoint(TRUNCATE)", [], |_| Ok(())).unwrap();
    }
    // Right key reads it back.
    let conn = reopen(&path, &KEY).unwrap();
    let v: String = conn.query_row("SELECT x FROM t", [], |r| r.get(0)).unwrap();
    assert_eq!(v, "needle-in-a-haystack-0123456789");
    drop(conn);
    // Wrong key: the first read fails.
    let mut wrong = KEY;
    wrong[0] ^= 1;
    let conn = reopen(&path, &wrong).unwrap();
    let err = conn.query_row("SELECT count(*) FROM sqlite_master", [], |r| r.get::<_, i64>(0)).unwrap_err();
    println!("WRONG KEY: {err}");
    drop(conn);
    // The file has no SQLite header and no plaintext.
    let bytes = fs::read(&path).unwrap();
    assert!(!bytes.starts_with(b"SQLite format 3"));
    assert!(!bytes.windows(6).any(|w| w == b"needle"));
    assert_eq!(bytes.len() % PAGE, 0, "file is a whole number of 4096-byte pages");
}

fn flip(path: &std::path::Path, offset: u64, mask: u8) {
    let mut f = fs::OpenOptions::new().read(true).write(true).open(path).unwrap();
    f.seek(SeekFrom::Start(offset)).unwrap();
    let mut b = [0u8; 1];
    f.read_exact(&mut b).unwrap();
    b[0] ^= mask;
    f.seek(SeekFrom::Start(offset)).unwrap();
    f.write_all(&b).unwrap();
}

/// A changed page fails when read, and `cipher_integrity_check` reports it.
#[test]
fn changed_page_fails_to_read_and_integrity_check_reports_it() {
    let dir = scratch("tamper");
    let path = dir.join("t.db");
    {
        let conn = open(&path, &KEY).unwrap();
        conn.execute_batch("CREATE TABLE kv(id INTEGER PRIMARY KEY, data BLOB)").unwrap();
        for i in 0..40 {
            conn.execute("INSERT INTO kv(id, data) VALUES (?1, ?2)", rusqlite::params![i, blob(3000, i as u64)]).unwrap();
        }
        conn.query_row("PRAGMA wal_checkpoint(TRUNCATE)", [], |_| Ok(())).unwrap();
    }
    let total_pages = fs::metadata(&path).unwrap().len() / PAGE as u64;
    assert!(total_pages > 20, "pages: {total_pages}");

    // Untouched file: integrity check clean (no rows), all rows readable.
    {
        let conn = reopen(&path, &KEY).unwrap();
        let mut stmt = conn.prepare("PRAGMA cipher_integrity_check").unwrap();
        let rows: Vec<String> = stmt.query_map([], |r| r.get(0)).unwrap().map(|r| r.unwrap()).collect();
        assert!(rows.is_empty(), "clean file reported {rows:?}");
        let n: i64 = conn.query_row("SELECT count(*) FROM kv", [], |r| r.get(0)).unwrap();
        assert_eq!(n, 40);
    }

    // Flip one bit in the ciphertext of page 10 (1-based), then in its last bytes (the HMAC).
    for (label, offset_in_page) in [("ciphertext", 100u64), ("hmac tail", PAGE as u64 - 5)] {
        let copy = dir.join(format!("t-{}.db", label.replace(' ', "-")));
        fs::copy(&path, &copy).unwrap();
        flip(&copy, 9 * PAGE as u64 + offset_in_page, 0x01);
        let conn = reopen(&copy, &KEY).unwrap();
        // Reading the rows that live on the changed page fails.
        let mut failed_reads = 0;
        let mut ok_reads = 0;
        for i in 0..40i64 {
            match conn.query_row("SELECT length(data) FROM kv WHERE id = ?1", [i], |r| r.get::<_, i64>(0)) {
                Ok(_) => ok_reads += 1,
                Err(e) => {
                    failed_reads += 1;
                    if failed_reads == 1 {
                        println!("TAMPER {label}: first read error: {e}");
                    }
                }
            }
        }
        println!("TAMPER {label}: {failed_reads} reads failed, {ok_reads} succeeded");
        assert!(failed_reads > 0, "{label}: a changed page must fail when read");
        // The integrity check names the page.
        let mut stmt = conn.prepare("PRAGMA cipher_integrity_check").unwrap();
        let rows: Vec<String> = stmt.query_map([], |r| r.get(0)).unwrap().map(|r| r.unwrap()).collect();
        println!("TAMPER {label}: cipher_integrity_check -> {rows:?}");
        assert!(!rows.is_empty(), "{label}: integrity check reported nothing");
    }

    // A flipped bit in page 1 (it holds the salt) means the key no longer opens the file.
    let copy = dir.join("t-page1.db");
    fs::copy(&path, &copy).unwrap();
    flip(&copy, 3, 0x01);
    let conn = reopen(&copy, &KEY).unwrap();
    let err = conn.query_row("SELECT count(*) FROM sqlite_master", [], |r| r.get::<_, i64>(0)).unwrap_err();
    println!("TAMPER page 1 (salt): {err}");
}

/// A WAL hook that reports an error after the commit does not undo the commit: the data
/// is there after reopening. This is the "failed or uncertain commit" case in the gate.
#[test]
fn hook_error_after_commit_does_not_roll_back() {
    let dir = scratch("hookerr");
    let path = dir.join("h.db");
    {
        let mut conn = open(&path, &KEY).unwrap();
        conn.execute_batch("CREATE TABLE t(x)").unwrap();
        conn.wal_hook(Some(|_: &mut rusqlite::hooks::Wal, _n: std::ffi::c_int| {
            Err(rusqlite::Error::SqliteFailure(rusqlite::ffi::Error::new(rusqlite::ffi::SQLITE_IOERR), None))
        }))
        .unwrap();
        let r = conn.execute("INSERT INTO t VALUES (1)", []);
        println!("HOOK ERROR: INSERT returned {r:?}");
        assert!(r.is_err(), "hook error should propagate to the statement");
    }
    let conn = reopen(&path, &KEY).unwrap();
    let n: i64 = conn.query_row("SELECT count(*) FROM t", [], |r| r.get(0)).unwrap();
    println!("HOOK ERROR: rows after reopen = {n}");
    assert_eq!(n, 1, "the commit had already happened");
}

#[test]
fn harness_sanity() {
    let work = scratch("sanity");
    let cfg = Config { cache_spill: true, cache_size_pages: None };
    let o = run_case(&work, &KEY, Op::InsertSession, Variant::Fresh, cfg);
    println!("{o:?}");
    assert_eq!(o.log_len_at_start, 0, "log empty after the truncating checkpoint");
    assert_eq!(o.hook_calls, 1);
    assert!(o.frames_by_hook > 100);
    assert_eq!(o.frames_by_hook, o.frames_by_file, "hook count agrees with the log length");
    let r = run_case(&work, &KEY, Op::RolledBack, Variant::Fresh, cfg);
    println!("{r:?}");
    assert_eq!(r.hook_calls, 0, "no commit, so no hook");
}
