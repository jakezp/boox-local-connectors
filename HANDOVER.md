# BOOX Note Air4C — detailed agent handover

Updated 2026-09-13. This file is the current state; older investigation notes contain superseded recommendations.

## Live continuation — 2026-09-13, Mac unlocked

Mac own-OAuth UI round trip now PASSED. Frozen lifecycle v0.5 app reconnected
through its own saved grant. A real UI drag and Save to library automatically
published `63c738253e9f026a40b1323fb5467f7f42a4f1b67a6de020ec743f1730a79899`.
BOOX committed it under the same native ID with six pens/129 samples. The new
diagonal was visually confirmed in stock Notes. Normal close/save generated
`5c96fe5a208b105a46319e1f34475754178e831ebda69a12a4305fc0a0db2fbd`,
which the Mac followed automatically, preserving page/zoom. Private evidence:
`notes-drive/research/incoming/mac-ui-own-oauth-add/`.

Mac GUI new notebook `38b21fb7a1d34a99936cd84dbe5f29a5` root `cc753485…`
then rename/move `ce663010…` into fixture Folder B committed on BOOX and opened
visibly blank. BOOX caught up to the latest descendant, skipping its root.
Readback includes ancestor folder rows in the repeated NoteModel wrapper; the
Mac reader wrongly assumes one row. Banach is fixing reader/writer handling and
adding synthetic regression coverage. Do not label this correction complete yet.
Drive metadata changed transiently immediately after uploads; immutable checks
held the queue, automatic retries completed, no data was dropped.

Source-only packaging and build portability work started; GitHub not created.
The older locked-Mac statements below are historical checkpoints.

## Latest validated checkpoint — 2026-09-13

Android **57 tests** pass. Installed APK:
`f15a19bb5de6652c14b4f3b08bff23229adb4308af0f34bc06d7012a157f6f4f`,
loaded hook `1b35137b83528a78`. Live evidence now includes pen add/erase,
all seven crash checkpoints, folder create/Unicode rename/nested move/delete/
restore, notebook delete/restore, Android conflict picker resolving both heads,
new Mac-created blank notebook and library/editor Sync control routing.
The editor Sync preserves native save and logs completion before requesting Drive.
The library cloud icon now opens a Drive status panel with Sync now/Settings.

**Original data preservation PASSED:** all 182 original associated files unchanged,
no additions, all fields in the three original NoteModel rows unchanged.
`notes-drive/research/incoming/final-original-preservation.json`.
Private checkpoint: `backups/notes-drive-native-v0.4-20260913/` with
`native-after.tar` SHA `99f4b4576fcb0dd3350f8e88246bfa334d27816c90c37d346dd9f981870616a9`,
connector state, both current APKs and Vector/AMS configuration, all mode600.
Native writers were frozen only during the coherent snapshot and resumed.

New Mac blank native ID `a21e60175a514e84b136b279055a04bb`, revision
`c7f86d2cadf0e8c3d8d49c7c054fe21966ceac4b522cfe3d0b5672a23d1c3608`,
re-export SHA `d147d6405c2cb1aac811c3363d026e1686f8b59f69c90840845078b484cc9826`.
Initial apply safely rolled back because BOOX added `extra/pb/extra`; validator now
accepts only that previously absent known export-info record (doc1/app45326/same
notebook ID/known fields), while retaining strict assets/unknown-field validation.
The same revision was then retried and committed. Its failed evidence is retained.

Read-only doctor now validates exact scopes, fw/model/slot/Magisk/modules/APK
identity. `tools/boox_setup.py` read-only preflight passed against a new checkpoint;
actual same-APK wrapper reinstall also passed, including post identity/scopes and
loaded-hook evidence. No root/flash occurred.
Pinned upstream EDL loader URL was recovered and downloaded byte-for-byte:
see `notes-drive/research/incoming/loader-provenance.json` and reproduction docs.

**Remaining blocker:** Mac still locked, Keychain requires manual Reconnect saved
grant (-25293). Mac v0.5 lifecycle checkpoint has125 local tests; it has not yet
passed interactive Mac own-OAuth round-trip validation. Its earlier isolated CLI
request's base is obsolete after disposable native tests; do not change/reuse that
request. Prepare a fresh request from the current verified head after reconnect.
Banach is preparing shareable synthetic fixture coverage while preserving private
originals. Leibniz owns reproduction docs and setup CLI. No GitHub repo/push or
workspace cleanup has occurred; user requested those after complete validation.
Start with README.md, IMPLEMENTATION-PLAN.md and notes-drive/INCOMING-VALIDATION.md.
Latest fixture heads/status are in notes-drive/research/incoming/latest-checkpoint.json.
Current mandatory Mac tests generate their own synthetic corpus:129 checks, plus
5 explicit optional private-corpus checks. Host doctor/setup56 tests pass.

## Active continuation — full Drive replacement, Mac editing and reproducible repo

The user authorized full automatic Notes replacement, Mac editing through Banach,
then cleanup, GitHub creation and complete reproducibility documentation/installer.
Start with `IMPLEMENTATION-PLAN.md` and `notes-drive/INCOMING-VALIDATION.md`.
This continuation is **still in progress**; do not treat the earlier v0.3 baseline
below as the current implementation.

Android v0.4 now implements same-ID native incoming updates with journaled
recovery, folder/tombstone support, native sync-control routing and explicit
conflict selection. **56 Android checks pass.** Mac-generated add/erase bytes
committed on BOOX via Android's own grant and visibly reopened correctly.
All seven crash checkpoints passed after fixing durable acknowledgement receipts.
Latest corrected acknowledgement revision:
`711521ca14a39130551769293cdd16a57503a71542473739081f357223477742`.
Two folder creates, Unicode rename, nested move and recoverable deletion passed
native SQLite readback. Restoration exposed the native active-only lookup;
current source also uses `loadRemovedNote`, retaining the original recycle-bin row
and capture title. Restoration/conflict UI testing is still in progress.

Mac v0.4.1 has 108 offline tests and a pinned own-OAuth probe CLI checkpoint.
Its live invocation stopped at locked Keychain (-25293), with exact candidate and
request retained in `notes-drive/research/incoming/mac-own-oauth-add/`; nothing was
published by that CLI. The user has been asked to unlock the Mac. Banach is adding
notebook lifecycle controls under `notes-drive/macos/**`. Leibniz prepares only
`docs/reproduction/**` plus a read-only setup doctor. No Mac editor UI/own-OAuth
round-trip has been established in this continuation. Do not substitute fixture
transport success for that evidence.

`tools/install_notes_drive.py` verifies the actual current-PID Vector source
marker, avoiding stale DEX after updates. Current installed/build evidence is in
`notes-drive/research/incoming/`; use `registration.json` and install receipts.
Do not force-stop unsaved native editors. Final original-data preservation and
native sync UI checks remain open. Strict offline preflight passed all three
original notebooks.

Pre-incoming backup restore input is the fully verified
`backups/pre-notes-incoming-v0.4-20260912/notes-and-connector.recovered.tar`
(SHA256 `5c9cfb10a7bf7f9f05bba5ef91b932f5fe3cdc6f343acafeb6db25edb6610b68`).
The original stream contains six toybox notices between tar headers; the
preamble-only diagnostic is incomplete. Both are preserved for evidence. Read the
backup's `README-recovery.md`; use authored `tools/repair_adb_tar.py`, never blind
byte replacement. New backups use relative paths under `tar -C /`.

No GitHub repo has been created/pushed. `gh` is authenticated as `jakezp`. Cleanup
must preserve private backups and exclude secrets, private keys, personal notes,
decompiled proprietary binaries and firmware from Git. Include all authored
source, synthetic tests, pinned tool acquisition and recovery/reproduction steps.

## Current work — Notes sync to Google Drive

**Latest continuation — automatic outbound library sync is working.** Start with
[AUTOMATIC-VALIDATION.md](notes-drive/AUTOMATIC-VALIDATION.md). Android **v0.3**
captures native Notes saves and closed notebooks into a private durable journal,
publishes automatically with its own Google grant, and retries on reconnection.
Mac **v0.2** reconnects silently and polls every 12 seconds while open.
**30 Android + 60 Mac tests pass.** All three original notebooks reached the Mac.
A new disposable notebook progressed from four to five pens through an offline
save, connector restart and automatic upload; the Mac followed the descendant,
retaining its page and 125% zoom. Original three rows and 182 files are unchanged.

Automatic publishing is enabled. `local.boox.notesdrive` is now a Vector module
scoped only to `com.onyx.android.note/0` (Notes45326). OpenAI's scope is unchanged.
The launcher's persistent Unfreeze setting was applied to the connector. Use
Xposed min API82 to preserve private preferences. The test notebook
`GDrive-Sync-Probe-Automatic` remains for testing. Native ONYX sync controls are
not routed yet; automatic incoming application, folders/deletions and broader
native apply safeguards still need work. This is not full bidirectional replacement.
Current build hashes are in `notes-drive/android/registration.json` and Mac
`evidence/preview-recovery-build-validation.json`; final checkpoint/evidence under
`backups/notes-drive-automatic-v0.3-20260912/` and `research/automatic/`.
The final Mac build was restarted and silently reconnected, then rendered the
latest five-pen / 154-sample test revision without the stale preview-error label.
The installed Android APK matches registration; pending uploads are zero.

### Earlier manual interoperability milestone

**Latest continuation — Mac second client is working.** The user explicitly asked
for a subagent to build a macOS reader instead of testing with another BOOX.
Subagent Banach (`01a09765-f638-7393-914e-a8f1188b574b`) completed `notes-drive/macos/`;
the parent reviewed, integrated and live-tested it. Start with
[INTEROPERABILITY-VALIDATION.md](notes-drive/INTEROPERABILITY-VALIDATION.md).

Android **v0.2 is installed** with durable immutable revision publication/retry and
incoming staging. **BOOX Notes Reader macOS v0.1** is built and open at
`notes-drive/macos/build/BOOX Notes Reader.app`. **22 Android + 25 Mac checks pass.**
The Mac is a native coordinate reader with page navigation, zoom, layer metadata,
internal-link navigation and explicit fidelity limits; it is not a full editor.

The Mac completed its own Desktop OAuth grant and reconnected from Keychain.
Both apps independently verified the same Google account and managed folder.
Android queued a fixture while Wi-Fi was off, survived force-stop/reopen, then
published the exact saved revision. The Mac downloaded/rendered it, published
B2 as a descendant, and Android downloaded and durably staged that descendant.
Two revisions/four immutable objects remain in the Drive folder for testing.
No real user notebook was accessed in this continuation. Wi-Fi is restored.

Shared wire format: `notes-drive/PROTOCOL.md`. Public Desktop client registration
is in `notes-drive/android/google-project.json`; private installed-client JSON is
`notes-drive/desktop-oauth.local.json` (mode 600, ignored). Mac state is under
`~/Library/Application Support/BOOX Notes Reader/`; refresh tokens stay in its own
Keychain entry. Preserve Android signing identity; do not extract/reuse tokens.
Latest checkpoint: `backups/notes-drive-mac-interoperability-20260912/`.

**Next is native Notes integration**, not another two-BOOX test: save/export hooks,
persistent app-open/recovery coordination, on-device semantic verifier and
broader notebook content support, then manual sync controls. Background scheduling
and large/resumable transfers are also unfinished. Automatic native application
and Notes library sync remain off. The v0.1 connection-only record below is history.

### Earlier native and v0.1 connection milestones

The user confirmed the OpenAI connector looks good, and asked to explore replacing
ONYX Notes data sync with Google Drive sync to a dedicated directory. They
confirmed we can create the directory and assume participating devices are rooted
or specifically equipped for this sync.

After the user said “go for it” and “please continue,” disposable native round-trip
and existing-notebook update experiments were run. Start with
[notes-drive/APPLY-VALIDATION.md](notes-drive/APPLY-VALIDATION.md), then
[NATIVE-VALIDATION.md](notes-drive/NATIVE-VALIDATION.md) and
[FEASIBILITY.md](notes-drive/FEASIBILITY.md). **40 offline tests and 24 live
fault/guard cases pass**, plus end-to-end verified commit/stale-proof checks.

An existing test notebook received the staged revision, reopened in BOOX and
exported successfully. Its notebook UUID, two page UUIDs and eight existing stroke
UUIDs stayed unchanged; three new strokes and one link were added. All eleven pen
strokes, compared styles, layers and internal-link destinations match the incoming
revision. A separate observer's inbound link retained its target; its entire
native components and NoteModel row were unchanged. Actual inbound-link tap
navigation was not established.

**A real stock import bug was reproduced and repaired:** BOOX remaps the target
page of an internal link but retains the old document ID. Supplying the missing
source/destination entry in `NoteCopyContext.noteDocIdMap` fixes both destination
and label. `notes-drive/probe/` retains the original fixture module;
`notes-drive/apply-probe/` contains the later import-map capture hook and root
apply/recovery helper. Both packages are now uninstalled and their scopes removed.
These are fixture experiments, not a deployed/general native sync adapter.

All five new disposable notebooks were removed through Notes and its Recycle Bin.
The original three NoteModel rows and **182 associated files** match the latest
pre-test snapshot (150 document files, 24 point files, eight database/sidecar files).
Test export/import/root-work files were removed after local evidence archiving.
Wi-Fi is enabled again. The OpenAI adapter/scope, ONYX sync settings and root/framework
configuration are unchanged. Latest private backups:
`backups/pre-notes-apply-20260912/` and `backups/post-notes-apply-20260912/`.
Earlier round-trip checkpoints remain; never restore them wholesale over later edits.

**No automatic Notes sync is enabled and no user notebook content was uploaded.**
The later Drive test uploaded only the bundled disposable fixture, verified its
downloaded bytes, then moved the test upload to Trash.
The desktop Drive connector previously created **BOOX Notes Sync** and verified
it empty; exact folder metadata is in `notes-drive/drive-folder.json`.

Key findings: ONYX uses Couchbase replication, so changing an endpoint is
insufficient. The installed firmware already offers a third-party `.note` export
path. Standard import assigns fresh notebook IDs, and its failure cleanup deletes
the destination. Do not force that importer to overwrite an existing notebook.
Whole-library restore also contains broad deletion operations. Fresh import,
editing, reopen after force-stop, truncated-import cleanup and bounded in-place
apply/recovery now have evidence. **Production automatic application remains
unfinished:** process checks do not atomically prevent app reopening, native
cache changes can pause recovery, the host verifies readback, and equal-length UUID
rewriting supports only the bounded fixture. Resources, rich text, recordings,
tags, locks, page deletion/reorder and other devices/firmware remain separate cases.

The independent Android preview **v0.1 is built and installed** in `notes-drive/android/`.
Ten Android tests and release/signature checks pass; the installed APK matches the
build. **Real Google authorization, folder creation and a Drive round trip pass.**
It uses Google's AuthorizationClient with only `drive.file`. The 18,171-byte
disposable `.note` was uploaded, its Drive size/MD5 checked, downloaded with an exact
SHA-256 match, and moved to Trash. Force-stop/reopen followed by manual reconnect
restored the same account/folder without another consent prompt.
A second transfer after restart passed too; both disposable uploads are in Trash.
See `notes-drive/research/apply/drive-live-validation.json` and
`notes-drive/android/README.md`. No connector credentials were reused or extracted.
Earlier status code 8 is historical; it no longer blocks authorization.

**Latest continuation:** the user replied “signed in.” A dedicated **BOOX Notes
Drive** project was created: ID `GOOGLE_PROJECT_ID`, number `GOOGLE_PROJECT_NUMBER`.
The Drive API is confirmed enabled. Reuse this project; details are in
`notes-drive/android/google-project.json`. The first project creation attempt
did not complete; retrying with the same ID completed successfully.

Google Auth branding, the Android OAuth client, one test user and the sole
`drive.file` scope are configured. The user completed the prepared Google policy
agreement directly in the browser while the approval question was pending;
no agreement approval is outstanding. The audience remains **External / Testing**.
The client is bound to package `local.boox.notesdrive` and the SHA-1 in
`registration.json`; its exact client ID is in `google-project.json`.
In-app browser 1, tab 1 is retained on this project's Data Access page.
The tablet is left in BOOX Notes Drive. BOOX auto-froze the new package initially;
it was re-enabled after launcher processing. Preserve its signing identity.
Checkpoints: `backups/notes-drive-preview-v0.1-20260912/` before authorization and
`backups/notes-drive-connected-v0.1-20260912/` after live validation.

The Android app created and selected **BOOX Notes Sync** with exact ID
`DRIVE_FOLDER_ID`, carrying its app marker. See
`notes-drive/android/drive-folder.json`. The earlier desktop-created folder is
separate; do not delete either folder by name. Cross-device access under the same
project/package/signing identity remains to be verified. No second device was
available, and token expiry/revocation/background renewal were not live-tested.

Persisted immutable revision uploads/downloads and manual retries are now implemented
and validated with the Mac as described above. Close the native app-open/recovery/
verifier gaps with disposable fixtures before routing Notes sync controls to Drive.
A successful transport test is not a completed automatic Notes sync replacement.
OpenAI v0.9 remains installed and working as recorded below.

## Completed — v0.9 model dropdown and live ChatGPT validation

The user completed sign-in, tested and activated ChatGPT mode, then requested a
dropdown of available models for cheaper/basic lookups. **v0.9/code9 is installed**
and matches the local build. SHA256:
`aab74d3be5bd44ece6674bd0645304cfa9bf09da85b8ab4ed99b199bfce25331`.

Setup now uses a real dropdown for the ChatGPT model. It fetches the account's
catalog automatically on opening, retains the saved selection, and offers a
manual refresh. Refreshing no longer saves the first model automatically.
An unavailable saved model leaves an explicit choice rather than selecting the
first catalog entry. The API model field and saved API configuration are unchanged.

The live account returned `gpt-6-astra`, `gpt-5.6-sol`, `gpt-5.6-terra`,
`gpt-5.6-luna`, and `gpt-5.5`. **GPT-5.6 Luna is tested and active**, replacing
the user's previous Astra selection. Setup marks Luna as a quick-lookup option.
The UI describes subscription usage allowance rather than inventing API prices.

Verified on the tablet: real Luna setup response, retained login and selected
model after force-stop/reopen, refreshed dropdown, full-screen native reply, and
NeoReader embedded passage extraction plus a follow-up using context. Both native
surfaces show `ChatGPT · gpt-5.6-luna`. All **48 checks** pass (18 existing +
30 OAuth). The first back-to-back OAuth instrumentation launch reported a process
crash; running it separately completed all 30 cases with no failures.

The tablet is left in Setup, signed in with Luna selected. API key/model
preferences remain byte-identical to the pre-change backup. Two identified
synthetic topics/six records and the temporary test file/app were removed.
The user's new `say hello` exchange was preserved; do not restore the older
pre-picker history wholesale. No root/framework/scope/OneNote changes.

Evidence: `openai-adapter/tests/model-picker-v0.9.json`,
`openai-adapter/tests/neoreader-luna-v0.9.png`, and
[OAuth validation](openai-adapter/OAUTH-VALIDATION.md).
Backups: `backups/pre-model-picker-20260912/` and
`backups/post-model-picker-20260912/`.

Further subscription-specific live checks, if requested: floating UI, Regenerate,
Stop/recovery and switching back to the saved API configuration. These passed
previously with API transport and/or isolated OAuth tests, but were not all
repeated live with subscription transport. Do not sign the user out just to
repeat destructive auth tests.

## Historical v0.8 — fixes returning from the sign-in browser

The user reported that Setup showed only API authentication when returning from
OpenAI's code-entry page. This was an activity-lifecycle bug: the configuration
dropdown reset to the active API mode and activity destruction canceled login.

**v0.8/code8 is installed**, matching local source/build. SHA256:
`1eecf6d48ede641a2579a8eb35de851bbc72c9acf0b47d87e1d3dea809696ad5`.
The previously reviewed account/session guards are included; no source/build
installation mismatch remains.

Login now belongs to an application-level `LoginCoordinator`; its unexpired
pending code is encrypted in `OAuthVault` for recovery after process death.
Setup remembers its configuration tab separately from active routing, uses
`singleTask`, and has a **Copy code** button. Token refresh preserves pending
login; cancel/replacement/expiry/completion clears it.

All **48 checks** pass (18 existing + 30 OAuth). On the real tablet, browser
round-trip and force-stop/reopen both preserved the same code and ChatGPT tab.
Only boolean evidence was saved in `openai-adapter/tests/oauth-return-v0.8.json`.
API credentials/model and original history remain hash-identical to the pre-fix
backup. The temporary test APK has been removed.

The tablet is left showing a fresh code. The user should copy it, open OpenAI's
sign-in page, paste it, then return and test the selected connection. **No real
subscription-backed reply is verified yet.** API remains active.
See [openai-adapter/OAUTH-VALIDATION.md](openai-adapter/OAUTH-VALIDATION.md).

Backups: `backups/pre-oauth-return-fix-20260912/` and
`backups/post-oauth-return-fix-20260912/`.

## Historical OAuth v0.7 work — superseded by v0.8 above

The user approved implementing the OAuth plan (“Sweet, let's do it!”).
The connection mode, device-code login, encrypted OAuth vault, refresh, model
catalog, Codex SSE transport, native routing/labels, and tests are implemented.
See [openai-adapter/OAUTH-VALIDATION.md](openai-adapter/OAUTH-VALIDATION.md) for exact
state and continuation instructions.

**Do not mistake implementation for completed live validation.** The tablet
successfully obtained a real OpenAI sign-in code. The user was asked to complete
sign-in privately and report the setup connection-test result. No real
subscription reply is verified yet; API remains active.

Installed sign-in candidate: **0.7/code7**,
`9adab0a4f75dadbd6579dca94aecae1789728b91c55310dcb0923a4f69160e2d`.
Reviewed local build/source: **0.7/code7**,
`71fa064a6479f3192649f9dd594e36c965b1e782883950d572907d78c558c9ff`.
The reviewed build adds account/session race guards and passes all **43 tests**
(18 existing + 25 OAuth). It has not been installed over the running login flow.
Install it after that flow completes or ends, preserve credentials, then run the
native live checks. The test runner now targets its own isolated test app and
does not interrupt the production setup app.

Backups: `backups/pre-oauth-20260912/` and
`backups/oauth-progress-20260912/`. API preferences and original history remain
hash-identical to the pre-OAuth snapshot. The temporary test app is still installed.
No subagents were spawned, no user-owned task was created, and no commit was made.

## Latest update — adapter 0.6: input footer spacing restored

The user found the input field too close to the screen edge after the disclaimer
removal. Version **0.6/code6** is now installed. The input disclaimer resource
`assistant_declaration_tips` uses `INVISIBLE`, keeping its measured height without
drawing text. Reply disclaimers still use `GONE`.

APK SHA256:
`66317251ab977e065b61e29f25f6f526e5de9f1034035080ae5e18e7d29fb83c`.
Build/signature verification passed. Native full-screen UI inspection confirmed
the input field moved up 74 physical pixels, from bottom y2478 to y2404 on the
2480-pixel screen. Screenshot:
`openai-adapter/tests/input-spacing-v0.6.png`.
The shared hook applies to both scoped apps; the user is manually testing the
other surfaces. The 18 regression checks were last run on v0.5; this layout-only
change was checked through build and live UI inspection.

Before/after checkpoints:
`backups/pre-input-spacing-20260912/` and
`backups/post-input-spacing-20260912/`.
No credential/history migration or cleanup was performed in this pass.

The user next wants ChatGPT subscription OAuth support, like OpenClaw, and asked
for planning while they manually test. Research and implementation sequence are
in [openai-adapter/OAUTH-PLAN.md](openai-adapter/OAUTH-PLAN.md). OAuth code is not
implemented; no account login or credential import was performed. Start with an
isolated native device-code login and one subscription-backed reply, then add
connection selection while preserving the API-key mode. Do not interfere with
the user's ongoing tablet test merely to repeat the completed layout check.

## Current update — adapter 0.5: disclaimers removed and NeoReader connected

The user's latest request was to remove the repeated BOOX disclaimer footers,
then connect NeoReader's embedded AI panel. Both are complete and live-tested.
See [openai-adapter/NEOREADER-VALIDATION.md](openai-adapter/NEOREADER-VALIDATION.md).

Installed: **0.5/code5**, SHA256
`93b9ace0a21cb0900e784a02370791468bf803b112c5e8de292404c01b0b9606`.
The installed APK matches the local build; APK v2/v3 signatures pass.
Vector scope is now exactly `com.onyx.aiassistant/0` and `com.onyx.kreader/0`.
The original system APKs, root/framework modules, and OneNote settings were not
modified in this pass.

The two static BOOX disclaimer views are hidden in full-screen, floating, and
embedded reading conversations. Answer content is not text-filtered.
NeoReader now passes selected text, nearby text, and local reading instructions
to the existing OpenAI provider. Opening a reading panel needs no cloud preflight
or document upload. Live tests covered factual extraction, multi-turn context,
Deep Analysis, Regenerate, Stop and recovery, per-document history, reopen,
floating chat, and expansion into full-screen chat without duplicate messages.
All 18 deterministic regression checks pass.

The encrypted key/model settings remain byte-for-byte unchanged;
`gpt-4.1-nano` is still selected. Original three topics, two records, and selection
were restored after guarded synthetic-test cleanup. The test APK and both test
documents were removed from the tablet.

Before changes: `backups/pre-neoreader-20260912-182353/` (adapter/assistant data,
source, original NeoReader APK/data, old scope). Final checkpoint:
`backups/post-neoreader-20260912/`. Intermediate footer-only build:
`patch/boox-openai-footer-only-v0.4.apk`.

Still outside scope: null-record `readingInfo` calls, whole-book analysis,
attachments/images, and streaming. Only one API reply runs at a time across the
two apps. This update supersedes the embedded-panel limitations in the older
v0.3 and v0.2 records below.

## Historical validation update — adapter 0.3

The user subsequently asked to continue validation and fix problems. That pass is
complete for core text workflows. See [openai-adapter/VALIDATION.md](openai-adapter/VALIDATION.md)
for the detailed test matrix, repairs, backups, and remaining boundaries.

Installed: **0.3/code3**, SHA256
`60c8ccf49a8fce074435e8876a3a3ab82bf8f3b956c55bfd7c42153cea4569c8`.
The installed APK matches the local build. The existing encrypted key/model
preferences are unchanged; `gpt-4.1-nano` remains selected.

Verified now: native new-topic activation and automatic title, multi-turn context,
regeneration without duplicate questions, rename, record/topic deletion,
persistence, live Stop with UI recovery and a successful next request, floating
chat, NeoReader selected-text handoff to the floating assistant, and actionable
error rendering. Fifteen deterministic regression tests and a separate native
error-UI test pass. A real reply was verified **after the final production edits**.

**NeoReader's embedded AI panel remains outside the adapter.** It runs in
`com.onyx.kreader`; only `com.onyx.aiassistant/0` is scoped. Its floating-assistant
button is the verified OpenAI route. Direct null-record readingInfo, streaming,
attachments, and full-document support remain incomplete.

Before validation: `backups/pre-validation-20260912-175358/`.
Validated APK/checkpoint: `backups/post-validation-20260912/`.
Test conversations were cleaned up after checking all original records unchanged;
the original three topics, two records, and selection were restored. The temporary
test app and tablet text fixture were removed. Source fixtures/results remain in
`openai-adapter/tests/`. Root/framework/OneNote settings were not changed.

The sections below retain the original implementation/recovery detail. Where they
describe v0.2 validation limitations, this update and VALIDATION.md supersede them.

## Current outcome and latest request

The BOOX is rooted. OneNote handwriting feels excellent according to the user. **The native BOOX AI Assistant now calls the user's OpenAI API account through a custom removable adapter.** A real API reply appeared in the original BOOX conversation UI. History survives force-stop/reopen; the final installed build displays the question before the answer correctly.

The latest request to remove disclaimer footers and connect NeoReader's embedded
AI panel is complete; see the v0.5 update above. Core text validation from v0.3
is also retained below as historical evidence.

Workspace: `/PATH/TO/USER/Documents/New project`

Project: `/PATH/TO/boox-local-connectors`

Host: macOS, zsh. Timezone: Africa/Johannesburg.

## User intent, decisions and authorization

- Original goals: root a previously rooted BOOX, extract/modify system apps, improve third-party stylus performance.
- Rooting and a firmware-specific Magisk manager compatibility fix are completed.
- The user installed/signed into OneNote and reported: “Onenote feels amazing! Super immediate.” No additional OneNote patch was needed.
- AI preference: preserve the native BOOX UI, floating/reading integration and entry points. They prefer this over handing prompts to the separate ChatGPT app.
- They asked about using a ChatGPT subscription. We explained that signing into ChatGPT Android does not supply a supported general OpenAI API credential. They accepted the API route.
- They explicitly asked for backups before changes.
- USB debugging is enabled and this computer authorized persistently. User granted root.
- User entered the API key privately in our setup app and reported its API test succeeded. **Never ask them to paste the key in chat or read it out with tools.**
- Vector/Zygisk installation was initially rejected by automatic approval review as a persistent framework with system-wide hooking capabilities and boot/security risks.
- After the exact action and risk were explained, the user explicitly replied **“Yes you may”** to installing Vector and enabling Zygisk. Both actions are now done. Do not ask again.
- Our adapter's Vector scope is `com.onyx.aiassistant` and `com.onyx.kreader`
  for Android user 0. The user explicitly requested the NeoReader expansion.

No subagents were used in either completed session. No commits, pushes, PRs, external messages or publication were performed.

## Device/access

| Item | Value |
| --- | --- |
| ADB serial | `DEVICE_SERIAL` |
| Product/model/device | `NoteAir4C` |
| Firmware | `2026-04-28_17-50_4.2-rel_04282_555977efe` |
| Android / kernel | Android 13 / 4.19 series |
| Security patch property | `2026-04-01` |
| Active boot slot | `_b` |
| Bootloader | Already unlocked; orange state |
| Current Magisk | 30.7, discovered before AI backup |
| Root executable | `/debug_ramdisk/su` |
| Magisk executable | `/debug_ramdisk/magisk` |
| Vector | v2.2, version code 3080, framework API 102 |
| Zygisk | Enabled; reboot completed successfully |

Commands assume workspace root:

```sh
boox-work/tools/platform-tools/adb devices -l
boox-work/tools/platform-tools/adb -s DEVICE_SERIAL shell /debug_ramdisk/su -c id
```

Plain `su` is not reliably on PATH. Root returned `uid=0(root)` and `u:r:magisk:s0`. Use serial rather than stale USB transport IDs. USB reconnected during a backup and reboot.

Use the current session's tool approval requirements. This session has unrestricted
filesystem/network access and approval policy `never`; do not supply
`sandbox_permissions`. Earlier sessions used escalation for ADB.

BOOX can freeze newly installed applications. Initial setup install succeeded but launch returned “Activity class does not exist”; package dump showed `enabled=3`, `lastDisabledCaller: com.onyx`. `pm enable local.boox.openai` fixed it. Later updates were enabled normally. BOOX launcher Unfreeze may be needed for persistent freeze settings.

## Backups

These are **scoped component backups, not a full tablet image**. They contain private app data; keep local.

### Before root

Directory: `backups/DEVICE_SERIAL-4.2-04282/`

Contains `boot_a.img`, `boot_b.img`, `recovery_b.img`, `vbmeta_b.img`, `devinfo.img`, original `services.jar`, `magisk-30.2.apk`, `ai-assistant-release.apk`, `SHA256.json`, and `onyx-mmkv-before.tar`.

**GPT caveat:** EDL reported saving GPT files, but that tool version's source had file writes commented out. No GPT backup was actually produced. No GPT/partition-table writes were performed. Do not infer a backup from the log.

Original boot B SHA256: `765d41d770c9fcccb812ebef9ce1eb56f24e799a2be97304c960b5b73f7bf0de`.

### Before OpenAI/Vector

Directory: `backups/pre-openai-20260912/`. Initially restricted to directory mode700/files600; a setup APK was added later.

| File | Contents and SHA256 |
| --- | --- |
| `app-data-and-magisk.tar` | Assistant/ksync app data, `/data/adb`, `/onyxconfig/mmkv`; `d7eb3e812bf1ce564ab7481de1f5ee0f1a96ebb24f85ad278c269c657e1d0faf` |
| `ai-assistant-release.apk` | Original system assistant; `982bac779548011cdac200f6dfb6d489f74f3c90be5fe6d9303a961e09754b9e` |
| `ksync-release.apk` | Original supporting service; `331531cd24aa380e175965ee65239a5d7e746688850fbd3f7be17cc064139931` |
| `boot_b-rooted.img` | Current Magisk30.7 rooted B, 100663296 bytes; `33475f2697db3e277300b9ec1438c9af7cb4ab457b7433e87170c5d87cca6d60` |
| `openai-setup-v0.1.apk` | Working setup APK before hook update; `815baa42244842be45332b1c38381102e68ad67701ca2b26c5301b293a5e57fe` |

`SHA256.json` covers the first four entries; the later setup APK hash is recorded here. Assistant and ksync were force-stopped before data archiving. Archive/hash checks completed; rooted boot backup matched the live partition hash. An initial boot transfer stalled near51MB; the incomplete copy was replaced with a complete staged ADB pull and verified.

The pre-AI archive predates the setup-app API key and does not back up its Keystore key. Do not overwrite all `/data/adb` from that archive casually while Android is running. Restore only the appropriate component, preserving ownership/SELinux contexts.

## Rooting already completed

Original state: unlocked bootloader, active B, disabled Magisk30.2 manager installed, no working su.

1. Backed up both boot slots and relevant partitions using Qualcomm EDL.
2. Patched exact original boot B with Magisk30.2 assets extracted from installed APK; ran its `boot_patch.sh` on-device.
3. Flags: `KEEPVERITY=true`, `KEEPFORCEENCRYPT=true`, `PATCHVBMETAFLAG=false`, `RECOVERYMODE=false`.
4. Verified original kernel/DTB unchanged; size remained100663296 bytes.
5. Flashed **only boot_b, UFS LUN4**, then read back and compared every byte.
6. Root persisted after reboot. Slot A and other partitions were not flashed.

Patched30.2 SHA256: `67563496cd465eb44c12e8f5dd3997d94717dcfd89da92be579cace8ef50cd3e`.

Artifacts: `patch/boot_b-magisk-30.2.img`, `patch/boot_b-readback.img`, `patch/flash-boot-b.log`, `patch/readback.log`.

Magisk was subsequently found to be30.7; the agent did not deliberately perform that update. Fresh pre-AI rooted boot is the30.7 backup. Do not assume old patched30.2 image is current.

### Magisk manager / BOOX AMS fix

BOOX `ActivityManagerService.addPackageDependency` dereferenced null `ProcessRecord` when Magisk `RootServerMain` started.

- Builder: `tools/build_ams_fix.py`.
- Module ZIP: `patch/boox-ams-fix-noteair4c-4.2.zip`.
- Module ID: `boox_ams_fix_na4c_42`.
- Original services.jar SHA256: `fd45574a43099f3e1abc0bca6a88db3d018d6f183c41d7e0a46f16801ae5b2e6`.
- Patched SHA256: `e2bad92800231d1ad86179202f42dd5f89d830114a7bc6273d25ce9c2c923c14`.
- Single DEX instruction change at `0x2b2ff0`: branch displacement `0x33`→`0x3f`, returning safely after monitor release for null process record.
- Recomputed DEX SHA1/Adler32; other instructions/JAR entries checked unchanged.
- Install guard checks model and original services.jar hash.
- Restored missing `/data/adb/magisk` support assets from original APK.
- Verified live patched framework hash, normal Magisk UI, persistent root. Used BOOX launcher Unfreeze for manager.

**Before BOOX OTA: disable/remove this firmware-specific module. Do not apply this JAR to another firmware without re-analysis.**

### EDL tooling/recovery context

- `tools/edl/`: bkerler checkout.
- `tools/edl-venv/`: Python3.12 environment, pyusb/libusb-package, androguard4.1.4.
- `tools/run_edl.py`: initializes bundled libusb backend then runs EDL entry point.
- `tools/noteair4c-loader.bin`: known-working loader used on this exact device, from bkerler Loaders Lenovo/Motorola collection.

Inspect saved flash/readback logs for exact working arguments before recovery. Do not invent offsets, switch slots, flash another model's image, or write GPT. Recovery was not re-tested during AI work because the tablet boots normally.

## OneNote and other app performance

Installed `com.microsoft.office.onenote`, inspected version `16.0.20326.20108`, versionCode1807294107. BOOX's native profile was already enabled:

```text
noteConfig.enable=true
noteConfig.supportNoteConfig=true
drawViewKey=com.microsoft.office.airspace.AirspaceInkLayer
globalStrokeStyle.enable=true
repaintLatency=500
```

`500` is a configuration value, not measured pen latency. We did not arbitrarily reduce it. The user physically tested and confirmed immediate handwriting.

Artifacts: `patch/onenote-default-profile.json`, `patch/onyx-mmkv-onenote-installed.tar`, original MMKV backup above. User asked about other apps; no next app was selected. RapidDraw was mentioned as experimental, not installed/tested. Preserve the successful OneNote setup.

## Native OpenAI integration: installed state

- Official framework release: `JingMatrix/Vector` v2.2.
- Installer `tools/vector.zip`, SHA256 `9ee8323575d615f7b3f1076ff60b2a63a49390ef11881b52632311a37f6f79cc`.
- Module directory `/data/adb/modules/zygisk_vector/`; CLI is its `cli` executable.
- Zygisk set through Magisk SQLite and verified `key=zygisk|value=1`; reboot verified `sys.boot_completed=1`.
- Custom APK package `local.boox.openai`, label `BOOX OpenAI Setup`, version0.8/code8.
- Vector enabled module `local.boox.openai`; scope `com.onyx.aiassistant/0` and `com.onyx.kreader/0`.
- No ksync/system_server injection. Original assistant and ksync APKs were not replaced/patched for this integration.

### Source/build inventory

Project: `openai-adapter/`; Java package directory `src/local/boox/openai/`.

| File | Purpose |
| --- | --- |
| `SetupActivity.java` | Private key/model entry; save/remove; direct API test |
| `KeyVault.java` | AES-GCM encrypted private prefs backed by Android Keystore |
| `ResponsesClient.java` | HTTPS Responses API, bounded parsing, errors, cancellation |
| `AssistantProvider.java` | Authorized IPC, history/config CRUD, API request assembly |
| `NativeHook.java` | BOOX client proxy and native callback adaptation |
| `DisclaimerViews.java` | Hides the two static BOOX disclaimer resources in both apps |
| `ReaderConversations.java` | Reads all bounded topic pages for NeoReader document lookup |
| `AndroidManifest.xml` | Provider, Xposed metadata, Internet permission; backup disabled |
| `assets/xposed_init` | `local.boox.openai.NativeHook` |
| `stubs/` | Compile-only legacy Xposed headers; excluded from APK dex |
| `build.py` | Local SDK build and signing |
| `local-signing.p12` | Preserve for compatible updates; local build password in build.py |
| `build/boox-openai-setup.apk` | Final installed APK |

Current installed APK SHA256: `1eecf6d48ede641a2579a8eb35de851bbc72c9acf0b47d87e1d3dea809696ad5`.
The pre-validation v0.2 APK hash was `49faa495b58979026a0f02ca08d3b81a2854c5b97eaa3d268e87ec10a83be785`.

```sh
python3 boox-work/openai-adapter/build.py
boox-work/tools/platform-tools/adb -s DEVICE_SERIAL install --no-incremental -r boox-work/openai-adapter/build/boox-openai-setup.apk
```

Build uses Java17 `/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home`, build tools35 at `tools/android-sdk/android-15/`, platform35 at `tools/android-sdk/android-35/android.jar`. Tools: javac,d8,aapt,zipalign,apksigner; no Gradle/remote dependencies. APK v2/v3 signatures verified. `android-15` is the extracted build-tools directory name, not the platform directory.

### How it works

1. Vector loads `NativeHook` in BOOX Assistant and NeoReader.
2. Hook replaces `AIAssistantRemoteServiceConnection.asInterface(IBinder)` result with a Java proxy implementing `IAIAssistantService`.
3. Original binder retained for `asBinder()` connection health only. Handled AI/config/history calls do not fall through to BOOX cloud. Unknown callback-bearing operations fail/return unsupported.
4. Proxy calls `content://local.boox.openai.bridge` in setup app; provider checks caller UID matches installed BOOX Assistant, NeoReader, or itself.
5. Provider loads key internally and calls `https://api.openai.com/v1/responses`, normal TLS verification, bearer auth, redirects disabled, `store:false`.
6. Proxy creates native `AIAssistantOutputArgs.success`, sets question/reply IDs and calls callback `read`.
7. New history lives in setup-app private `openai_history` preferences, separate from original BOOX history.

Code default model is `gpt-4.1`; **user saved `gpt-4.1-nano`**, shown in the successful native test. Preserve their chosen model unless asked otherwise.

Key stays in setup app and is not returned to native assistant. Vault alias `boox-openai-api-key-v1`; encrypted prefs `private_config`. Android backup disabled, input masked/not saved as view state, setup FLAG_SECURE. Root-capable software can still access process secrets; don't claim protection from other root modules.

Only Internet permission is requested. `forceQueryable=true` lets native package discover provider. Authorization is UID-based, not a signature permission; future shared-UID changes matter. Shell access was tested and rejected. Do not expose a general unauthenticated local API.

Native subtitle says `OpenAI API · billed separately`. A synthetic local permission/quota DTO enables the native UI for the user's separate API account; OpenAI still enforces its billing/quota. Original BOOX backend is not called to evade its quota.

### Original v0.2 features, limitations and test boundaries

Historical checkpoint: the validation update above and `openai-adapter/VALIDATION.md`
describe what was subsequently fixed and tested in v0.3.

Verified:

- Full-screen native assistant initializes with model/config/local conversation.
- Real request using saved key displays real API reply in native UI.
- Force-stop/reopen restores history; final build orders question then answer correctly.
- Unauthorized provider call from shell rejects with SecurityException.
- Setup parser fixtures ran successfully on tablet before hook work.

Implemented but not fully tested:

- Conversation create/update/delete/list, record deletion, pagination, selection persistence.
- Selected text from record `highlightText`/`highlightAroundContext` appended as source material; record systemPrompt passed as instructions.
- Last20 local records sent as context; current prompt limit60000 chars. No complete token-budget algorithm.
- Stop increments generation, asks provider to disconnect active request, suppresses canceled callback. **Final cancellation changes have not been tested with a live in-flight request.** Disconnect cannot guarantee no charge for already processed input.

Not implemented/verified:

- Streaming: reply appears when complete, not token-by-token. Max output2048 tokens; test uses128.
- Attachments/images/full-document analysis, meeting minutes/bookparse. Nonempty fileUrl rejects; unsupported native actions fail.
- Direct readingInfo chat where recordBean is null returns explanatory unsupported error. Do not claim full reading workflow works.
- Floating panel/NeoReader text-selection entry points have not been exercised end-to-end, although they share the hooked client SDK.
- Multi-turn quality/context, regenerate semantics, many-conversation paging/deletion and every native settings control remain to test. Regenerate may append rather than replace a pair.
- Some one-way methods without callbacks are no-ops, not implemented features.
- Data-retention value supplied to native config does not implement automatic cleanup.
- Original BOOX history is preserved but not imported into adapter history.
- No ChatGPT-subscription backend. Current installation state of official ChatGPT app was not rechecked.

### Verification/development evidence

- User confirmed setup API test succeeded before framework installation.
- Framework installed, boot completed, Vector status API102; scope explicitly verified one app.
- Initial config failure: BOOX passes a plain scene string to loadCommonConfig, not JSON. Provider now normalizes plain values. forceQueryable added during diagnosis.
- Log near device time05:38:05: `BooxOpenAIBridge: Native API response completed`.
- Prompt: `Reply with exactly: Native OpenAI connection working.`
- Native `tv_reply`: `Native OpenAI connection working.`; model label `OpenAI · gpt-4.1-nano`.
- Reopen initially showed reversed records. Provider fixed to return newest-first because native `sortLocalData` reverses. Final UI inspection shows question at y375, answer y583.
- **Last live API completion preceded final Stop/history/UI-label edits.** Final APK built/signed/installed and history display verified. No extra paid request after final edits.

Narrow logs contain operation names/error classes and sanitized errors, not prompts/keys. Avoid broad logcat or setup UI dumps with typed secrets.

## Commands for next agent

Read-only diagnostics:

```sh
boox-work/tools/platform-tools/adb -s DEVICE_SERIAL shell '/debug_ramdisk/su -c "/data/adb/modules/zygisk_vector/cli status"'
boox-work/tools/platform-tools/adb -s DEVICE_SERIAL shell '/debug_ramdisk/su -c "/data/adb/modules/zygisk_vector/cli modules ls"'
boox-work/tools/platform-tools/adb -s DEVICE_SERIAL shell '/debug_ramdisk/su -c "/data/adb/modules/zygisk_vector/cli scope ls local.boox.openai"'
boox-work/tools/platform-tools/adb -s DEVICE_SERIAL logcat -d -s BooxOpenAIHook:I BooxOpenAIBridge:I AndroidRuntime:E '*:S'
boox-work/tools/platform-tools/adb -s DEVICE_SERIAL shell dumpsys package local.boox.openai
```

Restart after update:

```sh
boox-work/tools/platform-tools/adb -s DEVICE_SERIAL shell 'am force-stop com.onyx.aiassistant; am force-stop com.onyx.kreader; am start -n com.onyx.aiassistant/.ui.MainActivity'
```

Exact already-applied scope/enable commands:

```sh
boox-work/tools/platform-tools/adb -s DEVICE_SERIAL shell '/debug_ramdisk/su -c "/data/adb/modules/zygisk_vector/cli scope set local.boox.openai com.onyx.aiassistant/0 com.onyx.kreader/0"'
boox-work/tools/platform-tools/adb -s DEVICE_SERIAL shell '/debug_ramdisk/su -c "/data/adb/modules/zygisk_vector/cli modules enable local.boox.openai"'
```

Do not invoke mutating CLI subcommands without targets to discover usage. An attempted bare modules-enable/scope-set usage probe was rejected by review as potentially broad mutation. Explicit `--help` safely showed syntax, and targeted configuration was approved. **There is no unresolved approval blocker now.**

## Rollback/recovery (instructions, not performed)

First-level rollback: disable only our adapter and restart native assistant. Original service path should resume; saved key/history remain:

```sh
boox-work/tools/platform-tools/adb -s DEVICE_SERIAL shell '/debug_ramdisk/su -c "/data/adb/modules/zygisk_vector/cli modules disable local.boox.openai"'
boox-work/tools/platform-tools/adb -s DEVICE_SERIAL shell 'am force-stop com.onyx.aiassistant; am start -n com.onyx.aiassistant/.ui.MainActivity'
```

This follows architecture but disable/re-enable rollback was not exercised after successful request. Verify when used.

If Vector causes boot trouble and root ADB is available:

```sh
boox-work/tools/platform-tools/adb -s DEVICE_SERIAL shell '/debug_ramdisk/su -c "touch /data/adb/modules/zygisk_vector/disable"'
boox-work/tools/platform-tools/adb -s DEVICE_SERIAL reboot
```

Do not casually disable separate AMS fix; Magisk manager needed it. Its own disable path is `/data/adb/modules/boox_ams_fix_na4c_42/disable` if specifically implicated.

If no Android/root ADB, inspect verified EDL flash/readback logs and restore appropriate saved boot B with known loader, **UFS LUN4 boot_b only**. Diagnose actual state first; no flashing is needed now.

Normal uninstall of setup app removes its key/history. Preserve local signing key and update with `install -r`; do not uninstall/reinstall routinely. Backed-up v0.1 APK is a reference/fallback; Android downgrade handling was not tested.

## Decompiled sources and useful findings

Assistant: `assistant-decompiled/sources/`. Ksync: `ksync-decompiled/sources/`.
NeoReader: `kreader-decompiled/sources/` and `resources/`; log
`patch/jadx-kreader.log`. Decompiled output contains errors in unrelated methods;
the panel/client/action paths used for v0.5 were inspected and verified at runtime.
The two disclaimer resources are `conversation_reply_tips` and
`assistant_declaration_tips`. NeoReader's `BaseDictTranslatorPopup` uses the same
`ConversationViewModel` client; `EnsureNewDocAiConversationAction` finds topics by
document ID and caches the local reading instructions.

JADX1.5.6 at `tools/jadx/bin/jadx`. Full assistant decompile reported154 errors in other/obfuscated methods; this is not perfectly recovered original source. Logs `patch/jadx.log`, `patch/jadx-ksync.log`. JADX may rename getters in its display; use actual runtime names from metadata or bytecode where ambiguous.

Key paths under `com/onyx/android/sdk/aiassistant/client/`:

- `service/AIAssistantRemoteServiceConnection.java`, `IAIAssistantService.java`, `AIAssistantInputArgs.java`, `AIAssistantOutputArgs.java`, `AssistantIntentArgs.java`.
- `bean/ConversationRecordBean.java`, `ConversationInfoBean.java`, `CommonConfigBean.java`, `AssistantQueryArgs.java`.
- `ui/viewmodel/ConversationViewModel.java`, `ui/adapter/ConversationAdapter.java`.
- Actions including `LoadCommonConfigAction.java`, `LoadRecordListAction.java`.

Important details:

- Native `onAIReplying(content)` replaces text: future streaming callbacks must carry cumulative content, not isolated deltas.
- Output events: partial1, success2, error3, permission4, config5, history6, fileURL7. Callback method `read`.
- AssistantIntentArgs defaults local dataSource2/success0; fail sets-1. Payload formats vary by action.
- Permission UI gate uses `getAvailableDayTimes()`; synthetic capability is not an OpenAI balance.
- AssistantReadingInfo can carry question/currentPageData/book metadata/filePath/imgUrl etc.; actual fields depend on caller. Don't log private book contents unnecessarily.
- Native client binds `com.onyx.android.ksync/com.onyx.android.ksync.service.KAssistantService`. Connection establishment remains, but our AI proxy intercepts before BOOX action execution.
- Lower provider interception was considered and avoided because BOOX actions can upload files/check accounts before provider invocation. Current earlier client boundary prevents that for handled calls.

Sources consulted:

- https://github.com/JingMatrix/Vector/releases/tag/v2.2
- https://github.com/bkerler/edl
- https://github.com/jdkruzr/BooxPalma2RootGuide
- https://github.com/dynamicfire/boox-ams-fix
- https://topjohnwu.github.io/Magisk/install.html
- https://developers.openai.com/api/docs/models/gpt-4.1
- https://developers.openai.com/api/reference/python/resources/responses/methods/create
- https://learn.chatgpt.com/docs/auth

Skills used: OpenAI docs for API/product guidance; simplify for code review. Framework approval came from automatic approval review, not a skill requirement.

## Next work if the user asks

The current phase is Notes-to-Drive native integration after the Mac interoperability
milestone at the top of this file. Subscription OAuth is complete. The AI feature
ideas below are optional subsequent work.

1. Preserve working APK/source and credentials before further edits.
2. Let the user try the connected embedded panel with their usual reading workflow;
   select text and tap its AI icon. Investigate any format-specific context issues.
3. Implement missing readingInfo paths only with a clear text/document data scope.
4. Add cumulative streaming, throttled appropriately for e-ink, if desired.
5. Improve context/token limits and history management; explicitly describe unsupported native controls.
6. Add image/document support if requested. Core text workflows now have a reusable
   regression suite and live validation evidence.

The main result is already working: OpenAI replies in native BOOX Assistant. Do not redo root or replace system APKs to continue.
