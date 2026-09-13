# BOOX Note Air4C: end-to-end setup

This guide takes a source checkout through dependency acquisition, builds,
firmware prerequisites, companion installation, account setup and a live
acceptance check. Run commands from the checkout root. Replace placeholder
paths and device selectors with your own values; keep generated plans, receipts,
signing keys and account configuration in private storage.

## Validated scope

The supported device is **NoteAir4C**, Android 13/API 33, firmware
`2026-04-28_17-50_4.2-rel_04282_555977efe`, active slot `_b`, with native Notes
**versionCode 45326**. Companion setup requires working root, the exact AMS
framework overlay and Vector **2.2 / 3080**. Magisk codes **30200** and **30700**
are accepted by the installer; the recorded root procedure originally used 30.2.

The source-only build checkpoint (`113c0fe`) produced all five Android APKs and
the Mac app without private notebooks, account configuration or existing signing
keys. Android 57, Mac 162, host 115 and portable prototype 19 checks passed. The
pre-publication documentation baseline is `3fa9050`; dependency verification
records 501 SHA-256 entries across 291 components and a passing strict offline
Android build. This used an existing Mac toolchain, not a fresh operating-system
installation. [Build evidence](../VALIDATION.md) records the artifacts.

Mac v0.6 live acceptance now includes a nested native notebook: GUI
draw/undo/redo/save through its own OAuth grant published `dc3b267a8ea4…`, Android
committed one pen under the same ID, and stock Notes displayed the result.
Normal native close produced `2cab97843ab9…`, which the Mac automatically
downloaded and then opened from the library. A subsequent native UI rename
produced `009b9ae05cad…`; the already-open Mac document automatically adopted the
new title while retaining page, zoom, one pen and two samples, without refresh
or reopening. The bundled test notebook loaded through the GUI with two pages
and 343 samples. All v0.6 live acceptance gates are complete. These abbreviated
historical evidence IDs are not revision bases for a new test.

| Automated stage | Operator-supplied prerequisites / acceptance |
| --- | --- |
| Hash-pinned dependency bootstrap and source builds | Installed Python/JDK/Xcode; initial dependency network access |
| Host-only Magisk input staging and AMS builder | Exact original boot/framework inputs; separate root/recovery procedure |
| Offline setup plan, read-only preflight, guarded APK apply | Root/AMS/Vector, current backup receipt, closed editors, correct signing identities |
| App revision queues and verified native application | Each app's own OAuth setup, matching Drive destination, supported notebooks |

A clean local clone and an existing NotesDrive update have passed. First-install
and OpenAI wrapper paths are offline-tested but have no fresh-device live
acceptance. Root flashing, module provisioning, backups and OAuth are outside
the companion installer's automation. [GAPS.md](GAPS.md) lists the remaining
boundaries without requiring the historical handover chronology.

## 1. Acquire the host tools

The tested host is Apple Silicon macOS with Python 3.10+, JDK 17 and Xcode
command-line tools. The Mac app targets macOS 13+ and uses `/usr/bin/python3`
at runtime. The bootstrap recipes supply macOS Android tools; they are not a
Linux/Windows SDK installer.

Choose an existing private review directory and a **new** dependency directory:

```sh
export BOOX_REVIEW='/PRIVATE/REVIEW'
export BOOX_DEPS='/PRIVATE/NEW-DEPENDENCIES'
python3 tools/bootstrap_dependencies.py > "$BOOX_REVIEW/dependencies-plan.json"
```

Review the plan, then materialize exactly those archives:

```sh
python3 tools/bootstrap_dependencies.py --apply \
  --destination "$BOOX_DEPS" \
  --reviewed-plan "$BOOX_REVIEW/dependencies-plan.json"
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
export ANDROID_HOME="$BOOX_DEPS/tools/android-sdk"
export BOOX_GRADLE="$BOOX_DEPS/tools/gradle-8.11.1/bin/gradle"
export GRADLE_USER_HOME='/PRIVATE/NEW-GRADLE-CACHE'
```

Defaults are platform-tools 37.0.1, build-tools 35.0.0, Android platform 35
revision 2 and Gradle 8.11.1. Every archive is size/hash-checked before extraction.
The output must not already exist. To reuse retained archives without a download,
add `--offline --archive-root '/PRIVATE/ARCHIVES'` to the apply command; archive
paths must match [acquisition.json](acquisition.json). Bootstrap does not accept
SDK licenses or install JDK/Xcode/Python.

Builders honor the environment above. The companion installer still expects
workspace-relative SDK/ADB paths. In a fresh checkout where the following two
paths are absent, expose the verified dependency tree to it:

```sh
ln -s "$BOOX_DEPS/tools/android-sdk" tools/android-sdk
ln -s "$BOOX_DEPS/tools/platform-tools" tools/platform-tools
```

If those paths already exist, retain and verify their tool versions instead.
The bootstrap includes both standard SDK layout and the historical
`android-15` build-tools directory used by the installer. That name denotes
build-tools 35.0.0, not API 15. [PACKAGING.md](PACKAGING.md) documents resolver
options and export mechanics; its older dated checkpoints are historical.

## 2. Build apps and run portable checks

For an update, supply each companion's existing private `local-signing.p12`
before building. With no key present, a builder generates a new installation
identity. Register that identity with Google for a new Notes deployment.

```sh
python3 openai-adapter/build.py
python3 openai-adapter/tests/build.py
python3 notes-drive/android/build.py
python3 notes-drive/probe/build.py
python3 notes-drive/apply-probe/build.py
bash notes-drive/macos/build.sh
bash notes-drive/macos/test.sh
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tools -p 'test_*.py'
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s notes-drive/prototype -p 'test_*.py'
```

OpenAI production must precede its instrumentation build. The Notes builder
creates the synthetic fixture assets before Gradle, runs unit tests, builds the
APK and writes `notes-drive/android/registration.json` with its certificate,
APK hash and source-derived hook generation. It imports the retained Mac
synthetic tooling; keep those source files in the checkout.

Normal installation uses these two APKs:

- `openai-adapter/build/boox-openai-setup.apk`
- `notes-drive/android/app/build/outputs/apk/release/app-release.apk`

The instrumentation APK and both historical probe APKs are built for source
verification and explicitly selected research tests, not normal installation.
Building instrumentation does not execute its 48 recorded device checks.
The default prototype suite skips 21 private-capture cases; one host
original-input staging case also skips unless explicitly requested. These skips
do not require copying private data into a source checkout.

After the first successful Notes build has populated the cache, repeat its
Gradle tasks with strict offline verification while preserving the hook value:

```sh
BOOX_HOOK_BUILD="$(python3 -c 'import json; print(json.load(open("notes-drive/android/registration.json"))["hook_build"])')"
"$BOOX_GRADLE" -p notes-drive/android --no-daemon --console=plain \
  --dependency-verification strict --offline "-PhookBuild=$BOOX_HOOK_BUILD" \
  testReleaseUnitTest assembleRelease
```

The checked-in `gradle/verification-metadata.xml` pins the resolved dependency
artifacts. Offline mode requires the populated cache; it does not acquire missing
dependencies. Re-run the Python builder before installer planning if an APK was
changed by a separate build, so registration matches the final APK.

## 3. Establish root, AMS and Vector prerequisites

An already-working supported installation can proceed to inspection. For a
root reproduction, use [ROOT-RECOVERY.md](ROOT-RECOVERY.md) and
[ROOT-STAGING.md](../ROOT-STAGING.md). They contain exact firmware/boot/JAR
hashes, historical flash geometry and evidence boundaries. The bootloader was
already unlocked; no unlock procedure was tested.

The official Magisk v30.2 download was verified byte-for-byte against the saved
installed APK. Its pinned recipe, Vector and the loader can be acquired as
opaque artifacts using a separate plan and new destination:

```sh
python3 tools/bootstrap_dependencies.py \
  --select magisk_30_2 --select vector --select edl_loader \
  > "$BOOX_REVIEW/root-artifacts-plan.json"
python3 tools/bootstrap_dependencies.py --apply \
  --select magisk_30_2 --select vector --select edl_loader \
  --destination '/PRIVATE/NEW-ROOT-ARTIFACTS' \
  --reviewed-plan "$BOOX_REVIEW/root-artifacts-plan.json"
python3 tools/prepare_magisk_stage.py \
  --apk '/PRIVATE/NEW-ROOT-ARTIFACTS/tools/Magisk-v30.2.apk' \
  --original-boot '/PRIVATE/ORIGINALS/boot_b.img' \
  --output '/PRIVATE/NEW-MAGISK-STAGE'
```

Staging verifies both exact originals and 15 APK assets. It produces an inert
payload, hashes and reviewed device-local patch instructions. Its status is
`prepared_not_patched`: no new patch or flash was performed. The historical
patched boot is a comparison target, not a newly verified result. The exact
missing-support-file repair and later Magisk 30.7 upgrade sequence were not
recorded. The historical EDL GPT command produced no GPT backup payloads.
Full recovery and OTA have not been rehearsed.

Build the AMS overlay only from the exact supported original `services.jar`.
Use a Python environment with the recorded `androguard==4.1.4` and `loguru`
dependencies; their full transitive acquisition lock remains incomplete:

```sh
python3 tools/build_ams_fix.py \
  --source '/PRIVATE/ORIGINALS/services.jar' \
  --output '/PRIVATE/NEW-OUTPUT/boox-ams-fix.zip' \
  --patched-jar '/PRIVATE/NEW-OUTPUT/services-patched.jar'
```

The builder verifies the original hash, the precise DEX change and the exact
patched JAR hash. Module installation, enabling Zygisk and installing Vector
are separate device operations described in the root guide. Historical module
installation used Magisk's `--install-module`; the proposed Zygisk UI route is
not a replayed installation test. Verify the active framework hash and Vector
version before companion setup. The AMS overlay must be disabled/removed before
a firmware update; a new framework requires analysis rather than reuse of the
old JAR.

Inspect the host and then the explicitly selected device:

```sh
python3 tools/boox_doctor.py
export BOOX_SERIAL='<SERIAL>'
python3 tools/boox_doctor.py --serial "$BOOX_SERIAL"
python3 tools/boox_doctor.py --serial "$BOOX_SERIAL" --root-checks
```

Root queries require existing shell authorization. The doctor reads selected
metadata and hashes; it does not install, read grants or change app state.
Its successful exit is an inventory result, not proof of loaded hooks or sync.

## 4. Register clients and install the companions

For Notes Drive, enable the Drive API in your Google project and register an
Android OAuth client for `local.boox.notesdrive` and the certificate SHA-1 in
`registration.json`. Configure the consent audience/test users and `drive.file`
scope. Register a Desktop OAuth client in the same project for the Mac. Existing
deployments retain their own project, signing identities and app-owned grants.

The installer requires saved/closed Notes, Assistant and NeoReader editors, the
supported rooted baseline, reviewed APK identities and a current device-bound
backup receipt. Prepare the receipt using
[the schema and required roles](SETUP-CLI.md#required-backup-receipt); the example
receipt is invalid until populated with actual checkpoint hashes and
attestations. The CLI validates that receipt but does not create backups.

For the live-validated **existing NotesDrive update** route:

```sh
python3 tools/boox_setup.py --install notesdrive \
  --write-plan "$BOOX_REVIEW/notes-plan.json"
```

Review the plan and supply a current receipt, then run preflight and apply:

```sh
python3 tools/boox_setup.py --install notesdrive --preflight \
  --serial "$BOOX_SERIAL" --editors-closed \
  --reviewed-plan "$BOOX_REVIEW/notes-plan.json" \
  --backups '/PRIVATE/BACKUP/receipt.json'
python3 tools/boox_setup.py --install notesdrive --apply \
  --serial "$BOOX_SERIAL" --editors-closed \
  --reviewed-plan "$BOOX_REVIEW/notes-plan.json" \
  --backups '/PRIVATE/BACKUP/receipt.json' \
  --report "$BOOX_REVIEW/notes-apply.json"
```

Each plan/report output must be new. Apply rechecks firmware, signatures, scopes,
backups and inputs, delegates Notes hook refresh to the guarded installer, and
verifies the actual current Notes process loaded the expected hook generation.
Its final status is `installed_pending_acceptance`; account setup and the sync
check below establish application readiness separately.

For a **first installation of both companions**, generate a different plan:

```sh
python3 tools/boox_setup.py \
  --install notesdrive --first-install notesdrive \
  --install openai --first-install openai \
  --write-plan "$BOOX_REVIEW/first-install-plan.json"
```

Use those same four component flags in the preflight/apply templates above,
select that first-install plan, supply the matching backup roles, and add
`--registration-confirmed` to both live phases. The first-install route requires
both packages/modules to be absent. For an OpenAI update, select
`--install openai` without its first-install flag and provide its update backup
roles. First-install, OpenAI-only and combined wrapper routes have offline
validation, not live installer acceptance.

The intended scopes are fixed:

| Companion | Native scope |
| --- | --- |
| `local.boox.openai` | `com.onyx.aiassistant/0`, `com.onyx.kreader/0` |
| `local.boox.notesdrive` | `com.onyx.android.note/0` |

The wrapper sets scopes only for explicit first installs. Updates require the
existing scopes to match. It does not provision root, AMS/Vector, backups, Google
registration or credentials. See [SETUP-CLI.md](SETUP-CLI.md) for exact guards,
receipt requirements and partial-failure handling. Preserve queued changes and
later native edits during recovery; old whole-library snapshots are historical
checkpoints, not automatic rollback targets.

## 5. Authorize and configure each app

Open **BOOX OpenAI Setup**, unfreezing it in the BOOX launcher if needed. Configure
an API key/model or complete the custom ChatGPT device-code sign-in in your own
browser session, then select a returned model, test the connection and activate
that mode. The ChatGPT transport is a custom integration with version-dependent
behavior; historical model lists are not configuration defaults. API keys and
grants stay in the app's Keystore-backed state. A private app-data backup alone
does not export Android Keystore keys.

Test a disposable question and follow-up in the native Assistant, then selected
passage context in NeoReader. [OAuth validation](../../openai-adapter/OAUTH-VALIDATION.md)
and [NeoReader validation](../../openai-adapter/NEOREADER-VALIDATION.md) describe
the recorded transport/surface coverage. Installation of the OpenAI APK alone
does not prove a newly loaded hook in both processes.

Open **BOOX Notes Drive**, complete Google Play services authorization and
create/select its managed folder. Enable automatic publishing/following as
appropriate. Run its disposable transport check before a notebook acceptance
check. For private corpus compatibility review, the optional offline command is:

```sh
python3 notes-drive/tests/preflight_native_corpus.py '/PRIVATE/EXPORT/notebook.note'
```

This consumes the selected private export and the pinned `org.json` dependency
from the build cache; it is not a mandatory build or a device test.

Launch the Mac app:

```sh
open 'notes-drive/macos/build/BOOX Notes Reader.app'
```

Import the downloaded Desktop OAuth client JSON through the app, complete its
own browser sign-in and allow any local Keychain access prompt. Select the same
managed Drive destination by its identity, not just its display name. Each app
uses its own normal grant. Builds omit Google configuration by default; a
configured bundle remains a private runtime artifact.

Open a verified library head, draw with Pen or use whole-stroke erasing on a
supported layer, then choose **Save to library**. Drafts and queued snapshots
are durable; revisions retain parent relationships and conflicts preserve all
heads. [The Mac guide](../../notes-drive/macos/README.md) covers supported editing,
folder/notebook operations, queue recovery and renderer limitations.

## 6. Accept the installed workflow

Use a clearly named disposable notebook, with all participating apps connected
to the same intended destination:

1. Record the installed companion identity and actual loaded Notes hook.
2. Save/close the notebook in BOOX and open its current verified head on the Mac.
3. Draw, undo/redo and save in the Mac UI using its own grant. Confirm Drive
   publication, native same-ID commit/readback and visible stock Notes opening.
4. Close/save in native Notes. Confirm the Android return revision reaches the
   Mac and opens correctly. Test automatic replacement of an already-open Mac
   document separately from automatic download plus a library open.
5. Exercise the supported lifecycle operations needed for your deployment and
   retain revision/queue evidence for interruptions or conflicts.

The v0.6 nested-notebook round trip passed steps 2–4, including automatic
return download and a library open, followed by a separate native rename that
updated the already-open Mac document without refresh or reopening. The bundled
fixture GUI check also passed. Earlier live validation covers add/erase,
notebook/folder lifecycle, all seven native crash checkpoints and Android
conflict selection with both heads retained. Long-term renewal, large-library
scale and complete rendering parity remain separate boundaries. Physical BOOX stylus entry on a freshly Mac-created blank
has not been established by the Mac mouse-drawn pen test.

## 7. Source export and repository status

The intended public repository is `jakezp/boox-local-connectors`. Its content is
reviewed authored source, documentation, synthetic fixtures and dependency
verification metadata. Planned `jakezp/boox-private-archive` holds the complete
private workspace archive, including firmware, keys, notebooks, backups and
historical evidence. Neither GitHub repository has been created or published at
this checkpoint; a clone from the published remote has therefore not been
verified. The source-only local clone/build has passed independently.

The public build does not require access to the private archive. Existing-device
updates and root repair still need the matching private signing/firmware inputs.
Private archive completeness and publication are separately verified operations;
no complete archive is claimed here.

For public source review, use the [source inventory](SOURCE-INVENTORY.md) and
[packaging CLI](PACKAGING.md):

```sh
python3 docs/reproduction/inventory_sources.py
python3 tools/package_source.py
```

Regenerate the inventory after final source/documentation edits, review the exact
allowlisted bytes and use the packaging tool's reviewed-plan export flow. The
public export excludes private runtime inputs and generated/acquired artifacts.
Preserve the original private workspace when preparing either repository.
