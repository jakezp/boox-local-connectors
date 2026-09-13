# Native update identity and recovery validation

Validated 2026-09-12 on Note Air4C, Android 13, Notes version code 45326.
This is a disposable pen/link experiment, not a production sync installation.

## Outcome

An incoming revision was staged through BOOX's native importer and applied to
the existing test notebook. BOOX then reopened and exported it successfully.

- The destination notebook UUID, both page UUIDs and eight existing stroke UUIDs
  stayed unchanged. Three added pen strokes and one added link used new UUIDs.
- All eleven pen strokes match the incoming revision's compared styles, layers
  and exact point blocks. Page dimensions and the two-layer configuration match.
- The internal page link points to the retained destination notebook and page.
- A separate observer notebook contained an inbound link to the destination.
  Its database, document and point components and complete NoteModel row stayed
  unchanged; its link still names an existing destination page. Actual tap
  navigation of that inbound link was not established.
- The journal advanced to the incoming revision only after native export/readback
  verification. A deliberately stale verification hash was rejected first.

Evidence: [readback-results.json](research/apply/readback-results.json),
[verification.json](research/apply/verification.json),
[screenshot](research/apply/target-after-open.png), and
[Target-after.note](tests/artifacts/Target-after.note).
The proof is reproducible using [verify_apply_fixture.py](prototype/verify_apply_fixture.py).

## Crash and guard tests

[24 live cases](research/apply/validation1-results.json) passed:

| Cases | Result |
| --- | --- |
| Nine forward checkpoints, including each old/new component rename and metadata commit | Real SIGKILL, then successful rollback; repeated recovery is idempotent |
| Eight recovery checkpoints | Recovery itself killed, then resumed successfully |
| Unexpected later point edit | Recovery paused and preserved every changed byte |
| Missing native readback proof | Applied revision did not advance |
| Damaged saved backup | Both apply and recovery refused until the injected damage was repaired |
| Stale local metadata; attempted local identity/title change | Prepare refused before replacement |
| Native processes running; damaged candidate | Prepare refused |

The stale-proof rejection and successful commit are additional end-to-end checks.
The offline suite has **40 passing tests**: 19 protocol, 13 native archive, eight
candidate preparation cases. The reproducible host verifier also matches the
saved proof.

SIGKILL tests are not power-loss or reboot tests. The test helper uses `app_process`
with exceptions caught explicitly; only exits accompanied by `STOP_AT` are counted
as injected process termination.

## Mechanism and boundaries

The temporary `local.boox.notesapplyprobe` module captured the native importer's
actual source/destination page and shape maps, and supplied the document mapping
needed by the stock internal-link bug. [prepare_apply_fixture.py](prototype/prepare_apply_fixture.py)
then built copied components retaining existing destination identities.

The root-only [ApplyMain.java](apply-probe/src/local/boox/notesapply/ApplyMain.java)
operates only on `GDrive-Sync-Probe-*` fixtures. Preparation of a replacement also
requires the Target fixture name. It replaces one notebook database, one document
tree and one point tree, then updates one NoteModel row transactionally. It preserves
local identity, title, location and settings outside a content-column whitelist.

Each job retains checksummed old/new copies, a fsynced atomic journal and a revision
ledger. Replacement uses sibling rename paths, checks app processes before each
step, and preserves ownership/mode/SELinux context. A file lock serializes helper
operations. Recovery accepts only known old/new component contents; unknown later
edits pause recovery. Acknowledgment rechecks the current component hashes,
NoteModel row and saved readback artifact supplied by the trusted host verifier.

**This is not ready for automatic production application:**

- Process checks do not atomically prevent Notes/KSync/launcher from reopening
  between checks. A persistent native app-open gate and recovery coordinator remain.
- Opening/exporting Notes changes native caches. After such changes, conservative
  recovery may pause because current bytes no longer match the planned old/new
  hashes. The successful acknowledgment used a fresh post-readback snapshot.
- Equal-length UUID/title rewriting is a research fixture technique, not a general
  protobuf/resource migration implementation.
- The supported fixture has two fixed pages, pen and link shapes, no shape removal
  and no resources. Rich text, recordings, locks, page addition/deletion/reorder,
  tags, other notebooks/firmware/devices and large files need separate validation.
- The trusted verifier runs on the host. A production native verifier and persistent
  device queue are not implemented.

## Cleanup and preservation

All five fixture notebooks were removed through Notes and its Recycle Bin.
The temporary module was disabled, its Notes scope removed, and its APK uninstalled.
Private map files, root test jobs and fixture import/export files were removed
after archiving the evidence locally.

The three original NoteModel rows and **182 files associated with their IDs**
match the pre-experiment backup: 150 document-tree files, 24 point-tree files,
six Notes database/sidecar files and two KSync database files. Earlier summaries
called all 182 “document files”; this is the precise breakdown.

Wi-Fi is enabled again. Vector lists only the existing OpenAI module, with its
original assistant/kreader scope. OpenAI v0.9, root/framework configuration,
OneNote and ONYX sync settings were not modified.

Private checkpoints:

- `backups/pre-notes-apply-20260912/`
- `backups/post-notes-apply-20260912/`
- `research/apply/device-jobs.tar` (retained recovery evidence)

Do not restore whole-library backups over later user edits.

The separate Android Google authorization/Drive connection preview in `android/`
now passes live authorization, app-owned directory creation and a disposable
notebook byte round trip. See its README and `research/apply/drive-live-validation.json`.
Persisted revision transport is now validated with the user-requested Mac reader;
see [INTEROPERABILITY-VALIDATION.md](INTEROPERABILITY-VALIDATION.md). The native
app-open gate/verifier, save/export integration and broader content tests remain
before automatic sync can be enabled.
