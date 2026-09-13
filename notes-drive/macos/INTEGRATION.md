# Mac client integration surface

The native app lives entirely in `notes-drive/macos/`.

- SwiftUI/AppKit shell and Core Graphics pen rendering.
- Bundled `Resources/note_reader.py`: bounded, read-only ZIP/protobuf decoder
  invoked with `/usr/bin/python3` (available on this Mac with Xcode). No extraction.
- Default builds contain no OAuth config. `build.sh --oauth-config FILE`
  explicitly stages a Desktop JSON byte-for-byte; it never inherits checkout,
  sibling or prior-bundle credentials. In-app import remains unchanged. The
  runtime uses the imported client's own Keychain entry and saved binding.
- OAuth: system browser, PKCE S256, random state, 127.0.0.1 loopback callback,
  `drive.file` only; refresh token in Keychain, access token in memory.
- `Sources/Revision.swift`: canonical `Revision.encode`/SHA-256 and head validation.
  `Sources/Drive.swift`: exact immutable object adapter for `../PROTOCOL.md`,
  including all three private properties, descriptive filenames, duplicates and
  bounded listing/download verification.
- No private folder ID is embedded. Discovery checks actual access and folder
  metadata; reconnection verifies the exact saved destination without fallback.
- Native notebook files are never overwritten. Cloud payloads go into a separate,
  hash-addressed local cache. Conflicting heads stay separately selectable.

App output: `build/BOOX Notes Reader.app`.
Validation entry point: `test.sh`.

Version 0.6 parent live acceptance now confirms authenticated nested-notebook
draw/undo/redo/save, own-OAuth publication, exact-branch Android **COMMITTED**
readback and a visible pen in stock Notes. After normal native close, the Mac
automatically fetched the verified one-pen/two-sample return and the parent
opened it explicitly from the library. A separate native rename/normal-close
test then updated the already-open Mac notebook without refresh or reopen,
preserving page and zoom. Bundled-fixture GUI opening also passed with two pages,
343 samples and the expected synthetic payload hash. All planned version 0.6
Mac acceptance checks pass; no Mac acceptance blocker remains.
The parent also reports 162 clean-clone checks passing. Exact private identities
and hashes remain in `notes-drive/research/incoming/mac-v06-nested-ui`; see
`PORTABLE-RUNTIME-VALIDATION.json` for the public acceptance summary.

## Shareable test-data preparation

The frozen 0.5 app and native-create fixture remain unchanged; see
`LIFECYCLE-HANDOFF.md`. `Tests/synthetic_fixtures.py` now generates every mandatory
notebook input independently using public UUID5 labels and arithmetic coordinates.
`Tests/synthetic-manifest.json` pins all five profile hashes. The large profile
retains 333-shape/319-pen, 54,234-sample, 20-page/chunk/resource/history coverage.
No original handwriting, titles or account identities are copied.

`test.sh` now creates a fresh `.test-runs/run.*` workspace, using local synthetic
protocol vectors rather than sibling artifacts. **162 mandatory checks pass**
from an isolated Mac-only source copy without private archives, shared artifacts,
config or evidence. The separate explicit `Tests/private_corpus.py` has five
passing checks and never rewrites its inputs. Existing private evidence is
untouched. Detailed commands and distribution boundaries: `SYNTHETIC-TESTS.md`.

Cross-directory coordination: the shared protocol-vectors file was not modified;
its local test copy was compared byte for byte. `build.sh` now generates its
Target-after/B2 resources from pinned synthetic profiles and defaults to no
config. No shared files were overwritten or cleanup/push performed.
Authorized version 0.6 runtime work removes private project/client/folder
defaults, binds the imported client to the verified saved destination and checks
generated fixtures against their bundled manifest. The supplemental CLI requires
that own-client binding while retaining its no-override and probe-only limits.

The authorized Python ancestor fix adds nine synthetic regressions. Native
`note_info` contains repeated NoteModel rows; select the document whose field-1
ID matches the archive root and preserve other folder rows byte-for-byte.
Metadata edits replace only that selected wrapper entry. Embedded folders are
validated for unique IDs, folder type and acyclic ancestry; incomplete/external
or retained old chains are allowed because Drive folder records control the
library. See `NESTED-METADATA-HANDOFF.md` for the separate signed live-test
checkpoint. The original live bundle remains unchanged; the authorized 0.6
runtime candidate is documented in `PORTABLE-RUNTIME-HANDOFF.md`.

`PORTABLE-BUILD.md` records packaging options and source-only app validation.
`Tests/generate_native_apply_fixtures.py` adds deterministic production-writer
add/erase variants and a normalized add derived by omitting only zero layer
fields. Original synthetic profiles remain unchanged. The Android parent can
generate these independently; no shared fixture paths are overwritten.

## Version 0.5 native notebook lifecycle

Frozen app: see `evidence/lifecycle-checkpoint.json`. The parent restarts it after
unlocking; neither tests nor the builder launch or modify the live app.
**125 checks pass:** 29 reader + 10 pen writer + 7 lifecycle writer + 79 Swift.

`Resources/note_library.py` adds bounded fresh blank creation and metadata-only
patches. Create plans use fresh 32-character document/page IDs, 1–32 pages,
one unlocked layer, native type/status/version 1, blank background and no copied
owner/resource/virtual metadata. App pages default to 1860 × 2480.
Metadata changes require an exact source hash and same root/document identity;
they patch NoteInfo fields 3/4/6/31 only (updated/title/parent/live status).
All other protobuf wire fields and ZIP member contents survive byte for byte.
An unchanged live metadata plan returns the exact original archive bytes.

`Sources/NotebookManagement.swift` connects real AppKit dialogs, native helper
readback, durable queue, opening saved snapshots and preserved page/zoom.
The native sync ID remains `boox-<nativeID>`. There is no wire-schema change.
Tombstones omit payloads and retain captured parents. Restore parents the
tombstone and uses its nearest uniquely identified retained payload; ambiguous
history is refused. Metadata/delete cannot replace same-book draft history,
including fully undone actions with Redo available; unrelated drafts survive.
New notebook creation does not auto-open over an unrelated draft. A visible deleted local copy cannot be edited into an
accidental resurrection. Explicit restore is required first.

`EditedSnapshotPublisher` rechecks native payload hash/document identity and
the live parent-folder chain against fresh verified state. Pending snapshots
are shown separately, never relabeled as remote verification. The account/folder/
device binding survives display and restart; no implicit rebase is introduced.
Lifecycle tests use real generated `.note` bytes with fake Drive, including
complete offline ancestry/restart, payload-before-record, lost tombstone
response retry, destination removal, conflict/mismatched identity, ambiguous
restore and actual controller state/draft checks.

Fresh blank fixture: `evidence/notebook-management/native-blank.note`,
SHA-256 `6a31831b150b9689afbeac89bebda4c7518741191f8d3d887e95a276b65b24b2`.
Metadata fixture hashes/plans: `metadata-fixture-handoff.json` in that directory.
Native readback of fresh creation is parent-owned. Stock export's generated
`extra/pb/extra` may need a narrowly validated creation allowance in Android;
existing archives' original extras are retained. This was flagged to the parent.

The native all-current-heads resolution convention is supported by Revision
and the catalog. The current Mac UI publishes preserved branches and does not
offer that resolution chooser. Publication/automatic following remains bounded
to 4 MiB payloads, 1,000 objects and 64 MiB/refresh; no unbounded parity or history
pruning is claimed. New blank pages can be chosen at creation; existing
page/layer topology and rich content are not editable.

The parent tested the pinned own-OAuth CLI, which failed closed with Keychain
`-25293` while locked, preserved an isolated request and did not publish.
Interactive editor/lifecycle and current own-OAuth tests await unlock/reconnect.
All prior pinned checkpoints and grants/state remain unchanged by this agent.

## Version 0.4.1 supplemental Mac OAuth CLI

`Sources/ProbeCLI.swift` adds `--validate-probe-publish`,
`--validate-probe-retry` and `--validate-probe-help` before ReaderModel/UI startup.
Detailed parent commands are in [PROBE-CLI.md](PROBE-CLI.md). There are no token,
account, folder or configuration overrides.

The CLI requires both the candidate and exact remote base to be the same native
GDrive-Sync-Probe notebook with unchanged title/pages/layers and an actual pen
change. It reads only saved connection/device/config metadata, uses the existing
Mac Desktop Keychain grant without interaction, verifies actual account and
saved directory, and checks the sole head before and after payload upload.
Modern and legacy Keychain prompt paths are disabled in the standalone process.

Exact request bytes are fsynced into a new directory outside live app state and
all app bundles. It never acquires or modifies the running UI's journal/lock,
cache, preferences, selection or device ID. Retry reuses exact bytes/parents and
verifies a prior unknown remote commit without duplicate writes. A late conflict
produces an explicit head-review receipt and exit code 2; interactive/native
validation flags always remain false. The complete CLI has a 300-second deadline.

**108 automated checks pass:** 29 decoder, 10 writer and 69 Swift checks. New
tests use synthetic grant/HTTP dependencies and a held fake UI process lock,
checking preserved UI files, silent-denial recovery, private-note rejection,
base/identity/account/config guards, isolated path protection, lost responses
and publication races. The agent has not executed live grant/publication CLI
commands. Frozen bundle/hash and safe packaged CLI checks:
`evidence/probe-cli-build-validation.json`.

Parent now reports add and erase both COMMITTED with correct native visual
reopen, plus exact component-hash/SQL recovery at DOCUMENT_REPLACED. Those results
are recorded as parent evidence in `evidence/editor-live-native-status.json`;
the Mac own-OAuth and interactive editor tests remain separate.

## Version 0.4 folder management handoff

`Sources/FolderManagement.swift` adds real AppKit dialogs for folder create,
rename/move, empty-folder deletion requests and restore. New folders use native
32-character IDs. Existing IDs and canonical UTF-8 folder bytes are retained.
Folder actions use the same durable 16-entry editor queue, captured destination
and immutable parent ancestry, without replacing the notebook draft. Queue
encoding is backward compatible; a folder tombstone has empty local payload
bytes and the wire `deleted:true,payload:null`. No payload object is uploaded.

Restore parents the deletion revision and reuses prior metadata. If ancestors
disagree, restore refuses to select a winner. A missing/deleted/conflicted parent,
cycle, unknown notebook parent metadata or live child prevents unsafe folder
mutation. Deletion checks are repeated against the fresh publication catalog,
including when an explicit conflicting branch is authorized. Android must still
validate native application; cross-record races cannot be eliminated by the
current per-folder protocol.

**97 automated checks pass:** 29 decoder, 10 writer and 58 Swift checks. The added
native readback regression reads `../tests/artifacts/native-mac-add-readback.note`,
confirms all six pen identities/point hashes and omitted proto3 zero, then adds a
seventh pen while preserving the six originals. That private fixture is read in
place and never bundled. The later parent COMMITTED result is recorded above.

Before the next live Mac pen edit, refresh/open the latest verified head instead
of starting from obsolete `c0249a…`. Parent published
`9962bcf6c58e8977924e10ab13c625929facaa56b9fc73d8ae1084e7bef77eb2`
using Android's own grant and logical device `mac-encoded-fixture-validator`.
Mac UI/OAuth and folder application still require parent live review.
`evidence/editor-live-native-status.json` records the exact distinction.

App output remains `build/BOOX Notes Reader.app`, version 0.4. Pinned editor
checkpoint copies remain unchanged. No app, Keychain, OAuth or live user state
actions are performed by the builder or this agent. Final evidence:
`evidence/folder-tests.txt`, `evidence/folder-build-validation.json` and
`evidence/folder-changed-paths.txt`.

## Version 0.3 pen editor handoff

No shared wire schema change. `Resources/note_editor.py` applies base-hash-bound
add/whole-stroke erase operations, preserving native IDs (32-character and
hyphenated) and unknown member contents/wire fields. Native erasure retains a
status-1 record and original points; semantic BOOX export readback must compare
enabled shapes and referenced points because re-export filters removed records.
Changed shape chunks receive fresh info-revision IDs. The book's title, parent,
page identities/list and unknown metadata remain intact.

`Sources/Editor.swift`: durable draft/history, atomic draft-to-queue transition,
16-save FIFO queue, conflict policy, actual edited-snapshot publisher and native
helper interface. `Sources/EditorController.swift`: real library/local editing,
offline saves, export, reconnect/retry and explicit conflict branch publication.
`Sources/App.swift`: mouse/tablet pen input, whole-stroke erase, undo/redo and save
controls. The existing manual/automatic operation guard serializes these actions.

Drafts persist after completed gestures and restore on launch. Dirty/history-bearing
drafts and queued edits suppress auto-follow. Incoming heads remain individually
visible. Outgoing retries keep captured ancestry/bytes/destination; a changed head
pauses for explicit review, and a chosen branch preserves competing heads.
Automatic updates also send queued saves using the existing own Mac grant.
No UI, grant or live app-state actions were performed by this agent.

Native apply fixtures are ready in `evidence/editor/`; start with
`offline-mac-add.note`, then `offline-mac-erase.note`. Exact hashes, preserved IDs
and base relationship are in `native-apply-handoff.json`. Plan JSON is included.
Parent owns Android apply, final UI restart and interactive end-to-end readback.
The README records all current editing limitations and CLI reproduction commands.

Verification: **85 passing checks** (29 decoder, 9 native writer, 47 Swift).
The writer regression suite exercises both synthetic fixtures and both private
native captures, opaque-member preservation, existing identities, native erasure,
deterministic output and rejected edits. Editor tests exercise fsynced restart,
successive offline ancestry, destination binding, an unknown remote commit,
conflict/deletion preservation and actual controller startup recovery failure.

The pinned intermediate bundle and its executable hash are recorded in
`evidence/editor-live-checkpoint.json`; it is never overwritten. Subsequent
builds at `build/BOOX Notes Reader.app` include the final canonical folder quoting
and corrupt-startup journal write guard. Build/CLI evidence is in
`evidence/editor-build-validation.json`. No live app restart is performed.

### Folder integration

`Sources/FolderRecord.swift` consumes the agreed opaque folder payload under
`folder-<native-id>` using the existing schema-1 revision envelope. Exact canonical
field order is `id,kind,parent,schema,title`. The explicit string encoder preserves
slash and all non-control Unicode (including em dash), escapes quote/backslash,
uses short `b,f,n,r,t` escapes, and lowercase `u00xx` for other controls.
No `JSONObject.quote`-specific slash or Unicode escaping is accepted.

Native notebook metadata includes `note_info` field-4 parent ID. Separate folder
rows and parent paths preserve every verified head, empty folder and tombstone.
Missing/cyclic/deleted/conflicting ancestry requests review. Version 0.3 folder
metadata was read-only; version 0.4 above adds folder management. Notebook rename,
move, delete and restore controls remain unimplemented. Pen edits retain current
notebook title and parent.

## Offline edit and active shape directories

The five-pen offline edit contained one current shape archive plus a historical
`stash/shape` archive for the new pen. Broad substring matching incorrectly read
both as current page data. The decoder now scopes shape/point files to the active
directories of the selected notebook. Stashed records are flagged and omitted;
they never collide with or fill in for current records.

Multiple active shape chunks with disjoint shape IDs are accepted. Duplicate
page/revision chunks, duplicate active shape IDs and malformed records still fail.
Cross-chunk drawing order is labeled approximate; unknown document fields are
explicitly reported. Existing aggregate decoding limits remain in effect.

Tests: **60 passing** (29 Python decoder + 31 Swift core), including both private
captures and all existing fixtures. The offline edit yields one page, five pens,
154 samples and no missing active point references. The prior native capture
still yields 20 pages, 319 supported pens and 54,234 samples.

The bundle is rebuilt for the parent to restart through UI; no UI/grant/state
actions were taken. Build hashes, signature verification and bundled CLI checks:
`evidence/decoder-shapes-build-validation.json`. Test log:
`evidence/decoder-shapes-tests.txt`.

## Built decoder fix for the native 20-page capture

The parent authorized rebuilding after the source tests passed. The updated
bundle is built and strict signature verification passes. **Parent UI restart
required**; the current process was not restarted. Grant, app state and current
UI are untouched, and the previous bundle is retained.

The private `../research/automatic/native-test.note` contains two valid point
chunks for its first page. The decoder now resolves `(page, revision, shape)`
instead of assuming one point file per page. Its 20 pages, 54,234 samples and
319 supported pen strokes decode successfully; 14 type-2000 shapes and the
existing resource/template/infinite-canvas limitations remain explicit.
Duplicate page/revision chunks and duplicate active shape IDs still fail.
Unreferenced point revisions cannot replace active points, and unknown/global
point pages still fail rather than being silently merged.

Tests: **51 passing** (20 Python decoder + 31 Swift core), including the private
capture and eight portable regression cases. Evidence:
`evidence/decoder-chunks-tests.txt`, `evidence/decoder-chunks-validation.json`.
No protocol, cloud, Android or Swift UI change is required for this fix.
Bundled CLI inspection also passes for the native 20-page capture and synthetic
two-page fixture. Build hashes and signature/CLI results are recorded in
`evidence/decoder-chunks-build-validation.json`.

## Version 0.2 automatic library handoff

**Restart required.** The app is rebuilt without launching, quitting or touching
the existing grant/state. The previous bundle is retained in
`build/previous-reader.*/` so a running process keeps its executable intact.
The parent owns the restart and live UI/native-save validation.

- On launch: silent own-Keychain reconnect, then automatic catalog consumption.
  No consent browser is opened. A visible persisted switch defaults on.
- Healthy interval: 12 seconds after completion; error backoff up to five minutes.
  Manual actions cancel/pause automatic work and own the same exclusive guard.
- Real native IDs `boox-<nativeID>` are consumed using unchanged schema 1.
  Native title/page count comes from the verified archive. Fixture controls/heads
  are kept in a separate validation disclosure.
- Per-account/folder immutable cache uses fresh Drive ID/version/parent/properties/
  size/MD5 metadata. Unchanged media is reused; every new duplicate or changed ID
  downloads and verifies in full. Persistent cache bytes validate on reload.
- An open notebook follows only a verified single descendant; page UUID/index and
  zoom survive. Conflict/deletion/missing/unrelated heads keep the current view.
- Original build tests: **42 passing** (11 Python decoder, 31 Swift core). Actual socket tests
  cover OAuth state and cancellation. Automatic tests cover backoff/guard handoff,
  cache integrity/reuse and library/follow behavior.
- This does not apply imports on BOOX or edit notes on the Mac. Resident immutable
  logical bytes are capped at 64 MiB; the existing protocol limits still apply.

Core integration files: `Sources/AutomaticLibrary.swift`,
`Sources/VerifiedObjectCache.swift`, `Sources/ReaderModel.swift`,
`Sources/Drive.swift`, `Sources/HTTP.swift` and `Sources/App.swift`.

Parent completed Google Cloud registration/live OAuth/launch review and the
bidirectional fixture exchange; see `../INTEROPERABILITY-VALIDATION.md`.
No browser or tablet state was changed by this subagent.

Previously validated version 0.1 UI flow (manual controls now live in disclosures):

1. Open `build/BOOX Notes Reader.app`.
2. **Connect Google Drive…** opens the default system browser. The app handles
   its own PKCE/state/loopback flow; no manual code copying.
3. A successful grant lists/checks app-managed directories. Version 0.6 restores
   the exact saved account/client/directory binding, or offers discovered writable
   marked directories for a new installation. It has no private fallback ID.
4. Refresh the catalog, then open its verified notebook. Version 0.2 performs the
   refresh automatically and offers **Open notebook** on each library row.
5. **Publish B2 fixture descendant** is enabled only for a single existing
   `boox-validation-notebook-v1` head and no unresolved publication.
6. The exact account/folder/device/parents/payload/record are fsynced first;
   **Retry saved publication** retains them after any uncertain result.
7. After publication, refresh Android. The Mac never claims a native apply.

Import the user's registered Desktop client JSON. Default builds contain no
config; an explicitly configured private build must not be published. Client
secrets are never printed or included in source, and tokens remain in this
client's existing app-only Keychain namespace.

For the parent's already signed-in browser, run the built executable with
`--connect-url`. It prints the genuine authorization URL, awaits its own loopback
callback for up to three minutes, then exchanges/saves its own grant in Keychain.
Open the printed URL with the parent's browser tool; no session/token extraction.
After success choose **Reconnect saved grant** in the app. This option does not
open a browser automatically or create normal app/cache state.
