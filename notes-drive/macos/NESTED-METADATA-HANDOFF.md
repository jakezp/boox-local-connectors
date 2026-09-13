# Native ancestor metadata hotfix — 2026-09-13

The native export after creating, renaming and moving a Mac notebook embeds its
ancestor folder in the same repeated `NoteModel` list as the notebook. The old
decoder incorrectly treated wrapper field 1 as scalar and rejected the export.

The reader now selects the document whose ID exactly matches the archive root.
Pen edits and notebook rename/move/restore replace only that document's wrapper
entry, preserving all other entries and unknown wire fields byte-for-byte.
Folder records are not imported into the Drive library or modified by this path.
The library continues to use the notebook parent ID and verified Drive folders.

Validation rejects duplicate IDs, missing root records, extra notebook records,
unsupported row types, invalid IDs/parents and cyclic ancestry. Metadata is
bounded to 256 rows. Missing external ancestors and retained old folder rows
are allowed so moving a notebook does not require fabricating or discarding
metadata. Embedded folders are reported in preview limitations.
The six-field revision protocol is unchanged.

## Stable checkpoint for parent restart

Bundle, relative to this Mac directory:

```text
build/ancestor-metadata-checkpoint-0cbdf84a/BOOX Notes Reader.app
```

Executable SHA-256:

```text
4f04be12ed0794ababb188d314b30d6fe5d0d01e64fd4a29723b1f98d022af4c
```

This is a signed copy of the frozen lifecycle checkpoint with only the three
Python resources replaced and the signature regenerated. Swift source, Info.plist,
existing bundled config and fixture resources are unchanged. The executable
hash changes because the signature seals the new resources. Strict signature
verification passes. The original checkpoint and standard output bundle are
unchanged; this checkpoint will not be overwritten.

The parent must quit/restart into this bundle and refresh the current verified
head to test the nested notebook. Historical revision IDs are not current-base
instructions. This agent performed no GUI launch, network/grant operation or
live app-state mutation.

## Validation

- 148 mandatory checks pass, including nine new synthetic ancestor tests.
- Existing deterministic synthetic profiles and add/erase hashes remain exact.
- The supplied native nested export decodes as one blank page and one embedded
  folder. Add, erase, rename, external move and root move preserve the ancestor
  bytes and original file.
- Four bundled `--inspect` runs and one local PNG render pass on the real
  readback and its isolated edited derivatives.
- Five optional private-corpus checks pass on the prior 20-page, offline-edit
  and native proto3 readback fixtures; originals remain unchanged.
- A separate source-only copy builds with no private notes or config, passes
  all 148 mandatory checks, verifies both generated resources and renders two
  synthetic page PNGs. See `PORTABILITY-VALIDATION.json`.

Detailed private evidence and helper hashes are in
`evidence/nested-metadata/handoff.json` and its referenced `.test-runs` logs.
These local artifacts and private notebook derivatives must not be published.
The mandatory suite only uses generated input.

Source files changed for this fix: `Resources/note_reader.py`,
`Resources/note_editor.py`, `Resources/note_library.py`,
`Tests/test_nested_metadata.py`, `test.sh` and the Mac handoff/build/test docs.
Earlier packaging preparation is documented separately in `PORTABLE-BUILD.md`.
