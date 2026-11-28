# Signing key for Yap builds

Yap's CI builds are signed with a private key that only the owner holds. The key is never
stored in the repository. This replaces the old public `android/app/debug.keystore`, which
is deleted. Anyone could sign an app with that key, and old copies of it stay public in the
repository history.

This page is a set of steps for the owner to follow by hand. Nothing here has been run, and
no key has been created or handled by anyone but the owner.

## How it works

- CI reads four GitHub Actions secrets. It decodes the keystore into a temporary file in the
  runner's temp folder, builds, then deletes the file.
- Gradle finds the key through environment variables: `YAP_SIGNING_KEYSTORE_PATH` (set by
  CI), `YAP_SIGNING_STORE_PASSWORD`, `YAP_SIGNING_KEY_ALIAS` and `YAP_SIGNING_KEY_PASSWORD`.
- If the secrets are missing, CI still builds and tests, using Android's default debug key,
  but it does **not** publish the APK. Pull requests from forks never receive secrets.
- A build on your own machine does not set those variables, so Android Studio or Gradle uses
  Android's own per-machine debug key. **Such a build cannot update an install that CI signed,
  and a CI build cannot update it.** Uninstall first (this deletes the app's data), or use the
  CI builds on your test phones.
- `DebugUpdater` still refuses an update whose signing key differs from the installed app.
  On Android 8.0 and 8.1 it skips its own pre-check, and Android's installer enforces the key.

## Steps for the owner

1. **Make the key** (once, on a machine you trust). Choose strong passwords and keep a note
   of the alias:

   ```powershell
   keytool -genkeypair -v -keystore yap-signing.jks -alias yap -keyalg RSA -keysize 4096 -validity 10000
   ```

   - `keytool` hides the password while you type it, so nothing appears on screen. That is normal.
   - The name, organisation and city it asks for can be read by anyone from the app, so
     don't use personal details.
   - If Android Studio's `keytool` is missing, any installed JDK's `bin\keytool.exe` works
     (for example `C:\Program Files\Microsoft\jdk-…\bin\keytool.exe`).

2. **Back it up.** Keep at least one offline copy of `yap-signing.jks`, for example on a USB
   drive kept somewhere safe. Store the passwords and alias in a password manager.
   If the key is lost, no further update can be installed over existing installs; everyone
   would have to uninstall and reinstall.

   You may also keep **one** cloud copy (for example Google Drive or OneDrive), on these
   conditions:

   - Put the `.jks` inside a password-protected 7-Zip archive (AES-256), with a different
     password from the key's.
   - Never store the key's password or the archive's password in the same cloud account. Use
     a password manager.
   - Turn on two-step sign-in for that account.

   A cloud copy guards against losing the key, which would stop all future updates. But
   someone who gets into the account and learns the password could sign a fake Yap.

3. **Turn it into text for GitHub:**

   ```powershell
   [Convert]::ToBase64String([IO.File]::ReadAllBytes("yap-signing.jks")) | Set-Clipboard
   ```

4. **Add four secrets** in GitHub under *Settings → Secrets and variables → Actions → New
   repository secret*:

   | Secret | Value |
   | --- | --- |
   | `YAP_SIGNING_KEYSTORE_B64` | the base64 text from step 3 |
   | `YAP_SIGNING_STORE_PASSWORD` | the keystore password |
   | `YAP_SIGNING_KEY_ALIAS` | `yap` (or the alias you chose) |
   | `YAP_SIGNING_KEY_PASSWORD` | the key password |

5. **Clear the clipboard and delete any loose copy** of the `.jks` file that is not one of
   your offline backups.

6. Run the *Android debug build* workflow. On every phone, uninstall the old build once and
   install the first build signed with the new key. Later updates then work in the app.

Never paste the key, the base64 text or the passwords into the repository, an issue, a chat
or a log.
