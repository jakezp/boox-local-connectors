# Complete private workspace archive

The public source is `jakezp/boox-local-connectors`. The complete project recovery
collection is in **`jakezp/boox-private-archive`**, an owner-private repository.
It includes firmware, project keys and credentials, notebooks, source, tools,
builds, tests, research and backups. No public-source exclusions or `.gitignore`
filters were applied to the collection. It has no additional encryption layer;
keep this repository private.

## Restore the published collection

The archive is stored directly in ordinary private Git under `archive/`.
GitHub release creation failed during publication, so the verified gzip stream
was split into **87 parts of at most 32 MiB** and pushed in bounded batches.
No Git LFS or release download is needed. Authenticate GitHub CLI, then run:

```sh
gh repo clone jakezp/boox-private-archive
cd boox-private-archive
python3 restore.py --extract-to /absolute/new-private-restore
python3 verify_restore.py /absolute/new-private-restore --report restore-check.json
```

Python 3.10+ and `tar` are required. After cloning, verification and extraction
work offline. The destination must not already exist. Omit `--extract-to` to
verify without extracting. Restoring the archive does not execute restored
programs or write to a BOOX device.

`snapshot.json` identifies every stored asset and checksum. The restore helper
checks the parts, combined archive and complete embedded inventory before
extraction. `verify_restore.py` checks exact restored entry coverage, file bytes,
modes, mtimes and links. The private repository contains the publication and
restoration receipts, plus synthetic tests for the restoration helpers.

The final capture contains **216,648 regular files / 248,519 total entries**,
with **5,760,072,542 logical file bytes**. Its compressed size is
**2,891,626,000 bytes**, and its full SHA-256 is:

```text
73cfeb727a3b503e7502efc0bd573a3a6a3d38fd24a42f78df394b798889185e
```

A full local restoration passed every content and supported-metadata comparison.
The original larger parts were repartitioned without changing the compressed
stream. Use the current private `snapshot.json`, rather than historical manifests
inside the captured workspace, to locate the published parts.

## Included project roots

Restored files are under `workspace/`:

- `boox-work`: the entire original workspace, including ignored files, dotfiles,
  firmware, keys, source/builds, device backups and failed-case evidence.
- `boox-local-connectors`: the reviewed source checkout and Git history.
- `boox-clean-checkout-20260913`: the earlier separate build and its generated files.
- `boox-remote-checkout-20260913`: the public GitHub clone and successful app builds.
- `boox-clean-dependencies-20260913` and `boox-clean-gradle-cache-20260913`:
  downloaded tools/dependencies and the final build cache.
- `boox-local-connectors-source-3fa9050.tar`, `external-project-receipts`,
  `COLLECTION.json` and collection-copy receipts: historical source and provenance.

The private root also contains `public-source-final.bundle`, preserving public
Git history and documentation updates made after the frozen capture. Its exact
commit and checksum are recorded in `source-publication.json`. Recover it offline:

```sh
git clone public-source-final.bundle boox-local-connectors-latest
```

For the final device checkpoint, start with
`workspace/boox-work/backups/final-validation-20260913/receipt.json`.
Use the verified archives it names; failed earlier captures are retained as
historical evidence. Do not overwrite later notebook edits with an old library.

## Capture another immutable collection

`tools/archive_private_workspace.py` is a Python standard-library, host-only
archiver for macOS/Linux. It performs no network, Git, account, ADB or root
operations. Its default command prints only aggregate metadata:

```sh
python3 tools/archive_private_workspace.py --root /absolute/stable-project-collection
```

Build an explicit collection of the project roots first. Stop its writers, keep
its output elsewhere, and do not scan unrelated home directories. The private
repository retains `snapshot_collection.py`, the exact copier used for this
capture, with per-file source/destination verification.

```sh
python3 tools/archive_private_workspace.py \
  --root /absolute/stable-project-collection \
  --create /absolute/private-output/new-snapshot \
  --chunk-mib 32 --gzip --recheck-content
```

Output includes consecutive byte parts, `source-index.json`, resumable
`state.json`, and a final `archive-manifest.json`. The PAX tar stream contains
`__archive__/inventory.json` with every path/type, regular-file SHA-256 and
supported metadata. Output directories are mode 0700 and files mode 0600.
Keep all outputs private, including indexes containing paths and link targets.

The strict default rejects source metadata changes. This capture encountered
ctime-only background churn, so `--recheck-content` was added: it ignores only
that field, then rereads every regular source file and requires its SHA-256 to
match the archived content. All other metadata checks remain; the tree is checked
again afterward. This is **not an atomic filesystem snapshot**. The input must
remain quiescent throughout capture and verification.

Resume with the identical root, output and options, adding `--resume`. It checks
saved state and chunks, then replays the stream from the start, reusing matching
completed chunks. Compression/runtime and content-recheck mode are bound to the
saved state. A changed input requires a new snapshot, not an edited index.
An incomplete output without a valid final manifest is not a verified backup.

```sh
python3 tools/archive_private_workspace.py --verify /absolute/private-output/new-snapshot
```

Verification checks all chunk and stream hashes, inventory checksum, exact
member coverage, content and recorded metadata without extracting or executing
anything. Retain independent trusted checksums: an altered manifest alone cannot
authenticate an archive. The private publisher validates the exact repository,
owner and private visibility and verifies parts before each bounded upload.

## Preservation boundaries

This is a complete collection of the selected project entries, **not a whole-Mac
or APFS backup**. It preserves bytes, modes, numeric ownership, nanosecond mtimes,
symlinks and internal hard-link relationships. Restoring ownership depends on
local privileges. ACLs, extended attributes/resource forks, birthtime and
filesystem flags are outside this format. External symlink targets are not
followed. Host Python, JDK and Xcode remain separately installed prerequisites.

The scoped Mac app-state archive was captured after normal app shutdown.
**macOS Keychain was not exported.** Sign in normally when restoring to another
Mac. Project key files and device app credentials are included in the private
capture; unrelated home credentials are outside its scope.

Build and companion-install automation is documented in the
[end-to-end guide](reproduction/README.md). Root/firmware prerequisites and the
historical gaps remain explicit in [acceptance boundaries](reproduction/GAPS.md).
No new root flash, OTA recovery or fresh-device install was performed to test
this archive.
