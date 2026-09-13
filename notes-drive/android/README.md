# BOOX Notes Drive — automatic native synchronization v0.4

Current connector: automatic captures/uploads, verified incoming updates with
per-notebook rollback journals, same native identities, folder and notebook
lifecycle, explicit conflict selection and native library/editor sync controls.
The hook is scoped only to Notes45326 and uses a UID-checked private bridge;
Google credentials remain in the companion.

All 57 mandatory Android unit tests pass using generated synthetic inputs.
The source-only asset build is installed with actual current-process hook build
`1b35137b83528a78` verified. Both automatic settings remain enabled. Mac UI and
native readback/return tests passed on the lifecycle build; Mac v0.6's final
interactive nested-export acceptance awaits its Keychain prompt.

Build with `python3 notes-drive/android/build.py` from the repository root after
acquiring declared dependencies. The builder prints the local certificate SHA-1
for Google Android registration. Keep your existing signing key for updates;
new checkouts generate a new local identity. No private fixture is required.

Follow the [end-to-end setup](../../docs/reproduction/README.md),
[setup CLI](../../docs/reproduction/SETUP-CLI.md), [protocol](../PROTOCOL.md) and
[current native validation](../INCOMING-VALIDATION.md). Limits include 4 MiB
notebooks,200 managed items,1,000 Drive objects and64 MiBverified catalogs. Native
application waits until editors close and may defer during sleep.

The connection/transport checkpoints below retain historical versions and their
original unfinished-work statements; they do not describe current v0.4 support.

## Earlier v0.2 transport milestone

**v0.2 is installed and live-tested with the Mac reader as the second client.**
It adds a persisted immutable revision queue, explicit retry, verified complete
catalogs and durable incoming staging. New controls refresh revisions, publish
the bundled fixture and retry pending uploads. No user notebook access or native
apply is enabled. The Mac replaces the proposed second BOOX for this phase.

**22 JVM tests pass** (the ten connection tests below plus twelve revision/queue
tests). Live Android offline staging, force-stop/reopen and retry preserved the
exact revision. The Mac downloaded/rendered it, published a B2 descendant, and
Android verified/staged that descendant with matching bytes. See
[interoperability validation](../INTEROPERABILITY-VALIDATION.md).

Wire contract: [PROTOCOL.md](../PROTOCOL.md). Revision tests retain four immutable
test objects in the Drive folder; only the older connection test moves its upload
to Trash. App-private `files/revisions/` stores outgoing jobs/payloads and
`files/incoming/<account-folder-hash>/` stores verified payloads, records and a
catalog published after the content files are flushed.

The section below records the original v0.1 connection milestone; artifact hashes
in `registration.json` now describe v0.2.

Built and installed on the Note Air4C on 2026-09-12. This is a standalone Google
Drive connection preview, not automatic Notes sync. It has no root permission,
Vector scope, Notes hook, scheduler or access to the user's local notebook data.

## Current validation

- Release build and APK signature verification pass.
- **10 JVM tests pass**, including Google's actual string-based authorization
  scope result, paginated folder discovery, incomplete/repeated listing rejection,
  folder validation, exact upload/download checksums, and scoped test cleanup.
- The installed APK hash matches [registration.json](registration.json).
- Real Google account authorization succeeds with only `drive.file`.
- The app created and selected its managed **BOOX Notes Sync** folder.
- A real **18,171-byte disposable `.note` upload/download passes**: Drive size/MD5
  and downloaded SHA-256 match. Only that verified test upload was moved to Trash.
- Force-stop/reopen followed by manual reconnect restores the same account and
  folder without another consent prompt. A second transfer after restart also
  passed; both disposable uploads were moved to Trash.
- The dedicated Google Cloud project, Drive API, branding, Android OAuth client,
  one test user and scope configuration are complete.
- Earlier status code 8 is historical. The app preserves Google's error code
  when a cancelled activity result contains a diagnostic.

Evidence: [device checks](../research/apply/android-device-checks.json),
[setup UI](../research/apply/drive-setup-initial.png),
[live results](../research/apply/drive-live-validation.json),
[successful transfer](../research/apply/drive-roundtrip-passed.png),
[reconnected UI](../research/apply/drive-reconnected.png),
and `app/build/test-results/testReleaseUnitTest/`.

BOOX initially auto-froze the newly installed package. `pm enable` after its
installation/launcher processing restored launchability. The preview is enabled
and left open. The OpenAI module and its scopes are unchanged.

## Google registration

The user signed into Cloud Console. The dedicated **BOOX Notes Drive** project
is `GOOGLE_PROJECT_ID` (number `GOOGLE_PROJECT_NUMBER`), and the Drive API is enabled.
Reuse it; do not create another project. State is recorded in
[google-project.json](google-project.json).

Branding uses the app name and the user's Gmail support/contact address, with an
**External / Testing** audience and one registered test user. The user completed
the Google policy agreement directly in the browser. No approval is outstanding.

Completed configuration:

1. Google Drive API enabled.
2. Google Auth branding named **BOOX Notes Drive**, with accurate account/contact
   details and appropriate audience/test-user settings for the user's account.
3. An **Android** OAuth client bound to the exact package and SHA-1 below.
4. The requested scope `https://www.googleapis.com/auth/drive.file`.

Package: `local.boox.notesdrive`

Signing SHA-1:
`AA:D0:A1:FC:C2:DF:4E:96:DC:A1:43:0C:0E:76:3C:13:CF:3A:F6:16`

Android client **BOOX Notes Drive Android**:
`YOUR_DESKTOP_CLIENT_ID`

The app also has a **Copy Google registration details** button. The APK uses
Google's `AuthorizationClient`; it does not need an embedded client secret or a
backend refresh-token exchange. Google Play services manages token renewal.
This preview explicitly reconnects/refreshes when requested; background renewal
and scheduling are later work.

Keep `local-signing.p12` private and backed up. Changing the package/signing identity
requires corresponding Google registration. Do not reuse the OpenAI adapter's key,
desktop connector token, a native application's token or someone else's OAuth client.

## Directory and connection test

Connect Google Drive, create or select a managed directory, then press
**Test Drive round trip**.

The app requests only `drive.file`. It discovers visible directories with its
private `booxNotesProtocol=1` app marker and checks writability, identity and Trash
state. It retains the chosen folder against the Drive account's permission ID.
An account change does not silently reuse the old directory. Directory discovery
handles pagination and duplicate IDs; incomplete listings cause failure.

The selected app-managed directory is **BOOX Notes Sync**, exact ID
`DRIVE_FOLDER_ID`; see [drive-folder.json](drive-folder.json).
The earlier desktop connector created a separate directory also named **BOOX Notes Sync**.
Its ID alone does not authorize this Android app. The preview therefore discovers
or creates its own managed directory; it does not yet offer Google Picker to
authorize the desktop-created one. The old empty directory can be reviewed
separately to avoid confusing duplicate names.
Do not delete either merely by matching its name.

The transfer test uploads the bundled disposable `Target-after.note` fixture,
checks Drive's upload size/MD5, downloads it and verifies SHA-256, then moves only
that newly created verified test file to Drive Trash. Native editability was
validated locally before bundling; the live Drive byte round trip now passes.
Fixture SHA-256:
`50ac19fd0b9a7dc806ea46157f6d053ee49cb7cf84d046938eb0140e7ba92ebc`.
The user’s notebooks are never read by this preview.

Network interruption or a failed checksum may leave a clearly named disposable
test file for inspection. A failed folder-creation response can likewise leave a
created directory; reconnect to list existing directories before retrying. This
preview is not a durable or resumable production transport.

Remaining validation and implementation:

- Expired/revoked access handling, long-lived renewal and background operation.
- Resumable large-file transfers, journal retention and automatic bounded retries.
- Native save/export/apply coordination and semantic verification.

Keep automatic application disabled while closing the native integration gaps
listed in [APPLY-VALIDATION.md](../APPLY-VALIDATION.md). Mac interoperability is
recorded separately and does not establish another BOOX firmware's compatibility.

## Build

From the project root:

```sh
python3 notes-drive/android/build.py
```

Uses JDK 17, the existing Android 35 SDK/build tools, verified Gradle 8.11.1,
Android Gradle Plugin 8.9.2 and `play-services-auth:22.0.0` from Google's repository.
Minimum Android API 28; target/compile API 35. The script runs release unit tests,
builds/signs the APK and records the signing fingerprint and artifact hashes.

APK: `app/build/outputs/apk/release/app-release.apk`

Private source/signing/APK checkpoints at the project root:
`backups/notes-drive-preview-v0.1-20260912/` before authorization and
`backups/notes-drive-connected-v0.1-20260912/` after live validation.
Latest: `backups/notes-drive-mac-interoperability-20260912/` includes v0.2 and the
Mac reader after live bidirectional validation.
No credentials are printed or stored by the app; access tokens remain in memory.
Folder/account identifiers and successful test hashes are stored in private app
preferences with Android backup disabled.
