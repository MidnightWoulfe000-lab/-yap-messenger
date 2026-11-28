# SQLCipher storage check (gate 1)

Feasibility check for [local-storage-v1 §5](../../docs/proposals/local-storage-v1.md) ("Gate 1's measurement"),
gate 1 of the [eighth review](../../docs/audit/2026-11-28-v3.1-update-eighth-review.md).
**Test harness only: not linked into the app, not reviewed, not an audit.**

## What was run, and on what

- **Machine:** Windows 11, `rustc 1.100.0`, native `x86_64-pc-windows-msvc`, NTFS. All measurements are from
  `cargo run --release` / `cargo test --release` there. Raw output: `results/measurements.md`,
  `results/settings.txt`, `results/test-output.txt`.
- **Android:** `build-android.ps1` cross-compiles SQLCipher, the harness and its test binaries for
  `aarch64-linux-android` (arm64-v8a) and `x86_64-linux-android` with NDK 28.2.13676358. Both **built and
  linked**; the binaries are the right architecture (ELF machine `0xb7` and `0x3e`) and contain the SQLCipher
  4.20.0 and LibTomCrypt strings. **Run only on an x86_64 emulator, not on arm64-v8a or a phone**; see the emulator run below.
- Storage was an ordinary NTFS temp folder. Windows file-size reporting and the page cache are not Android's.
  **Frame counts are properties of SQLite/SQLCipher and should carry over; file-size samples and timing may not.**

## Pinned versions (`Cargo.lock` is committed)

| Item | Pin |
| --- | --- |
| `rusqlite` | `=0.43.0`, `default-features = false`, features `sqlcipher`, `hooks`, `functions` |
| `libsqlite3-sys` | `=0.40.0`, features `sqlcipher`, `bundled_bindings` |
| SQLCipher | **4.20.0 community**, based on **SQLite 3.54.1** (from `sqlcipher-src =420.0.0`) |
| Crypto provider | **LibTomCrypt 1.18.2** (`PRAGMA cipher_provider` → `libtomcrypt`), AES-256 + HMAC-SHA512 + PBKDF2-HMAC-SHA512 |
| `cc` | 1.12.0 |
| Page size | **4096** (`cipher_page_size` and `page_size` both read back 4096); 4,120 bytes per WAL frame |
| Settings (all read back from a live connection, `results/settings.txt`) | raw key `PRAGMA key = "x'<64 hex>'"`, `cipher_memory_security = 1`, `journal_mode = wal`, `synchronous = 2` (FULL), `auto_vacuum = 2` (INCREMENTAL), `secure_delete = 1` |
| Compile options of interest | `HAS_CODEC`, `THREADSAFE=1`, `TEMP_STORE=2` on desktop (`3` on Android, mirroring `libsqlite3-sys`), `DEFAULT_WAL_SYNCHRONOUS=2` |

### Finding: rusqlite's stock bundled SQLCipher could not be built here, so the provider choice is mine

rusqlite's `bundled-sqlcipher` feature compiles SQLCipher against **OpenSSL** (for Windows and Android it is
`bundled-sqlcipher-vendored-openssl`, which builds OpenSSL from source with Perl and `make`). On this machine
that failed before any C was compiled:

```
openssl-src: failed to build OpenSSL from source
Can't locate Locale/Maketext/Simple.pm in @INC ... (the only Perl on PATH is a Cygwin-built one)
```

No native Windows Perl was found (I looked for Strawberry Perl) and I did not install software. Instead the experiment uses the
`sqlcipher-src` crate's own **LibTomCrypt** provider. That is a different crypto provider from the stock one, and
the build is **my recipe, not rusqlite's**: `ltc-sys/build.rs` compiles `sqlcipher.c` with
`-DSQLITE_HAS_CODEC -DSQLCIPHER_CRYPTO_LIBTOMCRYPT` and each LibTomCrypt file with `-DLTC_SOURCE`, using the SQLite
compile options `libsqlite3-sys` 0.40.0 uses for its own bundled build. `libsqlite3-sys` is then pointed at the
resulting static `sqlcipher` library through `SQLCIPHER_LIB_DIR` (`.cargo/config.toml`, or the script).
(Because the C library is built by a separate package, build it first: `cargo build -p yap-sqlcipher-ltc-sys`.)
A phone build of the design needs one of two decisions the design should make explicitly:

- **LibTomCrypt** (this experiment): pure C, builds with no Perl/OpenSSL on Windows and for both Android ABIs,
  but it is a less widely used SQLCipher provider and **its speed was not measured** (no AES-NI, portable C),
  or
- **OpenSSL**: the provider SQLCipher and rusqlite mainly test. It needs Perl and `make` on the build machine
  (a Linux CI runner would do) and was **not tried here**; none of the numbers below were taken with it. Frame
  counts should not depend on the provider (they depend on SQLite's b-tree and WAL), but that is an expectation,
  not a result.

## What was checked

### Opening, wrong key, tampering (`tests/store.rs`, all pass)

| Check | Result |
| --- | --- |
| Open with a raw 32-byte key, write, close, reopen with the same key | rows read back |
| The file starts with no `SQLite format 3` header, contains no plaintext, and is a whole number of 4,096-byte pages | pass |
| Wrong key (one bit different) | first read fails: `file is not a database` |
| One bit flipped in the **ciphertext** of page 10 | reads that reach it fail: `database disk image is malformed` (34 of 40 row lookups failed: page 10 was probably an interior b-tree page, which I did not check); `PRAGMA cipher_integrity_check` returns `HMAC verification failed for page 10` |
| One bit flipped in the **last bytes** of page 10 (the HMAC) | same two results |
| One bit flipped in **page 1** (it holds the salt) | `file is not a database`: indistinguishable from a wrong key |
| Untouched file | `cipher_integrity_check` returns no rows |
| A WAL hook that returns an error **after** the commit | the statement returns an error (`SQL logic error`), but the row **is** there after reopening: the commit had already happened, so the app must not treat a post-commit error as a rollback |

### Measurement rules from §5 (`src/lib.rs`, `cargo run --release`)

- Each operation gets **its own directory and its own connection**. On that connection the harness runs
  `PRAGMA wal_checkpoint(TRUNCATE)` itself, checks that it returned `(0, 0, 0)` and that the log file is 0 bytes,
  and only then installs the hook.
- The **WAL hook** (rusqlite `wal_hook`) counts calls and records the page count it is given. It always returns
  success. A commit case **fails** unless the hook ran exactly once; a rolled-back case **fails** if it ran at all
  (see finding 2). The hook's count is **cross-checked against the log file's length** (`(length − 32) / 4,120`);
  a mismatch would mean a checkpoint or restart had changed the log, and none did.
- **Peak space** = largest total of database + log + wal-index + any other file in the folder, above the starting
  total, in pages. It is sampled after every statement and at commit; **in addition** a poller thread samples
  every ~100 µs to catch peaks inside one statement. Both agreed in every case (differences of 0 pages).
- Run with `cache_spill` ON and OFF, each with a small cache (`cache_size = -64`: 64 KiB = 16 pages, which forces
  spilling) and the default (`-2000`), on a **fresh** database and a **used** one (24 envelope-size and 3
  session-size rows added, then most deleted, leaving several hundred free pages).
- **Estimate** = sum of each row's maximum rounded up to 4,096-byte pages, doubled, plus 16. Row maxima: 512 KiB
  session (128 pages → 272), 64 KiB envelope (16 pages → 48), two sessions (528).

### Results (all 4 configurations × 2 databases × 7 cases = 56 runs; **56 PASS, 0 FAIL**)

Worst case over the 8 configuration/database combinations. Full tables in `results/measurements.md`.

| Case | Estimate (pages) | Frames appended (range) | Peak extra space (range) | Verdict |
| --- | ---: | ---: | ---: | --- |
| insert one 512 KiB blob row | 272 | 134–137 | 135–138 | pass |
| update one 512 KiB blob row | 272 | 131 | 132 | pass |
| delete one 512 KiB blob row | 272 | 137–138 | 138–139 | pass |
| insert one 64 KiB envelope row | 48 | 20–23 | 21–24 | pass |
| transaction updating two 512 KiB rows | 528 | 262 | 264 | pass |
| transaction inserting two 512 KiB rows | 528 | 266–268 | 268–270 | pass |
| transaction updating two rows, then **ROLLBACK** | 528 | no commit, so none | 0 (spill off) or **253** (spill on, small cache) | pass |

The estimate holds with **about 2× headroom** in every case (peak 132–139 against 272, 21–24 against 48, 264–270
against 528): the rule's doubling is barely used. Updating a 512 KiB row costs about half its allowance (131 of 272
frames), which is what you would expect if SQLite reuses the freed overflow pages for the new content inside
the same transaction (I did not inspect the page lists to confirm the reason).

Informational (no rule): after deleting four large rows from the used database, `PRAGMA incremental_vacuum(256)`
freed 256 pages (918 → 662 free) and appended **2 frames**. Free pages sat at the end of the file, so it
truncated without moving data; a database with free pages in the middle would cost more. Not measured.

## Findings for the design

1. **Estimate rule: passes with headroom on these seven cases.** It does not show the rule is safe for every
   record type; "history row, sender-key state, pending and cached pieces, friend requests, admission counters,
   each index" were not tested, nor was group state, retirement, or a full session being rewritten alongside its
   history. The estimate was never close to exceeded, so those need their own gate-1 runs, not a new rule.
2. **A rolled-back transaction has no commit, so the hook never fires.** §5 says "a test where it was not called
   fails". For rollback that is backwards: the harness treats a call as the failure. The proposal should say so.
   Also, with `cache_spill` ON and a small cache, a transaction that later rolls back **leaves its spilled frames
   in the log file** (251 frames, 1,034,152 bytes here) until the log is restarted. The committed frame count says 0
   but the file is 1 MB. **Space accounting must use the log file's length, not the hook's count.**
3. **`cache_spill` did not change committed frame counts or peaks** in these cases (spill on and off gave the same
   numbers). With the default cache (`cache_spill` threshold 483 pages) none of these operations spills at all. The
   only visible effect of spilling was in the rollback case. Spill off with a small cache means SQLite keeps all dirty
   pages in memory until commit; memory use was not measured.
4. **A custom WAL hook replaces SQLite's automatic checkpoint.** rusqlite's docs say `wal_hook` and
   `wal_autocheckpoint` overwrite each other. While the observer hook is installed the app gets **no** automatic
   checkpoints; §5's "passive checkpoint over 16 MB" has to be done by the app. If the app only installs the hook in
   tests, as §5 says ("never in the app's own commit path"), this does not arise, but the app must then set
   `wal_autocheckpoint` itself.
5. **No temporary files exist in this build** (`TEMP_STORE=2` on desktop, `3` on Android: temp data stays in memory),
   so "any temporary files" is always 0 here and sorting or large temp use shows up as memory, not disk. The
   `libsqlite3-sys` Android default is the same. If the app ever sets `temp_store = FILE`, the measurement has to
   be repeated.
6. **SQLCipher reserves 80 bytes of every page** (IV + HMAC), leaving 4,016 usable. By arithmetic a 512 KiB blob needs about 131 overflow pages (4,012 bytes each), not 128; the insert wrote 134
   frames. The rule's "round up to pages of 4,096" understates capacity by about 2%; the doubling covers it,
   but a tighter rule would need the reserve.
7. `PRAGMA cipher_kdf_iter` returned no row in raw-key mode (not investigated); the others in `results/settings.txt`
   are present. The HMAC and KDF algorithms read back as `HMAC_SHA512` and `PBKDF2_HMAC_SHA512`.
8. **Crypto provider is an open design decision** (see above): LibTomCrypt built here; OpenSSL is untested and needs
   Perl and `make`.

## What this does not show

- Nothing was run on a real phone or arm64-v8a (only an x86_64 emulator, see above), and nothing on real flash storage. No power-loss or crash-injection test. No timing or
  battery measurement, and no LibTomCrypt-versus-OpenSSL speed comparison.
- The gate's other items (full sessions with their histories, group state, retirement, failed/uncertain commits beyond
  the post-commit hook error above, "WAL reuse and checkpoint interaction" beyond one commit per fresh log) are not
  covered. Each run does one commit on a freshly truncated log.
- "Verify raw-key opening, HMAC/integrity checks, FULL/WAL and callback availability on both Android ABIs" is
  verified on desktop and **built** for Android, not run there.

## Emulator run

Run on the existing `yap_test` emulator (`emulator-5556`, Android 9, API 28, ABI `x86_64`, "Android SDK built for x86_64"), by pushing the cross-compiled x86_64 test binary with `adb push` into `/data/local/tmp` and running it there. **An emulator is not a real phone**: it is x86_64 (the arm64-v8a build was not run), uses the host's CPU and disk, and says nothing about real-device timing, flash behaviour or vendor changes.

Results: the 5 tests **passed, 0 failed**; the full measurement matrix (`yap-sqlcipher-store` release binary, stdout in `results/emulator-report-stdout.md`) gave **56 PASS, 0 FAIL**, with the same frame counts and peaks as desktop (e.g. insert 512 KiB: 134 frames; two-row update: 262). The build there reports `TEMP_STORE=3`, `cipher_provider = libtomcrypt`. So the raw-key, HMAC/integrity, WAL, FULL and WAL-hook checks do work on Android x86_64. The harness's work folder is a path baked in at build time (a Windows path) and became an odd relative folder under `/data/local/tmp`; harmless. Storage was the emulator's disk image, not flash.

## Run it

```
cargo build -p yap-sqlcipher-ltc-sys   # builds SQLCipher + LibTomCrypt into lib/<target>/ (first)
cargo test --release -- --test-threads=1
cargo run --release                    # rewrites results/measurements.md and results/settings.txt
powershell -File build-android.ps1     # both Android ABIs (needs the NDK)
```

Set `YAP_SQLCIPHER_LOG=1` to see SQLCipher's own error log (very noisy in the tamper tests).
