#!/usr/bin/env python3
"""Build the exact supported NoteAir4C AMS overlay, without any device operation."""

import argparse
import copy
import hashlib
import io
import json
from pathlib import Path
import struct
import zipfile
import zlib


SUPPORTED_ORIGINAL = "fd45574a43099f3e1abc0bca6a88db3d018d6f183c41d7e0a46f16801ae5b2e6"
SUPPORTED_PATCHED = "e2bad92800231d1ad86179202f42dd5f89d830114a7bc6273d25ce9c2c923c14"
MODULE_PROP = """id=boox_ams_fix_na4c_42
name=BOOX Note Air4C 4.2 Magisk Manager Fix
version=1.0
versionCode=1
author=Local firmware patch; fix by dynamicfire
description=Null-check fix built from this NoteAir4C firmware 4.2-rel_04282 services.jar. Disable before a firmware update.
"""
CUSTOMIZE = f"""#!/system/bin/sh
[ "$(getprop ro.product.model)" = "NoteAir4C" ] || abort "Wrong device"
actual=$(sha256sum /system/framework/services.jar | cut -d ' ' -f 1)
[ "$actual" = "{SUPPORTED_ORIGINAL}" ] || abort "Firmware framework does not match the original backup"
ui_print "- Verified exact original services.jar"
"""


def require(condition, message):
    # Guards remain effective under python -O.
    if not condition:
        raise ValueError(message)


def patch_jar(original_jar):
    require(hashlib.sha256(original_jar).hexdigest() == SUPPORTED_ORIGINAL,
            "Unsupported original services.jar; no output was written")
    # Keep the CLI/import/hash guard usable without optional build dependencies.
    from androguard.core.dex import DEX
    from loguru import logger

    logger.remove()
    with zipfile.ZipFile(io.BytesIO(original_jar)) as jar:
        original_dex = jar.read("classes.dex")
        dex = DEX(original_dex)
        matches = [
            method
            for cls in dex.get_classes()
            if cls.get_name() == "Lcom/android/server/am/ActivityManagerService;"
            for method in cls.get_methods()
            if method.get_name() == "addPackageDependency"
            and method.get_descriptor() == "(Ljava/lang/String;)V"
        ]
        require(len(matches) == 1, "Expected exactly one AMS method")
        method = matches[0]
        instructions = dict(method.get_instructions_idx())
        require(instructions[0x32].get_raw() == bytes.fromhex("38013300"),
                "Original branch differs")
        require(instructions[0x30].get_name() == "monitor-exit", "Monitor release differs")
        require("UpdateWebViewUsedPkgsAction" in instructions[0x98].get_output(),
                "Original fallthrough differs")
        require(instructions[0xb0].get_name() == "return-void", "Return target differs")
        patch_offset = method.get_code_off() + 16 + 0x32 + 2
        require(patch_offset == 0x2b2ff0, "Patch offset differs")
        require(original_dex[patch_offset:patch_offset + 2] == b"\x33\x00",
                "Original displacement differs")
        patched_dex = bytearray(original_dex)
        struct.pack_into("<h", patched_dex, patch_offset, (0xb0 - 0x32) // 2)
        patched_dex[12:32] = hashlib.sha1(patched_dex[32:]).digest()
        struct.pack_into("<I", patched_dex, 8, zlib.adler32(patched_dex[12:]) & 0xffffffff)
        differences = [i for i, (a, b) in enumerate(zip(original_dex, patched_dex)) if a != b]
        require(all(8 <= i < 32 or i == patch_offset for i in differences),
                "Unexpected DEX modification")
        checked = DEX(bytes(patched_dex))
        checked_method = next(
            m for c in checked.get_classes()
            if c.get_name() == "Lcom/android/server/am/ActivityManagerService;"
            for m in c.get_methods() if m.get_name() == "addPackageDependency"
        )
        require(dict(checked_method.get_instructions_idx())[0x32].get_raw()
                == bytes.fromhex("38013f00"), "Patched branch proof failed")
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w") as result:
            for entry in jar.infolist():
                result.writestr(copy.copy(entry), patched_dex if entry.filename == "classes.dex"
                                else jar.read(entry))
        patched_jar = buffer.getvalue()
        with zipfile.ZipFile(io.BytesIO(patched_jar)) as after:
            require(jar.namelist() == after.namelist(), "JAR entries changed")
            require(all(jar.read(n) == after.read(n) for n in jar.namelist()
                        if n != "classes.dex"), "Non-DEX JAR content changed")
    require(hashlib.sha256(patched_jar).hexdigest() == SUPPORTED_PATCHED,
            "Patched JAR hash differs from the verified historical result")
    return patched_jar


def module_bytes(patched_jar):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as module:
        module.writestr("module.prop", MODULE_PROP)
        module.writestr("customize.sh", CUSTOMIZE)
        module.writestr("system/framework/services.jar", patched_jar)
    return buffer.getvalue()


def build(source, output, patched_output):
    paths = [Path(p) for p in (source, output, patched_output)]
    source, output, patched_output = paths
    require(len({p.resolve() for p in paths}) == 3, "Input and output paths must be distinct")
    require(not output.exists() and not output.is_symlink()
            and not patched_output.exists() and not patched_output.is_symlink(),
            "Outputs already exist; choose new paths")
    patched = patch_jar(source.read_bytes())
    module = module_bytes(patched)
    # Never overwrite an archive/JAR or create unrequested parent directories.
    for path, data in ((patched_output, patched), (output, module)):
        with path.open("xb") as stream:
            stream.write(data)
    return {
        "original_jar_sha256": SUPPORTED_ORIGINAL,
        "patched_jar_sha256": hashlib.sha256(patched).hexdigest(),
        "module_sha256": hashlib.sha256(module).hexdigest(),
        "dex_branch_offset": "0x2b2ff0",
        "module_signed": False,
        "device_operations": False,
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, type=Path, help="Exact original services.jar")
    parser.add_argument("--output", required=True, type=Path, help="New unsigned module ZIP")
    parser.add_argument("--patched-jar", type=Path, help="New JAR; default OUTPUT.services.jar")
    args = parser.parse_args(argv)
    try:
        result = build(args.source, args.output,
                       args.patched_jar or args.output.with_suffix(".services.jar"))
    except ValueError as error:
        parser.exit(1, str(error) + "\n")
    except (OSError, ImportError, zipfile.BadZipFile):
        parser.exit(1, "Build input/dependency/output unavailable; no device operation performed\n")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
