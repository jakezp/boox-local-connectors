# Native incoming sync validation

**Current checkpoint, 2026-09-13:** core bidirectional sync and Mac v0.6 nested
editing passed live validation. Native Sync controls, folder lifecycle, conflicts,
recoverable deletion/restoration, seven crash checkpoints and original-data
preservation passed. Clean-source builds require no personal notebook files.
See [current validation](../docs/VALIDATION.md) for the final versions and evidence.
This is a bounded Notes integration, not full BOOX content/renderer parity.

The sections below retain the chronological investigation, including earlier
locked-Mac and incomplete-implementation checkpoints. Their pending statements
are historical.

## Implementation

Android v0.4 adds stable-ID application, verified descendant selection, folder
records, recoverable deletions, and a per-notebook recovery journal. It waits for
closed native editors, gates opening and model saves during replacement, backs
up the affected notebook's components and owned rows, hydrates native PB/point
stores, re-exports, then acknowledges. Conflicting branches are preserved.

Native Notes sync controls have Drive routing/status hooks, pending UI validation.
ONYX's Notes sync flag is suppressed in the scoped Notes process while replacement
mode is configured.

The Mac editor has pen/whole-stroke erasure, durable drafts, undo/redo, offline
publication and conflict retention. Its checkpoint has 85 checks. Folder management
is still being extended. Parent UI validation is waiting for the user to unlock
macOS: CUA explicitly reported a locked Mac.

## Evidence so far

- 56 Android checks pass, including actual Mac add/erase payloads, folder encoding,
  tombstone ancestry, adoption recovery and native readback.
- A Mac-generated six-pen fixture was published through Android's existing
  app-owned Google grant as device `mac-encoded-fixture-validator`. This does
  **not** establish Mac UI/OAuth publication.
- Revision `9962bcf6c58e8977924e10ab13c625929facaa56b9fc73d8ae1084e7bef77eb2`;
  parent `c0249ae5c125c1b011fc3c55f4e0c3f2c34599c617453d4b4e5736ee347f3c4f`.
- First native attempts rejected a stroke comparison and rolled back; the local
  branch stayed at the parent. Actual export contains six pens with matching
  points. The sole content difference was explicit `zorder=0` versus omitted
  proto3 default zero. The comparator now equates known proto3 defaults while
  retaining nonzero and unknown fields. Actual readback is a regression fixture.
- Corrected add and erase commits now pass. Erase descendant:
  `e5d8e6d92edd651e4805f55bba1aebf8fc0d6e7fee614c408ac7d51fba3cc44e`.
  BOOX reopened and visibly rendered the blue Mac stroke and omitted the erased
  box edge. `research/incoming/erase-open.png` is the native visual evidence.
- Seven native crash checkpoints now have live evidence: PREPARED,
  DOCUMENT_REPLACED, POINTS_REPLACED, ROWS_CLEARED, HYDRATED, VERIFIED and
  ACKNOWLEDGED. Pre-ack crashes restore exact owned rows/component hashes, then
  retry. Acknowledged recovery retains the exact revision without an echo.
- ACK recovery initially exposed a missing native capture receipt. The fix saves
  `nativeReceipt` in the VERIFIED journal and restores it before COMMITTED.
  Corrected revision: `711521ca14a39130551769293cdd16a57503a71542473739081f357223477742`.
  Keep failed-case evidence under `fault-acknowledged-before-receipt-fix/`.
- Two folder creates, a Unicode rename, nested move and recoverable deletion have
  committed and passed direct SQLite readback. Restore exposed `loadNote` hiding
  deleted rows; source now checks `loadRemovedNote` and preserves deletion titles.
  Restore and conflict UI live verification are in progress.
- Production archive preflight passes against all three original notebooks with
  strict parent/resource/tag/link/point/asset checks. Original corpus is private.
- Mac v0.4.1 checkpoint has 108 offline checks. Its own-OAuth CLI stopped at locked
  Keychain (-25293); the isolated exact request is retained in
  `research/incoming/mac-own-oauth-add/`. No Mac CLI publication occurred.
  Interactive Mac validation and final original-notebook preservation remain open.

## Repeated-build activation

Vector 2.2 caches module DEX. Installing a same-version APK and restarting Notes
loaded an older adapter here. Immediate disable/enable commands coalesced without
a completed cache rebuild. Separate disable and enable, allow the asynchronous
rebuild after each, then restart the closed native application. Verify the expected
adapter marker in the **current Notes PID's** log; installation SHA alone is not
enough. Never force-stop an editor containing unsaved user work.

Disposable notebook: `GDrive-Sync-Probe-Automatic`, native ID
`9ad47965eb884731a7d3f868a07df91b`.

## Files and recovery

- Native source: `android/app/src/main/java/local/boox/notesdrive/NativeApply.java`,
  `NativeStore.java`, `NativeArchive.java`, `NativeMetadataApply.java`,
  `NativeInbox.java`, `NativeSyncUi.java`.
- `tests/publish_native_fixture.py`: guarded disposable-fixture publisher.
- `research/incoming/`: private live evidence, including rejected readback.
- `backups/pre-notes-incoming-v0.4-20260912/` at workspace root: pre-change backup.
  Use `notes-and-connector.recovered.tar`, SHA256
  `5c9cfb10a7bf7f9f05bba5ef91b932f5fe3cdc6f343acafeb6db25edb6610b68`.
  Six ADB/toybox absolute-path notices polluted the original stream. Authored
  `tools/repair_adb_tar.py` removed only exact notices at verified header
  boundaries; all 436 logical members/704 headers were checked. The raw original
  and incomplete preamble-only diagnostic are preserved, not restoration inputs.
  See the backup's `README-recovery.md`. New snapshots use `tar -C /` with relative paths.
- Notes-private `files/boox_drive_apply`: active journal, per-attempt backups,
  interrupted files and readbacks.

Root-provisioned `validation-fault.json` can terminate an apply only when its
exported title starts `GDrive-Sync-Probe`. It names an exact notebook/revision/
checkpoint and is consumed once. Latest source writes `validation-paused.json`
before termination so recovery can be inspected before another incoming attempt.
Remove the pause after checking recovery. No provider/intent exposes these files.
Rolled-back failures wait for **Retry incoming updates**. Never restore an entire
historical library backup over newer user edits.

## Repository work remaining

The user requested cleanup after completion and validation. No GitHub repo has
been created/pushed yet. `gh` is authenticated as `jakezp`. This working directory
belongs to a parent Git repo; do not add the parent folder wholesale.

The clean repo must exclude credentials, private signing keys, personal notes/
backups, generated decompilations and proprietary firmware. Include all authored
apps/scripts/tests, synthetic fixtures, pinned dependency acquisition, root/module
provenance, an ADB installer/doctor, and end-to-end docs. See the workspace-root
`IMPLEMENTATION-PLAN.md`.

## Latest live checkpoint

- Android source/build has 56 checks. Latest installed before toolbar changes:
  APK `dfd11fcd05d49dcfa7506d06351cd99e89b52ac468fabde36287405be422a7ff`,
  hook `9b41372029aa76a3`, evidence `conflict-cache-install.json`.
- Folder restore passed after active/recycle-bin lookup fix:
  `0e48a7d7fb678524412a905bae74b82411c01afe5b37498537d69c951016b04c`.
- Android conflict review passed from actual UI to Drive/native SQLite:
  selected Left, merge `34599a05d803a380b500d062c8e75133b183cf026080d1c7b19daa13fb6dd176`.
  Both competing heads are retained as parents. Confirmation initially ignored
  a choice during background `busy`; deferred choice now rechecks reviewed heads.
  Conflict review remains accessible during background refresh. Manual paths now
  use the same verified cache as automatic sync. The durable merge publication
  survived an APK update/retry without changing identity.
- Probe notebook delete and restore passed. Deleted revision
  `0a3c3f135eade88940f77a514917c21e2b37f570388812c49d952db824d4fbad`;
  restored/native current head
  `b4097599b022f4df99e4220864fbc5a50c857eccadf9df5dffe9b78ee0782e56`.
  Native readback SHA `8fdbaf3af7b0bf301d66bb80061fe4d1f2b20f066133c79b6380e964d5b796c3`,
  one page/five pens. The old Mac CLI isolated request's expected base is obsolete;
  retain it as failed evidence and prepare a fresh current-base request later.
- All automatic switches are ON. No fault/pause marker is intentionally active.
- Toolbar cloud action was still ONYX-only. Source now hooks inspected
  `LibraryViewModel.onSyncFolderTree` to a Drive status/settings/sync panel.
  This is being built/live-tested; editor ForceSync route still needs live evidence.
- `publish_native_fixture.py --create-anchor` can now seed a NEW named disposable
  notebook through an existing probe's account/folder binding, with empty base and
  no local branch. Native coordinator still refuses collisions with existing IDs.
- New authored verification tools: `verify_metadata_fixture.py`,
  `verify_native_fixture.py`, `verify_preservation.py`. No proprietary code copied.
- Live doctor: fw/model/slot/Magisk/modules/APK matches. Scope parser initially
  missed Vector's table format; Leibniz is correcting it and writing staged setup.
- Banach's Mac lifecycle checkpoint v0.5 has 125 tests; stable details in
  `macos/evidence/lifecycle-build-validation.json`. Still no new Mac UI/own-grant
  validation. Mac locked, Keychain asks manual Reconnect saved grant.
- No GitHub creation/push/cleanup yet. Private-original fixtures must be replaced
  by generated synthetic mandatory-test data before any source package is uploaded.

## Checkpoint 2026-09-13 — awaiting Mac unlock

`research/incoming/latest-checkpoint.json` is the latest machine-readable state.
Android57, Mac129 mandatory synthetic (+5 explicit private-corpus), host56 tests.
The frozen Mac lifecycle app was built/verified with125 checks before test-data
isolation; app code/bundle is unchanged by the synthetic-test changes.

- Installed APK `f15a19bb5de6652c14b4f3b08bff23229adb4308af0f34bc06d7012a157f6f4f`,
  actual Notes hook `1b35137b83528a78`.
- Library cloud icon shows Drive panel; native editor Sync calls normal save,
  then requests Drive. Evidence `native-sync-controls.log` and panel screenshot.
- New Mac blank revision `c7f86d2cadf0e8c3d8d49c7c054fe21966ceac4b522cfe3d0b5672a23d1c3608`
  committed after narrow generated `extra/pb/extra` allowance. Exact known fields,
  docVersion1/app45326/sameID only; arbitrary extra files/fields remain rejected.
  Native re-export `d147d6405c2cb1aac811c3363d026e1686f8b59f69c90840845078b484cc9826`.
  Opened and visibly blank. ADB stylus attempt produced0pens; physical pen editing
  of this new notebook is NOT claimed. It closed/saved normally.
- Latest existing probe head `b1892bd28b724910628faa2d2a1d6d2d3e554ec12f3ce153753eb2d3beb87cfe`;
  Mac-created probe head `2096ea69d6eb746ad93436a5b412580a804d491ad183eb816a967b353e741caa`.
  Re-read before future publication. Normal native save may advance export metadata.
- All182 original associated files and every field of3 original rows unchanged:
  `final-original-preservation.json`. Coherent native checkpoint SHA
  `99f4b4576fcb0dd3350f8e88246bfa334d27816c90c37d346dd9f981870616a9`.
- `tools/boox_setup.py` existing Notes update path passed both read-only preflight
  and actual same-APK guarded reinstall, including post signature/hash/scopes and
  current-PID hook receipt. `setup-apply.json`; this does NOT validate first install,
  OpenAI wrapper update, rerooting, firmware flashing or Mac OAuth.
- Mac lock/Keychain blocker reconfirmed. No new interactive Mac own-grant publication.
  Both Android automatic switches remainON. Native editors closed. No GitHub/cleanup.
- Reproduction docs and rootREADME prepared. Exact upstream EDL loader binary was
  recovered at a pinned commit and its downloaded SHA verified; see acquisition
  manifest. Mac mandatory tests now generate synthetic inputs; remaining Android/
  packaged-fixture sanitization and clean-checkout build belong to cleanup phase.

## Mac unlocked: real UI / independent OAuth — 2026-09-13

The lifecycle v0.5 GUI reconnected its own grant, added a stroke by mouse drag,
and automatically published `63c738253e9f…`. Native COMMITTED readback had the
same notebook/page IDs, six pens and129 samples; stock Notes visibly showed the
new blue diagonal. Normal native close/save returned `5c96fe5a208b…`, and the
open Mac reader automatically followed it with page and zoom preserved. Evidence:
`research/incoming/mac-ui-own-oauth-add/{result,roundtrip,mac-receipts}.json`.

GUI new notebook `38b21fb7a1d34a99936cd84dbe5f29a5`, followed by rename/move
into fixture Folder B, was applied at latest revision `ce6630107948…` and opened
in stock Notes. The root revision was skipped because the device caught up after
the descendant existed. Mac decoder correction is in progress: native exports
include ancestor folder rows in the repeated NoteModel wrapper. Native apply
verified successfully; the offline Mac readback decoder rejected duplicate scalar
field1. This is retained as regression evidence, not a native rollback.
