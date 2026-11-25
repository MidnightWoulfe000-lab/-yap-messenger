# Yap 0.2 debug verification

Verified locally on 25 November 2026. The working checkout includes the reviewed
endpoint and Android transport fixes; the supplied source archive records a
SHA-256 for each project source file. No remote CI run or independent audit is
claimed.

| Check | Result |
| --- | --- |
| Android assemble/debug signing | Passed; v2 signature verified |
| Android unit tests | 14 passed: legacy state plus bounded WebSocket parsing and writer failure |
| Android instrumentation | 15 passed on API 28 x86_64 emulator; zero skipped/failing |
| Endpoint Rust tests | 11 passed, including restart, replay/tamper, out-of-order delivery, retirement and receipt admission at full outbox |
| Relay core | 23 passed during this session, including abrupt exit after durable commit |
| Rust TLS relay/client | 8 passed during this session |
| Pinned Signal experiment | Passed; ciphertext through restarted relay and actual TLS, encrypted return receipt, 100 established exchanges |
| Rust formatting and Clippy | Passed for core, service, experiment, endpoint and JNI boundary |
| Android lint | 0 errors, 9 warnings; dependency/API compatibility and existing UI state advisories |
| Native builds | arm64-v8a and x86_64 release libraries built with 16 KiB ELF alignment |
| Markdown local links | Passed |

The instrumentation suite loads the packaged JNI library, mutually pairs two
independent endpoints, commits encrypted Android Keystore snapshots, restarts
them, forwards ciphertext through a restarted on-device native relay, decrypts
and returns an authenticated receipt. It also verifies ciphertext tamper and
missing-key failure, uncertain disk-write lockout, native QR readiness, legacy
composer validation, navigation, recreation and preview separation.

The optional internet test ran against the real Rust TLS WebSocket service via
ADB reverse forwarding with separate sender/recipient accounts. It verified the
Android client's CA/hostname-checked handshake, durable custody acknowledgement
and recipient mailbox delivery. It used only the public localhost test certificate
and test credentials. No public relay is enabled in the app.

Source review found and resolved oversized WebSocket buffering, writer-failure
reconnection, foreground shutdown races and an imported-confirmation admission
bypass. These agent reviews are not an independent cryptographic/security audit.

API 35's local emulator stalled at startup; the device evidence above is API 28.
The API 29+ LE L2CAP implementation compiles but physical A–B–C radio exchange,
camera scan reliability, newer-Android hardware behaviour and battery/latency
measurements remain unverified. Background/iOS operation, groups and device
recovery are unavailable. See [release boundaries](debug-release-0.2.md).
