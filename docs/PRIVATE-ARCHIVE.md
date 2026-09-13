# Archive your local project workspace

`tools/archive_private_workspace.py` is an optional host tool for capturing a
user-selected project tree. It is not required to build or install the apps.
It includes everything under the selected root, including ignored files,
signing keys and account configuration, so keep its output private.

The tool uses Python 3.10+ on macOS/Linux. It does not upload anything, access
Keychain, contact a device or execute restored programs. It preserves file bytes,
POSIX metadata and links; it is not an APFS or whole-machine backup.

## Inspect and capture

Choose an explicit project collection and stop its writers. Keep output outside
that collection. The default command prints aggregate metadata only:

```sh
python3 tools/archive_private_workspace.py --root /absolute/stable-project-collection
```

Create a new archive directory:

```sh
python3 tools/archive_private_workspace.py \
  --root /absolute/stable-project-collection \
  --create /absolute/new-private-snapshot \
  --chunk-mib 32 --gzip --recheck-content
```

The result contains byte parts, a source index, resumable state and a final
manifest. The tar stream includes a per-entry inventory and regular-file hashes.
`--recheck-content` tolerates ctime-only metadata churn while requiring a second
complete source-content comparison. The source must remain quiescent; this is
not an atomic filesystem snapshot.

For an interrupted capture, repeat the identical command with `--resume`.
Changed inputs or compression/runtime options require a new snapshot. Do not
edit the index to force a resume. An incomplete capture without a valid final
manifest is not a verified backup.

## Verify before relying on it

```sh
python3 tools/archive_private_workspace.py --verify /absolute/new-private-snapshot
```

Verification checks parts, the combined archive, embedded inventory and entry
content/metadata without extraction. Keep a trusted independent manifest copy
and test your own restoration procedure before relying on a backup. Hashes do
not authenticate a manifest that was itself replaced.

The tool does not include ACLs, extended attributes/resource forks, birthtime or
filesystem flags. External symlink targets are not followed. Ownership restoration
depends on local privileges. Mac Keychain and Android Keystore grants are not
exported by copying project files; another installation may require normal sign-in.

Notebook backups and installer receipts have additional consistency requirements.
See [required backup receipt](reproduction/SETUP-CLI.md#required-backup-receipt)
for those roles. An arbitrary workspace archive does not automatically satisfy
native Notes recovery requirements.
