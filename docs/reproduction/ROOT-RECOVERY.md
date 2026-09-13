# Historical root, firmware guard, rollback and OTA

Evidence checkpoint: 2026-09-12. All commands in this document are examples for a
qualified operator on a confirmed matching device. **None was executed by the
documentation sidecar.** Existing flash/readback files were hashed offline.

## What actually happened

The original NoteAir4C was already bootloader-unlocked, running firmware
`2026-04-28_17-50_4.2-rel_04282_555977efe`, active `_b`. No bootloader unlock,
slot switch, GPT write or slot A flash was performed.

Saved originals were `boot_a.img`, `boot_b.img`, `recovery_b.img`,
`vbmeta_b.img`, `devinfo.img`, `services.jar` and the installed Magisk30.2 APK.
The local backup directory includes a device identifier, so its path is represented
here as `backups/<SERIAL>-4.2-04282/`; originals must remain private.
`STATUS.md` and historical `HANDOVER.md` explain the procedure.

| Artifact | SHA-256 |
| --- | --- |
| Original boot A, 100663296 bytes | `d25af02440eae31b9a639cba4c3bd57440852f43a3060d9425c82a103601bbd5` |
| Original boot B, 100663296 bytes | `765d41d770c9fcccb812ebef9ce1eb56f24e799a2be97304c960b5b73f7bf0de` |
| Installed Magisk30.2 APK backup | `123093f51eeb1aa459ac188a22ac3e775e8243293d48df2ceda9ca9dad22d2d6` |
| Magisk30.2 patched B and readback | `67563496cd465eb44c12e8f5dd3997d94717dcfd89da92be579cace8ef50cd3e` |
| Later observed Magisk30.7 boot B backup, historical record | `33475f2697db3e277300b9ec1438c9af7cb4ab457b7433e87170c5d87cca6d60` |
| Exact original services.jar | `fd45574a43099f3e1abc0bca6a88db3d018d6f183c41d7e0a46f16801ae5b2e6` |
| AMS patched services.jar | `e2bad92800231d1ad86179202f42dd5f89d830114a7bc6273d25ce9c2c923c14` |

The 30.7 hash is historical handover evidence, not a newly verified device read.
The old 30.2 patched image is not evidence of the current installed boot.

Magisk assets were extracted from the installed **30.2 APK** and its
`boot_patch.sh` run **on the tablet** against original boot B with:

```text
KEEPVERITY=true
KEEPFORCEENCRYPT=true
PATCHVBMETAFLAG=false
RECOVERYMODE=false
```

Kernel/DTB were checked unchanged; patched boot remained 100663296 bytes.
The original staging/extraction shell transcript was not preserved. The new
[host-only staging tool](../ROOT-STAGING.md) now recreates all 15 required assets
from the exact saved APK, checks their individual hashes against the retained
environment, and emits a private payload plus device-patch instructions.
Its 28 checks include actual offline asset equality. It does not run a patcher,
repair the installed support directory or flash a device; those acceptance levels
remain separate.
Do not repatch an already-patched image or substitute a later Magisk release
and expect this historical hash.

## Exact EDL boundary and missing GPT

`patch/flash-boot-b.log` records **physical partition/LUN 4, start sector 258954,
24576 sectors, 4096 bytes per sector**. `patch/readback.log` records the same start
and count. Offline SHA-256 of `patch/boot_b-magisk-30.2.img` and
`patch/boot_b-readback.img` was recomputed and matches the table above.

The logs retain parameters and results, not a full shell transcript. The following
syntax is reconstructed from the pinned EDL command parser and recorded geometry.
It has **not** been rerun and is **not** portable to another partition layout:

```sh
# Historical geometry only: run only after independent matching-device verification.
tools/edl-venv/bin/python tools/run_edl.py ws 258954 \
  patch/boot_b-magisk-30.2.img --memory=ufs --lun=4 \
  --loader=tools/noteair4c-loader.bin
tools/edl-venv/bin/python tools/run_edl.py rs 258954 24576 \
  /PRIVATE/NEW-READBACK.img --memory=ufs --lun=4 \
  --loader=tools/noteair4c-loader.bin
cmp patch/boot_b-magisk-30.2.img /PRIVATE/NEW-READBACK.img
```

The size/count above is recorded, not an instruction to invent offsets on a new
firmware. Before any recovery write, inspect the actual partition geometry and
current firmware/slot and validate the saved image and loader. Read back and
compare the entire written range before rebooting. Never broaden to whole-LUN
writes, GPT repair, slot A, vbmeta or recovery partitions.

The archived GPT log says all six LUN primary/backup GPT files were dumped.
**They were not.** In the captured
`tools/edl/edlclient/Library/firehose_client.py` `gpt` branch, file writes are
commented out while success lines remain. No GPT payload files are present in
the original backup. Do not list GPT as recoverable, and do not trust CLI success
alone for future backup acquisition. A verified GPT backup workflow remains a gap.

EDL checkout observed commit:
`2f8e89a848afaaef68997fcbcb5b178d958d497b`.
`tools/run_edl.py` selects the bundled libusb backend then runs that checkout.
Python 3.12, pyusb/libusb-package and androguard 4.1.4 are historically recorded.
The full Python environment lock remains missing. Exact-binary loader acquisition
is now reproducible: on2026-09-13 the parent downloaded the pinned upstream raw
URL and verified the same SHA-256 as `tools/noteair4c-loader.bin`.

| Loader provenance field | Verified reproduction value |
| --- | --- |
| Repository | `bkerler/Loaders` |
| Commit | `2fe9ee42e2135c8db5005cb542472eda16d81473` |
| Path | `lenovo_motorola/0000000000000000_bdaf51b59ba21d8a_fhprg.bin` |
| Git blob SHA-1 | `c34319cc5f5ccbed1d5e7ee261d0ef3b148e14ca` |
| Size | 759504 bytes |
| File SHA-256 | `1eadbcd4c5e64d7f7ae6b13f51f548148d53c7163fe960e50840e03daa742e73` |

The pinned raw URL is recorded in [acquisition.json](acquisition.json).
Parent evidence: `notes-drive/research/incoming/loader-provenance.json`.
This establishes an upstream source for the identical known-working binary.
**It does not establish which upstream commit was used during the original
historical root session.** No loader/device operation was repeated by this sidecar.

## AMS null-check overlay

The stock `ActivityManagerService.addPackageDependency(String)` dereferenced a
null ProcessRecord during Magisk RootServerMain startup. `tools/build_ams_fix.py`
changes one branch displacement, at DEX offset `0x2b2ff0`, from `0x33` to `0x3f`,
returning after monitor release; it recomputes DEX SHA-1/Adler32 and checks
unchanged non-DEX JAR entries.

As of the installer continuation on2026-09-13, the builder accepts explicit input
and output paths and has no private backup directory baked in. It verifies the
**known original hash** internally before importing DEX dependencies; it never
accepts a different input merely because its bytecode looks similar:

```sh
ORIGINAL_SERVICES='/PRIVATE/PATH/services.jar'
tools/edl-venv/bin/python tools/build_ams_fix.py \
  --source "$ORIGINAL_SERVICES" \
  --output /PRIVATE/NEW-OUTPUT/boox-ams-fix.zip \
  --patched-jar /PRIVATE/NEW-OUTPUT/services-patched.jar
```

Choose new output paths in an existing directory. Omitting `--patched-jar`
defaults to the ZIP path with `.services.jar` in place of its suffix.
The historical output was `patch/boox-ams-fix-noteair4c-4.2.zip`, module
`boox_ams_fix_na4c_42`, overlaid `system/framework/services.jar`.
The installer validates model and input hash. Do not weaken its guard when it
sees an already-overlaid JAR or different firmware; inspect why it differs.
Builder guards now remain active under Python `-O`. A failed output write can
leave one newly created result; inspect it and choose fresh output paths for a
retry. Existing files are never overwritten or removed.
ZIP timestamps/entry metadata mean a module archive need not match its historical
outer ZIP hash. The exact patched JAR hash and every unsigned module entry's
contents are separately compared; no signing or installation is performed.
The2026-09-13 offline check rebuilt the exact original JAR in memory and confirmed
the historical patched hash plus identical contents for all three unsigned
module entries. No output archive was written and no device was contacted.

Historical installation used Magisk's `--install-module`, then reboot. Missing
`/data/adb/magisk` support assets were restored from original APK resources.
That support-asset repair was not captured in an idempotent installer and should
not be blindly replayed onto a functioning newer Magisk installation.

## Reversible feature rollback

These are architecture-based procedures; post-success disable/re-enable rollback
was not fully exercised historically. First save/close editors, pause connector
automatic publication, and retain queued/draft/recovery state.

Disable only the affected Vector module:

```sh
tools/platform-tools/adb -s "$BOOX_SERIAL" shell \
  '/debug_ramdisk/su -c "/data/adb/modules/zygisk_vector/cli modules disable local.boox.notesdrive"'
# Or, for the AI connector:
tools/platform-tools/adb -s "$BOOX_SERIAL" shell \
  '/debug_ramdisk/su -c "/data/adb/modules/zygisk_vector/cli modules disable local.boox.openai"'
```

Restart the relevant closed native process after Vector applies the configuration.
Re-enable with `modules enable <exact-package>` and verify activation, rather than
uninstalling the companion. Disabled Notes hooks cease interception; inspect native
ONYX sync configuration before assuming which remote service will become active.
Disabling a hook does not retract published Drive revisions or undo committed edits.

If Vector itself is implicated and Android/root ADB works:

```sh
tools/platform-tools/adb -s "$BOOX_SERIAL" shell \
  '/debug_ramdisk/su -c "touch /data/adb/modules/zygisk_vector/disable"'
tools/platform-tools/adb -s "$BOOX_SERIAL" reboot
```

The targeted disable marker is reversible; removing only that marker and rebooting
is the corresponding enable operation after diagnosis. Do not disable the separate
AMS fix casually: the stock Magisk Manager crash may return.

For native apply failures, retain Notes-private `files/boox_drive_apply`, backups,
interrupted files and readbacks. Let the production recovery path finish. Do not
delete journals or overwrite the library with an old whole-device archive.
Uninstalling companions loses app state and can make encrypted backups unusable.
Mac drafts/queues remain in its Application Support; keep the matching Keychain
item. Restoring a Mac app bundle alone is not state recovery.

If Android cannot boot and root ADB is unavailable, use the validated loader and
appropriate saved boot B only after diagnosing firmware/slot. Restoring original
boot B removes that boot image's root modifications; it does not undo app data or
guarantee all modules are safe. Never relock the bootloader as a rollback shortcut.
Full disaster restoration has not been rehearsed.

## OTA procedure: proposed, not executed

1. Save/close apps; pause sync; preserve current private notes, journals, component
   backups and signing identities. Record model/slot/build/module versions and
   verify backup files by reopening/hash checks.
2. **Disable/remove the firmware-specific AMS overlay before the OTA**. Its
   disable marker is `/data/adb/modules/boox_ams_fix_na4c_42/disable`; reboot and
   verify the overlay is absent. A return of the old Manager crash is possible.
3. Disable custom Vector hooks and framework as appropriate for the update plan.
   Follow the device/Magisk version's reviewed OTA procedure. No OTA, inactive-slot
   install or root-preservation method was validated in this work.
4. After update, record the new active slot, firmware, boot image and stock
   services.jar. Do not flash the historical 30.2 B image onto the updated system.
5. Reanalyze AMS and native API/Notes-version compatibility. Build a new guarded
   overlay only if warranted; the old JAR must not be carried over.
6. Reestablish root/framework using matching inputs, run diagnostics and disposable
   AI/Notes/Mac acceptance checks, then resume sync only after preservation checks.

This procedure deliberately ends at new-firmware revalidation. It is not a promise
of OTA compatibility or a firmware-independent root installer.
