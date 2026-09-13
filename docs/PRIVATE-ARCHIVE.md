# Complete private workspace archive

This guide is for the **complete private project collection**, separate from the
public source repository. The parent reports public source commit `3fa9050` and a
validated clean clone; this sidecar neither inspected nor modified that repository.
The parent subsequently reports final Mac v0.6 live acceptance and a completed
device backup under `backups/final-validation-20260913`, with the original 182
files/three notebook rows unchanged. The parent will separately place the
project-specific Mac app-state snapshot inside the workspace backup. This helper
will include that snapshot once present; it does not enumerate Application
Support, general Keychain or other home credentials itself.
The user explicitly authorizes keys and credentials in the owner-private GitHub
archive and chooses **direct private release assets without an additional
encryption layer**. Encryption is optional, not a prerequisite or confirmation
step. The parent reports `jakezp/boox-private-archive` already exists, is private,
and is owned/administered by `jakezp`; the public source destination is
`jakezp/boox-local-connectors`. This sidecar did not query those repositories.
No remote, release, upload or real private archive was created by this sidecar.
Only filesystem metadata was inspected in the original workspace. Tests below
used disposable synthetic files.

## Final private Git transport

The final recovery collection uses ordinary private Git after GitHub's release
creation API returned repeated server errors. The already verified gzip stream
was repartitioned into **87 parts of at most 32 MiB**, preserving its full SHA-256:
`73cfeb727a3b503e7502efc0bd573a3a6a3d38fd24a42f78df394b798889185e`.

The collection contains **216,648 regular files and 248,519 total entries**, with
5,760,072,542 logical file bytes. The compressed stream is 2,891,626,000 bytes.
A full local restoration matched every file, directory, link, mode and mtime in
the embedded inventory. Git stores the parts as binary files without filters;
bounded pushes avoid a single multi-gigabyte upload. The private repository's
`restore.py` and `verify_restore.py` provide offline recovery after cloning.

For a new capture intended for this transport, use the archive tool with
`--chunk-mib 32 --gzip --recheck-content`. Keep the output outside the captured
source. The private index records the actual snapshot, transport and verification
receipts; the larger original-part manifest remains historical evidence.
The release and optional encryption procedures below remain alternatives, not
the transport used for this completed capture.

## Measured size and scope

Metadata scan on 2026-09-13, before adding this helper/test/guide:

| Item | Measured value |
| --- | ---: |
| Regular files | 185,770 |
| Directories, excluding the workspace root | 19,063 |
| Symlinks | 8 |
| Logical regular-file bytes | 3,869,488,069 |
| Logical regular-file size | 3.604 GiB |
| Allocated bytes counted per path | 4,368,601,088 |
| Files over 100 MiB | 3 |
| Files at least 2 GiB | 0 |
| Scan errors / special files | 0 / 0 |

Largest top-level totals: tools 1,207,365,091 bytes; backups 1,093,592,970;
Notes Drive 557,691,582; NeoReader decompilation 454,116,940; patch working files
273,593,799. These are size totals, not payload inspection.

There were 1,100 regular paths with multiple filesystem links, but no duplicate
inode payload within the measured workspace. Links to files elsewhere therefore
do not reduce this archive's payload count. Three symlinks point outside the
workspace; their target strings are preserved **without following them**.
Those external referents, Keychain, unrelated home files, sibling projects and
external Gradle caches are not workspace entries and are not collected.

The archive includes every entry actually under the chosen root: dotfiles,
nested `.git` data if present, ignored/generated files, firmware/images,
downloaded tools, keys, configuration, notebooks, evidence, backups, proprietary
decompilation and source. It never consults `.gitignore`, source inventory or the
public packaging allowlist. There is no hidden exclusion list. Unreadable or
unsupported special entries stop creation rather than disappearing silently.

The measured size is not the final tar size. PAX headers, path padding and the
embedded per-file inventory add overhead. Allow roughly 5 one-GiB chunks for this
snapshot and enough local space for plaintext parts, encrypted copies and a
restore test. The completed manifest provides the exact byte/chunk count.

A later metadata-only refresh after the new device backups observed **4,054,535,322
bytes (about 3.78 GiB), 185,791 regular files, 19,066 directories including root,
and eight symlinks**. The helper reports one directly external symlink target;
the initial scan resolved link chains and counted three ultimately external
links. No link referent is copied. These are **original-workspace observations,
not the size of the final larger collection**. Compression savings have not been
measured or assumed.

The parent owns a stable collection outside the original workspace, copying only
these known project roots/files with metadata and symlinks preserved:

- `boox-work`
- `boox-local-connectors`, including `.git`
- `boox-clean-checkout-20260913`, including generated keys and builds
- `boox-clean-dependencies-20260913`
- `boox-clean-gradle-cache-20260913`
- `boox-local-connectors-source-3fa9050.tar`

Archive the collection root **once** after copying finishes. Do not scan the
unrelated parent directory or home. The scoped Mac app-state tar is already
captured by the parent and will be included through the collection. General
Keychain/home credentials remain outside scope.

Run the metadata-only plan on that stable collection to obtain its actual total.
The previous roughly-15-GiB working-space suggestion applied only to the original
approximately-four-GiB tree. Rebudget for the larger collection, archive output,
download verification and restore; do not assume the final collection or upload
is 3.6 GiB.

## Practical GitHub recommendation

Keep the public source/setup repository and private recovery repository separate.
Keep ordinary Git history in the private repository small: archive instructions,
tool version and transport checksums. Put the complete immutable archive parts
on a **private release**, under the user's explicit authorization. This preserves
all selected files/keys without placing large binary snapshots in ordinary Git
history. No additional encryption identity is required for the chosen workflow.

Official GitHub documentation, checked 2026-09-13:

| Mechanism | Documented limit / constraint | Consequence |
| --- | --- | --- |
| Ordinary Git | Files over 100 MiB are blocked; GitHub recommends small repositories | Three current files already exceed the ordinary-file limit |
| Git push | A push is limited to 2 GB | A single bulk push of this workspace is not a suitable archive transport |
| Release assets | Each asset must be **under 2 GiB**; at most 1,000 assets per release; the page lists no total-release-size or bandwidth limit | One-GiB parts fit; use the final manifest for actual asset count |
| Git LFS | Per-file cap: Free/Pro 2 GB, Team 4 GB, Enterprise Cloud 5 GB | The same one-GiB parts fit every listed tier |
| Git LFS allowances | Free/Pro/Free organizations: 10 GiB storage and download bandwidth; Team/Enterprise Cloud: 250 GiB | Existing account usage, repeated full snapshots and downloads still matter; do not assume the whole allowance is unused |

References:

- [GitHub: large files and repository guidance](https://docs.github.com/en/repositories/working-with-files/managing-large-files/about-large-files-on-github)
- [GitHub: repository and push limits](https://docs.github.com/en/repositories/creating-and-managing-repositories/repository-limits)
- [GitHub: releases, asset count and size](https://docs.github.com/en/repositories/releasing-projects-on-github/about-releases)
- [GitHub: Git LFS per-file limits](https://docs.github.com/en/repositories/working-with-files/managing-large-files/about-git-large-file-storage)
- [GitHub: Git LFS billing and allowances](https://docs.github.com/en/billing/concepts/product-billing/git-lfs)

The 10-GB repository-size figure on GitHub's repository-limits page is guidance
about `.git` storage, not permission to bypass object/push limits. The large-files
page separately encourages repositories below 1 GB and strongly below 5 GB.
Release assets fit this archival use better than a growing history of binary
snapshots. LFS is a workable alternative if the parent deliberately chooses its
storage/download accounting; each changed snapshot creates new objects.
No account plan, quota, billing setting or repository visibility was queried here.

## Host-only archive tool

`tools/archive_private_workspace.py` uses the Python 3.10+ standard library and
POSIX filesystem/locking APIs. It is intended for macOS or Linux, not native
Windows. It performs no Git, network, encryption, Keychain, ADB, root, flash or
extraction operations.

The default command is **metadata-only** and prints aggregate counts, never a
filename, link target, credential value or file payload:

```sh
python3 tools/archive_private_workspace.py --root /absolute/original/boox-work
```

That command reproduces the earlier original-workspace measurement. For final
archival preparation, set `--root` to the **parent's stable collection** instead.

After all writers have stopped, create a new archive directory outside the source:

```sh
python3 tools/archive_private_workspace.py \
  --root /absolute/stable-project-collection \
  --create /absolute/private-output/boox-snapshot-20260913 \
  --chunk-mib 1024 --gzip
```

Creation necessarily reads the chosen workspace's file contents to archive and
hash them, including its private files. It never prints them. This creation
command was **not** run on the real workspace by this sidecar.

Output:

- `part-000001.tarpart`, etc.: consecutive byte chunks of a PAX tar stream,
  optionally gzip-compressed **before** splitting, at most one GiB each.
  Their checksums and order are recorded. One large source file may span parts.
- `source-index.json`: private local metadata used to bind resumptions to the
  original source state. It contains paths/link targets; keep it local/private.
- `state.json`: resumable chunk ledger.
- `archive-manifest.json`: final transport manifest, written only after successful
  source validation and stream completion. It records whole-archive and chunk
  SHA-256, byte counts, embedded-inventory hash and coverage summary.
- `__archive__/inventory.json` **inside the tar**: every workspace path, its type,
  mode, numeric ownership, timestamps, symlink target, and regular-file SHA-256.
  Source payload members live under `workspace/`.

The new output directory is mode 0700; parts/control files are 0600. The user
authorizes direct uploads of the completed parts and manifest to the verified
private repository. The helper preserves symlinks,
internal hard-link relationships, file/directory modes, numeric UID/GID and
nanosecond modification times in PAX metadata. External hard-linked files are
archived through their workspace path, without searching for other links.

**Explicit metadata boundary:** ACLs, extended attributes/resource forks,
birthtime and filesystem flags are not preserved. This is a complete workspace
entry/data-fork archive with the listed POSIX metadata, not an APFS image or
machine backup. If those additional macOS attributes are required, retain a
separate filesystem-native backup; do not describe this tar as preserving them.
The tool exposes these limitations in every plan/completion/verification report.

## Resume and verify

After an interrupted creation, use the same root, output and chunk size:

```sh
python3 tools/archive_private_workspace.py \
  --root /absolute/stable-project-collection \
  --create /absolute/private-output/boox-snapshot-20260913 \
  --chunk-mib 1024 --gzip --resume
```

Resume checks the saved source index and every retained chunk, then replays the
deterministic tar stream. Existing completed chunks are hash-compared and reused,
not rewritten. A partial tool-owned chunk may be discarded/recreated.
This saves output and permits per-asset upload retries later, but it still
rereads source payloads from the beginning. It is not a seek-based incremental
filesystem backup.

`--gzip` uses streaming gzip level 6, a fixed zero gzip timestamp and no source
filename in the gzip header. The compressed stream is split as it is produced;
no intermediate full tar is needed. Repeat `--gzip` on resume. The saved state
also binds the zlib runtime version; a changed codec/runtime requires a new
snapshot. Omit `--gzip` consistently for uncompressed parts. Verification and
reassembly support both formats automatically.

During the real collection capture, background metadata activity changed only
`ctime` on copied files, causing the strict default to stop. For this case, add
`--recheck-content` to creation and repeat it on resume. This mode compares every
other metadata field as before, then reads **every regular source file again**
and requires its SHA-256 to match the archived bytes before completing. It also
checks the tree again after that pass. Changed bytes are rejected even if a writer
restores the original size and modification time. The strict default is unchanged.
Four additional regressions cover this mode, bringing the archive suite to 22.
The mode is recorded in the manifest as `source_content_second_pass` and is bound
to saved resume state. Start a new output when changing modes; do not edit state.

A source addition/removal/content-or-metadata change aborts the snapshot or
resume. The tool checks metadata before each read, file descriptors after each
read, and the entire tree again before completion. That detects ordinary concurrent
changes; it does not turn a changing tree into an atomic cross-file snapshot.
Stop parent builds, sync writers and doc edits first. A changed source requires a
new snapshot/output, not an edited resume index or a forced override.

An exclusive advisory lock prevents concurrent writers to the same output.
Completed chunks are never replaced. An incomplete directory without a matching
final manifest is not a verified backup.

```sh
python3 tools/archive_private_workspace.py \
  --verify /absolute/private-output/boox-snapshot-20260913
```

Verification checks each chunk, concatenated tar checksum, embedded inventory
checksum, exact member coverage, every file's content checksum, modes, numeric
ownership, mtime and link metadata. It does not extract or execute anything.
Keep an independent copy of the final transport checksums; hashes alone do not
authenticate a maliciously replaced manifest.

## Authorized direct private-release workflow

The selected workflow uploads the completed parts directly, without age,
passphrase or a separate recovery-key dependency. The parts contain the full
collection, including its authorized keys and credentials. They must never be
sent to the public source repository.

The parent performs uploads, with a fail-closed repository check before each
batch/asset and after the transfer. An API error, unexpected repository/owner,
missing admin permission or non-private visibility must stop the workflow.
The following are **parent/operator commands, not executed by this sidecar**;
the release/tag must already have been prepared by the parent:

```bash
set -euo pipefail
PRIVATE_REPO="jakezp/boox-private-archive"
EXPECTED_OWNER="jakezp"
RELEASE_TAG="YOUR_FINAL_SNAPSHOT_TAG"
ARCHIVE="/absolute/private-output/boox-snapshot-20260913"

require_private() {
  gh api "repos/$PRIVATE_REPO" |
    jq -e --arg full "$PRIVATE_REPO" --arg owner "$EXPECTED_OWNER" \
      '.full_name == $full and .private == true and
       .visibility == "private" and .owner.login == $owner and
       .permissions.admin == true' >/dev/null
}

python3 tools/archive_private_workspace.py --verify "$ARCHIVE"
(set -C; cd "$ARCHIVE"; shasum -a 256 part-*.tarpart archive-manifest.json > SHA256SUMS)
require_private
for asset in "$ARCHIVE"/part-*.tarpart "$ARCHIVE"/archive-manifest.json "$ARCHIVE"/SHA256SUMS; do
  require_private
  gh release upload "$RELEASE_TAG" "$asset" --repo "$PRIVATE_REPO"
done
require_private
```

The metadata fields used by the gate are documented in GitHub's
[Get a repository API](https://docs.github.com/en/rest/repos/repos#get-a-repository).
Do not change visibility during transfer. No `--clobber` is used: keep completed
assets immutable and retry only missing/failed assets after checking hashes.
The helper itself contains no upload client and cannot bypass this parent-owned
visibility gate.

The release needs every numbered part, `archive-manifest.json` and `SHA256SUMS`.
The complete per-file inventory is embedded in the archive. `source-index.json`
and `state.json` are creation/resume controls, not required for remote recovery;
they may be retained or separately stored privately if desired. Omitting these
generated controls from release assets does not omit any collection file.

After upload, refresh the private gate, download the assets into a new private
directory, check `SHA256SUMS`, and run `--verify` on the downloaded copy. This
checks actual remote bytes rather than relying on successful upload messages.
Git LFS remains an alternative transport for the same parts if the parent elects
it; the chosen release route avoids a separate LFS checkout requirement.

## Optional encryption

Encryption is not required for the currently authorized private-only workflow.
If the user later chooses it, the official [age CLI](https://github.com/FiloSottile/age)
can encrypt each completed part and manifest. Use a new identity held outside the
collection and archive output, with a separately retained recovery copy.
Never encrypt with a decryption key that is itself stored in the same archive.
The archiver does not generate/read encryption identities, and no encryption
was performed here. An encrypted variant would require its own transport hashes
and a decrypt-before-verify recovery step.

## Recovery check before relying on the remote copy

Use a new private download/restore directory. Verify `SHA256SUMS`, then run the
archive helper's `--verify` against the downloaded parts and manifest.
No `source-index.json` is needed for verification/restoration; it is only for
resuming creation.

Use the helper to reassemble **only manifest-listed parts in their exact order**.
It verifies the chunks and per-file inventory first, refuses an existing output,
then checks the copied whole-stream hash again. It supports both gzip and plain
tar; the output suffix should match the format recorded in the manifest:

```sh
python3 tools/archive_private_workspace.py \
  --reassemble /absolute/private-download \
  --tar-output /absolute/new-restore/workspace.tar.gz
tar -tf /absolute/new-restore/workspace.tar.gz
tar -xpf /absolute/new-restore/workspace.tar.gz -C /absolute/new-empty-restore
```

These are manual recovery commands, not executed by this sidecar. Do not extract
over a live workspace, dereference archived symlinks, or run restored tools
automatically. External symlinks remain host dependencies; numeric ownership
restoration depends on the operator's privileges. Test an actual
download/verify/reassemble/restore before treating the private release as the sole
usable recovery copy.

## Verification performed here

The offline synthetic suite tests ignored/dotfile inclusion, complete member
coverage, internal/external/broken symlinks, hard links, modes and nanosecond mtime,
deterministic plain/gzip chunks, interruptions before/after chunk and ledger
completion, resume reuse,
source-change refusal, corruption detection, unsupported-entry refusal, output
isolation, recovery without creation state, and diagnostics that do not reveal
values. No encryption key is needed for direct-part recovery.

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover \
  -s tools -p 'test_archive_private_workspace.py' -v
```

No real keys, notebooks, firmware payloads or credentials were read by those tests.
No real-workspace archive, encryption, remote upload or recovery extraction has
been performed by this sidecar. Parent owns remote creation and final archival
execution after the workspace is stable.
