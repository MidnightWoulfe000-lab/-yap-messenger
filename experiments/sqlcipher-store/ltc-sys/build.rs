//! Builds SQLCipher 4.20.0 (SQLite 3.54.1) with the LibTomCrypt crypto provider from the
//! `sqlcipher-src` crate's sources, as that crate's README describes, using the same SQLite
//! compile options `libsqlite3-sys` 0.40.0 uses for its own bundled build. This stands in
//! for rusqlite's stock `bundled-sqlcipher` feature, which needs OpenSSL (and so Perl and
//! make) and could not be built on this machine.
use std::env;
use std::path::PathBuf;


fn main() {
    let dir = sqlcipher_src::source_dir().to_owned();
    let out = PathBuf::from(env::var("OUT_DIR").unwrap());
    let android = env::var("CARGO_CFG_TARGET_OS").is_ok_and(|v| v == "android");
    let msvc = env::var("CARGO_CFG_TARGET_ENV").is_ok_and(|v| v == "msvc");
    let windows = env::var("CARGO_CFG_WINDOWS").is_ok();
    let include = dir.join(sqlcipher_src::LIBTOMCRYPT_INCLUDE_DIR);

    // 1. SQLCipher amalgamation.
    let mut sql = cc::Build::new();
    sql.file(dir.join(sqlcipher_src::SOURCE_FILE))
        .include(&include)
        .include(&dir)
        .out_dir(out.join("sqlcipher-obj"))
        .warnings(false);
    for d in [
        "SQLITE_CORE",
        "SQLITE_DEFAULT_FOREIGN_KEYS=1",
        "SQLITE_ENABLE_API_ARMOR",
        "SQLITE_ENABLE_COLUMN_METADATA",
        "SQLITE_ENABLE_DBSTAT_VTAB",
        "SQLITE_ENABLE_FTS3",
        "SQLITE_ENABLE_FTS3_PARENTHESIS",
        "SQLITE_ENABLE_FTS5",
        "SQLITE_ENABLE_JSON1",
        "SQLITE_ENABLE_LOAD_EXTENSION=1",
        "SQLITE_ENABLE_RTREE",
        "SQLITE_ENABLE_STAT4",
        "SQLITE_SOUNDEX",
        "SQLITE_THREADSAFE=1",
        "SQLITE_USE_URI",
        "HAVE_USLEEP=1",
        "HAVE_ISNAN",
        "_POSIX_THREAD_SAFE_FUNCTIONS",
        // SQLCipher
        "SQLITE_HAS_CODEC",
        "SQLCIPHER_CRYPTO_LIBTOMCRYPT",
        "SQLITE_EXTRA_INIT=sqlcipher_extra_init",
        "SQLITE_EXTRA_SHUTDOWN=sqlcipher_extra_shutdown",
        "HAVE_STDINT_H=1",
    ] {
        sql.define(d.split('=').next().unwrap(), d.split_once('=').map(|x| x.1));
    }
    // libsqlite3-sys: temp store 2 for SQLCipher; 3 on Android.
    sql.define("SQLITE_TEMP_STORE", if android { "3" } else { "2" });
    if !windows {
        sql.define("HAVE_LOCALTIME_R", None);
    }
    if msvc {
        sql.flag("/utf-8");
    }
    let mut objects = sql.compile_intermediates();

    // 2. LibTomCrypt files, each its own translation unit with LTC_SOURCE.
    let mut ltc = cc::Build::new();
    ltc.include(&include)
        .include(&dir)
        .define("LTC_SOURCE", None)
        .out_dir(out.join("ltc-obj"))
        .warnings(false);
    if msvc {
        ltc.flag("/utf-8");
    }
    for f in sqlcipher_src::LIBTOMCRYPT_SOURCES {
        ltc.file(dir.join(f));
    }
    objects.extend(ltc.compile_intermediates());

    // 3. One static library called `sqlcipher`, which libsqlite3-sys asks the linker for.
    let mut lib = cc::Build::new();
    for o in objects {
        lib.object(o);
    }
    lib.compile("sqlcipher");
    println!("cargo:rerun-if-changed=build.rs");

    // libsqlite3-sys is told (SQLCIPHER_LIB_DIR) to link a static `sqlcipher` from
    // `lib/<target>/` and is compiled before or alongside this crate, so the finished
    // archive is also copied there. Build this package first (`-p yap-sqlcipher-ltc-sys`).
    let target = env::var("TARGET").unwrap();
    let dest = PathBuf::from(env::var("CARGO_MANIFEST_DIR").unwrap()).join("..").join("lib").join(&target);
    std::fs::create_dir_all(&dest).unwrap();
    for name in ["sqlcipher.lib", "libsqlcipher.a"] {
        let from = out.join(name);
        if from.exists() {
            std::fs::copy(&from, dest.join(name)).unwrap();
        }
    }
}
