# BOOX local connectors

Firmware-specific BOOX Note Air4C integrations for OpenAI/ChatGPT and Google Drive
Notes sync, with a native Mac reader/editor and reproducibility tooling.

**Current work is still under validation.** The original OpenAI/ChatGPT connector
is working; the Drive replacement now supports automatic publishing, journaled
native incoming updates, folders, moves, recoverable deletion/restoration and
explicit conflict resolution. Final Mac interactive validation and clean-checkout
reproduction remain unfinished. This workspace has not yet been published to GitHub.

Start with the [end-to-end reproduction guide](docs/reproduction/README.md). It
covers the historical root procedure, Magisk AMS fix, Vector, builds, connector
configuration, native application changes, testing and recovery. It distinguishes
what was actually tested from steps that still require rehearsal.

| Component | Source and guide |
| --- | --- |
| Root recovery and firmware boundaries | [Root/recovery guide](docs/reproduction/ROOT-RECOVERY.md) |
| Recreate the exact historical Magisk patch inputs | [Private boot staging](docs/ROOT-STAGING.md), `tools/prepare_magisk_stage.py` |
| Firmware-specific Magisk manager fix | `tools/build_ams_fix.py` |
| OpenAI API key and ChatGPT sign-in | [OpenAI validation](openai-adapter/OAUTH-VALIDATION.md), `openai-adapter/src/` |
| Native AI/NeoReader integration and footer spacing | [NeoReader validation](openai-adapter/NEOREADER-VALIDATION.md) |
| Notes Drive Android connector and native hooks | `notes-drive/android/`, [current validation](notes-drive/INCOMING-VALIDATION.md) |
| Mac Notes reader/editor | [Mac guide](notes-drive/macos/README.md), `notes-drive/macos/Sources/` |
| Shared immutable revision protocol | [Protocol](notes-drive/PROTOCOL.md) |
| Setup planning and diagnostics | [Setup CLI](docs/reproduction/SETUP-CLI.md), `tools/boox_doctor.py` |
| Dependency provenance and authored file inventory | [Source inventory](docs/reproduction/SOURCE-INVENTORY.md) |
| Active continuation | [Implementation plan](IMPLEMENTATION-PLAN.md), [handover](HANDOVER.md) |

See [the current validation record](docs/VALIDATION.md) for source/build evidence.

## Validation checkpoint

Android currently passes 57 unit tests. Live disposable tests have verified
Mac-generated pen add/erase payloads in the native editor, all seven native
crash/recovery checkpoints, folder creation/rename/move/delete/restore, notebook
deletion/restoration and Android conflict selection retaining both branches.
All 182 original files and every field in the three original notebook rows are
unchanged. A new Mac-created blank notebook also applied successfully. The native
library Drive panel and editor Sync action passed live checks.

The frozen Mac v0.5 lifecycle checkpoint passed 125 local tests. The current
Mac v0.6 suite passes 162 checks using generated synthetic notebooks. The Mac now reconnects with its own saved Google grant. A real UI pen edit
published automatically, applied in BOOX with six strokes intact, opened visibly,
and returned through a native save to the Mac via automatic following. A new
notebook created and renamed/moved in the Mac UI also appeared in BOOX. Whole-stroke
erase, undo/redo, recoverable deletion and restoration passed through the Mac UI
and native readback. Testing found a Mac decoder issue with ancestor folder
records in native exports; v0.6 corrects it and preserves those records during
edits. The source-only checkout builds all five Android APKs and the Mac app, with
162 Mac checks, 57 Android checks and 115 host checks passing. Live acceptance
of that rebuilt Mac app is awaiting its macOS Keychain access prompt. Earlier Android-grant
fixture tests remain separately identified from this own-OAuth round trip.

## Supported boundary

The native adapter is gated to Notes **45326** on the inspected NoteAir4C firmware
`2026-04-28_17-50_4.2-rel_04282_555977efe`. The AMS framework patch has an exact
original-JAR hash guard. Other firmware requires analysis and validation.

Current limits include 4 MiB notebook payloads, 200 Android-managed items,
1,000 Drive objects and 64 MiB per verified catalog refresh. Locked/associated
notebooks and unsupported metadata remain held for review. Incoming native
changes wait for editors to close. The Mac synchronizes while running and edits
pen strokes and library metadata; it does not reproduce every BOOX rendering or
editing feature. Android may defer work during sleep.

## Working safely and reproducing results

Run the host-only inventory without touching a device:

```sh
python3 tools/boox_doctor.py
```

Read the reproduction and setup guides before issuing device commands. An
already-working device does not need rerooting. Preserve its signing keys,
app-owned grants, scoped module configuration and current backups.

The original bootloader was already unlocked. The historical root operation
flashed only boot B on UFS LUN 4. No GPT backup was actually produced despite the
old tool's success message. Boot images, firmware JARs, private keys, personal
notebooks and credential-bearing backups are local inputs, not source assets.

The cleanup phase will retain every authored app, script and test, replace private
test dependencies with reproducible synthetic fixtures, organize generated/private
evidence separately, scan the exact upload set and verify a fresh checkout.
Existing backups and investigation evidence must remain intact throughout.

## Build and test from source

On Apple Silicon macOS, install Python 3.10+, JDK 17 and the Xcode command-line
tools. The [dependency bootstrap](docs/reproduction/PACKAGING.md) retrieves
hash-pinned Android SDK components and Gradle into a separate directory. Set
`JAVA_HOME`, `ANDROID_HOME` and `BOOX_GRADLE` if using tools outside the checkout.

```sh
python3 openai-adapter/build.py
python3 openai-adapter/tests/build.py
python3 notes-drive/android/build.py
bash notes-drive/macos/build.sh
bash notes-drive/macos/test.sh
python3 -m unittest discover -s tools -p 'test_*.py'
python3 -m unittest discover -s notes-drive/prototype -p 'test_*.py'
```

Builds generate synthetic notebook assets. New Android signing keys stay local;
preserve existing keys when updating an installed connector. The Mac build
contains no Google credentials by default: import your downloaded Desktop client
JSON in the app and sign in independently. Android's OAuth registration uses
its package name and the certificate fingerprint printed by its build.

The prototype suite runs 19 portable tests and explicitly skips 21 historical
capture-dependent tests unless `BOOX_PRIVATE_WORKSPACE` is supplied. The optional
Magisk and Mac private-input checks are similarly separate from mandatory tests.
All historical probe source and builders are retained for investigation.

For installation, follow the [end-to-end guide](docs/reproduction/README.md):
establish the matching rooted firmware, prepare the AMS overlay, install Vector,
configure the two narrowly scoped connectors, authorize each client, then test a
disposable notebook. The [setup CLI](docs/reproduction/SETUP-CLI.md) supports
reviewed companion installation on an already-rooted baseline. Root preparation
and partition flashing remain separate stages with explicit input verification.
