# Full sync and reproducible repository work

User request: complete the ONYX Notes cloud replacement, add Mac editing through
the existing subagent, validate the workflow, then clean up and publish a
reproducible GitHub repository containing our applications, modules, scripts,
tests, fixtures and end-to-end documentation.

## Ownership

- Parent: native incoming sync, Android connector, device integration and live
  tests, shared protocol, repository packaging and installation workflow.
- Banach (`01a09765-f638-7393-914e-a8f1188b574b`): `notes-drive/macos/**`,
  real editing, durable publication, tests and build. Parent performs live UI
  and authorization operations.

## Ordered work

1. Preserve the automatic v0.3/v0.2 checkpoint; implement incoming branch tracking
   and a durable native apply coordinator. Retain stable notebook/page/stroke IDs,
   refuse conflicting/local unsaved changes, recover before opening a notebook.
2. Integrate Mac edits and validate actual Mac → Drive → native BOOX readback,
   then BOOX → Drive → Mac. Exercise interruption, offline retry, conflicts,
   renames, folder moves, removals and restoration using disposable notebooks.
3. Integrate native Notes sync controls and automatic scheduling. Clearly report
   unsupported firmware/content and actionable errors.
4. Inventory every original change: EDL/root workflow, boot image identity,
   Magisk/AMS correction and module, Vector, OpenAI/ChatGPT setup, AI/NeoReader
   UI hooks, Notes/Drive/Mac applications, reverse-engineering tools and tests.
5. Produce a clean repository without credentials, personal notebooks, private
   device backups, generated decompilations or bundled proprietary firmware.
   Include reproducible acquisition/build commands and hashes for dependencies,
   sanitized synthetic fixtures, all authored source/test scripts, and a private
   local evidence inventory. Preserve the existing workspace and backups.
6. Build an ADB-oriented setup/doctor/backup/install/verify workflow with exact
   device/firmware checks. Root/flashing steps must remain explicit, resumable
   and backed up; never flash this user's already-working device merely to test
   installation. Exercise nondestructive stages and build-from-clean-source.
7. Write detailed README, architecture, tested scope, recovery, chronology,
   maintainer/agent handover and limitations. Create a private GitHub repository
   using the authenticated account if available, scan the exact upload content,
   push it, and verify a fresh clone can follow the documented build/tests.

## Current baseline

Historical stable checkpoint: `backups/notes-drive-automatic-v0.3-20260912/`.
That checkpoint has Android v0.3 / Mac v0.2, 30 Android + 60 Mac tests and preserved
the original three notebooks / 182 files.

Active v0.4:57 Android tests, native add/erase, seven crash checkpoints,
folder/book lifecycle, conflict UI, new Mac blank creation/open and native Sync
controls passed. Original3rows/182files unchanged. Mac frozenv0.5 lifecycle125
checks; current mandatory synthetic129 plus5 explicit private-corpus checks.
Host doctor/setup56 tests; existing Notes wrapper update passed live preflight/
apply with signatures, APK hashes, scopes and loaded-hook evidence.

Mac own-OAuth UI pen round trip passed after unlock: real GUI drag, automatic
publication, native exact-ID six-pen readback/visual open, then automatic Mac
following of the normal native save. GUI notebook creation and rename/move also
committed on BOOX. Native nested-folder exports exposed repeated ancestor rows
that the Mac decoder must handle; that correction is active. Source packaging,
generated Android fixture integration and portable build paths are in progress.
Both Android auto switches remain ON. Re-read latest branch IDs before mutations.
GitHub creation/push and clean-checkout validation are still outstanding.

Do not conflate transport verification with native application verification,
overwrite concurrent edits, or claim full renderer/content parity from pen tests.
Do not restore old whole-library snapshots over newer edits. OAuth grants remain
app-owned; secret configuration/signing keys stay in private local storage.

## Current continuation gate

Source implementation and clean local checkout builds pass, including Mac v0.6
ancestor handling and generic OAuth binding. Actual v0.5 own-grant UI pen and
notebook lifecycle round trips passed. The rebuilt v0.6 app is awaiting user
authorization in macOS Keychain; SecurityAgent is blocked to computer-use tools.
After that, finish the nested-note live check, update acceptance, and publish the
private repo from the cleaned sibling source directory. No GitHub push yet.
