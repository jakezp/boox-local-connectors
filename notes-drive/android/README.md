# BOOX Notes Drive for Android

BOOX Notes Drive synchronizes supported native BOOX notebooks through a managed
Google Drive directory. It publishes saved notebooks and applies verified
incoming edits after the native Notes editor closes. The companion also handles
notebook/folder metadata, recoverable deletion/restoration and conflict review.

It requires the exact rooted NoteAir4C configuration in the
[project setup guide](../../README.md). Installing an APK alone does not establish
that the native Notes hook is active.

## Build and register

After preparing the pinned Android tools and JDK 17:

```sh
python3 notes-drive/android/build.py
```

Run from the repository root. The builder runs the 57 mandatory unit tests,
generates synthetic fixtures, signs the APK and writes `registration.json` in
this directory. Output: `app/build/outputs/apk/release/app-release.apk`.

For a new deployment, use the generated certificate SHA-1 to register package
`local.boox.notesdrive` with your Google Cloud project. Follow
[Google Drive setup](../../docs/GOOGLE-DRIVE-SETUP.md). Keep `local-signing.p12`
for updates; another key creates a different installation identity. No Desktop
OAuth JSON or client secret needs to be embedded in this Android app.

## Install and connect

Use the [guarded installer](../../docs/reproduction/SETUP-CLI.md) with the required
root/framework baseline and your current backup receipt. Then:

1. Open **BOOX Notes Drive → Connect Google Drive** and authorize your account.
2. Create a managed directory with **Create BOOX Notes Sync directory**, or
   select an existing authorized one for the same library.
3. Run **Test Drive round trip**.
4. Enable **Automatically publish saved notebooks** and
   **Automatically apply incoming notebook edits**.
5. Complete the [native notebook round trip](../../README.md#8-check-your-first-notebook-round-trip).

Google Play services manages Android authorization. The requested scope is
`drive.file`; a folder ID alone does not grant access. Use **Copy Google
registration details** to compare the installed app with your OAuth registration.

## Use and troubleshoot

Save and leave a native notebook's editor to publish it and permit incoming
application. Sleep can defer scheduled work. **Retry pending revision uploads**
and **Retry incoming updates** retry their respective queues; they do not resolve
conflicts automatically. Use **Review conflicting versions** when required.

The connection test uses disposable data. It proves transport, not native hook
loading or safe application. Do not delete journals, Drive history or app state
to clear an unexplained error. Preserve the report and current notebook state.

See the [technical brief](../../docs/TECHNICAL-BRIEF.md),
[protocol](../PROTOCOL.md), [native incoming checks](../INCOMING-VALIDATION.md),
and [validation record](../../docs/VALIDATION.md) for implementation details.
