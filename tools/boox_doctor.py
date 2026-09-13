#!/usr/bin/env python3
"""Read-only BOOX setup inventory. No device access unless --serial is supplied.

Uses only Python's standard library. Root queries require --root-checks and an
existing shell root authorization. Never reads app data, grants, keys or logs.
ADB and Magisk may perform their own connection/authorization bookkeeping.
"""

import argparse
import hashlib
import json
from pathlib import Path
import re
import shlex
import subprocess


ROOT = Path(__file__).resolve().parents[1]
FIRMWARE = "2026-04-28_17-50_4.2-rel_04282_555977efe"
ORIGINAL_FRAMEWORK = "fd45574a43099f3e1abc0bca6a88db3d018d6f183c41d7e0a46f16801ae5b2e6"
PATCHED_FRAMEWORK = "e2bad92800231d1ad86179202f42dd5f89d830114a7bc6273d25ce9c2c923c14"
VECTOR = "/data/adb/modules/zygisk_vector/cli"
PACKAGES = (
    "com.topjohnwu.magisk", "com.onyx.aiassistant", "com.onyx.kreader",
    "com.onyx.android.note", "com.onyx", "local.boox.openai", "local.boox.notesdrive",
)
SCOPES = {
    "local.boox.openai": ("com.onyx.aiassistant/0", "com.onyx.kreader/0"),
    "local.boox.notesdrive": ("com.onyx.android.note/0", "com.onyx/0", "com.onyx.kreader/0"),
}
PROPERTIES = {
    "model": ("ro.product.model", r"[A-Za-z0-9 ._-]{1,60}"),
    "firmware": ("ro.build.display.id", r"[A-Za-z0-9._ -]{1,100}"),
    "android": ("ro.build.version.release", r"[0-9.]{1,16}"),
    "sdk": ("ro.build.version.sdk", r"[0-9]{1,3}"),
    "security_patch": ("ro.build.version.security_patch", r"\d{4}-\d{2}-\d{2}"),
    "slot": ("ro.boot.slot_suffix", r"_[ab]"),
    "bootloader_locked": ("ro.boot.flash.locked", r"[01]"),
    "verified_boot": ("ro.boot.verifiedbootstate", r"(?:green|yellow|orange|red)"),
    "boot_completed": ("sys.boot_completed", r"[01]"),
}


def exact_value(text, pattern):
    value = text.strip()
    return value if re.fullmatch(pattern, value) else None


def parse_package(text):
    """Only emit package version metadata, never a whole dumpsys response."""
    code = re.search(r"^\s*versionCode=(\d+)\b", text, re.MULTILINE)
    version = re.search(r"^\s*versionName=([0-9][A-Za-z0-9._+-]{0,59})\s*$",
                        text, re.MULTILINE)
    return {
        "version_code": int(code.group(1)) if code else None,
        "version_name": version.group(1) if version else None,
    }


def parse_sha256(text):
    match = re.fullmatch(r"([a-fA-F0-9]{64})(?:[ \t]+[^\r\n]+)?\s*", text.strip())
    return match.group(1).lower() if match else None


def parse_apk_path(text):
    # Single base APK only. Reject split packages and shell metacharacters.
    match = re.fullmatch(
        r"package:(/data/app/[A-Za-z0-9_./=+~-]+/base\.apk)\s*", text.strip())
    if not match or ".." in match.group(1).split("/"):
        return None
    return match.group(1)


def parse_scope_rows(text):
    """Return known CLI rows, or None for help/errors/ambiguous output.

    Vector2.2 uses a two-column table. Also accept plain slash-form lists used by
    historical synthetic fixtures. A valid empty table returns an empty set.
    """
    lines = [line.strip() for line in text.splitlines() if line.strip()]
    if not lines:
        return None
    table = re.fullmatch(r"APP_PACKAGE\s+USER_ID", lines[0]) is not None
    if table:
        lines = lines[1:]
    rows = set()
    for line in lines:
        if table and set(line) == {"-"}:
            continue
        pattern = r"([a-z][A-Za-z0-9_.]*)\s+(\d+)" if table else r"([a-z][A-Za-z0-9_.]*)/(\d+)"
        match = re.fullmatch(pattern, line)
        if match is None:
            return None
        row = match.group(1) + "/" + match.group(2)
        if row in rows:
            return None
        rows.add(row)
    return rows


def parse_scope(text, expected):
    found = parse_scope_rows(text)
    parsed = found is not None
    found = found or set()
    return {
        "expected": list(expected),
        "found_expected": sorted(found.intersection(expected)),
        "unexpected_count": len(found.difference(expected)),
        "parse_valid": parsed,
        "matches_expected": parsed and bool(found) and found == set(expected),
    }


def parse_module_version(text):
    values = {}
    for line in text.splitlines():
        key, separator, value = line.partition("=")
        if separator and key == "version":
            match = re.fullmatch(r"v?(\d+(?:\.\d+){0,3})(?: \([A-Za-z0-9._-]+\))?", value)
            values["version"] = match.group(1) if match else None
        elif separator and key == "versionCode":
            values["version_code"] = exact_value(value, r"\d+")
    return {"version": values.get("version"), "version_code": values.get("version_code")}


def local_inventory(workspace):
    required = {
        "adb": "tools/platform-tools/adb",
        "android_platform_35": "tools/android-sdk/android-35/android.jar",
        "aapt": "tools/android-sdk/android-15/aapt",
        "d8": "tools/android-sdk/android-15/d8",
        "zipalign": "tools/android-sdk/android-15/zipalign",
        "apksigner": "tools/android-sdk/android-15/apksigner",
        "gradle_8_11_1": "tools/gradle-8.11.1/bin/gradle",
        "notes_connection_fixture": "notes-drive/tests/artifacts/Target-after.note",
        "mac_revision_fixture": "notes-drive/tests/artifacts/B2.note",
    }
    files = {name: (workspace / path).is_file() for name, path in required.items()}
    files["fixed_jdk_17"] = Path(
        "/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home/bin/javac"
    ).is_file()
    identities = {}
    for name, relative in {
        "openai": "openai-adapter/build/boox-openai-setup.apk",
        "notesdrive": "notes-drive/android/app/build/outputs/apk/release/app-release.apk",
    }.items():
        path = workspace / relative
        digest = None
        if path.is_file():
            with path.open("rb") as stream:
                hasher = hashlib.sha256()
                for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                    hasher.update(chunk)
                digest = hasher.hexdigest()
        identities[name] = digest
    return {"dependencies_present": files, "local_apk_sha256": identities}


class Reader:
    """Allowlisted callers supply commands; no command or stderr is reported."""

    def __init__(self, adb, serial, timeout=10, run=subprocess.run):
        self.adb = str(adb)
        self.serial = serial
        self.timeout = timeout
        self.run = run
        self.failures = []

    def query(self, label, *args, root=False):
        command = shlex.join(args)
        if root:
            command = shlex.join(("/debug_ramdisk/su", "-c", command))
        try:
            result = self.run(
                [self.adb, "-s", self.serial, "shell", command],
                stdin=subprocess.DEVNULL, capture_output=True, text=True,
                timeout=self.timeout, check=False,
            )
        except (OSError, subprocess.TimeoutExpired):
            self.failures.append({"check": label, "reason": "unavailable_or_timeout"})
            return ""
        if result.returncode:
            self.failures.append({"check": label, "reason": "command_failed"})
            return ""
        return result.stdout


def device_inventory(reader, root_checks, expected_hashes):
    device = {}
    for name, (prop, pattern) in PROPERTIES.items():
        device[name] = exact_value(reader.query(name, "getprop", prop), pattern)
    device["historical_baseline"] = {
        "model_matches": device["model"] == "NoteAir4C",
        "firmware_matches": device["firmware"] == FIRMWARE,
        "slot_matches": device["slot"] == "_b",
    }
    framework = parse_sha256(reader.query(
        "framework", "sha256sum", "/system/framework/services.jar"))
    framework_states = {ORIGINAL_FRAMEWORK: "original", PATCHED_FRAMEWORK: "ams_fix",
                        None: "unknown"}
    device["framework"] = {"sha256": framework,
                           "state": framework_states.get(framework, "different")}
    packages = {}
    for package in PACKAGES:
        packages[package] = parse_package(reader.query(
            "package_" + package, "dumpsys", "package", package))
        if package in SCOPES:
            path = parse_apk_path(reader.query("apk_path_" + package, "pm", "path", package))
            digest = parse_sha256(reader.query(
                "apk_hash_" + package, "sha256sum", path)) if path else None
            expected = expected_hashes.get(package)
            packages[package].update({
                "apk_sha256": digest,
                "expected_sha256": expected,
                "matches_expected_apk": digest == expected if digest and expected else None,
                "signing_certificate_verified": False,
            })
    device["packages"] = packages
    device["root"] = {"queried": root_checks, "uid_zero": None}
    if root_checks:
        uid = exact_value(reader.query("root_uid", "id", "-u", root=True), r"\d+")
        device["root"]["uid_zero"] = uid == "0" if uid is not None else None
        if uid == "0":
            device["root"]["magisk_version"] = exact_value(reader.query(
                "magisk_version", "/debug_ramdisk/magisk", "-v", root=True),
                r"[0-9][A-Za-z0-9._():+-]{0,59}")
            device["root"]["magisk_version_code"] = exact_value(reader.query(
                "magisk_version_code", "/debug_ramdisk/magisk", "-V", root=True), r"\d+")
            # Query only public module metadata; never Magisk's grant database.
            modules = {}
            for module in ("zygisk_vector", "boox_ams_fix_na4c_42"):
                base = "/data/adb/modules/" + module
                version = reader.query("module_version_" + module, "grep",
                                       "-E", "^version(Code)?=", base + "/module.prop", root=True)
                modules[module] = parse_module_version(version)
            device["root"]["modules"] = modules
            device["root"]["scopes"] = {
                package: parse_scope(reader.query("scope_" + package, VECTOR,
                                                  "scope", "ls", package, root=True), expected)
                for package, expected in SCOPES.items()
            }
    # Never infer enabled/loaded hooks from configured scopes or APK identity.
    device["hook_activation_verified"] = False
    device["oauth_or_sync_verified"] = False
    device["query_failures"] = reader.failures
    return device


def redact_selected_serial(value, serial):
    """Suppress even a device response unexpectedly echoing the chosen serial."""
    if isinstance(value, str):
        return value.replace(serial, "<SERIAL>") if serial else value
    if isinstance(value, list):
        return [redact_selected_serial(item, serial) for item in value]
    if isinstance(value, dict):
        return {key: redact_selected_serial(item, serial) for key, item in value.items()}
    return value


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--workspace", type=Path, default=ROOT)
    parser.add_argument("--serial", help="Explicit ADB target; omitted means host-only")
    parser.add_argument("--adb", type=Path, help="ADB executable; defaults to workspace tools")
    parser.add_argument("--root-checks", action="store_true",
                        help="Read Magisk/module metadata using existing shell root access")
    parser.add_argument("--timeout", type=float, default=10)
    args = parser.parse_args(argv)
    if args.root_checks and not args.serial:
        parser.error("--root-checks requires --serial")
    if not 0 < args.timeout <= 60:
        parser.error("--timeout must be greater than zero and at most 60")
    report = {
        "schema": 1,
        "mode": "device_read_only" if args.serial else "host_only",
        "host": local_inventory(args.workspace),
        "limits": [
            "No credentials, account identifiers, private preferences or notebook data read.",
            "APK hashes identify bytes, not signing certificates or running hook generation.",
            "No OAuth, Drive, native sync, backup recoverability or OTA success verification.",
            "Root checks may prompt in Magisk; no grant-setting commands or approval UI actions are issued.",
        ],
    }
    if args.serial:
        reader = Reader(args.adb or args.workspace / "tools/platform-tools/adb",
                        args.serial, args.timeout)
        local = report["host"]["local_apk_sha256"]
        report["device"] = device_inventory(reader, args.root_checks, {
            "local.boox.openai": local["openai"],
            "local.boox.notesdrive": local["notesdrive"],
        })
    print(json.dumps(redact_selected_serial(report, args.serial), indent=2, sort_keys=True))
    # An inventory is not an installation certificate. Only failed queries set 1.
    return 1 if args.serial and report["device"]["query_failures"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
