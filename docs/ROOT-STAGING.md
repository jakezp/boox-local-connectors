# Host-only preparation of the historical Magisk 30.2 patch inputs

This tool closes the APK extraction/staging part of the root reproduction gap.
It does not reproduce or certify a new patched boot image. The host never runs
ADB, Magisk, an extracted binary or shell script, installs root, flashes a
partition, or contacts a device or network.

Use [the historical root/recovery record](reproduction/ROOT-RECOVERY.md) for the
firmware boundary and saved flash/readback evidence. That record's missing
authored staging procedure is now addressed by
[`prepare_magisk_stage.py`](../tools/prepare_magisk_stage.py), subject to the
remaining device-patch and acquisition gaps below.

## Exact private inputs

The inspected originals belong to the NoteAir4C firmware
`2026-04-28_17-50_4.2-rel_04282_555977efe`, Android 13, original active slot B,
with an already-unlocked bootloader. No bootloader unlock procedure was recorded.
The tool accepts only these two original files:

| Input | Bytes | SHA-256 |
| --- | ---: | --- |
| Installed Magisk 30.2 APK backup | 12339254 | `123093f51eeb1aa459ac188a22ac3e775e8243293d48df2ceda9ca9dad22d2d6` |
| Original boot B | 100663296 | `765d41d770c9fcccb812ebef9ce1eb56f24e799a2be97304c960b5b73f7bf0de` |

These hashes were recomputed from the local originals on 2026-09-13. The APK was
backed up from the installed application. On 2026-09-13 the official Magisk v30.2
release asset digest and a fresh download both matched this exact SHA-256. The
`magisk_30_2` entry in [acquisition.json](reproduction/acquisition.json) provides
the pinned download, also available through the dependency bootstrap. A similarly
named or different-hash APK is not a substitute.
There is no hash override, alternate ABI, force option, or already-patched-input
mode. Keep the originals and generated stage private, outside the source export.
No APK, boot image, extracted binary or upstream script is redistributed here.

## Prepare a new stage

Use Python 3.9 or later with its standard library. No package installation or
Android SDK is required. Inputs must be regular files, with no symlink at the
input filename. Choose a new output directory under an existing private parent:

```sh
python3 tools/prepare_magisk_stage.py \
  --apk '<PRIVATE-INPUTS>/magisk-30.2.apk' \
  --original-boot '<PRIVATE-INPUTS>/boot_b.img' \
  --output '<PRIVATE-OUTPUTS>/new-magisk-stage'
```

The placeholder paths are supplied by the operator. The tool takes verified
in-memory snapshots of both inputs and copies only those verified bytes.
It validates the complete ZIP member directory before creating output:
traversal, absolute/ambiguous names, duplicate paths, case collisions involving
selected assets, file/directory collisions, links, special files, encryption,
unsupported compression and excessive declared expansion are rejected.
The pinned APK has case-distinct Android resource names (for example
`res/2F.xml` and `res/2f.xml`); these unused members are never extracted.
Only pinned members are decompressed; their exact sizes and hashes are
independently checked.

Existing files, directories and dangling output symlinks are refused. Output
parent aliases are resolved once before exclusive creation of the final
directory; missing parents are not created. On POSIX hosts every result file is
initially non-executable and private (`0600`), and directories are `0700`, subject
to a more restrictive host umask. On other hosts, keep the output under a private
directory protected by the host's access controls. Existing input permissions
are unchanged.
A write failure can leave a newly created partial output. Preserve/inspect it
and choose a fresh output path; the tool never overwrites or removes an earlier
stage. A complete, parseable `manifest.json` is written last.

The result is:

```text
new-magisk-stage/
  manifest.json
  SHA256SUMS
  DEVICE-PATCH.md
  payload/
    boot_b.img
    [15 pinned Magisk assets listed below]
```

`manifest.json` records input hashes, the generator source hash, ABI, firmware,
four patch flags, and each output file's hash, size and APK source member.
Its status is always `prepared_not_patched`; `new_patch_verified` is false.
It contains no operator input/output paths or device identifiers.
`SHA256SUMS` covers the payload and generated device instructions, using relative
paths. The manifest also hashes `SHA256SUMS`. These are integrity records, not
cryptographic signatures; retain the trusted source and original input hashes.
Neither the manifest nor the checksum list hashes itself.

## Inspected APK layout and support assets

All six native binaries come from `lib/arm64-v8a/`. There are no
`libmagisk64.so` or `libmagisk32.so` members in this APK. The installed 30.2
layout uses `libmagisk.so` and `libinit-ld.so`; guessing an older release's layout
would produce the wrong stage.

| APK member | Stage file under `payload/` | Purpose established by inspected scripts/layout |
| --- | --- | --- |
| `assets/boot_patch.sh` | `boot_patch.sh` | Regular-file unpack/patch/repack entry point |
| `assets/util_functions.sh` | `util_functions.sh` | Patch helpers, version constants and module installation functions |
| `assets/stub.apk` | `stub.apk` | Compressed into the patched ramdisk |
| `lib/arm64-v8a/libmagisk.so` | `magisk` | Magisk payload and boot-mode preinit-device query |
| `lib/arm64-v8a/libmagiskinit.so` | `magiskinit` | Ramdisk init replacement |
| `lib/arm64-v8a/libmagiskboot.so` | `magiskboot` | Boot image manipulation |
| `lib/arm64-v8a/libinit-ld.so` | `init-ld` | Ramdisk init preload payload |
| `lib/arm64-v8a/libbusybox.so` | `busybox` | Consistent Android shell utilities |
| `lib/arm64-v8a/libmagiskpolicy.so` | `magiskpolicy` | Required by the 30.2 app's support-environment check |
| `assets/app_functions.sh` | `app_functions.sh` | Support-environment check and app helper definitions |
| `assets/module_installer.sh` | `module_installer.sh` | Loads `/data/adb/magisk/util_functions.sh` before module installation |
| `assets/addon.d.sh` | `addon.d.sh` | Retained support asset; not executed for staging/patching |
| `assets/uninstaller.sh` | `uninstaller.sh` | Retained support asset; not executed for staging/patching |
| `assets/bootctl` | `bootctl` | Retained boot-control helper; not executed |
| `assets/main.jar` | `main.jar` | Retained APK support asset; not executed |

The tool contains the independently inspected SHA-256 and byte length of every
row. On 2026-09-13 all 15 selected files matched the corresponding retained
`magisk-env/` files byte-for-byte. The original environment also contains
ChromeOS utilities/key material, dexopt profiles and a public-suffix database.
Those are not needed for this Android boot-image path and are not staged.
The patcher's optional ChromeOS signing path is outside this tool's scope.

### What is known about the historical missing-support-file repair

`STATUS.md`'s “Root and manager fix” and `HANDOVER.md`'s “Magisk manager / BOOX AMS
fix” record restoration of missing `/data/adb/magisk` support assets from the
original APK, followed by AMS module installation and successful Magisk UI/root
verification. They do **not** record which individual files were missing,
their pre-repair state, the exact copy commands, or a per-file post-repair
ownership/mode/context receipt. That missing evidence cannot be reconstructed
from the surviving host environment alone.

The exact source-derived diagnostic boundary is available:

- `module_installer.sh` requires
  `/data/adb/magisk/util_functions.sh`, then sources it and checks its Magisk
  version code before calling `install_module`.
- `app_functions.sh`'s `env_check` requires `busybox`, `magiskboot`,
  `magiskinit`, `util_functions.sh` and `boot_patch.sh`; for version 30200 it
  also requires `magiskpolicy`. It checks the exact utility-script version
  (`30.2`, `30200`) and the existence of a preinit block node under
  `$MAGISKTMP/.magisk/device/preinit` or `$MAGISKTMP/.magisk/block/preinit`.
  Missing preinit state is a different failure from a missing support file.
- The staged files and hashes above provide exact candidate replacements for
  a separately diagnosed matching-30.2 repair. Host asset equality proves
  neither the tablet's installed support-file state nor a completed repair.
- The APK's `fix_env` helper deletes the existing support directory contents
  and its source directory. It is not a missing-files-only repair. Do not
  execute it or copy the whole staging directory into `/data/adb/magisk`;
  that would also copy the original boot and generated review records.

A future repair requires its own inventory of actually missing files, retained
preimages for any replacements, a reviewed per-file destination/permissions
plan, and device readback plus Magisk/module acceptance. No particular chmod,
chown or SELinux restoration sequence is claimed as the historical repair.
This host tool does not implement that device mutation.

The working device was later observed on **Magisk 30.7**. The upgrade
provenance/procedure was not recorded. The 30.2 payload must not be used to
fill presumed gaps or downgrade support files on that functioning installation.

## Device-local patch instructions and acceptance boundary

`DEVICE-PATCH.md` contains the complete proposed device-local command. It is
generated from the reviewed source; the host never executes it. Transfer is a
separate operator action. Use a new private device working copy after verifying
the actual firmware, model, ABI, slot and recovery inputs.

The command checks transferred payload hashes, requires ARM64/NoteAir4C, starts
BusyBox with a clean environment, loads the utility functions, assigns a fresh
stage-local `TMPDIR`, and sources `boot_patch.sh` against regular-file
`boot_b.img`. Its four recorded settings are exactly:

```text
KEEPVERITY=true
KEEPFORCEENCRYPT=true
PATCHVBMETAFLAG=false
RECOVERYMODE=false
```

The wrapper explicitly retains the script's default `LEGACYSAR=false`.
It loads architecture detection before setting `SOURCEDMODE=true`, which keeps
the patcher from sourcing the utility script again and resetting `TMPDIR`.
The stock utility script assigns `/dev/tmp` and its abort handler removes that
directory; redirecting it to a fresh stage-local directory limits that cleanup.
The patcher also recursively changes working-directory permissions, so run it
only in a disposable device copy of the complete stage.

These wrapper details are reconstructed from inspected scripts, not a retained
historical transcript and not a newly executed device patch. The boot-mode
patcher invokes `magisk --preinit-device`; the original result/environment was
not retained as a complete recipe. This can affect a repeat output. The tool
does not silently replace the invocation with `install_magisk` or
`direct_install`, which include flashing.

| Evidence level | What is established |
| --- | --- |
| Host preparation | Exact original inputs and 15 assets verified; inert stage produced |
| Historical patch | Saved patched boot and saved readback both rehashed to the comparison target below |
| New device patch | Not run or verified by this sidecar |
| New installation/readback | Not performed |
| Current-device Magisk upgrade | Later 30.7 observed; upgrade provenance remains missing |

Historical patched boot B and saved flash readback SHA-256:
`67563496cd465eb44c12e8f5dd3997d94717dcfd89da92be579cace8ef50cd3e`.
Both historical files were rehashed offline on 2026-09-13. Historical output
size was 100663296 bytes, with unchanged kernel and DTB recorded at the time.

For any future patch, retain its own SHA-256, size and kernel/DTB comparison
against the original. Investigate differences before considering installation.
A matching output hash is still distinct from a verified new device write and
readback. Existing historical EDL evidence covers only boot B; the historical
GPT backup success message did not correspond to saved GPT payloads. Staging
does not repair that recovery gap or establish compatibility with other firmware.

## Verification

Run the synthetic safety suite without any private inputs:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 tools/test_prepare_magisk_stage.py
```

To explicitly run the additional original-input comparison:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 tools/test_prepare_magisk_stage.py \
  --apk '<PRIVATE-INPUTS>/magisk-30.2.apk' \
  --original-boot '<PRIVATE-INPUTS>/boot_b.img' \
  --reference-env '<PRIVATE-HISTORICAL-ENV>/magisk-env'
```

The optional comparison uses the unchanged production pins, creates a temporary
host stage outside the repository, compares all 15 assets against the named
historical environment, rechecks original input identity, and removes only its
own temporary directory. It does not execute extracted code or infer a patch
result. The ordinary suite uses synthetic data and does not discover/read
private originals automatically.

Verification checkpoint, 2026-09-13: 27 synthetic safety tests pass, and the
explicit original-input staging/comparison test passes (28 total with private
inputs enabled). The synthetic-only invocation skips that one opt-in test.
All 27 synthetic tests also pass under `python3 -O`; safety gates do not depend
on assertions being enabled.
The source is executed directly by Python; no compiled tool or APK build is
involved. Each stage records the SHA-256 of the exact generator source used.
The final source hashes are handed to the parent for coordinated inventory and
source-export inclusion. No source inventory or other agent's files are changed
by this sidecar.
