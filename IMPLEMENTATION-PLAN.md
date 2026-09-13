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
   maintainer/agent handover and limitations. Create the public source and private archive repositories requested below,
   scan the exact public upload content, and verify a remote source clone and
   the complete private archive.

## Current acceptance

Implementation and final Mac v0.6 live acceptance are complete for the bounded
core Notes sync described in `docs/VALIDATION.md`. The nested native export
round trip, open-notebook automatic following, bundled synthetic fixture and
post-validation original-data preservation all passed. Clean-source builds pass
for all five Android APKs and the Mac app. No fresh-device root/flash is claimed.

The user subsequently specified two distribution destinations:

1. Public `boox-local-connectors`: reviewed authored source, tests, synthetic
   fixtures, dependency pins, automated setup/diagnostics and end-to-end guides.
2. Private `boox-private-archive`: complete original project files, including
   ignored files, firmware, downloaded tools, build outputs, keys, configuration,
   notebook evidence and backups, with checksummed restoration instructions.

Final tasks are exact publication-set review, public push/remote-clone checks,
and complete private archive upload/verification. Keep private storage separate
from the public repository. Preserve the original working directory.

Do not overwrite concurrent edits or restore an old whole-library snapshot over
newer data. OAuth grants remain app-owned. The private archive is a backup;
restoration does not authorize copying a grant between different clients.
