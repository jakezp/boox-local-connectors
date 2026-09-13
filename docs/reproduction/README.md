# BOOX Note Air4C: setup and reproduction record

This guide describes the authored root-support tools, firmware-specific AMS fix,
Vector hooks, OpenAI/ChatGPT integration, Notes Drive Android app and Mac editor.
It is a reproducibility guide and evidence map, **not a claim that full Notes sync
replacement or a clean-machine reinstall has passed**.

Historical documentation baseline: **2026-09-12**; latest parent update:
**2026-09-13**. Android v0.4 now has57 parent-reported checks plus passing native
crash7, folder lifecycle, notebook deletion/restore, conflict UI with all parents,
Mac-created blank notebook application, toolbar Drive panel and editor Sync/save
validation. The completed pre-reinstall preservation snapshot confirmed all three original notebook rows and182
associated files unchanged. The parent-recorded native snapshot is
`backups/notes-drive-native-v0.4-20260913/native-after.tar`, SHA-256
`99f4b4576fcb0dd3350f8e88246bfa334d27816c90c37d346dd9f981870616a9`.
The archive remains private and was not extracted by this sidecar. These are
parent-reported results, not device tests rerun by this sidecar.
The Mac was locked at that checkpoint. The parent's later **own-OAuth UI round
trip passed**: GUI pen drag/save automatically published to BOOX, native apply
committed six pens/129 samples with exact IDs and a visible diagonal, and normal
native close/save automatically followed back to the Mac with page/zoom retained.
UI notebook creation/rename/move also committed and opened natively. A Mac decoder
ancestor-row bug found during that follow-up is being fixed; fixed-build
acceptance remains pending. The parent reports129 mandatory
synthetic Mac checks plus a separate five-check explicit private-fixture suite.
These are distinct from the frozen v0.5 app's125-check build/test checkpoint.
Do not combine those counts into a claim that the frozen app was rebuilt;
the later interactive result is separate parent-reported evidence.

Installer continuation **2026-09-13**: the [staged setup CLI](SETUP-CLI.md) now
provides offline planning, explicit read-only preflight and guarded companion
installation on an already-rooted baseline. The AMS builder accepts explicit
input/output paths and pins the exact supported original JAR internally.
The parent has now passed the **existing-install NotesDrive** wrapper preflight
and guarded same-APK apply, including delegated hook verification and final
signature/hash/scopes. Its original report status is `installed_pending_acceptance`.
Subsequent parent acceptance passed: Notes opened the new Mac-created blank
notebook with a visible canvas and closed normally; both latest fixture heads
were verified in Drive, uploads pending were zero, and both automatic settings
were on. Evidence is `notes-drive/research/incoming/latest-checkpoint.json`.
The later Mac own UI/OAuth round trip passed as recorded above. Physical BOOX
stylus validation is not established by the Mac GUI pen round trip.
OpenAI wrapper and first-install
paths are not live-validated; rooting/flashing remain outside the wrapper.
The stable [setup checkpoint](setup-checkpoint.json) records the tool/helper hashes
and the offline-validated plan for Notes APK `f15a19bb…`, hook build
`1b35137b83528a78`. The subsequent NotesDrive-only live plan is
`2d4531791aadf0796405372e3d2386727ccab53c01fc1a8a44503393b8edcfbf`;
its evidence is recorded in that checkpoint and [SETUP-CLI.md](SETUP-CLI.md).
Future reruns still require a current full device-bound backup receipt; the
native snapshot alone does not supply every required backup role. The parent
requested one inventory regeneration after these documentation updates, including
root README/HANDOVER, OpenAI XML-resource coverage and the two named shareable Mac
test-input JSON files. It is not a final-release snapshot. Final packaging and the
fresh-clone build await the Mac decoder fix/acceptance and source review;
parent README/HANDOVER hold the latest
overall acceptance status.

Older v0.3 headings in `HANDOVER.md`, `STATUS.md` and Notes READMEs describe
historical outbound sync. Their unfinished-feature lists are not authoritative
for the active v0.4 source. Consult `notes-drive/INCOMING-VALIDATION.md` and the
parent's final report for subsequent evidence.

## Read first

| File | Purpose |
| --- | --- |
| [Historical root and recovery](ROOT-RECOVERY.md) | Exact firmware boundary, boot/JAR hashes, flash/readback evidence, rollback and OTA |
| [Magisk input staging](../ROOT-STAGING.md) | Recreate the exact 15 verified historical APK assets and boot input without running a patcher |
| [Acquisition manifest](acquisition.json) | Observed versions, SHA-256 and source URLs; explicit missing provenance |
| [Source inventory](SOURCE-INVENTORY.md) | What belongs in the source repository, prototypes, exclusions and regeneration |
| [Packaging and dependency bootstrap](PACKAGING.md) | Dry-run source staging, checked dependency acquisition, build overrides and remaining fixed bindings |
| [Machine-readable inventory](source-inventory.json) | Per-file paths/hashes for selected authored source at this checkpoint |
| [Remaining gaps](GAPS.md) | Required inputs and unexecuted reproduction/live checks |
| [Staged setup CLI](SETUP-CLI.md) | Reviewed-plan/backup/closed-editor contract and explicit installation phases |
| `notes-drive/PROTOCOL.md` | Immutable revision, parent, folder and tombstone contract |
| `openai-adapter/OAUTH-VALIDATION.md` | Historical subscription tests and limitations |
| `notes-drive/macos/README.md` | Mac build, editor behavior and its current limits |

Paths below are relative to the workspace root unless they begin with `/` or `~`.
Run commands after `cd` to your checkout; `BOOX_SERIAL` is your own ADB serial.
No actual serial, account identity, notebook title, OAuth registration identity or
private signing key is included in these reproduction documents.

## 1. Establish the supported baseline

Historical hardware: **NoteAir4C**, Android 13 / API 33, firmware
`2026-04-28_17-50_4.2-rel_04282_555977efe`, security patch property `2026-04-01`,
active `_b`, already-unlocked bootloader. The Notes hook requires
`com.onyx.android.note` **versionCode 45326**. Vector was **2.2 / 3080 / API 102**.
Magisk initially **30.2 / 30200**, later observed **30.7** without a deliberately
recorded upgrade procedure.

A working existing device does not need rerooting. Use a separate recovery plan
before changing firmware, boot partitions, signing identities or app data.
No unlock procedure was performed or validated. A locked bootloader is outside
the demonstrated path. The original procedure flashed only boot B on UFS LUN 4.
**No GPT backup exists despite the EDL success messages.**

Read-only inventory:

```sh
python3 tools/boox_doctor.py
BOOX_SERIAL='<SERIAL>'
python3 tools/boox_doctor.py --serial "$BOOX_SERIAL"
python3 tools/boox_doctor.py --serial "$BOOX_SERIAL" --root-checks
```

Only the first command was executed by this documentation sidecar. The other
commands are reproduction examples for the parent/operator to run later.
The parent subsequently reported a read-only live doctor run matching
model/firmware/slot/root/Magisk/modules and both local APK hashes. Its table-form
scope output revealed a parser false negative; that parser is now corrected and
offline-tested. The final parent rerun now reports both scopes exact and zero
query failures. This doctor result is separate from the subsequent successful
NotesDrive wrapper preflight/apply described above. Live evidence was not changed
by this sidecar.
`--root-checks` assumes existing shell authorization in Magisk; omit it when
root requests must not trigger a prompt. ADB/Magisk may update their own connection
bookkeeping. The doctor itself issues only read queries, never installations,
grant changes, preference writes, UI actions, process stops or network/OAuth calls.

Doctor output includes an allowlisted set of model/firmware/slot properties,
package versions, framework hash, local/installed adapter APK hashes, and optional
Magisk/module versions and expected Vector scopes. It never reads grants,
Keychain, private app files, notebooks, private keys, accounts, broad logs or a
complete property list. Raw command output, exception text and the selected serial
are not printed. Unknown parsing stays unknown. APK hashes are byte identity,
**not certificate verification or proof of the hook loaded in a running process**.
Configured scope does not establish enabled module state. Exit 1 means a query
failed; exit 0 is an inventory result, not an installation/sync certificate.

## 2. Provision build tools and private inputs

The acquisition manifest pins observed archives. The parent has now passed a
fresh network bootstrap of all four default host-tool archives with exact hashes
and successful extraction; the actual fresh-clone build remains pending.
Other recorded archives need their own verification. Check each SHA-256 before extracting.
Do not download firmware, a loader or Magisk from an arbitrary mirror.
The loader is now an explicit exception to the earlier missing-acquisition
record: the parent downloaded its pinned bkerler/Loaders raw URL and verified
the identical SHA-256. Its exact commit/path/blob are recorded in
[ROOT-RECOVERY.md](ROOT-RECOVERY.md) and [acquisition.json](acquisition.json).
This closes exact-binary reproduction provenance, without identifying the
original historical loader checkout.

Historical host layout (still used by installer/auxiliary-tool defaults):

```text
tools/platform-tools/adb
tools/android-sdk/android-15/{aapt,d8,zipalign,apksigner}
tools/android-sdk/android-35/android.jar
tools/android-sdk/build-tools/35.0.0/...
tools/android-sdk/platforms/android-35/...
tools/gradle-8.11.1/bin/gradle
/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home/bin/javac
```

`android-15` is the historical extraction name for **build-tools 35.0.0**, not
Android API 15. Both historical flat paths and normal SDK paths are present in the
working environment. The new [bootstrap CLI](PACKAGING.md) verifies declared
archives and creates both layouts as ordinary files in a new directory.
The parent added `tools/android_build_env.py`: main Notes/OpenAI builds now honor
`JAVA_HOME`, `ANDROID_HOME`/`ANDROID_SDK_ROOT`, `BOOX_ANDROID_BUILD_TOOLS` and
`BOOX_GRADLE`. See PACKAGING for exact precedence and legacy fallbacks.
Probe/instrumentation/preflight builders now use the same resolver; native-corpus
preflight also honors `GRADLE_USER_HOME`. The frozen doctor's host report retains
its historical JDK path.
The acquisition manifest records current host metadata:
Oracle JDK17.0.16 (`17.0.16+12-LTS-247`), Xcode26.4.1 and Swift6.3.1. Original
download URLs/archive hashes and the original historical compiler version remain
unproven; current installed metadata is not a retained acquisition receipt.

Android compilation targets Java 8 bytecode. The manual OpenAI/probe builders use
API 35 libraries and D8 minimum API 26; Notes uses minSdk 28, target/compile 35.
Notes dependencies: Gradle **8.11.1**, AGP **8.9.2**,
`com.google.android.gms:play-services-auth:22.0.0`, `junit:junit:4.13.2`,
`org.json:json:20240303`. Google/Maven Central/plugin repositories are configured
in `notes-drive/android/settings.gradle`. A clean dependency download and full
transitive lock verification have not been executed here.

The Mac build requires Apple Silicon, macOS 13+, Apple command-line developer
tools providing `xcrun swiftc`, and working **`/usr/bin/python3`** at runtime.
It compiles with Swift language mode 5 and target `arm64-apple-macosx13.0`,
using SwiftUI, AppKit, Security, Network, CryptoKit and LocalAuthentication.
It creates an ad-hoc signed app; it is not notarized distribution.

Private inputs must be supplied locally:

1. Original matching boot/framework backups and compatible loader, if root repair
   is needed. They are not repository assets. See ROOT-RECOVERY.
2. Existing `openai-adapter/local-signing.p12` and
   `notes-drive/android/local-signing.p12` for compatible updates. Never put them
   in Git. Builders generate a new key when absent; that is a new installation
   identity, not a compatible update. The hard-coded local build password is not
   protection for distributing the private key.
3. For a new Notes installation, register its exact package
   `local.boox.notesdrive` plus newly built certificate SHA-1 with Google.
   Existing installations must preserve their original key/registration.
4. A Desktop OAuth client JSON for the Mac's **own** grant, at
   `notes-drive/macos/config/oauth-desktop.json` or
   `notes-drive/desktop-oauth.local.json`. Never extract another app's tokens.
5. Historical private fixture suites used reviewed disposable exports such as
   `Target-after.note` and `B2.note`. Parent/Mac owners are replacing mandatory
   build/test dependencies with synthetic generation. Verify those replacements
   in a fresh source export; do not distribute old native exports to fill the gap.

The current Mac build defaults to synthetic notebook resources without OAuth
configuration. Private configuration is included only through explicit
`--oauth-config FILE`; no prior bundle is inherited. Keep any configured bundle
private; source-only exports never include bundles.

## 3. Root, AMS repair and Vector

Follow [ROOT-RECOVERY.md](ROOT-RECOVERY.md), including input verification and the
distinction between saved historical evidence and reconstructed commands.
Do not run an EDL write just to test a normally booting tablet.

The AMS module must be built from the exact original `services.jar`, never a
generic replacement. Its installer checks `NoteAir4C` and the supplied original
JAR hash. The parameterized builder now checks the exact known original SHA-256
before importing its DEX dependencies, retains explicit instruction/DEX/JAR
proofs, and requires the exact historical patched JAR hash before writing new
outputs. It refuses to overwrite existing files. See ROOT-RECOVERY for its CLI.

After working Magisk, the historical Vector installer was
`tools/vector.zip`, v2.2 release. Installation and Zygisk changes are operator
actions, not doctor functions. Historical module installation used
`/debug_ramdisk/magisk --install-module`; Zygisk was enabled via Magisk SQLite.
The exact SQL invocation is not retained here. Reproduction should use the
Magisk application's explicit Zygisk setting and reboot, then verify actual
Vector status; that UI route is proposed, not replayed by this sidecar.

## 4. Build and configure the AI integration

For reviewed installation use [SETUP-CLI.md](SETUP-CLI.md). The direct commands
below preserve the historical build/scope procedure for reference.

```sh
python3 openai-adapter/build.py
tools/platform-tools/adb -s "$BOOX_SERIAL" install --no-incremental -r \
  openai-adapter/build/boox-openai-setup.apk
```

These are source-derived reproduction commands; the original build/install path
has historical evidence, but was not rerun here. Preserve the signing key and
private app state. Source package is `local.boox.openai`; its Xposed init entry
is `local.boox.openai.NativeHook`. Headers in `stubs/` are compile-only.

Set only these scopes and enable the module:

```sh
tools/platform-tools/adb -s "$BOOX_SERIAL" shell \
  '/debug_ramdisk/su -c "/data/adb/modules/zygisk_vector/cli scope set local.boox.openai com.onyx.aiassistant/0 com.onyx.kreader/0"'
tools/platform-tools/adb -s "$BOOX_SERIAL" shell \
  '/debug_ramdisk/su -c "/data/adb/modules/zygisk_vector/cli modules enable local.boox.openai"'
```

After saving native work, restart both Assistant and NeoReader so they load the
new hook. Do not scope this module to `system_server` or ksync. The original
system APKs remain in place. Enable/unfreeze the companion through the BOOX
launcher if installation auto-freezes it.

Open **BOOX OpenAI Setup** and privately configure one connection:

- **OpenAI API:** enter your own API key and model, save, run the explicit
  connection test, then activate that mode. Source calls
  `https://api.openai.com/v1/responses`, disables redirects and sends `store:false`.
  The app's UI describes separate API billing.
- **ChatGPT:** choose that configuration tab, initiate sign-in, copy the code,
  complete the official page yourself, return, refresh/select an available model,
  run the connection test and activate the mode. The source's sign-in page is
  `https://auth.openai.com/codex/device`. Its subscription transport uses
  `https://chatgpt.com/backend-api/codex`; this is the custom integration's
  historical behavior, not a guarantee of a stable public third-party API.
  Account eligibility and the currently returned model catalog must be checked
  in the user's own session. Do not hard-code historical account model lists.

API and OAuth secrets stay in the companion's encrypted, Keystore-backed state.
Do not collect codes, tokens, typed setup dumps or refresh grants. A private-data
backup alone does not export Android Keystore keys.

Historical v0.9/code9 has 48 recorded regression checks and live Setup,
full-screen Assistant and NeoReader contextual follow-up evidence. Earlier API
transport also exercised floating UI, Stop/recovery and regeneration; those were
not all repeated live for the subscription transport. Verify both native surfaces
with disposable text and a follow-up before describing a new installation as ready.

## 5. Build and connect Notes Drive Android

```sh
python3 notes-drive/android/build.py
```

The builder runs release JVM tests, assembles/signs the APK and writes
`notes-drive/android/registration.json` with APK SHA-256, signing certificate SHA-1
and the Java-source-derived `hook_build`. It also copies the disposable fixture
into assets and writes local SDK configuration. Preserve the generated registration
for your installation privately.

Register/confirm a Google project with Drive API enabled, an Android OAuth client
matching that package/certificate, correct consent branding/audience/test-user
configuration, and sole Drive scope `https://www.googleapis.com/auth/drive.file`.
The existing deployment already has its own project; reuse its local registration
instead of creating a duplicate. A fresh operator creates their own registration.
No identities from the existing project are reproduced here.

For an initial install, install the APK first, then explicitly set the scope and
enable the module:

```sh
tools/platform-tools/adb -s "$BOOX_SERIAL" install -r \
  notes-drive/android/app/build/outputs/apk/release/app-release.apk
tools/platform-tools/adb -s "$BOOX_SERIAL" shell \
  '/debug_ramdisk/su -c "/data/adb/modules/zygisk_vector/cli scope set local.boox.notesdrive com.onyx.android.note/0"'
tools/platform-tools/adb -s "$BOOX_SERIAL" shell \
  '/debug_ramdisk/su -c "/data/adb/modules/zygisk_vector/cli modules enable local.boox.notesdrive"'
```

Initial install/registration on a clean device is not end-to-end revalidated.
For subsequent updates, close/save **every** native editor before the guarded
installer:

```sh
python3 tools/install_notes_drive.py --serial "$BOOX_SERIAL" --notes-closed
```

The broader staged wrapper in [SETUP-CLI.md](SETUP-CLI.md) adds exact
device/signing/backup guards around an unchanged, private staged copy of this
helper. Prefer that wrapper for reproducible reviewed deployment.

This installer is **mutating**, unlike the doctor. It verifies Notes45326, stops
the closed Notes process, separates Vector disable/enable to permit cache rebuild,
installs and checks APK bytes, launches the library/setup, then requires the expected
hook-build marker from the current Notes PID. It does not set scopes itself.
`--notes-closed` is an operator assertion; never use it to bypass unsaved work.
Same-version APK installation alone previously left stale hooks loaded.

Launch the connector, connect through Google Play services, and create/select its
managed folder. Use the registered Android and Desktop clients in the same project
and independently authorize each client. Match the selected destination by exact
identity in the private app session, not merely the display name; earlier work
created more than one similarly named folder. Unfreeze the connector persistently
through the BOOX launcher.

Run the explicit disposable Drive round-trip test. Before enabling native apply on
new content, run strict preflight against private exports and review unsupported
features:

```sh
python3 notes-drive/tests/preflight_native_corpus.py /PRIVATE/PATH/notebook.note
```

This preflight is offline but consumes private notebook content; keep its output
private. It needs the pinned `org.json` JAR resolved by the Android build. It is
not the doctor and should not be run on personal data during repository packaging.

## 6. Build and independently connect the Mac editor

```sh
bash notes-drive/macos/test.sh
bash notes-drive/macos/build.sh
open "notes-drive/macos/build/BOOX Notes Reader.app"
```

These are reproduction instructions, not actions performed by this sidecar.
`build.sh` preserves the prior app bundle and does not restart the running app.
Save any drafts, then restart deliberately to use the new build.

Configure the matching Desktop OAuth client locally; complete the Mac's own
browser consent. Its refresh token belongs in its own Keychain item. Do not
copy Android grants or desktop-connector credentials. If the Mac is locked or
Keychain requires interaction, unlock/reconnect normally; successful encoding
or an Android-grant publication is not a substitute.

Select the managed Drive folder and open a verified current head. Draw with Pen
or use whole-stroke erasing on a supported visible/unlocked layer, then save to
the library. Drafts and queued snapshots are durable; conflicts retain parents
and both heads. The Mac's preview uses recorded pen coordinates and nominal
width/color; it does not reproduce BOOX's full brush/pressure renderer.
Consult the Mac README for current folder/notebook operations and limits.

Supplemental `--validate-probe-publish` in the Mac app is an explicit disposable
probe transport path, not interactive editor validation. Read
`notes-drive/macos/PROBE-CLI.md` before use. Do not claim that a publication through
the Android app's grant proves the Mac's independent OAuth path.

## 7. Acceptance and regression checks

Run the applicable local suites after build inputs are supplied:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tools -p 'test_*.py'
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover \
  -s notes-drive/prototype -p 'test_*.py'
python3 notes-drive/android/build.py
bash notes-drive/macos/test.sh
python3 openai-adapter/tests/build.py
```

The last command builds the isolated Android instrumentation app; it does not run
the 48 device checks. Follow `openai-adapter/VALIDATION.md` and
`OAUTH-VALIDATION.md` for runner targets and fixture constraints. Prototype tests
are historical research, not the production native-apply verifier.
Device publishers, crash injectors and apply probes are mutating validation tools;
never include them in a generic unattended doctor or run all scripts indiscriminately.

Full replacement acceptance still needs a current recorded sequence:

1. Verify the installed APK and actual loaded hook generation.
2. Save a disposable native notebook, reach the Mac, edit from its current verified
   head using the interactive Mac app, publish with its own grant, and reopen the
   same-ID descendant in BOOX with semantic readback.
3. Repeat after offline saves and app/device restarts, verifying exact queued bytes
   and ancestry; exercise concurrent edits without losing either head.
4. Exercise supported folders, rename/move, notebook/folder deletion and restore,
   routed native sync controls, open-editor exclusion and recoverable failures.
5. Confirm originals against the correct pre-change checkpoint, not a stale older
   library snapshot. Do not restore historical archives wholesale over newer work.

Historical outbound sync, staged Mac interoperation and native add/erase commits
are separate passed milestones. They do not certify every notebook type,
background lifetime, long-term grant renewal or an OTA.

## 8. Package the source only after final validation

Use [SOURCE-INVENTORY.md](SOURCE-INVENTORY.md) as the allowlist basis. This sidecar
does not create/push Git repositories, move files, remove evidence or alter the
root README. A private repository still must exclude credentials, keys, firmware,
decompiled proprietary apps and personal notebooks/backups.

The new [packaging CLI](PACKAGING.md) provides a deterministic dry-run manifest
and an explicitly reviewed local tar export. A plan with privacy, path or digest
findings blocks export. No real source tar or dependency installation was created
by this sidecar during this continuation.

Before final packaging regenerate the source inventory after parent/Mac edits,
sanitize legacy narrative files and test fixtures, and execute a clean-checkout
build using the documented inputs. Preserve private backups separately.
No claim of a completed GitHub publication is made here.
