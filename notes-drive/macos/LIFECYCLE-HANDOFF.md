# Frozen lifecycle handoff for parent validation

No app rebuild or live-state change is needed for this handoff.

App: `build/lifecycle-final-checkpoint-a6c72cc9e6cc-ec0c7894/BOOX Notes Reader.app`

Executable SHA-256:
`a6c72cc9e6cc55a1a0615a20722e528f0a4c198243afea8b89b168d8fa262611`

Local validation: 125 checks (29 reader, 10 pen writer, 7 lifecycle writer,
79 Swift); strict signing passed. The frozen folder/CLI/editor and intermediate
lifecycle checkpoints remain unchanged. This agent has not launched the UI,
used the grant or changed live app state.

## Fresh native notebook fixture

- File: `evidence/notebook-management/native-blank.note`
- SHA-256: `6a31831b150b9689afbeac89bebda4c7518741191f8d3d887e95a276b65b24b2`
- Native document: `a21e60175a514e84b136b279055a04bb`
- Sync ID: `boox-a21e60175a514e84b136b279055a04bb`
- Native page: `a21e60175a514e84b136b279055a04bc`
- Title: `GDrive-Sync-Probe-Mac-Created`
- Root parent; one blank 1860 × 2480 page; visible/unlocked layer 0; zero pens.
- Native type/status/version 1. No copied ownership/resource/ONYX account fields.
- Repeatable helper input: `evidence/notebook-management/create-plan.json`.

Publish as a new root revision with no parents only if that sync ID has no
existing head. This fixture is independent of the previously edited probe's
changing ancestry. If already published, inspect its own current heads before
choosing a descendant. The Mac agent has not published this fixture.

Parent acceptance: native same-ID create → semantic readback → open/visible blank
page → native pen save and later capture. Stock export may add `extra/pb/extra`;
parent owns narrowly checking generated extra metadata during fresh creation.
Existing-note edits preserve original opaque records and do not need this
creation-specific handling.

The root rename descendant fixture is
`evidence/notebook-management/native-blank-renamed.note`, SHA-256
`12ceddade48072960c83da2303e01b2e038a4604a7bb8a267d9fe671391606d1`.
It preserves document/page IDs and changes title to
`GDrive-Sync-Probe-Mac-Created-Renamed`. Its publication must parent the actual
verified creation head, not a head from another notebook.

## UI after unlock

Reconnect the app's own saved Google grant. Use **New notebook…**,
**Rename or move…**, **Request deletion…**, **Restore notebook…**, and **Saved
changes**. Pen controls include draw, whole-stroke erase, undo/redo and Save to
library. All queued changes retain captured account/folder/parent identity.
The current Mac reads all-head resolutions but does not offer a resolution picker.

Mac UI/current Keychain publication remain unvalidated while locked. The existing
isolated probe request may now have an obsolete base; never rebase it implicitly.
The supplemental CLI is private-project validation, not a general public OAuth
registration tool. See `PROBE-CLI.md`.
