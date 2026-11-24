# Testing Yap on Android

We have added an automated Android build to GitHub Actions. It compiles the debug app and saves an APK as a workflow artifact.

## Getting the APK

1. Open the **Actions** tab in our GitHub repository.
2. Select **Android debug build** and open a successful run.
3. Download the **yap-android-debug** artifact.
4. Extract the ZIP and install the APK on two Android phones.

Android may ask you to allow installs from your browser or file manager. Only install test builds from our own repository. Debug APKs are not production releases.

## Our first test

1. Turn on Bluetooth on both phones.
2. Open Yap on both phones and grant the requested Nearby Devices permissions.
3. On phone A, tap **Make this phone discoverable**.
4. On phone B, tap **Find another Yap phone**.
5. Keep both apps on screen. Phone B should receive **Hello from Yap!**.

If it fails, record the phone models, Android versions and the message displayed in Yap. This helps us fix real compatibility issues rather than guessing.

## Important

The APK only tests Bluetooth discovery and a fixed greeting. There are no user accounts, encrypted rooms, chat history or secure messages yet. Do not rely on it for private conversations.

A successful cloud build proves the code compiled. It does not prove that Bluetooth works correctly on real phones.
