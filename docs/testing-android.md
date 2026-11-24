# Testing Yap on Android

We have added an automated Android build to GitHub Actions. It compiles the debug app and saves an APK as a workflow artifact.

## Getting the APK

1. Open the **Actions** tab in our GitHub repository.
2. Select **Android debug build** and open a successful run.
3. Download the **yap-android-debug** artifact.
4. Extract the ZIP and install the APK on two Android phones.

Android may ask you to allow installs from your browser or file manager. Only install test builds from our own repository. Debug APKs are not production releases.

## Two-phone messaging check

1. Turn on Bluetooth on both phones.
2. Open Yap on both phones and grant the requested permissions: Nearby Devices on Android 12+, or Location on Android 8–11 for BLE scanning.
3. In **Network**, open the top-right connection controls and tap **Make this phone discoverable** on both phones. Wait for confirmation.
4. On phone A, open **Chats → Nearby Yap phone**, type `Hi from A` and tap **Send**.
5. Keep both apps on screen. Phone B should show the incoming text. Phone A should show **Bluetooth acknowledged**, which confirms a GATT write, not end-user delivery or reading.
6. Repeat from B to A, then try a 20-byte message and five emoji (20 UTF-8 bytes). Six emoji must disable Send and show the byte-limit error. The keyboard Send action must also respect this limit.
7. Turn Bluetooth off on the sender and try sending. The draft should remain available for retry. Turn Bluetooth on, make both phones discoverable again and retry.

## Compose interface checks

Run the [local build and test commands](../android/README.md#build-and-automated-checks) before committing. CI builds the APK, runs unit tests and lint, and compiles the device tests. Run device tests separately with a connected emulator or phone.

- System Back: Bluetooth controls → conversation → Chats; Network/Settings → Chats. With the keyboard open, Back should dismiss it first.
- Try a small phone, landscape and 200% font size. Scroll to the discoverability button and settings text; keep the composer above the keyboard.
- Try Android's Remove animations setting. No decorative loops should run.
- Confirm the network view shows no invented peers, distances or routes, and that the chat/avatar implies no verified identity or live connection.
- Check that the local phone stays at the centre of the network, before and after observing or connecting to a peer. Technical explanations belong in connection/privacy details; the main screens should not repeat prototype banners.
- Tap a real outgoing message to inspect its timeline. Acknowledged writes must leave **Delivered → Recipient receipt not confirmed**. Failed/interrupted attempts must retain their draft and never become confirmed deliveries.
- Open **Settings → Preview the full design**. Check all six screens against the [approved v2 reference](../design/proposals/yap-refined-six-screen-v2.png). Confirm that the sample banner stays visible, mesh links are solid lime, and internet links are dashed cobalt with text labels. Exit preview and confirm live history is intact.
- In preview, Send and Clear this session must be disabled, and no discoverability action may invoke Bluetooth. Groups and membership requests remain read-only examples.
- Rotate while composing a real message. The route and draft should survive; an active send that stops must be shown as unconfirmed.

If it fails, record the phone models, Android versions and the message displayed in Yap. This helps us fix real compatibility issues rather than guessing.

## Important

The APK exchanges short, user-written BLE messages with no saved history, authentication or encryption. There are no user accounts, encrypted rooms or relay routes. Do not rely on it for private conversations.

A successful cloud build proves the code compiled. It does not prove that Bluetooth works correctly on real phones.
