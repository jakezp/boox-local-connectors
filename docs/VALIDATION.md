# Source and live validation checkpoint

Checkpoint: 2026-09-13. Private notebook captures and device credentials remain in
the original workspace; the source repository contains the reproducible tests,
synthetic fixture generators and validation scripts.

## Reader Drive and Android styling — 2026-09-13

The Reader storage/apply implementation passed on NeoReader38701 with hook
`ddf8963994a48c9b` (APK SHA-256
`f0840a5b993d55e1e491f337f642e1ebf017890422ea13e9994bc20a89943adf`).
The subsequent native-switch UI fix is recorded separately from this storage
checkpoint; changing a hook build identifier does not rerun prior device tests.

- A generated PDF was published to the real managed Drive directory and restored
  as a fresh native book identity, with matching complete manifest readback.
- A native bookmark, highlight/text note and pen stroke were saved. The pen was
  entered through the inspected device digitizer input path; this was automated
  native input, not a physical stylus trial.
- A changed annotation was published through Drive and applied to the same book.
  NeoReader visibly displayed “Returned through Google Drive” with the retained
  highlight and orange pen stroke. The saved page reopened at 2/4.
- The annotated incoming bundle retained two annotation rows, one bookmark, one
  ReaderNote shape and 24 reading-statistics rows, including repeated annotation
  UUIDs used for distinct native history events.
- Interruption after file writes, after database writes and after commit recovered
  matching bundle hashes and every native row/ID. These checks were repeated with
  the annotated fixture after fixing event-row identity handling.
- Two concurrent reading heads remained separate. The normal settings dialog
  selected a version; the verified result retained both original heads as parents.
- A second fresh-identity restore passed without the earlier fixture repair path.
  It restored the ebook and twelve reading-statistics rows through normal sync.
- Reader held synchronization while a book was open; saved document close and
  subsequent automatic checks published changes. Switching between the two
  disposable books completed without a lock deadlock.

Tests exposed and corrected deterministic row ordering, preservation of device-local
paths, native per-book database caching after rollback, and repeated statistics
UUIDs. Failed attempts and their recovery evidence are retained privately. The
explicit instrumented checks are shipped as source and use the companion's own
normal Google authorization; they do not borrow a token from another app.

The source suites pass **66 Android tests** and **140 host tests**, with one
optional host case skipped (141 discovered). The OpenAI styling APK installed
successfully with every saved preference file unchanged. Its existing ChatGPT
session and selected model remained visible. No new paid API request was required
for the styling change; the setup window retains its secure-screen flag.

The final installed settings build is `f8f7839abd047ae1` (APK SHA-256
`44d88f6505afb22ce91bb10361e91154219f09c2b5a4f6c31177583830ba2adf`).
Its native Library switch passed OFF/ON, info navigation and refreshed state on
return. The native Notes section also displayed the persisted enabled state.
The final fixes change settings state handling; Reader storage/apply code is
unchanged from the data checkpoint above.

The before/after device backup comparison retained all 236 pre-existing shared
assets. All 24 native Notes databases either matched byte for byte or, for the
configuration database, contained identical rows. The original ebook bytes,
annotations, bookmarks, reading position and prior statistics were retained.
Opening that book normally during validation changed access/document timestamps
and added three reading-history events; it was not used for incoming-write tests.

These are real Drive/native tests on one BOOX using disposable books. A physical
second-BOOX full-library restore, multi-day unattended operation and large-library
scale remain separate acceptance work. Library shelves, native file deletion
propagation and provider-encrypted books are outside the current per-book sync
contract. See [Reader setup and boundaries](READER-DRIVE.md).

## Native Settings update — 2026-09-13

The Notes Drive editor hook and settings-only launcher hook are verified on
Notes45326 / launcher56737. The native Google Drive section appears below ONYX
Cloud, its info icon opens setup, and its switch controls both automatic directions.
The setup UI now describes BOOX-to-BOOX sync and shows library counts rather than
Mac validation records. Optional fixture publication is collapsed under diagnostics.

Current Android suite:58 tests. An added empty-local-state case discovers current
remote notebook/folder heads without a pre-existing branch. This does not replace
physical second-BOOX acceptance. The host suite adds a launcher-version guard test
and verifies the two-scope installation contract. Earlier Mac and root results
below are historical checkpoints and were not rerun for this UI change.

## Clean-checkout results

A separate Git clone of source commit `113c0fe` built:

- Notes Drive Android connector and its57 unit tests.
- OpenAI connector and its separate instrumentation APK.
- Both historical Notes probe APKs.
- Mac app and162 mandatory checks (69 Python / 93 Swift).
- Host suite:115 passed, one explicitly optional private-input case skipped.
- Protocol/prototype suite:19 passed,21 historical private-capture cases skipped.

The final publication candidate also passed all 137 host checks (138 discovered,
one optional private-input case skipped), including 22 complete-archive tests. The extra archive checks cover timestamp-only
metadata churn with a mandatory second content pass; Android/Mac runtime code
is unchanged from the successful remote-clone builds.

No private `.note` file, OAuth config or signing key was copied to that clone.
It generated fresh signing identities. Android SDK/Gradle came from freshly
downloaded hash-verified archives, and Gradle used an empty user cache. The Mac's
existing Python/JDK/Xcode installations were used. This was not a clean OS
provisioning or a fresh-device root/install.

The subsequent dependency manifest records501 SHA-256 entries across291 components.
The Android build passed again with `--dependency-verification strict --offline`.
Future dependency changes require a deliberate review of regenerated checksums.
Builds of new clones use a different local signing key, so APK hashes need not
match the installed development APK; hook source and synthetic asset hashes do.

## Live device and Mac results

The installed Notes connector uses hook `1b35137b83528a78`; the synthetic-asset
APK hash is `fce3cc2727b64f139796ae4f45d2dab48a4a5c41ce1f2fdf02bd3fb432bf42e6`.
Its installer verified the actual current Notes process loaded that generation.

The Mac v0.5 lifecycle UI independently reconnected its Google grant and passed:

1. Mouse-drawn pen → Save to library → automatic Drive publication → native
   same-ID six-pen readback → visible stock Notes opening.
2. Normal native save/close → automatic Mac following, with page/zoom retained.
3. Whole-stroke erase, undo and redo → native five-pen readback.
4. New notebook, rename and folder move → native creation/opening.
5. Recoverable deletion → native Recycle Bin status 0 → restoration under the
   original ID with the retained blank page.

Earlier native tests passed all seven apply crash checkpoints, folder lifecycle,
and Android conflict selection retaining both heads as parents. The recorded
preservation snapshot matched all 182 associated original files and every field
of three original notebook rows. That snapshot is a dated proof, not an assertion
that later user edits cannot exist.

Testing found repeated ancestor folder records in native notebook exports. Mac
v0.6 selects the exact archive-root notebook and preserves ancestor/unknown bytes
through edits. Its162automated checks and retained native export exercises pass.
The rebuilt app imported the existing Desktop configuration through its UI and
reconnected its own grant after the user completed Keychain authorization.
Its final nested-notebook live test passed:

1. Open the latest BOOX export containing an ancestor folder record.
2. Draw a pen through the Mac UI, undo, redo and save. Automatic publication
   produced revision `dc3b267a8ea4ce47ef4606349cc4079ccc26c9ccddff8747d52a6164e5d44921`.
3. Verify BOOX committed that exact branch under the same notebook ID, retaining
   the folder and one pen. Native readback SHA-256:
   `d1fe8bf958136d8f15cce2ac81fa6230a88b81e74abb55bebd5c4bd376f9a918`.
   Stock Notes visibly rendered the blue diagonal.
4. Close normally and open the automatically fetched native return in the Mac.
5. Rename in stock Notes while the notebook remains open in Mac Read mode.
   The Mac automatically followed revision
   `009b9ae05cadb61e06d5b58c4c5735f78d962115da7542faee3bff1b9b782099`,
   showing the new title and retaining the pen, page and zoom without refresh
   or reopening.
6. Open the bundled synthetic notebook through the UI: two pages and 343 samples.

The post-validation native snapshot again matched all 182 original associated
files and every field in the three original notebook rows. Private evidence is
retained under `notes-drive/research/incoming/mac-v06-nested-ui/`. An initial
comparison used an incomplete snapshot that omitted shared `.ksync` files;
the complete snapshot comparison passed and both reports remain available.

The v0.6 executable checkpoint is
`fc19e074c3dab9f9b19a0a6125c577042cc066f5ddbd3cf4f2a56eb4e6996890`.
This exact binary passed the live acceptance above. No runtime code changed
between its build and the final test.

## Reproduce and interpret

Use the commands in the root README and [end-to-end guide](reproduction/README.md).
Run live fixture helpers only against an explicitly selected device and current
revision. A verified upload is not proof of native application; the native
readback helpers check the applied branch separately.

Root preparation has27 mandatory safety tests plus an optional actual-input test;
all 28 passed in the original workspace. The 15 staged assets matched the historical
environment. No new boot patch or flash was performed. See
[root staging](ROOT-STAGING.md) and [remaining limits](reproduction/GAPS.md).

## Private recovery verification

The complete project collection contains 216,648 regular files across 248,519
entries. Creation used a second full source-content pass. A full restoration
matched the embedded inventory's file bytes, entry coverage, modes, mtimes and
links. Repartitioning for ordinary private Git preserved the exact compressed
archive SHA-256. The private repository carries its own publication and remote
checkout verification receipts; credentials and firmware are absent here.
