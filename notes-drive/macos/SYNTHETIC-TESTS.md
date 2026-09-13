# Shareable Mac regression data

The mandatory Mac suite generates its own notebooks and reads no `.note` from
sibling `research`, shared `tests/artifacts`, private evidence or the live app.
Run from this Mac source directory:

```sh
./test.sh
```

The runner creates a new private `.test-runs/run.*` directory for each invocation.
Fixtures, executable and disposable application state belong there. Existing
evidence and app bundles are not replaced. This directory is ignored by Git.
Running Python tests directly also creates isolated generated input under
`.test-runs`; it never falls back to private inputs.

**162 mandatory checks:** 4 generator/provenance, 6 build packaging, 4 native-apply
fixture generation, 29 decoder, 10 pen writer, 7 lifecycle writer, 9 repeated
metadata/ancestor regressions and 93 Swift.
No private-corpus skips are part of that count.
The full suite passed from a Mac-only source copy containing no notebooks,
Google config, evidence, sibling research directory or shared artifacts.

## Generator and profiles

`Tests/synthetic_fixtures.py` is a new independent standard-library encoder.
It imports neither the production encoder/reader nor repository prototype code,
reads no notebook, and needs no account, device, network or clock input.

IDs are UUID5 of fixed public labels. Coordinates are arithmetic zigzags, for
example `[120,180] → [125,201] → [130,222]`; they are not anonymized, translated
or sampled handwriting. All notebook titles start with “Synthetic”. ONYX account
fields are absent. Resources and opaque bytes are literal test content.
ZIP_STORED, fixed member metadata and a fixed synthetic timestamp make outputs
repeatable. `Tests/synthetic-manifest.json` pins their hashes.

| Generated file | Coverage |
| --- | --- |
| `two-page.note` | 2 pages, 11 pens, 343 samples, 2 layers on page 2, internal link |
| `two-page-variant.note` | Same identities/point bytes; different synthetic title and payload |
| `chunked-20-page.note` | 20 pages, 333 shapes, 319 pens, 14 unsupported type-2000 records, 54,234 samples |
| `history-five-pen.note` | 5 pens, 154 samples, disjoint active shape chunks, duplicated stashed shapes |
| `proto3-reexport.note` | 6 pens, 158 samples, simulated proto3 omission of the added pen's zero layer |

The large profile includes two point chunks on its first page (151/48 indexed
records), shape chunks with different boundaries, 32-character page/document
IDs, resource/extra/template/virtual records, archived history, and stashed
shape/point copies. It reproduces the decoder cases that motivated the private
regressions, not a user's notebook content. The “reexport” profile simulates
serialization behavior; it is not evidence of a native device roundtrip.

Generate copies explicitly without changing shared artifacts:

```sh
/usr/bin/python3 Tests/synthetic_fixtures.py --output .test-runs/shareable-fixtures
```

Identical outputs may be reused; differing files are refused, never overwritten.
Tests verify pinned hashes, repeatability, metadata/coordinate provenance, no
filesystem/network access during generation, and unchanged input bytes after
rendering/editing. Collision, missing-reference, malformed chunk, unknown-field,
cumulative-budget and unsupported-content checks remain mandatory.

`Tests/protocol-vectors.json` is an exact local copy of the parent-owned shared
canonical vectors: synthetic `hello`/`world` payload hashes and deletion. The
copy was compared byte for byte with `../tests/protocol-vectors.json`, avoiding
a cross-directory test dependency. Coordinate future vector/schema changes with
the parent; do not silently regenerate different wire expectations.

## Optional private corpus

Private regression is explicit and separate:

```sh
PYTHONDONTWRITEBYTECODE=1 /usr/bin/python3 Tests/private_corpus.py \
  --native20 '/private/corpus/twenty-page.note' \
  --offline '/private/corpus/offline-edit.note' \
  --readback '/private/corpus/native-readback.note'
```

All arguments are required. No private paths, titles, account IDs, notebook IDs,
hashes or automatic searches are embedded. The five checks validate chunked
reading, offline edit/stash, native omitted proto3 default, metadata preservation
and unchanged input bytes. Edits stay in memory. The mandatory runner never
imports or invokes this optional suite. It passed locally against the previously
supplied private files, unchanged.

Keep private originals, output logs, `.test-runs`, evidence, configs and built
bundles out of the public source package. Do not upload or replace existing
`notes-drive/tests/artifacts` contents as part of this change. Shareable test
assets are the generator source, manifest and local synthetic protocol vectors.
This is test-data preparation, not approval to publish the whole repository.

## Build and live validation boundaries

The frozen lifecycle app remains at the checkpoint in `LIFECYCLE-HANDOFF.md`.
No application source or bundle was rebuilt for this test-data change.
The live creation fixture is separate from this generated regression corpus.

`build.sh` now generates synthetic equivalents under its existing Target-after/B2
resource names and defaults to no OAuth config. No sibling artifact or previous
bundle config is read. `PORTABLE-BUILD.md` documents explicit private config
packaging, the version 0.6 client/destination binding and fixture-manifest checks,
source-only validation and deterministic Android add/erase/normalized fixtures.
The five original synthetic profiles and hashes remain unchanged.

`Tests/test_nested_metadata.py` builds repeated NoteModel wrappers around the
existing generated profiles. It checks notebook selection in any row order,
32-character and hyphenated IDs, raw ancestor/unknown-byte preservation through
pen and metadata edits, incomplete external ancestry, retained old folders,
cycle/duplicate/type rejection and metadata bounds. It reads no private export
and does not change the pinned profiles or derived add/erase hashes.
The corresponding Python-only runtime fix and signed checkpoint are described
in `NESTED-METADATA-HANDOFF.md`; the earlier frozen bundle remains unchanged.

The supplemental own-OAuth CLI uses the saved client's own Keychain namespace
and exact verified connection binding, as documented in `PROBE-CLI.md`. Fourteen
new Swift checks cover arbitrary synthetic clients, legacy migration, preserved
state, mismatched client/account, lost destination, discovery ambiguity, CLI
binding and generated bundle integrity. No real client ID or credential is used.
