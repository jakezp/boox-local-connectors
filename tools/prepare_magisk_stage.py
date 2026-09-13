#!/usr/bin/env python3
"""Prepare private, inert Magisk 30.2 inputs on the host; never run a patcher."""

import argparse
import hashlib
from io import BytesIO
import json
import os
from pathlib import Path
import stat
import sys
import zipfile


APK_SHA256 = "123093f51eeb1aa459ac188a22ac3e775e8243293d48df2ceda9ca9dad22d2d6"
APK_SIZE = 12339254
BOOT_SHA256 = "765d41d770c9fcccb812ebef9ce1eb56f24e799a2be97304c960b5b73f7bf0de"
BOOT_SIZE = 100663296
HISTORICAL_PATCH_SHA256 = "67563496cd465eb44c12e8f5dd3997d94717dcfd89da92be579cace8ef50cd3e"
PATCH_FLAGS = {
    "KEEPVERITY": "true",
    "KEEPFORCEENCRYPT": "true",
    "PATCHVBMETAFLAG": "false",
    "RECOVERYMODE": "false",
}

# Inspected installed APK, not assumed upstream release paths. All 15 entries
# were compared byte-for-byte with the retained historical magisk-env directory.
# (APK member, destination, SHA-256, uncompressed bytes)
ASSETS = (
    ("assets/addon.d.sh", "addon.d.sh", "9a61e919b71b73a4ffbb41c00e6a9631c09de289b589b77f421ef5f7a43a55d2", 4034),
    ("assets/app_functions.sh", "app_functions.sh", "ae41623242e8fb13861fc4d0ecc56f817f98bd6944b02fc7eea0fe0fbf38bb8a", 5455),
    ("assets/boot_patch.sh", "boot_patch.sh", "47a4ca63f19460a24ff1cc0976a1173e5640fd103cfe87cf2d5dbb53285f4782", 6943),
    ("assets/bootctl", "bootctl", "36b977ecf13424091f3c8c6bb47e98bd318d7908e2070e417efe81a6460cdef6", 154248),
    ("assets/main.jar", "main.jar", "ae7fdb8b70bd2c1cc2c27043e985f8dc5cfff2cb9142a1576dcdbc889763ef7b", 3656),
    ("assets/module_installer.sh", "module_installer.sh", "bcf4b1d9913f3af17755569c853e0b5a75b8005f6a18eb3f86dadcc0e968c29d", 612),
    ("assets/stub.apk", "stub.apk", "b80f0fd751f39633c10b47822145a1adc5f00ed5c94c6379d7031b8fd0789599", 33149),
    ("assets/uninstaller.sh", "uninstaller.sh", "74cabed92170d5871eb39d66355deee984c5bd2a3ce4d7b98c069c8e39144109", 4762),
    ("assets/util_functions.sh", "util_functions.sh", "28066cafa664758f9c29294db138e4443734c035466bcadc934feaf9af78086b", 20089),
    ("lib/arm64-v8a/libbusybox.so", "busybox", "4d60ab3f5a59ebb2ca863f2f514e6924401b581e9b64f602665c008177626651", 1710600),
    ("lib/arm64-v8a/libinit-ld.so", "init-ld", "13dce50183a260840f3dca8367a01b09d9056d494f1968766baf604a9ad28b23", 5112),
    ("lib/arm64-v8a/libmagisk.so", "magisk", "613c2e7ae2d4de04b12ddb4853bf983ce34117b45617aa8c03a7dfd7d66c4ecb", 371488),
    ("lib/arm64-v8a/libmagiskboot.so", "magiskboot", "4eddca71045c9f35df0a8b1dbf847c43a363937344514f95528681892747e671", 884120),
    ("lib/arm64-v8a/libmagiskinit.so", "magiskinit", "44d86e9fed2aae629f3be1a150fe47571395b753482fc5fdc80abea54c4295c8", 210808),
    ("lib/arm64-v8a/libmagiskpolicy.so", "magiskpolicy", "237fb976dc22625c71e5b61b8ae899ae4bf0295395e3fce3f88b0dc79b79e141", 358000),
)


class StageError(Exception):
    """A failed input, archive, or destination safety gate."""


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def read_input(path, expected_size, expected_hash, label):
    """Read once: the verified snapshot is also the only data we later copy."""
    path = Path(path)
    if path.is_symlink():
        raise StageError(f"{label}: symbolic-link inputs are not accepted")
    flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_NONBLOCK", 0)
    with os.fdopen(os.open(path, flags), "rb") as source:
        metadata = os.fstat(source.fileno())
        if not stat.S_ISREG(metadata.st_mode):
            raise StageError(f"{label}: input must be a regular file")
        if metadata.st_size != expected_size:
            raise StageError(f"{label}: size does not match the pinned original")
        data = source.read(expected_size + 1)
    if len(data) != expected_size or sha256(data) != expected_hash:
        raise StageError(f"{label}: SHA-256 does not match the pinned original")
    return data


def validate_zip(archive):
    """Check even unused members; never apply ZIP paths or permission bits."""
    entries = {}
    canonical_names = set()
    folded_names = set()
    required_names = {member.casefold() for member, _, _, _ in ASSETS}
    total = 0
    for info in archive.infolist():
        name = info.orig_filename
        parts = (name[:-1] if name.endswith("/") else name).split("/")
        if (
            not name
            or name != info.filename
            or name.startswith("/")
            or "\\" in name
            or ":" in name
            or any(ord(char) < 32 or ord(char) == 127 for char in name)
            or any(part in ("", ".", "..") for part in parts)
        ):
            raise StageError("APK: unsafe ZIP member path")
        canonical = name.rstrip("/")
        folded = canonical.casefold()
        if canonical in canonical_names or (folded in required_names and folded in folded_names):
            raise StageError("APK: duplicate or ambiguous ZIP member")
        canonical_names.add(canonical)
        folded_names.add(folded)
        mode = info.external_attr >> 16
        kind = stat.S_IFMT(mode)
        allowed_kind = stat.S_IFDIR if info.is_dir() else stat.S_IFREG
        if kind not in (0, allowed_kind):
            raise StageError("APK: ZIP links and special files are forbidden")
        if info.flag_bits & 1:
            raise StageError("APK: encrypted ZIP members are forbidden")
        if info.compress_type not in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED):
            raise StageError("APK: unsupported ZIP compression")
        total += info.file_size
        if info.file_size > 32 * 1024 * 1024 or total > 128 * 1024 * 1024:
            raise StageError("APK: ZIP expansion limit exceeded")
        entries[name] = info
    regular_names = {name.casefold() for name, info in entries.items() if not info.is_dir()}
    for name in entries:
        parts = name.rstrip("/").split("/")
        for end in range(1, len(parts)):
            if "/".join(parts[:end]).casefold() in regular_names:
                raise StageError("APK: ZIP file/directory collision")
    return entries


def read_assets(apk):
    result = {}
    with zipfile.ZipFile(BytesIO(apk)) as archive:
        entries = validate_zip(archive)
        for member, destination, expected_hash, expected_size in ASSETS:
            info = entries.get(member)
            if info is None or info.is_dir() or info.file_size != expected_size:
                raise StageError("APK: missing or wrong-sized required asset")
            data = archive.read(info)
            if len(data) != expected_size or sha256(data) != expected_hash:
                raise StageError("APK: required asset SHA-256 mismatch")
            result[f"payload/{destination}"] = data
    return result


def device_instructions():
    flags = " ".join(f"{key}={value}" for key, value in PATCH_FLAGS.items())
    return f"""# Device-local patch review: prepared, never executed by the host tool

This directory is a private staging input, not a patched boot image.
Only the matching historical NoteAir4C firmware, original slot B, and ARM64
Magisk 30.2 input are covered. Independently verify the device/firmware/slot
and backups before any operator-controlled transfer or device-local execution.
Do not use this on the currently working later Magisk installation.

The following is a reconstructed command, not a preserved historical transcript
or a newly tested patch. Use a fresh private working copy of this complete stage
on booted Android. Preserve the host stage and its manifest. There must be no
extra files, symlinks, or previous patch outputs in that device working copy.

In a device-local shell, replace the placeholder directory, then review/run:

```sh
cd '<DEVICE-STAGE>' || exit 1
/system/bin/sha256sum -c SHA256SUMS || exit 1
cd payload || exit 1
[ "$(getprop ro.product.model)" = NoteAir4C ] || exit 1
[ "$(getprop ro.product.cpu.abi)" = arm64-v8a ] || exit 1
[ ! -e new-boot.img ] && [ ! -L new-boot.img ] || exit 1
[ ! -e patch-tmp ] && [ ! -L patch-tmp ] || exit 1
chmod 700 busybox || exit 1
./busybox env -i PATH=/system/bin:/system/xbin ASH_STANDALONE=1 \\
  BOOTMODE=true {flags} \\
  LEGACYSAR=false ./busybox ash -c '
    . ./util_functions.sh
    TMPDIR="$PWD/patch-tmp"
    mkdir -m 700 "$TMPDIR" || exit 1
    api_level_arch_detect
    [ "$ABI" = arm64-v8a ] || exit 1
    SOURCEDMODE=true
    . ./boot_patch.sh boot_b.img
  ' || exit 1
/system/bin/sha256sum new-boot.img
/system/bin/wc -c < new-boot.img
```

The clean environment prevents inherited patch configuration. Setting TMPDIR
after sourcing util_functions.sh confines its abort cleanup to this new work
directory instead of the script's /dev/tmp default. SOURCEDMODE uses the utility
functions and architecture detection already loaded above. The four recorded
flags are unchanged; LEGACYSAR=false is the patcher's default. These wrapper
choices are newly reviewed, not evidence of the historical invocation.

The inspected boot_patch.sh modifies files in its working directory (including
recursive chmod) and repacks a regular-file boot_b.img to new-boot.img. It calls
magisk --preinit-device in boot mode; that device-derived value and the original
shell environment were not preserved. A repeat hash is therefore a comparison
to perform, never a promised result. Do not call install_magisk, direct_install,
fix_env, module_installer.sh, addon.d.sh, or uninstaller.sh as part of this patch.

Historical comparison target, independently rehashed from the saved patch and
saved flash readback: {HISTORICAL_PATCH_SHA256}
Historical size: {BOOT_SIZE} bytes.

Any newly produced image still needs its own hash, size, original-kernel/DTB
comparison and reviewed matching-device recovery plan. A mismatch is a stop
for investigation, not permission to weaken the input guard. This stage does
not flash, establish root, verify a new patch, or verify a new device readback.
Never edit manifest.json to turn prepared_not_patched into an acceptance claim.
Keep a separate evidence record for any subsequent device work.
""".encode("utf-8")


def new_output_path(path):
    path = Path(path).absolute()
    if ".." in path.parts:
        raise StageError("output: parent traversal is not accepted")
    if path.exists() or path.is_symlink():
        raise StageError("output: destination already exists")
    # Resolve existing parent aliases once (e.g. macOS /var), then create only
    # the requested final directory, exclusively. Never create missing parents.
    parent = path.parent.resolve(strict=True)
    if not parent.is_dir():
        raise StageError("output: parent must be an existing directory")
    return parent / path.name


def write_new(path, data):
    with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "wb") as target:
        target.write(data)


def prepare_stage(apk_path, boot_path, output_path):
    output = new_output_path(output_path)
    apk = read_input(apk_path, APK_SIZE, APK_SHA256, "APK")
    boot = read_input(boot_path, BOOT_SIZE, BOOT_SHA256, "original boot B")
    files = read_assets(apk)
    files["payload/boot_b.img"] = boot
    files["DEVICE-PATCH.md"] = device_instructions()
    checksums = "".join(f"{sha256(data)}  {name}\n" for name, data in sorted(files.items()))
    files["SHA256SUMS"] = checksums.encode("ascii")
    sources = {f"payload/{dest}": member for member, dest, _, _ in ASSETS}
    sources["payload/boot_b.img"] = "original_boot_b"
    manifest = {
        "schema": 1,
        "status": "prepared_not_patched",
        "abi": "arm64-v8a",
        "magisk_version": "30.2",
        "firmware": "2026-04-28_17-50_4.2-rel_04282_555977efe",
        "generator_sha256": sha256(Path(__file__).read_bytes()),
        "inputs": {
            "installed_apk": {"sha256": APK_SHA256, "bytes": APK_SIZE},
            "original_boot_b": {"sha256": BOOT_SHA256, "bytes": BOOT_SIZE},
        },
        "patch_flags": PATCH_FLAGS,
        "historical_comparison": {
            "patched_boot_sha256": HISTORICAL_PATCH_SHA256,
            "bytes": BOOT_SIZE,
            "new_patch_verified": False,
        },
        "files": [
            {
                "path": name,
                "sha256": sha256(data),
                "bytes": len(data),
                "source": sources.get(name, "generated"),
            }
            for name, data in sorted(files.items())
        ],
    }
    # All input/archive validation precedes any output creation. A write failure
    # leaves only this newly created partial directory for operator inspection.
    output.mkdir(mode=0o700)
    (output / "payload").mkdir(mode=0o700)
    for name, data in sorted(files.items()):
        write_new(output / name, data)
    write_new(output / "manifest.json", (json.dumps(manifest, indent=2, sort_keys=True) + "\n").encode())
    return manifest


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", required=True, type=Path, help="exact original installed Magisk 30.2 APK")
    parser.add_argument("--original-boot", required=True, type=Path, help="exact original boot B regular file")
    parser.add_argument("--output", required=True, type=Path, help="new private host directory; parent must exist")
    args = parser.parse_args(argv)
    try:
        prepare_stage(args.apk, args.original_boot, args.output)
    except StageError as error:
        print(f"Staging refused: {error}", file=sys.stderr)
        return 1
    except (OSError, ValueError, RuntimeError, EOFError, zipfile.BadZipFile):
        # Raw exceptions can include operator-supplied private paths.
        print("Staging failed: input/archive/filesystem error. Inspect any new partial output; use a fresh path.", file=sys.stderr)
        return 1
    print("Prepared private stage: manifest.json, SHA256SUMS, DEVICE-PATCH.md and payload/. No patch executed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
