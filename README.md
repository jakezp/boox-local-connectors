# BOOX local connectors

OpenAI/ChatGPT integration for the native BOOX AI Assistant and NeoReader, plus
Google Drive synchronization between native BOOX Notes and a Mac pen editor.
The Android hooks target **NoteAir4C**, firmware
`2026-04-28_17-50_4.2-rel_04282_555977efe`, and **Notes versionCode 45326**.

Start with the [end-to-end setup guide](docs/reproduction/README.md). It covers
hash-verified dependency acquisition, builds, root prerequisites, guarded APK
installation, independent account setup and a disposable notebook acceptance
check. Builds and companion installation have automation; root/flashing,
AMS/Vector provisioning, backups and account authorization are separate steps.

## What works

The native AI integration supports an API connection or the custom ChatGPT
sign-in route, with retained conversations and NeoReader passage context.
Notes Drive publishes immutable revisions and applies incoming changes through
an idle-editor, journaled transaction with semantic readback. It supports pen
edits, folders, notebook creation/rename/move, recoverable deletion/restoration
and explicit conflict resolution that preserves every head.

The Mac v0.6 editor uses its own Google authorization. Live nested-notebook
acceptance passed: GUI draw/undo/redo/save published a revision, BOOX committed
one pen under the same notebook ID and displayed it in stock Notes, and normal
native close produced a return revision that the Mac downloaded automatically
and opened from its library. A subsequent native rename automatically updated
the already-open Mac document title while retaining page, zoom, one pen and two
samples, without refresh or reopening. The bundled test notebook also loaded
in the GUI with two pages and 343 samples. The v0.6 live acceptance gates are complete.

Earlier live checks cover pen addition/whole-stroke erasing, notebook and folder
lifecycle, seven native apply crash checkpoints and Android conflict selection.
The recorded preservation checkpoint matched all 182 associated original files
and all fields in three original notebook rows; it describes that checkpoint,
not the state of later user edits.

## Build and verification

A separate source-only Git clone built all five Android APKs and the Mac app,
using freshly downloaded, hash-verified Android tools, an empty Gradle cache,
synthetic notebook fixtures and newly generated signing identities. It used the
host's existing Python, JDK and Xcode installations.

| Suite | Passed |
| --- | ---: |
| Android unit tests | 57 |
| Mac mandatory checks | 162 |
| Host tools (including archive checks) | 133 |
| Portable protocol/prototype tests | 19 |

One host private-input test and 21 historical prototype cases skip by default.
The Android dependency verification metadata contains **501 SHA-256 entries
across 291 components**; a subsequent strict offline build passed.
[Validation evidence](docs/VALIDATION.md) records the build artifacts and source
relationship. The pre-publication build/documentation baseline is `3fa9050`.

On Apple Silicon macOS, provision Python 3.10+, JDK 17, Xcode command-line tools
and the [pinned SDK/Gradle dependencies](docs/reproduction/README.md#1-acquire-the-host-tools),
then run from the checkout:

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

The instrumentation and two historical probe APKs are verification/research
artifacts; normal use installs only the OpenAI and Notes Drive companions.
Existing installations need their original signing keys for compatible updates.
New builds generate local keys and need matching Google registration. The Mac
bundle contains no Google configuration by default; import a Desktop client JSON
through the app and complete its own sign-in.

## Guides and components

| Area | Guide |
| --- | --- |
| Acquisition, builds and installation | [End-to-end setup](docs/reproduction/README.md) |
| Reviewed plan, backup receipt and APK apply | [Setup CLI](docs/reproduction/SETUP-CLI.md) |
| Root, exact firmware inputs and recovery | [Root/recovery](docs/reproduction/ROOT-RECOVERY.md), [host-only Magisk staging](docs/ROOT-STAGING.md) |
| Native AI and reading context | [OpenAI/ChatGPT](openai-adapter/OAUTH-VALIDATION.md), [NeoReader](openai-adapter/NEOREADER-VALIDATION.md) |
| Native Notes application and protocol | [Incoming changes](notes-drive/INCOMING-VALIDATION.md), [immutable protocol](notes-drive/PROTOCOL.md) |
| Mac editor and independent OAuth | [Mac guide](notes-drive/macos/README.md) |
| Source export and dependency provenance | [Source inventory](docs/reproduction/SOURCE-INVENTORY.md), [packaging](docs/reproduction/PACKAGING.md) |
| Current limitations | [Acceptance boundaries](docs/reproduction/GAPS.md) |

Run `python3 tools/boox_doctor.py` for a host-only inventory. Device inspection
requires an explicit `--serial`; existing-root queries also require
`--root-checks`. The installer checks firmware, Notes, framework, signatures,
backups and scopes before applying reviewed APKs. Its existing Notes update path
has live acceptance; first-install and OpenAI wrapper paths have offline tests.

## Scope and distribution

Payloads are limited to 4 MiB per notebook, 200 Android-managed items, 1,000 Drive
objects and 64 MiB per verified catalog refresh. Native incoming changes wait
for editors to close. Android sleep can defer work; Mac synchronization runs
while the app is open. The Mac supports normal pen editing and whole-stroke
erasing, with bounded library operations; it does not reproduce every BOOX
content type, pressure brush or page/layer editing feature.

Root support is firmware-specific. The original bootloader was already unlocked;
host staging reproduces the verified Magisk 30.2 inputs, but no new boot patch or
flash was performed. The historical GPT command produced no GPT backup files.
The exact earlier Magisk support-file repair and later 30.7 upgrade sequence
remain unrecorded. Recovery and OTA boundaries are in the root guide.

The public source is maintained at **`jakezp/boox-local-connectors`**. The owner's
complete recovery collection is kept separately in **`jakezp/boox-private-archive`**,
including firmware, project keys, builds, backups and evidence. The private
repository's snapshot manifest and verification receipts identify the retained
files and release assets. The public source builds its apps and mandatory tests
without access to that archive.

See [private archival and restoration](docs/PRIVATE-ARCHIVE.md) for the complete
snapshot format and [third-party notices](docs/THIRD-PARTY-NOTICES.md) for interface
attribution. Private runtime inputs and firmware-derived artifacts remain outside
the public source export.
