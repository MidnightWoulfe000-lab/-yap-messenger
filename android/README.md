# Yap for Android

This is the first Yap-to-Yap Bluetooth experiment. We're keeping it simple until we've tested the basics on real phones.

## What works in the code

- One phone advertises a Yap-specific Bluetooth Low Energy service.
- Another phone scans for that service, connects and reads a fixed **"Hello from Yap!"** greeting.
- Both phones must keep Yap open in the foreground for this test.

This is **not yet private messaging**. The greeting is fixed, unauthenticated and not encrypted by Yap. Don't use this build for sensitive communication.

## Try it

Open the `android/` folder in Android Studio with JDK 17 and Android SDK 35. The Gradle wrapper is not committed yet, so you'll need a local Gradle installation or an IDE configured to supply Gradle. Build and install on two physical Android phones. On phone A, tap **Make this phone discoverable**. On phone B, tap **Find another Yap phone**. Keep both apps visible and grant Bluetooth permissions.

BLE advertising support varies by phone. Some older Android devices may also require location services enabled. If the connection fails, note the phone models and Android versions.

## Next

Replace the fixed greeting with a bounded, authenticated message exchange and connect it to the shared Rust engine. We'll test Android-to-iPhone discovery separately.

The source has not yet been verified with an Android build in CI.
