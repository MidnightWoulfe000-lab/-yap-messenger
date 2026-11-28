# Yap for Android

The dev build has encrypted one-to-one chats, in-person QR pairing, encrypted
storage, and nearby messaging over Bluetooth and Wi-Fi (same network or hotspot)
that keeps running in the background. Messages hop through other phones. Link IDs
rotate so phones can't be tracked, and floods get slowed down. There's also an
optional private internet relay. The old unencrypted 20-byte Bluetooth
chat has been removed.

What's done and what's left is in the [main README](../README.md). How nearby
messaging works is in [local mesh v2](../docs/proposals/local-mesh-v2.md).
Install and pairing steps are in [the debug release guide](../docs/debug-release-0.2.md).
No independent security audit, iOS support or group chats yet.

## Build

Install Java 17, Android platform 35, Build Tools 34.0.0, NDK 28.2.13676358,
Rust 1.100.0, Protobuf `protoc` and the Rust targets `aarch64-linux-android` and
`x86_64-linux-android`. Set `JAVA_HOME`, `ANDROID_HOME` and `PROTOC` as needed.

Build both native libraries from the repository root:

```powershell
./scripts/build-native.ps1
```

On Linux/macOS use `bash scripts/build-native.sh`. Then from `android/`:

```powershell
./gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
./gradlew.bat :app:connectedDebugAndroidTest
```

Use `./gradlew` on Linux/macOS. APK output is
`app/build/outputs/apk/debug/app-debug.apk`. Pre-build checks reject missing native
libraries. Native compilation uses locked dependencies and 16 KiB ELF alignment.

Unit tests cover the bounded WebSocket parser, the
nearby link layer (`MeshHubTest`: rotating IDs, HELLO contents, bad frames,
duplicate links, Wi-Fi preference) and flood slow mode (`FloodGuardTest`). Android
device tests cover native pairing, encrypted snapshot/restart, tamper and missing
key rejection, uncertain disk commits, native relay restart and UI navigation.
The optional `InternetIntegrationTest` needs the public localhost TLS fixture
server, `adb reverse tcp:8443 tcp:8443` and
`-Pandroid.testInstrumentationRunnerArguments.internet_test=true`.
These tests do not establish physical Bluetooth exchange.

## Interface

The app retains the [approved v2 design](../docs/design-system.md). The network
screen reports actual connections and advertising state. Open Settings → Preview
the full design for labelled sample conversations and group screens; preview
cannot send, change discoverability or clear live history.

See [the endpoint contract](../docs/android-endpoint-v1.md) and
[implementation status](../docs/messaging-implementation.md) for protocol,
persistence, quotas, licensing and remaining verification.
