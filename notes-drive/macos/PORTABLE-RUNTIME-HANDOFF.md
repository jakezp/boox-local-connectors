# Version 0.6 (build 7) — parent live acceptance

The candidate includes the ancestor metadata fix and portable client/folder
binding. No personal project, Desktop client or Drive folder ID remains in Swift
source, Swift tests or the compiled executable. Tests use synthetic identities.

Stable credential-free bundle:

```text
build/portable-runtime-0.6-checkpoint-3221cd0b/BOOX Notes Reader.app
```

Executable SHA-256:

```text
fc19e074c3dab9f9b19a0a6125c577042cc066f5ddbd3cf4f2a56eb4e6996890
```

This path is pinned and will not be overwritten. The standard output bundle,
frozen 0.5 bundle and ancestor-only checkpoint are unchanged. The candidate
was compiled from a Mac-only source copy without private notes, config or
sibling/shared artifacts. Strict signature verification passes before and after
CLI inspection/rendering.

## Parent activation and acceptance

1. Quit the currently running app and launch this candidate. This agent has
   performed no app restart, grant access or live state mutation.
2. An existing imported Desktop config remains usable. If none was imported,
   use **Google Drive connection → Import OAuth configuration…** with the exact
   existing user-owned Desktop JSON, then **Reconnect saved grant**. The build
   intentionally has no bundled config.
3. Legacy `connection.json` lacks `desktopClientID`. Successful reconnection
   checks the configured client's own grant, same Google account permission ID
   and exact saved folder before durably adding that client binding. No notebook,
   queue, destination ID or Keychain service/account is migrated or replaced.
   Failure leaves the saved selection intact and never chooses another folder.
4. Refresh the current verified native notebook that contains ancestor metadata.
   Test drawing, undo/redo and saving from its latest head, then verify the native
   roundtrip. Historical revision hashes are not suggested edit bases.
5. Open the bundled synthetic fixture from the disposable validation area to
   exercise the clean-build resource path. New installation discovery has no
   private folder fallback; multiple verified folders require user selection.

The user reports that the earlier 0.5 UI passed own-OAuth add/erase, undo/redo,
create/rename/move/delete/restore and native readback. Those results do not claim
live acceptance of the new binding/migration code; that acceptance remains for
the parent.

## Completed checks and boundaries

- 162 mandatory source-only checks pass: 69 Python + 93 Swift.
- Fourteen new Swift checks cover arbitrary Desktop projects/client identities,
  own-client Keychain query isolation, legacy migration, preserved journal bytes,
  client/account mismatch, unavailable/unwritable saved folder, direct permission
  recheck, ambiguous discovery, import/CLI binding and fixture-manifest integrity.
- Nine ancestor regressions and existing reader/editor/lifecycle/protocol tests
  pass. Pinned synthetic base and add/erase fixture hashes are unchanged.
- The signed app inspects both generated resources and the real nested native
  export plus isolated added/erased/renamed derivatives. Two synthetic page PNGs
  render. Originals remain unchanged.
- No external network or live OAuth/Keychain access was used by this agent.
  Mandatory OAuth socket tests use a local loopback server and synthetic data.

`drive.file`, PKCE/state/loopback, immutable six-field revisions, duplicate/hash
verification, captured parents, queue bounds and no-override CLI rules remain.
The existing Keychain service and client-keyed account names are retained.
CLI use requires an established client binding; an unbound legacy selection
must reconnect through the app first. A different client cannot silently take
over a saved library.

`Resources/note_reader.py`, `note_editor.py` and `note_library.py` retain the
root-selected, lossless repeated metadata handling from the ancestor checkpoint.
Version 0.6 runtime edits are in `Sources/OAuth.swift`, `LocalStore.swift`,
`Drive.swift`, `ReaderModel.swift`, `ProbeCLI.swift`, `Models.swift` and the
directory-picker placeholder in `App.swift`. Tests add `PortableConfigTests.swift`
and update `CoreTests.swift`, `ProbeTests.swift`, resource packaging and `test.sh`.
`build.sh` bumps the version; Mac build/test/integration docs are current.

Public validation summary: `PORTABLE-RUNTIME-VALIDATION.json`.
Detailed local evidence: `evidence/portable-runtime-0.6/handoff.json` and its
referenced fresh `.test-runs` logs. Private evidence, generated local artifacts,
credentials and bundles are ignored and must not be included in the source repo.
