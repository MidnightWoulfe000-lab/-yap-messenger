# Updating debug builds on a phone

Debug builds can update themselves. In **Settings → About Yap**, tap **Check for
update**, then **Download and install**. The button does not appear in release builds,
and the app contacts GitHub only when you tap it.

## How it works

- Every push to `main` (and every manual run of the *Android debug build* workflow)
  replaces a rolling GitHub release called `debug-latest` with the newest
  `yap-debug-<build>.apk`. `<build>` is `1000` plus the workflow run number and is also
  the app's `versionCode`.
- The app reads that release, compares the number with its own, downloads the APK,
  checks it against GitHub's SHA-256 digest and confirms it has the same package name
  and signing key, then opens Android's installer.
- Android asks for confirmation each time. It cannot update silently.

## First time on a phone

Android only installs an update when the signing key matches. Debug builds from before
this change used a different key each time, so **uninstall Yap once**, install a build
made after this change by hand, and allow "Install unknown apps" for Yap when asked.
Later updates keep your data.

## Signing key

`android/app/debug.keystore` is a deliberately public development key (password
`android`). Anyone can sign an APK with it, so it must never be used for a release or for
anything other than disposable debug builds. If that stops being acceptable, move the
keystore into a GitHub secret and have CI write it to this path before building.
