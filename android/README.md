# Yap for Android

We've got the first Bluetooth greeting working on two real Android phones. Now we're trying actual, user-written messages.

## What this version does

- One phone advertises Yap over Bluetooth Low Energy.
- The other phone finds it and writes a short text message to its Bluetooth service.
- The receiving phone shows the message on screen.
- To reply, both phones can advertise, then take turns pressing Send.

**This is a very early test.** Messages are limited to 20 UTF-8 bytes, so some emoji and non-English characters take more than one byte. There is no encryption at the Yap protocol layer, authentication, chat history, offline queue or delivery guarantee. Don't use it for anything sensitive.

## Test it on two Android phones

1. Get the APK from a successful **Android debug build** in GitHub Actions.
2. Install the new build on both phones and keep Yap open.
3. Open **Network**, tap the top-right connection controls, then **Make this phone discoverable** on both phones. Wait for **Yap is visible to nearby phones**. The **Make yourself visible** row opens the same controls.
4. Open **Chats → Nearby Yap phone** on phone A, type a short message such as `Hi from A`, and tap **Send**. The composer counts UTF-8 bytes and disables sending above 20 bytes.
5. Phone B should show an incoming message bubble. Phone A should show **Bluetooth acknowledged** below its outgoing bubble. This confirms the GATT write only; receipt and reading are not confirmed.
6. Repeat from phone B to phone A.

If a message fails, record the status text and the phone models. The sender's confirmation only means the Bluetooth write was acknowledged, not that a person read the message.

## The approved interface

The app follows the [approved v2 concept](../design/proposals/yap-refined-six-screen-v2.png): a compact chat list, full-height conversations, a living network visualisation, framed message details, group details and settings.

The default mode uses real Bluetooth events. Tap a message for its local send/receive details. Recipient delivery remains unconfirmed; Bluetooth acknowledgement is not a delivery receipt. The network shows only observed Bluetooth activity, with no invented devices or internet routes. Local message history and drafts survive rotation but are not saved to disk.

Open **Settings → Preview the full design** to explore all six screens with sample data, including the mesh/internet topology and group details. Every preview screen is labelled. Sending, discoverability changes and clearing live history are disabled in the preview. Exit preview to return to live Bluetooth messaging.

## Build and automated checks

Install Java 17 and Android SDK platform 35 with Build Tools 34.0.0, then set `JAVA_HOME` and `ANDROID_HOME`. The checked-in wrapper downloads Gradle 8.9 and verifies its SHA-256 checksum.

From `android/`:

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
# With an emulator or Android phone connected:
.\gradlew.bat :app:connectedDebugAndroidTest
```

On macOS/Linux, use `./gradlew` with the same tasks. Unit tests cover existing BLE callbacks, acknowledgement times, errors, UTF-8 limits, local unread counts and history clearing. Device tests cover the composer, navigation, activity recreation, all six preview screens, and separation of live data from the preview. They do not verify radio exchange between two phones.

## What's next

We need message framing, identity, persistent storage, multi-hop routing and end-to-end encryption. The app is not ready for private communication.
