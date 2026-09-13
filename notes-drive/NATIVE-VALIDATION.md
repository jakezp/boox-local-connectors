# Native notebook validation — 2026-09-12

**Result:** native editable snapshots are usable, and a real import bug was
reproduced and repaired in an isolated probe. This validates the snapshot and
staging approach. It does **not** yet provide automatic Drive sync or safe
in-place replacement of existing notebooks.

Device: Note Air4C `DEVICE_SERIAL`, firmware 4.2, Notes code `45326`.
The original Notes APK was not patched. The OpenAI v0.9 connector was not changed.

## What was tested on the tablet

Five disposable notebooks were created, all named `GDrive-Sync-Probe-*`.
Wi-Fi was disabled during the experiment, then restored to its original enabled
state after cleanup. No notebook content was uploaded to Google Drive or ONYX.
The Notes cloud settings were not changed.

| Export | Native operation | Verified result |
| --- | --- | --- |
| `A1.note` | Create two pages and eight pen strokes; export both pages. | Eight strokes, 248 recorded samples, native point and shape records. |
| `B1.note` | Import A1 as a new notebook; export without editing. | All eight point blocks byte-identical; compared pen style, dimensions and page order preserved. Notebook, page and stroke IDs regenerated. |
| `B2.note` | Edit the imported B: add a two-stroke checkmark, a second layer with one stroke, and a link from page 2 to page 1. | Original eight strokes preserved; three additional strokes and one link; layer counts 1 and 2. B's document and page IDs stayed stable during editing. |
| `C1.note` | Import B2 through the unmodified native importer. | All 11 pen strokes and their compared styles/layers preserved. **Internal link broken:** old source notebook ID paired with a newly generated destination page ID. |
| `D1.note` | Import the same B2 with the temporary mapping repair. | All 11 pen strokes, compared styles, page dimensions and layers preserved. Link points to the new notebook and its page 1. Link label also updates to the new title. |
| `Corrupt.note` | Attempt import of a truncated 512-byte archive. | Native ZIP error and destination cleanup logged. All eight existing NoteModel rows remained identical; no partial notebook remained. |

The repaired notebook also reopened after a Notes force-stop. Both pages rendered
correctly, and the second page retained its two layers. This is a same-device
export/import/edit experiment, not a physical two-device sync test.

`D1.note` is the export from the notebook titled `GDrive-Sync-Probe-D(2)`.
An earlier probe import also created `D(1)`; both were removed. BOOX appended
`(1)` even for first imports of B and C. Titles are unsuitable as sync identities.

### Exact import bug and repair

The native importer creates a fresh destination notebook ID and remaps page IDs.
`LinkShapeModelData` consults `NoteCopyContext.getNoteDocIdMap()`, but the standard
single-notebook importer never supplies the source-to-destination entry.

In the observed broken C export, the link contains B's document ID and C's page
ID. It is not merely an old display label. Supplying:

```text
noteDocIdMap[sourceNotebookId] = destinationNotebookId
```

before copying link data repairs the stored destination. Native title handling
then updates the link label as well.

The final probe hooks `NoteModelData.copyNoteAttr` after successful completion.
It only runs in `com.onyx.android.note`, checks Notes version code `45326`, and
requires the exact B2 source UUID and destination import title
`GDrive-Sync-Probe-D`. It never chooses an existing notebook as the destination.
It has no network permission, exported provider, or background service.

Source/build: [probe/](probe/).
The temporary package `local.boox.notesprobe` was installed, scoped only to
Notes, tested, disabled, and uninstalled. The existing OpenAI scope is unchanged.
Do not present this fixture-only repair as a deployed general sync adapter.

The stored internal link destination and its visual label are verified. Following
the link through a native tap was not established by the input automation.
Inbound links from other notebooks remain a separate untested problem.

## Reproducible checks

```sh
python3 -m unittest discover -s notes-drive/prototype -p 'test_*.py' -v
python3 notes-drive/prototype/native_fixture.py \
  notes-drive/tests/artifacts/B2.note notes-drive/tests/artifacts/D1.note
python3 notes-drive/probe/build.py
```

**32 tests pass:** 19 existing offline protocol cases plus 13 native fixture
regressions. The latter use the actual saved exports and detect the stock link
bug, verify the repaired export, compare pen data/style/layers, and reject
selected malformed inputs. APK signature verification passed for the probe.

The Python reader is deliberately limited to these small firmware-specific
fixtures: v1 point files, normal-pen shapes and link shapes. It does not certify
all `.note` files as safe or fully supported. It reads ZIPs without extracting
them, bounds sizes, and checks point-index/shape correspondence.

Native pen blocks are compared independently of regenerated IDs and revision
metadata. Raw ZIP hashes cannot detect unchanged notebook content reliably.
The original A export contains archived stroke records that a subsequent native
import/export does not retain; current editable strokes pass, but undo/history
preservation is not established.

## Cleanup and preservation

- Original NoteModel rows are identical to the pre-test snapshot.
- **182 original document files** compare byte-for-byte by SHA-256, including
  all three original notebook databases.
- Exactly the original three notebooks remain. All five disposable notebooks
  were removed through the native library and then its Recycle Bin.
- Temporary tablet export/import files and the probe APK were removed.
- Wi-Fi is enabled again. OpenAI's Vector scope is identical to its initial state.
- Root/framework configuration and ONYX sync settings are unchanged.

Coherent private component backups:
`backups/pre-notes-roundtrip-20260912/` and
`backups/post-notes-roundtrip-20260912/`.
They contain app data and must remain local. Do not restore the pre-test library
wholesale over later user edits.

Evidence:

- [Device preservation checks](research/native-device-checks.json)
- [All regression results](research/all-test-results.txt)
- [A → B comparison](research/native-a1-b1.json)
- [B before/after editing](research/native-b1-b2.json)
- [Stock import failure comparison](research/native-b2-c1.json)
- [Repaired import comparison](research/native-b2-d1.json)
- [Corrupt-import log](research/corrupt-import-log.txt)
- [Probe log](research/probe-log.txt)
- [Repaired page](research/probe-d-repaired.png)
- [Page 1 after restart](research/probe-d-reopened-page1.png)
- [Saved native exports](tests/artifacts/)

## Next implementation boundary

The next component needs a durable staging/recovery journal and stable notebook,
page and link mappings. A successful fresh import must not automatically delete
or replace the old notebook. The stock importer's failure cleanup still makes
forcing an existing destination ID unsafe.

Before automatic apply, prove replacement while preserving existing local
identity and inbound links, reject changes to a locally edited/open notebook,
and recover from interruption after each write boundary. The truncated-file
test here covers failed fresh import, not crash-atomic in-place updates.

Then register the companion app's Google OAuth identity, authorize the dedicated
folder, and test real Drive upload/download on two installations. The desktop
Drive connector grant cannot be reused by the Android app.

Additional native coverage is still needed for images, attachments, custom
templates, rich text, recordings, tags, locked notebooks, page moves/deletions,
cross-notebook links, and other firmware/device combinations.
