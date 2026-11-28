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

Android only installs an update when the signing key matches. Builds from before the move
to the private key (see [signing.md](signing.md)) used a public key, so **uninstall Yap
once**, install a build signed with the new key by hand, and allow "Install unknown apps" for Yap when asked.
Later updates keep your data.

## Signing key

CI signs builds with a private key held only by the owner, supplied through GitHub Actions
secrets. See [signing.md](signing.md) for how it works and the owner's setup steps. A build
made on your own machine uses Android's per-machine debug key instead, and **cannot update an
install that CI signed**. Builds from CI without the secrets are not published.
