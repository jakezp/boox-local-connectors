# Working on this repository

Read `README.md`, `docs/reproduction/README.md`, `IMPLEMENTATION-PLAN.md` and
the newest section of `HANDOVER.md` before changing device integrations.
Historical handover sections record earlier checkpoints; they are not all
current acceptance claims.

## Components

- `openai-adapter/`: Android API/ChatGPT connector, native AI and NeoReader hooks.
- `notes-drive/android/`: Drive client, durable revision queue and native Notes
  hooks. Supported versions are Notes45326, launcher56737 and NeoReader38701;
  compile-only Xposed headers live in
  `xposed-stubs/`.
- `notes-drive/macos/`: Mac reader/editor, independent OAuth and matching protocol.
- `notes-drive/prototype/`, `probe/`, `apply-probe/`: retained experiments. They
  are not production installation steps.
- `notes-drive/tests/`: shared protocol vectors, synthetic fixture generation,
  and explicitly invoked live validation tools.
- `tools/`: dependency acquisition, builds, setup, diagnostics and source export.
- `docs/reproduction/`: historical root/recovery evidence, build/setup procedure,
  dependency pins and acceptance boundaries.

## Preserve the protocol and native data

Keep document/page/stroke identities stable. Revisions are immutable and
content-addressed. Conflicts preserve every head; wall-clock ordering is not
conflict resolution. Native incoming changes require an idle editor, durable
preimages, semantic readback and acknowledgement after verification.

Do not copy a grant between apps, infer Drive access from a folder ID, or replace
private signing identities during an existing installation update. Each client
uses its own normal OAuth flow. Do not read or print credentials for debugging.

Do not restore an old whole-library snapshot over later changes. Keep live
fixtures explicitly named and separate from existing user notebooks. Record
whether a result proves local encoding, Drive publication, native application,
or the full UI round trip; these are different acceptance levels.

## Builds and checks

Build paths are resolved by `tools/android_build_env.py`. Read the acquisition
manifest before installing dependencies; normal build commands do not fetch or
flash firmware. Fresh signing keys identify a new installation and require
matching Google registration. Existing keys remain private local inputs.

Run `python3 notes-drive/android/build.py` for the Android unit suite and APK;
run `bash notes-drive/macos/test.sh` for the synthetic Mac suite. Follow each
component's README for build and optional live tests. Private historical corpus
tests require explicit opt-in and are not part of clean-source acceptance.

After a change, record the tested source/build relationship, relevant checks and
limitations. Update current documentation and regenerate the source inventory.
Export only reviewed source; exclude notebooks, credentials, device backups,
decompiled applications and acquired proprietary firmware. Preserve the original
private working directory when preparing a clean repository.
