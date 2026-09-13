# Portable Mac build packaging — version 0.6

Build from the Mac source directory without private files or credentials:

```sh
./build.sh --output-dir "$PWD/.test-runs/public-build"
./test.sh
```

The default output remains `build/BOOX Notes Reader.app` when `--output-dir`
is omitted. The builder preserves an existing output bundle under
`previous-reader.*` instead of replacing its executable in place. It does not
launch or quit the app, touch Application Support, access Keychain, or contact
Google. The version 0.6 candidate was built from an isolated source copy into a
new checkpoint directory. The running/frozen lifecycle bundles remain unchanged.

## Generated bundle resources

`Tests/package_resources.py` uses the deterministic fixture generator and checks
the pinned source manifest before staging:

| Bundle resource | Generated profile | SHA-256 |
| --- | --- | --- |
| `Target-after.note` | `two-page.note` | `8bfae218c43404b6c60bf0d29ff1c38b16303d285fcfe03fce47a2588410f150` |
| `B2.note` | `two-page-variant.note` | `8690023e718bdf5f8f61456a66d33e6939a86be3768dcd921d47d7de37aee724` |

These are generated arithmetic coordinates and synthetic titles, not renamed
copies of private notebooks. No sibling `tests/artifacts` input is consumed.
`synthetic-resources.json` records the profile mapping/hashes inside the signed
bundle. The runtime verifies profile, filename, bounded size and SHA-256 against
that manifest before opening or publishing either disposable fixture.

## Configuration packaging

Default builds include **no OAuth config**. They never inherit:

- `config/oauth-desktop.json` from the checkout;
- a sibling `desktop-oauth.local.json`;
- configuration from any previous bundle.

For an explicitly requested private build:

```sh
./build.sh --output-dir "$PWD/.test-runs/private-build" \
  --oauth-config '/absolute/path/to/downloaded-desktop-client.json'
```

The build helper bounds and validates the JSON shape, then preserves the exact
requested bytes with mode 0600. It never prints the client secret or config
values, strips a project field, rewrites a client identity, or substitutes a
token. The existing in-app import workflow is unchanged. A config-free build
can be inspected locally without any grant; online functionality needs the
user's registered Desktop client and its own grant.

## User configuration and saved destination binding

Version 0.6 includes the explicitly authorized runtime parameterization:

1. `OAuthConfig` accepts the imported Desktop client/project without a personal
   project constant. It preserves the existing installed/flat formats and rejects
   web configs, invalid types/identities and oversized input.
2. `ConnectionSelection.desktopClientID` binds that client to the saved Google
   account permission ID and exact folder. Legacy selections are bound only after
   the configured client's own grant authenticates the same account and verifies
   that folder. Existing grant keys, destination IDs, journals and bytes remain.
3. Reconnect checks the exact saved folder; lost access never falls back to another
   directory. Fresh discovery has no known/private folder ID, selects a sole
   verified result or leaves multiple results for user choice. No authorized
   directory means no selected destination and no fabricated sync success.
4. The supplemental CLI requires the saved client binding and unchanged
   config/account/folder/device snapshot. It cannot migrate the live selection,
   override a destination, broaden scope, reuse another app's grant or change
   its explicit expected revision base.
5. Fixture verification uses the generated manifest; no private payload hash
   remains in the runtime. Both fixture UI paths preserve size/hash guards.

An already imported config is reused on upgrade. If the old app only contained
bundled config, import the exact existing Desktop JSON through the UI and
reconnect its saved grant. This does not require inventing a new client or
copying tokens. A different client cannot replace an already bound library.
The parent controls live candidate restart and acceptance. See
`PORTABLE-RUNTIME-HANDOFF.md` for the pinned credential-free build.

## Deterministic Android native-apply fixtures

The five existing synthetic profiles and their hashes remain unchanged.
Additional fixtures derive from the production pen writer:

```sh
PYTHONDONTWRITEBYTECODE=1 /usr/bin/python3 Tests/generate_native_apply_fixtures.py \
  --output "$PWD/.test-runs/native-apply"
```

| Output | Raw shapes | Visible pens | Tombstones | SHA-256 |
| --- | ---: | ---: | ---: | --- |
| `history-five-pen.note` | 5 | 5 | 0 | `e9753bf576d83ee263ef4f4906951d6fc17648c78a12fca099e90990fb86c437` |
| `history-add.note` | 6 | 6 | 0 | `f0b41b1a2d4956ae760284e9772d867a9855f484bf50454317bf855e0f3feda8` |
| `history-erase.note` | 6 | 5 | 1 | `24505daf755464f32a4f67b26dae05556da54cd827ac8dda1333bc98684b30d3` |
| `history-add-normalized.note` | 6 | 6 | 0 | `999c44592c8d9bef906d729faea5ebe17690d87b97cb634e38aa0a0fc0f95c6f` |

The directory also contains `add-plan.json`, `erase-plan.json` and
`native-apply-manifest.json`. Source pins are in `Tests/native-apply-manifest.json`.
All files are deterministic; existing differing outputs are refused.
Document ID `40fcad0aefc7592aa757e22fb871d446`, page ID
`9a80f7e349c75a43af147b575f4bfeb5` and all shape IDs come from public UUID5 labels.

Reproduce exact add/erase bytes through the production CLI, using new output paths:

```sh
FIXTURE_DIR="$PWD/.test-runs/native-apply"
PYTHONDONTWRITEBYTECODE=1 /usr/bin/python3 Resources/note_editor.py \
  "$FIXTURE_DIR/history-five-pen.note" "$FIXTURE_DIR/add-plan.json" \
  "$FIXTURE_DIR/reproduced-add.note"
PYTHONDONTWRITEBYTECODE=1 /usr/bin/python3 Resources/note_editor.py \
  "$FIXTURE_DIR/reproduced-add.note" "$FIXTURE_DIR/erase-plan.json" \
  "$FIXTURE_DIR/reproduced-erase.note"
```

Normalized add is derived from the actual production add output. It omits only
explicit zero-valued protobuf field 6 in active shape records, retaining IDs,
point blocks, other wire fields, metadata and history. It simulates proto3
normalization; it is not a native device export. Android tests can compare the
add/normalized pair and the six-shape/one-tombstone erase without private inputs.
The parent owns where these generated files enter Android build/test packaging.

## Verified behavior and remaining boundaries

The source-only copy had no `.note`, private config, shared artifacts, evidence
or sibling research directory before generation. **162 mandatory checks pass**:
4 provenance, 6 packaging, 4 native-apply generation, 29 reader, 10 pen writer,
7 lifecycle writer, 9 repeated-metadata/ancestor regressions and 93 Swift.
Config tests use synthetic data only. The latest source-only build includes
the Python ancestor fix and version 0.6 runtime parameterization.
`PORTABLE-RUNTIME-VALIDATION.json` records its executable/resource hashes,
test counts and completed live acceptance. Earlier validation reports and pinned
bundles remain as historical evidence.

The parent reports 162 checks passing from a clean clone and successful live
acceptance of version 0.6 authentication and nested-notebook draw/undo/redo/save.
The own-OAuth publication committed on the exact Android branch and appeared
visually in stock Notes. After normal close, the Mac automatically fetched the
verified one-pen/two-sample return, then the parent opened it from the library.
Separate parent tests then confirmed already-open following after a native
rename/normal close, without Mac refresh or reopen, preserving page and zoom.
The bundled synthetic fixture also opened through the GUI with its expected
two pages, 343 samples and pinned payload hash. All planned version 0.6 Mac
acceptance checks pass, with no Mac acceptance blocker remaining. The private
evidence reference is recorded in `PORTABLE-RUNTIME-HANDOFF.md`.

The parent reports that an actual GUI reconnect, pen drag, save and automatic
Drive publication succeeded on the frozen app. One intervening refresh rejected
a metadata mismatch after media download; the unchanged queued revision later
published and verified on automatic retry. The message does not identify which
field changed. It must not be described as proven content corruption or proven
version-only drift.

The current guard compares listed metadata with the post-download metadata,
in addition to verifying size, MD5 and SHA-256. A mismatch preserves the old
verified cache and retains pending publication. No race guard, digest check,
identity check, token handling or retry ancestry was weakened here.
