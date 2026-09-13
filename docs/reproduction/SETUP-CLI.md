# Staged companion installer for an already-rooted device

`tools/boox_setup.py` installs reviewed companion APKs on the exact supported
baseline. Its default is an **offline plan**. `--preflight` performs read-only ADB
checks; `--apply` performs the selected installation. Neither phase roots, flashes,
installs AMS/Vector, changes Magisk grants, performs OAuth, creates backups or
configures the Mac.

Start with the [first-time setup walkthrough](../../README.md#5-install-the-android-companions).
This reference explains the CLI's exact contract and backup requirements.
The existing NotesDrive update route has live validation; OpenAI, first-install
and combined wrapper routes have offline tests. Installation and hook loading
are separate from account setup and the native notebook round trip.
Recorded results belong in [validation evidence](../VALIDATION.md).

## Preconditions and supported operations

- NoteAir4C; firmware `2026-04-28_17-50_4.2-rel_04282_555977efe`;
  Android API33; active `_b`; boot completed.
- Existing authorized shell root via `/debug_ramdisk/su`; Magisk versionCode
  30200 or 30700.
- Active guarded AMS overlay with exact patched framework hash, and working
  Vector2.2/3080. Disabled/removal markers are rejected. The CLI does not repair
  missing framework prerequisites.
- Native Notes versionCode45326, including for an OpenAI-only installation.
- Working local `aapt`/`apksigner` at `tools/android-sdk/android-15`, Java runtime,
  and `tools/platform-tools/adb`.
- Built APKs using the preserved signing identities for updates. A new install
  is explicit and must have no installed package or registered module.
- Every Notes/Assistant/NeoReader editor saved and closed. `--editors-closed` is
  an **operator assertion**, not an automatic inspection of unsaved editor state.
  No other device editing, build, installer or configuration change may run during
  the live phase.

Supported components are `notesdrive` and `openai`. Select them with repeated
`--install`. An offline plan with no selection considers both; a live phase
requires explicit selection. Updates require existing enabled modules and exactly
the expected scopes. Unexpected scopes, disabled targets, certificate changes,
downgrades and unsupported formats fail closed rather than being silently repaired.

## Plan, review, preflight, apply

Existing installations from before the native Settings section need the
[explicit scope migration](../NOTES-SETTINGS.md#firmware-and-installation-details)
first. The Notes update helper now restarts the BOOX launcher and verifies its
settings-only hook as well as the editor hook. Launcher version 56737 is required.

Run from the workspace root. Paths under `/PRIVATE/REVIEW` must be a pre-existing
private local directory. These are command templates for the validated existing
NotesDrive route, replace them with your own local paths.

```sh
# Host-only: validates APK signatures/package metadata and emits a proposed plan.
python3 tools/boox_setup.py --install notesdrive \
  --write-plan /PRIVATE/REVIEW/plan.json
```

The saved plan is created exclusively with mode600; an existing file is never
overwritten. It records package/version, APK and certificate digests, required
scopes, component modes, a deterministic plan ID and hashes of the orchestration
code, doctor and delegated Notes installer. The Notes APK must match
`registration.json`, its certificate SHA-1 and Java-source hook-build digest.
If Java source changed after the build, rebuild before planning.

Review the saved plan and prepare the backup receipt described below. Then:

```sh
BOOX_SERIAL='<SERIAL>'
python3 tools/boox_setup.py --install notesdrive \
  --preflight --serial "$BOOX_SERIAL" --editors-closed \
  --reviewed-plan /PRIVATE/REVIEW/plan.json \
  --backups /PRIVATE/BACKUP/receipt.json
```

`--preflight` checks current model/firmware/slot/root/framework and native app versions,
enabled modules and strict Vector scope rows. It checks each installed base APK's
signature and hash by pulling **code only** into its private temporary staging
area; no app data, grants or private keys are pulled. Installed APK hashes must
match the supplied backup checkpoint. Single-base-APK installs are supported;
split APKs and signing rotations are not.

Apply rechecks the same plan and requirements from scratch; a previous preflight
is not a reusable authorization token:

```sh
python3 tools/boox_setup.py --install notesdrive \
  --apply --serial "$BOOX_SERIAL" --editors-closed \
  --reviewed-plan /PRIVATE/REVIEW/plan.json \
  --backups /PRIVATE/BACKUP/receipt.json \
  --report /PRIVATE/REVIEW/apply-result.json
```

The report must be a new path. It records started/completed actions and failure
state, without the serial, account IDs, commands containing private paths, or raw
ADB/stdout/stderr. Keep plans, receipts and reports private. A plan redirected
from stdout includes a display wrapper; use `--write-plan` for the exact file
accepted by `--reviewed-plan`.

To include OpenAI, add `--install openai` consistently when generating the plan
and running either live phase, and supply its additional backup roles. That
combined wrapper route has not been live-validated.

## What apply does

Before device mutation, the CLI copies the reviewed APKs and the unchanged Notes
installer plus registration into a private temporary directory and checks all
copied hashes. This keeps a concurrent APK rebuild from changing the delegated
installer's inputs. The staging area has the helper's expected relative paths and
an ADB symlink; no project files or live evidence are modified. Only these
new temporary files are removed on exit.

For an existing Notes installation it invokes the **byte-identical**
`tools/install_notes_drive.py` from that staged root. The helper owns the Notes
force-stop/install/Vector disable-enable delays/relaunch/current-PID hook-marker
validation. The wrapper does not implement a second cache-refresh procedure.
Python `-E` prevents an inherited `PYTHONOPTIMIZE` from disabling the helper's
assertions. Notes adapter v0.4 is the helper's explicit supported version.

For OpenAI updates it installs the reviewed APK with `-r`, then stops the saved
Assistant and NeoReader processes. Existing scopes are checked, not rewritten.
It does **not** assert that Vector loaded the new OpenAI hook; reopen and validate
both native surfaces separately. APK/hash/signature checks cannot establish that.

After installation, the CLI verifies installed signatures/hashes/versions, target
enabled state and exact scopes, plus unchanged unselected module states and the
other known companion's scope when present. Notes hook verification comes only
from the delegated helper's expected receipt. The CLI never reports Google
authorization or sync as verified.

## Explicit first installation

Add `--first-install <component>` to the plan **and** the same live command:

```sh
python3 tools/boox_setup.py --install openai --first-install openai \
  --write-plan /PRIVATE/REVIEW/first-openai.json
```

The first-install path installs the APK, issues only the documented targeted
`cli scope set <package> <exact-scopes>` and `cli modules enable <package>`,
then verifies registration. Expected scopes:

| Package | Scope |
| --- | --- |
| `local.boox.openai` | `com.onyx.aiassistant/0`, `com.onyx.kreader/0` |
| `local.boox.notesdrive` | `com.onyx.android.note/0`, `com.onyx/0`, `com.onyx.kreader/0` |

A first Notes install additionally requires `--registration-confirmed` for a
live phase: the operator confirms that the local package/certificate is registered
with Google. The CLI checks local registration but cannot verify Cloud Console
or consent. After initial module registration it delegates to the normal Notes
helper, including a second update install and its proven cache-refresh logic.
This bootstrap is source-derived and offline-tested, not a new live milestone.

No credentials are configured by first installation. Launch each companion and
complete its own private setup/authorization after reviewing the installation.

## Required backup receipt

Use [the example receipt](BACKUP-RECEIPT.example.json) as a schema reference.
It is deliberately invalid until an operator supplies actual hashes, paths,
timestamp and reviewed attestations. The CLI does not create a receipt or backups.

The receipt must:

1. Use schema1 and the exact supported model/firmware.
2. Bind to the chosen ADB serial with its SHA-256 (UTF-8, no newline). The receipt
   is private; even a serial digest should not be treated as anonymous.
3. Carry a timezone-aware creation timestamp no more than24 hours old and not in
   the future. Do not refresh that timestamp without making a current checkpoint.
4. Attest that editors were closed, the restore procedure was reviewed, signing
   keys were preserved separately and Android Keystore limitations are understood.
5. List exactly the required roles for the selected plan, with nonempty existing
   files, byte counts and SHA-256. Paths must be relative to and remain within the
   receipt directory; final-file symlinks and `..` paths are rejected.
   APK roles require `.apk`; other roles require `.tar`, `.tar.gz`, `.tgz` or
   `.zip`. Raw key/config files are not accepted as backup artifacts.

| Role | Required for | Expected checkpoint contents, prepared separately |
| --- | --- | --- |
| `vector_state` | Every plan | Current module/scopes configuration and corresponding recovery state |
| `notes_native` | Drive first install/update | Consistent native Notes and NeoReader databases/sidecars, ebooks, shared documents/points and native apply journals/backups |
| `ai_native` | OpenAI first install/update | Relevant original Assistant/NeoReader state/history |
| `notesdrive_state` | Notes update | Companion private state/queues sufficient for reviewed recovery |
| `notesdrive_apk` | Notes update | Exact currently installed companion APK |
| `openai_state` | OpenAI update | Companion private state/history sufficient for reviewed recovery |
| `openai_apk` | OpenAI update | Exact currently installed companion APK |

For both updates, include all seven roles. For OpenAI first install, include only
`vector_state` and `ai_native`. For Notes first install, include only
`vector_state` and `notes_native`. Do not use a receipt for another selection.

The CLI hashes backup files as opaque bytes. It does not unpack or read notebook
records, grant fields or private-key material. File hashes prove that the supplied
files exist unchanged, **not** that the operator's role labels, completeness or
restore claims are true. App data backups do not export Android Keystore keys.
Preserve compatible signing keys privately; the CLI never opens them.

## Failure and recovery contract

Exit0 means dry-run generation, preflight success or completed installation
pending acceptance. Exit1 means a guard/command failed; argparse usage errors use
exit2. Unknown command output is rejected. A failed apply report says
`failed_manual_review_required` and retains its completed actions.

Do not blindly retry a failed first-install plan: the first APK may now exist.
Review the report, installed state and backups; make a new current checkpoint and
plan if proceeding as an update. There is no unattended downgrade, uninstall,
grant reset, whole-library restore or automatic rollback. The delegated helper
retains its own targeted re-enable behavior on install failure.

A timeout or interruption can leave partial installation and requires inspection.
An external hard kill can leave a `started` event without completion; that is not
proof the action did nothing. Refer to [ROOT-RECOVERY.md](ROOT-RECOVERY.md) for
bounded rollback. Root flashing and OTA remain manual and separately validated.
