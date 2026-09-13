# BOOX Notes Reader — macOS native pen editor and live library 0.6

Native SwiftUI/AppKit app for this Apple Silicon Mac. It opens real BOOX `.note`
exports, previews recorded pen coordinates and independently connects to Google
Drive to verify immutable notebook revisions.
Version 0.3 adds normal-pen drawing, whole-stroke erasing, durable undo/redo,
edited `.note` export and immutable publication of real notebook edits. The
automatic library reader consumes verified notebook and folder revisions.
Version 0.4 adds native folder creation, rename/move, empty-folder deletion
requests and restore using the same durable queue and schema-1 protocol.
Version 0.4.1 adds an opt-in, isolated CLI for supplemental own-Mac-grant
publication of a reviewed disposable probe edit. See [PROBE-CLI.md](PROBE-CLI.md).
Version 0.5 adds native notebook creation, rename/move, recoverable deletion and
restore. This is a bounded second-device workflow, not unrestricted ONYX parity.
Version 0.6 selects native notebook metadata by archive-root identity, preserves
embedded ancestor rows during edits, and binds the user's Desktop OAuth client
to the verified saved account/directory. It contains no personal project, client
or folder defaults. See [PORTABLE-RUNTIME-HANDOFF.md](PORTABLE-RUNTIME-HANDOFF.md).

**Frozen lifecycle build: 125 automated checks passed** (29 decoder, 10 pen writer,
7 notebook lifecycle writer, 79 Swift checks). The frozen bundle and hashes are
recorded in `evidence/lifecycle-checkpoint.json`; validation details are in
`evidence/lifecycle-build-validation.json`. Prior frozen editor/folder/CLI
checkpoints are retained. This agent has not launched the GUI, read the user's
grant or changed live app state.

**Current shareable source suite: 162 mandatory checks pass.** Its fixtures are
generated arithmetic pen data; private notebooks are no longer mandatory inputs.
The suite passed from an isolated Mac-only source copy with no private/shared
archives, evidence or Google config. Five additional private-corpus checks are
available through an explicit separate command. See
[SYNTHETIC-TESTS.md](SYNTHETIC-TESTS.md). Test output now uses fresh ignored
`.test-runs` directories and preserves existing evidence and frozen bundles.

**Version 0.6 live nested-notebook roundtrip passed (parent report, 2026-09-13).**
The authenticated Mac opened a native notebook with one ancestor row and no
pens; GUI draw/undo/redo/save published one pen through its own OAuth grant.
Android committed the exact branch and displayed the blue diagonal in stock
Notes. After native close, the Mac automatically fetched the verified return
(one pen, two samples), which the parent opened from the library. The parent
also reports 162 checks passing from a clean clone.
Separate live checks also passed: after a native rename and normal close, the
already-open Mac notebook updated its title and retained one pen/two samples,
page and zoom without refresh or reopen. The bundled synthetic fixture opened
through the GUI with two pages and 343 samples. No planned version 0.6 Mac
acceptance blocker remains. See
[PORTABLE-RUNTIME-HANDOFF.md](PORTABLE-RUNTIME-HANDOFF.md) for the confirmed scope
and private evidence reference.

The focused ancestor-metadata hotfix is documented in
[NESTED-METADATA-HANDOFF.md](NESTED-METADATA-HANDOFF.md). Native re-exports may
include folder rows alongside the notebook in `note_info`. The reader selects
the exact archive-root notebook; pen and metadata edits preserve the folder rows
and unknown wire bytes. The retained ancestor-only checkpoint is historical;
the activated 0.6 candidate includes this fix.

The parent previously ran the frozen 0.4.1 own-OAuth probe CLI while the Mac was locked.
It failed closed with Keychain `-25293`, saved its isolated request/candidate,
and did not publish. After unlock, the parent reports GUI reconnect, an actual
pen drag, Save to library and automatic Drive publication succeeding on the
frozen lifecycle app. A transient metadata mismatch resolved on automatic retry;
the integrity guard and captured revision were retained. Broader live lifecycle
validation remains parent-owned. See [PORTABLE-BUILD.md](PORTABLE-BUILD.md).

**Prior live parent validation passed:** this Mac completed its own OAuth grant,
reconnected from Keychain, accessed the Android-created folder, downloaded and
rendered Android's revision, then published B2 as its descendant. Android verified
and durably staged that descendant. The parent subsequently confirmed automatic
discovery of all three native notebooks and following a real edited notebook
while retaining the same page and 125% zoom. See
[INTEROPERABILITY-VALIDATION.md](../INTEROPERABILITY-VALIDATION.md) for exact evidence.

The retained version 0.4.1 passed **108 automated checks** (29 decoder, 10 native writer and 69
Swift core/editor/library/folder/CLI checks). The parent reports native add and
erase both **COMMITTED**, visually reopened correctly, and recovered exact
component hashes/SQL values after a crash at DOCUMENT_REPLACED. The native
six-pen re-export also renders in the Mac tests and accepts a subsequent seventh pen.
The later 0.5 and 0.6 live results above remain distinct from those earlier
checks. The parent has now activated 0.6. This subagent has not opened the UI,
accessed the grant or altered the running app's state.

For the next live notebook edit, refresh the verified catalog and open its
current head. Historical hashes in evidence are not current-base suggestions.
The parent reported six native rollback phases and ACK receipt recovery passing;
native validation remains separate from this Mac's own OAuth/UI validation.

The optional `--validate-probe-publish` command requires a full explicit current
base, a reviewed edited probe export and a new isolated journal directory.
It uses only this app's existing silent grant and exact saved destination;
the running UI journal/cache/preferences remain untouched. If Keychain needs UI,
it fails. The separate retry command retains exact bytes and ancestry. Receipt
flags always distinguish transport from interactive/native validation.
**This agent has not run the live CLI.** Command examples, limits and exit codes
are in [PROBE-CLI.md](PROBE-CLI.md); build evidence is in
`evidence/probe-cli-build-validation.json`.

## Edit and save notebooks

1. Open a verified library revision or a local `.note` export. Choose **Pen**,
   a visible unlocked layer, ink color and width. Draw with a mouse or tablet.
2. **Erase stroke** selects an entire normal pen by clicking it. Text, links,
   attachments, unsupported shapes and locked/hidden layers are not erased.
3. **Undo/Redo** operates on completed gestures. The base export, operations and
   history cursor are flushed together after each completed action, so an app
   restart restores the draft. A stroke still being dragged is not yet durable.
   Saving a snapshot starts a new draft history; undo does not retract a queued
   or published revision.
4. **Save to library** encodes and rereads the native result, caches it and
   atomically queues its exact payload/revision bytes with the new clean draft.
   Subsequent offline saves parent the preceding queued revision. The queue holds
   16 saves; reaching the limit preserves the draft and prior saves.
5. Automatic library updates send one queued edit per successful polling cycle
   using the Mac's own saved grant. **Send next queued edit** explicitly retries.
   Turning automatic updates off also pauses automatic uploads.
6. **Export .note copy…** writes a separate UUID-preserving export and leaves
   the draft available for publication. The originally opened file cannot be
   overwritten by this action. **Discard draft** discards draft gestures only;
   already queued snapshots remain recoverable.

Local files can attach to an already verified library only when their exact
base payload matches its sole head, or when that native notebook ID is absent
from the verified catalog. Otherwise open the desired library revision first.
An existing library draft already carries its destination and parent, so it can
be saved while offline without opening consent or reaching Drive.

The **Saved changes** panel shows the next notebook/folder and queue status, and can open
the latest queued snapshot. A head that advanced, conflicted or requested deletion
pauses automatic publication for review. **Publish saved branch with conflict**
is an explicit choice that keeps the captured parent and preserves both heads.
There is no timestamp-based winner, implicit merge or rebasing of queued data.
An open editor, draft history or pending edit prevents automatic replacement of
the current notebook; incoming versions remain accessible in the library.

The editor uses the existing schema-1 wire format. It does not apply the native
snapshot on BOOX; the parent's Android coordinator owns that step and its readback
acknowledgment. Verified publication is reported separately from native application.

## Create and manage notebooks

Connect and obtain a verified catalog once in the current app session. Library
dialogs then work from that catalog plus exact queued local revisions; they do
not require a network write to save. A cold offline launch can recover existing
drafts/queued snapshots, but creation or metadata dialogs need a verified
catalog before they can validate destination folders.

1. **Native notebooks → New notebook…** accepts a title, parent folder and
   1, 2, 5, 10, 20 or 32 blank pages. Each is 1860 × 2480 with one visible,
   unlocked layer. Document/page IDs are fresh native 32-character IDs.
   The app queues the blank snapshot and opens it if no other notebook draft is
   active. Draw and **Save to library** to queue its child revision.
2. **Rename or move…** changes title/parent only. Existing native IDs, pages,
   resources, pen chunks, extra records and stash/history remain intact.
   An open affected notebook keeps its page identity and zoom.
3. **Request deletion…** presents a confirmation and queues
   `deleted:true,payload:null` for native Recycle Bin application. It never
   removes local files or Drive history. The open copy remains viewable;
   further editing requires restore/review.
4. **Restore notebook…** uses the nearest retained snapshot from the deletion's
   ancestry, retains its native ID and parents the deletion revision. The
   dialog allows a live replacement parent if the original folder is unavailable.
   Ambiguous snapshots and conflicting heads are refused rather than choosing
   a winner. Existing archived status is reactivated when necessary.
5. **Saved changes** lists pending notebook snapshots and deletion requests,
   including open/review, rename/move, deletion and restore of the latest
   local change. These are explicitly pending, not verified remote heads.
   Offline create → edit → rename → move → delete → restore shares the same
   16-entry queue and exact captured ancestry. No action removes a prior job.

Save/discard a notebook's pen draft and undo/redo history before changing that
same notebook's metadata or deleting it. A fully undone draft still has redo
history and requires explicit discard. An unrelated notebook's draft and undo history survive
library management. The publication path checks payload/native identity and
the complete destination-folder chain against a fresh verified catalog.
Missing/deleted/conflicted destinations remain queued for review.

Fresh notebooks are generated from the native NoteInfo schema without copying
resource/virtual/ONYX ownership records. Parent native apply/reopen is a separate
acceptance test. In particular, stock export may generate `extra/pb/extra` for
a fresh note; the Android verifier owns validation of that generated metadata.
The early blank and metadata fixtures are under `evidence/notebook-management/`.
Existing-note metadata updates preserve all original extra/unknown bytes.

### Native preservation and editing limits

`Resources/note_editor.py` verifies the exact base hash before reading and edits
only active shape/point directories next to `note/pb/note_info`.
Existing document, page, layer and stroke identities remain unchanged, including
32-character native IDs and hyphenated UUIDs. New strokes receive new UUIDs and
new v1 point chunks. The writer updates known native modification fields and
assigns a new info-revision ID to each changed shape chunk.

Whole-stroke erasure sets native `ShapeStatus.REMOVED=1`, retaining the original
stroke UUID, unknown protobuf fields and exact point bytes. The reader understands
these tombstones and does not draw orange placeholders for them. Stock native
re-export can omit removed records/points; semantic readback should compare enabled
shapes and their referenced point blocks.

Untouched ZIP member contents are preserved byte for byte along with their metadata
and archive comment. ZIP compression and container offsets may change. Unknown
protobuf fields in changed messages retain their original wire encodings. Known
page-info JSON is updated without dropping unknown keys. Resources, templates,
virtual-canvas data, stash/history and unedited shape content remain in the export.
Preserving opaque content does not establish rendering/editing fidelity for it.

Pen editing is limited to normal type-2 pens in declared pages and visible
unlocked layers. Notebook creation supplies blank pages, but this version does
not add/remove/reorder pages or layers in an existing notebook,
edit rich text/media/templates, perform partial/pixel erasing, reproduce pressure
brushes, or edit infinite-canvas metadata. On infinite notebooks new coordinates
must stay inside a declared page. It supports 500 history operations, 50,000 added
samples, 5,000 samples per stroke, pen widths 0.5–40, and 4 MiB base/output payloads,
in addition to the reader's existing archive/shape/point caps. UI widths are 1–20.
Local journal size is capped at 100 MiB (draft plus at most 16 pending payloads).
The immutable library remains capped at 4 MiB per payload and 1,000 total Drive
objects, with 16 KiB records and 64 MiB per refresh. History growth can reach the
object cap; refresh/publication then fails visibly. No garbage collection,
automatic history pruning, large-notebook chunking or background Mac daemon
is implemented. Split/large notebook migration is not an implied capability.
All-head merge ancestry is readable, but the Mac UI currently offers branch
review/publication rather than the parent's native “choose a resolution” action.

The single `editor-journal.json` contains both draft transition and queue updates,
using the existing private atomic write/file fsync/directory fsync path. A corrupt
journal or payload fails closed and is preserved. Queued account/folder/device,
parent hashes and bytes cannot be redirected after reconnection. Completed payloads
remain in the hash-addressed cache; the journal retains 32 publication receipts.

### Version 0.3 verification and native apply fixtures

Run `test.sh` for decoder, native writer, editor history, durable queue, conflict
policy, actual Drive adapter retry and existing OAuth/library tests. The tests use
disposable local state and fake HTTP; they never open the UI or use the user's grant.
The build preserves any in-use prior bundle. The parent owns the final restart and
interactive drawing/offline-sync review.

Early same-identity native apply artifacts:

- `evidence/editor/offline-mac-add.note`: six pens, one new pen.
- `evidence/editor/offline-mac-erase.note`: follows that output, five enabled pens.
- `evidence/editor/native-apply-handoff.json`: source/output hashes and exact IDs.
- `evidence/editor/*-plan.json`: repeatable source-hash-bound operations.

Writer CLI, available before normal app state/Keychain initialization:

```sh
'notes-drive/macos/build/BOOX Notes Reader.app/Contents/MacOS/BOOXNotesReader' \
  --edit notes-drive/research/automatic/offline-edit.note \
  notes-drive/macos/evidence/editor/offline-mac-add-plan.json \
  /tmp/new-edited-copy.note
```

The output must be a new path. `--inspect` and `--render` also work on edited
exports. Private captures are not bundled. The current mandatory tests use
generated fixtures; private-corpus checks require explicit separate invocation.
Final test/build hashes and CLI checks are recorded in `evidence/editor-tests.txt`
and `evidence/editor-build-validation.json`.

The separately pinned intermediate build is recorded in
`evidence/editor-live-checkpoint.json`. It will not be overwritten by builds.
That checkpoint supports the native pen workflow but predates the final folder
quoting correction and startup recovery guard; use the standard newly built
bundle for those changes.

## Build and run

```sh
notes-drive/macos/build.sh
open 'notes-drive/macos/build/BOOX Notes Reader.app'
```

Build requirements on this Mac: Xcode 26 / Swift 6.3, macOS 13 or newer, Apple
Silicon, and `/usr/bin/python3` from the installed developer tools. The built
app is ad-hoc signed, not notarized for distribution. Keychain may request
permission again after rebuilding its executable.
Builds compile/sign in a new staging directory and retain the previous bundle
under `build/previous-reader.*/`, preserving a running executable. Restart the
app to activate the newly built version. No build step launches/quits the app,
reads/writes Keychain or changes Application Support state.

`build.sh` defaults to generated synthetic validation notebooks and no OAuth
config. It does not read sibling artifacts, checkout/sibling private config or
config from an older bundle. `--output-dir DIR` selects a separate output tree;
`--oauth-config FILE` explicitly includes a private Desktop config byte-for-byte
with mode 0600 and no secret logging. In-app config import remains unchanged.
Do not commit private config or distribute a private-config bundle.

Version 0.6 accepts the user's Desktop project/client, verifies its saved
destination binding, and checks generated fixtures against the bundled manifest.
See [PORTABLE-BUILD.md](PORTABLE-BUILD.md) for source-only validation and
[PORTABLE-RUNTIME-HANDOFF.md](PORTABLE-RUNTIME-HANDOFF.md) for the pinned candidate
and legacy connection migration. Live activation remains parent-controlled.

## Local reading

- **Open .note file…**, file association/open URL, or **Open bundled test notebook**.
- Page navigation, fit-page/reset zoom, zoom in/out with two-axis scrolling.
- Draws exact recorded `x,y` samples, nominal pen width and ARGB color, plus
  supported affine coordinate transforms.
- Shows title, notebook/page IDs, archive SHA-256, dimensions, sample counts,
  layer/z-order metadata, visibility and lock state.
- Honors recorded hidden layers; **Inspect hidden layers** reveals them for review.
- Link bounds are dashed orange placeholders. Internal links can navigate to a
  decoded page using the inspector button. External notebook links are not followed.
- No archive extraction or modification of originally opened input files.

The bundled decoder derives its format from `../prototype/native_fixture.py`
and the recorded firmware 4.2 SDK `PointDocumentLoaderV1` / `NoteShapeDocProto`.
It does not import repository code at runtime.

The stock exporter splits a page's points into separate revisions after 20,000
samples. The decoder indexes chunks by page and point-revision ID, then resolves
each shape's field-16 revision and shape ID. It rejects duplicate chunks for the
same page/revision and duplicate active shape IDs. Unreferenced older point
revisions are flagged and never substitute for missing referenced points.
Unknown point pages remain unsupported and fail visibly; the supplied native
capture needed multiple active chunks, not acceptance of unknown/global pages.

Shape and point files must belong to the active directories beside the decoded
`note/pb/note_info`. Native `stash/shape` and `stash/point` records are historical
content, flagged and omitted even when they reuse a current shape/revision ID.
They cannot supply missing active records. Multiple active shape chunks may
contribute disjoint shape IDs; duplicate page/revision chunks or active shape IDs
still fail. Cross-chunk drawing order is labeled approximate, and unknown shape
document fields are reported. Archive, shape and point limits remain cumulative.

## Automatic native library

- Launch silently reconnects the saved Desktop grant if configured. No browser
  consent is opened automatically. Automatic Keychain calls use an authentication
  context with interaction disabled; if macOS requires approval after rebuilding,
  choose **Google Drive connection → Reconnect saved grant** once.
- **Automatic library updates** is visible and persisted, defaulting on. An
  explicit off preference survives restart/reconnection. Launch still attempts
  one silent connection with it off, but does not start catalog polling.
- A healthy poll starts 12 seconds after the previous operation finishes. Failed
  polls back off through 24, 48, 96, 192 and 300 seconds, capped at five minutes.
  Success restores the normal interval. No app-independent daemon is installed.
- One operation guard covers automatic polls, file dialogs, manual refresh,
  publication and foreground authorization. A manual action cancels the active
  automatic request and starts only after it has released the guard. URLSession
  cancellation aborts a stalled request promptly. A decoder already running may
  take up to its existing 25-second deadline to return before handoff.
- **Native notebooks** shows every non-fixture verified head, using native title
  and page count from the checksummed archive header. All conflict versions remain
  individually accessible. Metadata can still be shown when pen rendering is
  unsupported; full preview errors are explicit.
- Titles are decoded once per new payload hash in batches of up to 24, stopping
  after ten seconds between decodes. Any remaining verified heads are immediately
  listed by their sync ID and gain titles on subsequent automatic passes.
- The open library notebook follows only a single verified descendant in the same
  account/folder. It retains the page UUID across reorder, falls back to a clamped
  page index if that page disappeared, and preserves zoom. Local `.note` files
  opened separately are never replaced automatically.
- A conflicting, unrelated, missing or deletion head preserves the current view.
  Deletion rows request review and retain the nearest ancestor's title/page
  metadata. No cached payload or native notebook is deleted.
- Bundled fixtures and `boox-validation-notebook-v1` stay in the explicit
  **Disposable validation** disclosure. Real Android sync IDs such as
  `boox-<nativeNotebookId>` require no schema change.

The sidebar shows the last completed check, reused object count, downloaded
bytes and bounded-retry errors. A failed refresh keeps the prior verified library.

### Folder metadata

The **Folders** section lists every verified `folder-<native-id>` head, including
empty folders. Notebook and folder rows show their parent path using the native
`note_info` parent ID and the verified folder records. Missing, conflicting,
deleted or cyclic parents remain visible with a review message; a folder
tombstone retains its prior title and never removes a local notebook or cache.

Folder payloads retain the unchanged six-field schema-1 revision envelope.
Their canonical UTF-8 JSON field order is `id,kind,parent,schema,title`, with
`kind:"folder"`, `schema:1` and null for a root parent. Quoting escapes quote,
backslash and control characters (short `b,f,n,r,t` escapes; other controls
lowercase `u00xx`), preserving slash, em dash and other Unicode. Noncanonical
bytes, mismatched identities and invalid parent/title fields fail visibly.

Folder controls:

1. **New folder…** lets you enter a title and select a verified or locally queued
   parent, then **Save folder** queues the canonical record with a fresh native
   32-character ID.
2. **Rename or move…** retains the folder ID and captures the current effective
   head, including earlier queued folder changes. Repeated offline saves chain
   their immutable parents; they never replace a notebook draft.
3. **Request deletion…** is available only for an empty folder with no live
   folder or notebook children. It queues a recoverable `payload:null` tombstone
   after the user reviews the exact folder. Missing/unsupported notebook parent
   metadata blocks deletion. Local files and Drive history remain intact.
4. **Restore folder…** restores the prior native ID/title/parent with the deletion
   revision as its parent. An unavailable destination must be changed explicitly.
   Conflicting prior metadata is refused instead of choosing an arbitrary version.
5. **Folder changes saved locally** distinguishes pending revisions from verified
   Drive rows. **Review saved change** displays their exact identity, ancestry and
   metadata. **Send next queued edit** and automatic updates send folder and
   notebook saves through the same 16-entry FIFO queue.

Folder actions use the existing operation guard, including while a native dialog
is open. Publication checks a fresh complete catalog for changed heads, valid
parent ancestry, cycles and live children. Changed heads require conflict review;
explicit branch permission cannot bypass hierarchy/empty-folder checks. A new
notebook payload whose parent metadata has not yet been decoded leaves a deletion
queued until metadata is verified. Directory access and the captured
account/folder/device identity are checked independently.

These are immutable per-folder changes, not a multi-record transaction. Another
device can race after the pre-publication check; resulting conflicts/cycles stay
visible for review and Android owns native validation. Recursive deletion,
automatic conflict merging and notebook creation/rename/move/delete/restore
controls are not implemented. Pen publication preserves the existing native
notebook title, parent and page list.

Version 0.4 evidence: `evidence/folder-tests.txt`,
`evidence/folder-build-validation.json` and `evidence/folder-changed-paths.txt`.
The earlier pinned editor checkpoint remains unchanged and has no folder
management controls.

### Immutable object cache

The cache is scoped to Google's account permission ID and the selected folder ID.
Every poll rechecks account/folder access, pages the complete object listing and
validates every ID's parent, properties, size, checksum and Drive `version`.
An unchanged ID/version may reuse previously verified bytes. A new duplicate ID
must download in full even when another ID advertises the same hash.

New/changed objects receive size/MD5/SHA-256 validation and a second metadata read
after download. A version change during the read fails the refresh. Missing
version metadata falls back to full download. Canonical records, ancestry,
payload availability and every duplicate still have to verify.

Verified records/payloads and receipts persist in
`verified-objects-<account-folder-hash>.plist` using the existing atomic/fsynced
writer. Unchanged polls do not reread the disk cache or download media. Persisted
bytes are hash-checked before use after restart; a corrupt cache is preserved and
reported. In-memory canonical/catalog checks still hash bytes for integrity.

This bounded reader caps resident cached logical bytes at 64 MiB in addition to
the protocol's 64 MiB download budget, 4 MiB payload and 1,000 object limits.
Each network catalog refresh has a two-minute deadline. Cache size and transport
caps fail visibly while preserving the current view.

## Fidelity boundaries

This is a **coordinate preview**, not a reimplementation of BOOX’s renderer.
Pressure/tilt brushes, smoothing, eraser compositing, dash styles, template
backgrounds, rich text, attachments, media, full link thumbnails and infinite
canvas semantics are not reproduced. Unknown shapes and resource/virtual
metadata are reported in the inspector; unsupported shapes with available bounds
get orange outlines. Only normal pen type 2 is drawn as a stroke. Unsupported
point versions, unsafe/malformed archives and invalid transforms fail visibly.

Local preview limits: 16 MiB input and aggregate expanded nested archives,
512 entries per ZIP, 200 declared pages, 10,000 shapes and 200,000 point samples.
The helper has a 25-second runtime deadline and a 32 MiB output cap.
Cloud payloads use the stricter shared 4 MiB protocol limit.

The synthetic fixture check renders 11 pen strokes across two pages with one
internal link placeholder, 343 stored samples and one/two layers respectively.
Pen coordinates and point hashes match the recorded fixtures. This does not
establish fidelity for arbitrary user notebooks or another BOOX firmware.

The private native `test` capture decodes 20 declared pages, 54,234 stored samples
and 319 supported pens. Page 1 contains two point chunks (151 and 48 blocks);
both match the decoded shapes' exact block hashes. Fourteen type-2000 shapes
remain unsupported. Resource, template, infinite-canvas and stashed historical
content limitations remain explicit. The private source file is read in place
and is not copied into the app or distributed with tests.
The separate private offline-edit capture decodes one page, five pens and 154
samples. Its extra `stash/shape` archive duplicates the newly added pen's ID;
that historical record is omitted, so the new pen appears exactly once.

## Google authorization and transport

OAuth uses the user's imported Desktop client configuration.
It opens the default system browser with random state, S256 PKCE and a callback
listener bound only to `127.0.0.1` on an ephemeral port. The callback expires
after three minutes, rejects wrong/duplicate state fields and limits request size.
The only requested/accepted scope is `https://www.googleapis.com/auth/drive.file`.

Refresh tokens are stored in Keychain under service
`local.boox.notesreader.google-oauth`, with the Desktop client ID as account.
Access tokens stay in memory. No Android, browser, OpenAI or connector tokens are
read or reused. The app does not log OAuth responses or token-bearing requests.
**Forget this Mac's Google grant** deletes its local Keychain entry; it does not
revoke the grant remotely.

The exact saved directory ID is checked at runtime, not treated as permission.
The app checks MIME type, exact ID, Trash state, private protocol marker and edit/
add-child capability. It discovers other authorized marked directories and binds
the chosen folder to Google's account permission ID and the imported Desktop
client ID. Existing selections gain that client binding only after the same
account and exact saved directory verify. Lost access or a different client/account
never falls back to another folder. A new installation discovers authorized
marked directories, selecting a sole verified result or offering an explicit
choice when several exist. It creates no replacement if none is authorized.

`Sources/Drive.swift` implements `../PROTOCOL.md`:

- Objects are immutable direct children with protocol/type/hash properties.
  Names are descriptive, not identity. Every new or changed duplicate Drive ID
  is downloaded/verified; unchanged IDs may reuse verified cache receipts.
- Canonical six-field JSON is compared byte for byte and SHA-256 checked.
  Unknown fields, duplicate JSON keys and noncanonical encodings are rejected.
- Complete ancestry and every referenced payload must verify before the catalog
  is accepted. All concurrent heads are shown; no timestamp-based winner.
- Deletion heads are visible review requests. No local notebook is deleted.
- Caps: 4 MiB payload, 16 KiB record, 1,000 unique IDs, 100 listing pages,
  64 MiB total downloaded object bytes. Redirects/incomplete searches/repeated
  tokens, changed metadata during pagination and checksum failures are refused.
- Explicit **Publish B2 fixture descendant** requires one existing validation head.
  It never starts a new library or edits a real notebook. The existing B2 fixture
  has different export bytes from Android's Target-after fixture.
- A pending publication persists its account/folder/device identity, captured
  parents and exact bytes with file/directory fsync before network writes.
  Payload upload/readback precedes revision upload/readback. A final full catalog
  verification precedes the saved publication receipt.
- Unknown write outcomes remain pending. Retry validates existing duplicates
  before writing, retains the original ancestry and cannot redirect to a new
  account/folder. No protocol objects are edited, trashed or deleted.

Private app state lives under
`~/Library/Application Support/BOOX Notes Reader/`: device ID, connection selection,
optional imported OAuth config, pending/last publication, automatic-refresh
preference, editor draft/queue/receipts, verified object cache and hash-addressed
payload cache.
A process lock prevents simultaneous writers. Downloaded payloads
are cached separately; checksum mismatches preserve suspect cache files for review.

Automatic inbound library consumption is implemented while the app is open.
Native BOOX import/apply remains the Android coordinator's responsibility.
The Mac editing scope is the bounded native pen workflow described above.
The Mac's successful publication means **verified transport**, never native apply.
The parent completed the live OAuth/Android interoperability exercise documented
above. The parent's native save/export hook and Android automatic uploader supply
real snapshots; their live end-to-end validation is separate from the Mac tests.

## Verification and command-line preview

```sh
notes-drive/macos/test.sh
'notes-drive/macos/build/BOOX Notes Reader.app/Contents/MacOS/BOOXNotesReader' \
  --inspect notes-drive/tests/artifacts/Target-after.note
'notes-drive/macos/build/BOOX Notes Reader.app/Contents/MacOS/BOOXNotesReader' \
  --render notes-drive/tests/artifacts/Target-after.note notes-drive/macos/evidence/target
```

`--inspect` prints metadata and limitations; `--render` writes one PNG per page.
These modes exit before creating normal app state or reading Keychain.

Tests cover real native fixture structure/point coordinates, input preservation,
archive/path/point limits, malformed protobuf and pre-decode hash rejection;
all three shared Python protocol vectors; concurrent/deletion heads; corrupt
duplicates and missing ancestry; denied/incomplete/repeated Drive listings;
payload-first publication and retry after a simulated server commit with lost
response; durable reopen/concurrent-writer exclusion; and an actual loopback
HTTP callback rejecting wrong state before accepting the matching state.
Additional live-library tests exercise warm cache zero-download polls, changed
versions/new duplicates, post-download metadata drift, persistent cache corruption
and account isolation, saved automatic preference, backoff/no-overlap, actual
stalled-HTTP cancellation, manual handoff, descendant/page/zoom following and
conflict/deletion/empty-catalog preservation. Results:
`evidence/automatic-tests.txt` and `evidence/automatic-validation.json`.
The decoder chunk regression results are in `evidence/decoder-chunks-tests.txt`
and `evidence/decoder-chunks-validation.json`. They cover native multi-chunk
reading, unchanged fixture strokes, conflicting chunks/active shapes, stale
revision isolation, missing references, aggregate budgets and malformed extra
chunks. The private capture test is skipped only when that local file is absent.
The subsequently rebuilt bundle's hashes, strict signing verification and native/
fixture CLI checks are recorded in `evidence/decoder-chunks-build-validation.json`.
The later shape-directory/chunk regressions and rebuilt bundle checks are in
`evidence/decoder-shapes-tests.txt` and `evidence/decoder-shapes-build-validation.json`.
They include both private captures, disjoint chunks, cross-chunk collisions,
stash isolation, malformed records, cumulative limits and unsupported-field warnings.

Keychain reconnect, Google browser consent, shared folder visibility and live
Android ↔ Mac transfers passed the parent's live review. Expiry/revocation and
long-lived background operation remain separate tests. The parent has confirmed
native library discovery and automatic preview following. The new editor's
interactive Mac → Drive → native application review is owned by the parent.

If the signed-in Google browser is different from the default browser:

```sh
'notes-drive/macos/build/BOOX Notes Reader.app/Contents/MacOS/BOOXNotesReader' --connect-url
```

This starts the same real state/PKCE/loopback flow and prints only its authorization
URL to stdout, leaving browser selection to the caller. Open that exact URL in
the signed-in browser within three minutes. The command stays running until the
callback/token exchange completes, saves the grant in this app's Keychain entry
and reports success to stderr without printing any tokens. It does not read
browser cookies or existing client tokens. Then use **Reconnect saved grant** in
the native app. The normal **Connect Google Drive…** button still opens the
system browser.
