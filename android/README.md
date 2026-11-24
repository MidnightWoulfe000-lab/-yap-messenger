# Yap for Android

This is our first Android app scaffold. It shows the Yap name and runs a **10-second Bluetooth Low Energy scan** when you press the button and grant permission. It only counts nearby Bluetooth devices. It does **not** identify Yap peers, connect phones, send messages or provide encryption yet.

Open the `android/` directory as a project in Android Studio with JDK 17 and Android SDK 35. Let Android Studio sync Gradle. A Gradle wrapper is not included yet, so local Android Studio tooling is needed for the initial sync.

Test on a physical Android phone. A Bluetooth scan cannot reliably be tested on an emulator. Some Android versions and devices may require location services enabled for BLE scanning.

We will add an authenticated Yap-specific advertisement and connection protocol before we send any messages.
