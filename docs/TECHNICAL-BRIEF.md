# Technical brief

This document explains the implementation behind BOOX Local Connectors. For a
first installation, start with the [README](../README.md). For exact test results
and historical source/build identifiers, use [validation evidence](VALIDATION.md).

## Design and device boundary

The project adapts native BOOX applications through companion APKs and Vector
runtime hooks. Assistant, NeoReader and Notes remain the installed vendor apps.
A separate Swift Mac app acts as a second Notes client. Each companion owns its
own credentials, queues and configuration.

The native integrations target NoteAir4C firmware
`2026-04-28_17-50_4.2-rel_04282_555977efe`, Android API 33 and Notes version 45326.
Method signatures, database layouts and the framework patch are tied to that
baseline. Support for another firmware is an engineering port requiring new
inspection and validation, not a matter of deleting version checks.

## 1. Root and Android framework work

The recorded configuration uses Magisk and Vector 2.2/3080. Root work began with
an already-unlocked bootloader and operated on boot slot B. Saved-image hashes,
patch settings and the recorded EDL geometry are in
[ROOT-RECOVERY.md](reproduction/ROOT-RECOVERY.md). The historical geometry is not
a template for another partition layout.

`tools/prepare_magisk_stage.py` verifies the exact original boot and Magisk APK,
extracts the required assets and emits device-local patch instructions. It does
not patch or flash the device. The missing-support-file repair and later Magisk
30.7 upgrade were not fully captured, so bit-identical recreation of the final
root installation is not established.

`tools/build_ams_fix.py` builds the firmware-specific AMS overlay from the exact
original `services.jar`. It checks the original hash, applies the targeted DEX
change, and verifies the patched JAR hash. Magisk supplies the overlay; Vector
loads the application hooks. The build and installation stages remain distinct.
The overlay needs separate review before firmware changes or OTA.

## 2. AI Assistant and NeoReader

The OpenAI companion intercepts the native AI client boundary and services the
supported conversation actions locally. `NativeHook.java` installs the hooks;
`AssistantProvider.java` provides the private bridge. Calls are restricted to
the expected native application identities and the companion.

Two transports share that integration:

- API mode uses `ResponsesClient.java` and the user's API key.
- ChatGPT mode uses `CodexOAuth.java`, `LoginCoordinator.java` and
  `CodexResponsesClient.java` for the custom subscription sign-in/transport.

`SetupActivity.java` exposes connection testing, explicit activation and the
subscription model dropdown. API mode retains a model-ID field. The active mode
is explicit; a failed subscription request does not switch into API billing.
Credentials use Android Keystore-backed storage. The login coordinator retains
pending device-code state so returning to the app can continue the same sign-in.

NeoReader uses the same conversation bridge. Selected passage text and supplied
surrounding context are preserved, and document-linked conversations can be
reopened. Opening the panel itself does not send the whole book. Handling was
also added for duplicate record loading and for scoping cancellation to the
native session that requested it.

`DisclaimerViews.java` hides the static BOOX disclaimer resources. The input
footer retains its layout space, keeping the text field above the screen edge.
Generated response text is not searched or stripped.

See [NeoReader behavior](../openai-adapter/NEOREADER-VALIDATION.md) and
[authentication implementation](../openai-adapter/OAUTH-VALIDATION.md) for
lower-level coverage and historical changes.

## 3. Native Notes integration

`NativeNotesHook.java` observes native save/export and library operations.
`NativeSyncUi.java` routes the supported native sync controls into the companion.
`NativeDriveSettings.java` adds the native Settings section using vendor adapter
models. `NativeLauncherSettings.java` installs that settings-only integration in
launcher version 56737. The editor and launcher have separate Vector scopes; see
[native settings](NOTES-SETTINGS.md) for the narrow system-UID bridge boundary.
`NotesBridge.java` provides the boundary between the native process and
`BOOX Notes Drive`, where Google authorization and network work live.

The outgoing path captures saved native content, preserves notebook/folder
identities, records it in a durable queue and publishes it to Drive. Native
editing and network availability are decoupled. The automatic scheduler retries
work subject to Android's execution and sleep behavior.

The incoming path verifies a complete revision and stages it before touching
native state. `NativeApply.java` and `NativeMetadataApply.java` apply supported
notebook and library changes only when the native editor is idle. A durable
journal, preimages and readback checks distinguish a prepared update from a
committed one. Recovery handles interruption across the recorded apply phases;
an acknowledgement follows verification of native state.

Deletion is represented as a revision and maps to recoverable native Recycle Bin
state. Restoration retains the native identity. Folders have their own records;
parent relationships and empty-folder deletion are checked separately.
[Incoming-change validation](../notes-drive/INCOMING-VALIDATION.md) describes the
transaction and crash cases in detail.

## 4. Drive protocol and conflicts

The [schema-1 protocol](../notes-drive/PROTOCOL.md) stores immutable payload and
revision objects inside an authorized managed directory. A revision names its
notebook, originating device, parent revisions, payload hash and deletion state.
SHA-256 identifies the exact bytes; filenames are descriptive rather than unique
Drive identities.

Clients upload and verify payloads before publishing revision records. Catalog
refresh validates metadata, parentage, sizes and content checksums. An existing
Drive object can reuse cached media only after its identity/version and expected
metadata are rechecked. Interrupted retries reuse matching immutable objects.

Ancestry determines current heads. Concurrent heads remain visible; the clients
do not pick the most recent clock timestamp. Android provides an explicit
resolution action retaining all conflict parents. Mac saved-branch publication
preserves its captured parent rather than silently merging or rebasing edits.

Each app uses its own Google authorization with `drive.file`. The Mac and Android
clients are registered in the same user-managed Google project. Account and
folder bindings prevent saved work being redirected after reconnecting.

## 5. Mac reader and editor

The Mac app uses SwiftUI/AppKit for its library, canvas and connection controls.
Bundled Python code decodes and edits BOOX `.note` exports. It resolves native
page, shape and point records, preserving identities and unknown protobuf bytes
when making supported changes.

The reader selects the notebook record matching the archive-root identity.
This matters because native exports can also contain ancestor folder rows;
assuming the first row is the notebook caused an early interoperability bug.

The editor supports normal pen drawing, whole-stroke erasure, undo/redo and
native notebook/folder lifecycle operations. Saved changes capture exact
payload/revision bytes in a durable queue. A draft remains local until saved;
queued changes are distinct from verified remote heads and native commits.

Automatic refresh polls the verified library while the app runs. It follows a
single compatible descendant for an open document, preserving page and zoom.
Active edits, queued work, conflicts and deletion states prevent unreviewed
replacement. This is asynchronous synchronization, not collaborative live drawing.

Unknown content may survive a round trip without full rendering fidelity. The
Mac does not reproduce all native brushes, media, templates or page/layer editing
features. See the [Mac guide](../notes-drive/macos/README.md) for operational limits.

## 6. Reproducible build and setup tooling

The public source contains the authored apps, compile-time interface stubs,
synthetic fixtures, tests, dependency pins and host scripts. Builds generate
new local signing identities when none exist. Account configuration, personal
notebooks and original firmware inputs are supplied separately by each user.

`bootstrap_dependencies.py` materializes an explicit reviewed dependency plan
with size/hash checks. Android dependency verification pins resolved artifacts.
The Mac build stages a new app before replacing the build output and retains
previous bundles; it does not activate the new executable automatically.

`boox_doctor.py` inventories the selected host/device configuration.
`boox_setup.py` separates offline planning, read-only preflight and guarded
installation. It checks firmware, app signatures, modules/scopes and a current
backup receipt. It does not create backups, obtain root, configure Google or
sign into accounts. A completed install deliberately remains pending feature
acceptance until the user's own round trip succeeds.

`inventory_sources.py` and `package_source.py` enumerate and review publishable
source. The optional host archive tool captures a user-selected quiescent
project tree with content hashes; see [local archival](PRIVATE-ARCHIVE.md).

## 7. What the validation establishes

Source builds and mandatory tests use generated notebooks, with private corpus
checks available only as explicit optional runs. Recorded suites cover 57 Android,
162 Mac, 137 host and 19 protocol/prototype checks. Fresh source clones built the
production APKs, instrumentation/probe APKs and Mac app.

Live checks exercised AI/native reading surfaces; independent Android/Mac Google
sign-in; bidirectional pen editing; notebook/folder lifecycle; explicit conflicts;
seven native apply crash checkpoints; and automatic following of an already-open
Mac document. Native data preservation was checked against a fixed checkpoint.

These results establish the tested workflow on the recorded configuration.
They do not establish a universal rooting procedure, a fresh-device combined
installation, unlimited library size, full BOOX rendering parity, or unattended
multi-day token/OTA behavior. The [acceptance boundaries](reproduction/GAPS.md)
separate those open areas from working functionality.

## NeoReader Drive integration

The shared Drive companion now includes a separate Reader protocol namespace for
ebooks and per-book native data. Library Settings uses the same BOOX settings
models as the ONYX section; its information icon opens the companion and its
switch controls reading sync independently of notebook sync. Both custom Android
apps use white backgrounds, black outlines, native-sized rows and ON/OFF controls.

Reader bundles include the ebook, native reading metadata, bookmarks, annotations,
statistics and per-book handwriting records/assets. Immutable chunked manifests
reduce repeat uploads. Incoming changes require a cross-process editor lock, a
durable per-book preimage, exact native readback and a committed receipt. Three
fault checkpoints exercise recovery without replacing the whole library.
See [Reader setup and architecture](READER-DRIVE.md) for limits, version guards,
new-device linking and conflict selection.
