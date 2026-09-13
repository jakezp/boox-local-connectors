#!/usr/bin/env python3
"""Plan by default; explicitly preflight/apply companion APKs on an already-rooted BOOX.

No root installation, flashing, Magisk module installation, OAuth or backup creation.
Apply requires a reviewed plan, verified backup receipt and saved/closed editors.
"""

import argparse
from datetime import datetime, timezone, timedelta
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys
import tempfile

import boox_doctor as doctor


ROOT = Path(__file__).resolve().parents[1]
APKS = {
    "openai": "openai-adapter/build/boox-openai-setup.apk",
    "notesdrive": "notes-drive/android/app/build/outputs/apk/release/app-release.apk",
}
REGISTRATION = "notes-drive/android/registration.json"
HELPER = "tools/install_notes_drive.py"


class GuardError(Exception):
    """Messages contain fixed check names, never raw command output or paths."""


def require(condition, message):
    if not condition:
        raise GuardError(message)


def file_hash(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_json(path):
    # Receipts/plans/registration only; never private configuration or grant JSON.
    require(Path(path).stat().st_size <= 1024 * 1024, "JSON input exceeds limit")
    result = json.loads(Path(path).read_text())
    require(isinstance(result, dict), "JSON input must be an object")
    return result


def digest_json(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


class Commands:
    def __init__(self, run=None):
        self.run = run or subprocess.run

    def call(self, label, args, timeout=30):
        try:
            result = self.run([str(arg) for arg in args], stdin=subprocess.DEVNULL,
                              capture_output=True, text=True, timeout=timeout, check=False)
        except (OSError, subprocess.TimeoutExpired):
            raise GuardError(label + ": unavailable or timed out") from None
        require(result.returncode == 0, label + ": command failed")
        return result.stdout


def apk_identity(root, path, commands):
    tools = root / "tools/android-sdk/android-15"
    before = file_hash(path)
    certs = commands.call("APK signature", [tools / "apksigner", "verify", "--verbose",
                                           "--print-certs", path])
    sha256 = re.findall(r"^Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]{64})$",
                        certs, re.MULTILINE)
    sha1 = re.findall(r"^Signer #1 certificate SHA-1 digest: ([0-9a-fA-F]{40})$",
                     certs, re.MULTILINE)
    require(len(sha256) == len(sha1) == 1 and not re.search(r"Signer #[2-9]", certs),
            "Exactly one verified APK signer is required")
    badging = commands.call("APK package", [tools / "aapt", "dump", "badging", path])
    match = re.search(r"^package: name='([a-zA-Z0-9_.]+)' versionCode='(\d+)' "
                      r"versionName='([a-zA-Z0-9_.+-]+)'", badging, re.MULTILINE)
    require(match is not None, "APK package metadata unavailable")
    require(file_hash(path) == before, "APK changed while checking its signature/metadata")
    return {
        "package": match.group(1), "version_code": int(match.group(2)),
        "version_name": match.group(3), "apk_sha256": before,
        "certificate_sha256": sha256[0].lower(), "certificate_sha1": sha1[0].lower(),
    }


def source_digest(root):
    digest = hashlib.sha256()
    base = root / "notes-drive/android"
    paths = sorted((base / "app/src/main/java").rglob("*.java"))
    require(bool(paths), "Notes Java source missing")
    for path in paths:
        digest.update(path.relative_to(base).as_posix().encode())
        digest.update(path.read_bytes())
    return digest.hexdigest()[:16]


def make_plan(root, components, first_install, commands):
    require(bool(components) and set(components) <= set(APKS), "Select supported components")
    require(set(first_install) <= set(components), "First-install must be a selected component")
    artifacts = []
    inputs = {}
    for name in sorted(set(components)):
        path = root / APKS[name]
        identity = apk_identity(root, path, commands)
        package = "local.boox." + name
        require(identity["package"] == package, "Unexpected APK package")
        identity.update(component=name, path=APKS[name],
                        mode="first_install" if name in first_install else "update",
                        scopes=list(doctor.SCOPES[package]))
        if name == "notesdrive":
            registration_hash = file_hash(root / REGISTRATION)
            registration = load_json(root / REGISTRATION)
            require(file_hash(root / REGISTRATION) == registration_hash,
                    "Notes registration changed while reading")
            require(registration.get("package_name") == package, "Notes registration package differs")
            require(registration.get("apk_sha256") == identity["apk_sha256"], "Notes APK differs from registration")
            signing_sha1 = registration.get("signing_certificate_sha1")
            require(isinstance(signing_sha1, str), "Notes registration certificate missing")
            require(signing_sha1.replace(":", "").lower()
                    == identity["certificate_sha1"], "Notes registration certificate differs")
            require(registration.get("scope") == "https://www.googleapis.com/auth/drive.file",
                    "Notes Google scope differs")
            require(registration.get("version") == identity["version_name"] == "0.4",
                    "Delegated helper supports Notes adapter v0.4 only")
            hook = registration.get("hook_build", "")
            require(re.fullmatch(r"[0-9a-f]{16}", hook) is not None
                    and hook == source_digest(root), "Notes source changed since build")
            identity["hook_build"] = hook
            inputs[REGISTRATION] = registration_hash
            inputs[HELPER] = file_hash(root / HELPER)
        artifacts.append(identity)
    # Pin orchestration and shared parsers too; changing code invalidates review.
    for relative in ("tools/boox_setup.py", "tools/boox_doctor.py"):
        inputs[relative] = file_hash(root / relative)
    actions = []
    for item in artifacts:
        name = item["component"]
        if item["mode"] == "first_install":
            actions += [name + ":install-first-apk", name + ":set-exact-scope",
                        name + ":enable-module"]
        if name == "notesdrive":
            actions.append("notesdrive:delegate-verified-installer")
        elif item["mode"] == "update":
            actions.append("openai:install-update")
        if name == "openai":
            actions.append("openai:stop-saved-native-processes")
        actions.append(name + ":verify-installed-identity-and-scope")
    plan = {
        "schema": 1, "artifacts": artifacts, "input_sha256": inputs, "actions": actions,
        "baseline": {"model": "NoteAir4C", "firmware": doctor.FIRMWARE,
                     "sdk": "33", "slot": "_b", "notes_version_code": 45326,
                     "magisk_codes": [30200, 30700], "vector_code": 3080,
                     "framework_sha256": doctor.PATCHED_FRAMEWORK},
        "requirements": [
            "Already-rooted baseline with working Vector 2.2 and AMS overlay",
            "Explicit selected components and reviewed unchanged plan",
            "Device-bound backup receipt at most 24 hours old; files hash-verified",
            "All native Notes/Assistant/NeoReader work saved and editors closed",
            "First Notes install: operator confirms matching Google registration",
            "No concurrent device edits, builds, configuration changes or other installers",
        ],
        "not_performed": ["root", "flash", "OTA", "backup_creation", "credential_access",
                          "OAuth", "Mac_setup", "OpenAI_loaded_hook_verification", "sync_acceptance"],
    }
    plan["plan_id"] = digest_json(plan)
    return plan


def verify_reviewed_plan(plan, path):
    reviewed = load_json(path)
    require(reviewed == plan, "Reviewed plan differs; rebuild/review before continuing")


def verify_backups(receipt_path, serial, plan, now=None):
    receipt = load_json(receipt_path)
    require(receipt.get("schema") == 1, "Unsupported backup receipt schema")
    require(receipt.get("device_serial_sha256") == hashlib.sha256(serial.encode()).hexdigest(),
            "Backup receipt is for another device")
    require(receipt.get("model") == "NoteAir4C" and receipt.get("firmware") == doctor.FIRMWARE,
            "Backup receipt firmware/model differs")
    require(isinstance(receipt.get("created_at"), str), "Invalid backup timestamp")
    try:
        stamp = datetime.fromisoformat(receipt["created_at"].replace("Z", "+00:00"))
        require(stamp.utcoffset() is not None, "Backup timestamp needs timezone")
        age = (now or datetime.now(timezone.utc)) - stamp
    except (KeyError, ValueError, TypeError):
        raise GuardError("Invalid backup timestamp") from None
    require(timedelta(0) <= age <= timedelta(hours=24), "Backup receipt is stale or future-dated")
    attestations = receipt.get("attestations", {})
    require(isinstance(attestations, dict) and all(attestations.get(key) is True for key in
            ("editors_closed", "restore_procedure_reviewed", "signing_keys_preserved",
             "keystore_limit_understood")), "Backup attestations incomplete")
    required = {"vector_state"}
    for item in plan["artifacts"]:
        name = item["component"]
        required.add("notes_native" if name == "notesdrive" else "ai_native")
        if item["mode"] == "update":
            required.update((name + "_state", name + "_apk"))
    records = receipt.get("files")
    require(isinstance(records, list), "Backup file records missing")
    root = Path(receipt_path).resolve().parent
    verified = {}
    for record in records:
        require(isinstance(record, dict), "Invalid backup record")
        role = record.get("role")
        require(isinstance(role, str) and role in required and role not in verified,
                "Unexpected or duplicate backup role")
        relative = record.get("path")
        require(isinstance(relative, str) and not Path(relative).is_absolute()
                and ".." not in Path(relative).parts, "Backup paths must be bounded relative paths")
        suffixes = (".apk",) if role.endswith("_apk") else (".tar", ".tar.gz", ".tgz", ".zip")
        require(relative.lower().endswith(suffixes), "Backup role requires an APK or opaque archive")
        path = root / relative
        require(not path.is_symlink() and root in path.resolve().parents,
                "Backup path leaves receipt directory")
        require(path.is_file() and path.stat().st_size == record.get("bytes")
                and path.stat().st_size > 0, "Backup file missing or size differs")
        require(file_hash(path) == record.get("sha256"), "Backup file hash differs")
        # Opaque byte hashing only: never unpack, inspect or restore archive contents.
        verified[role] = record["sha256"]
    require(set(verified) == required, "Required backup roles missing")
    return verified


def scope_table(text):
    scopes = doctor.parse_scope_rows(text)
    require(scopes is not None, "Unknown Vector scope output")
    return scopes


def module_table(text):
    lines = [line.strip() for line in text.splitlines() if line.strip()]
    require(bool(lines) and re.fullmatch(r"PACKAGE\s+UID\s+STATUS", lines[0]),
            "Unknown Vector module output")
    modules = {}
    for line in lines[1:]:
        if set(line) == {"-"}:
            continue
        match = re.fullmatch(r"([a-z][A-Za-z0-9_.]*)\s+\d+\s+(enabled|disabled)", line)
        require(match is not None and match.group(1) not in modules,
                "Unknown or duplicate Vector module row")
        modules[match.group(1)] = match.group(2)
    return modules


class Device:
    def __init__(self, root, serial, commands):
        self.root = root
        self.serial = serial
        self.commands = commands
        self.adb = root / "tools/platform-tools/adb"

    def shell(self, label, *args, root=False):
        command = shlex.join(args)
        if root:
            command = shlex.join(("/debug_ramdisk/su", "-c", command))
        return self.commands.call(label, [self.adb, "-s", self.serial, "shell", command])

    def vector(self, *args):
        return self.shell("Vector " + args[0], doctor.VECTOR, *args, root=True)

    def scopes(self, package):
        return scope_table(self.vector("scope", "ls", package))

    def modules(self):
        return module_table(self.vector("modules", "ls"))

    def installed(self, package, directory):
        listing = self.shell("Installed package list", "pm", "list", "packages", "--user", "0", package)
        lines = [line.strip() for line in listing.splitlines() if line.strip()]
        require(all(re.fullmatch(r"package:[a-zA-Z0-9_.]+", line) for line in lines),
                "Unknown installed-package output")
        if "package:" + package not in lines:
            return None
        raw = self.shell("Installed APK path", "pm", "path", package)
        remote = doctor.parse_apk_path(raw)
        require(remote is not None, "Installed APK must be a single safe base APK")
        target = Path(directory) / (package + ".apk")
        self.commands.call("Pull installed APK", [self.adb, "-s", self.serial,
                                                 "pull", remote, target], timeout=60)
        result = apk_identity(self.root, target, self.commands)
        require(result["package"] == package, "Installed package differs")
        return result

    def install(self, path, update):
        args = [self.adb, "-s", self.serial, "install", "--no-incremental"]
        if update:
            args.append("-r")
        self.commands.call("APK installation", args + [path], timeout=120)


def preflight(device, plan, backups, directory):
    for prop, expected in {
        "ro.product.model": "NoteAir4C", "ro.build.display.id": doctor.FIRMWARE,
        "ro.build.version.sdk": "33", "ro.boot.slot_suffix": "_b", "sys.boot_completed": "1",
    }.items():
        require(device.shell("Device baseline", "getprop", prop).strip() == expected,
                "Unsupported device baseline")
    require(device.shell("Existing root", "id", "-u", root=True).strip() == "0",
            "Existing shell root is required")
    magisk = device.shell("Magisk version", "/debug_ramdisk/magisk", "-V", root=True).strip()
    require(magisk in ("30200", "30700"), "Unvalidated Magisk version")
    framework = doctor.parse_sha256(device.shell("Framework hash", "sha256sum",
                                                  "/system/framework/services.jar"))
    require(framework == doctor.PATCHED_FRAMEWORK, "Expected guarded AMS overlay is absent")
    notes = doctor.parse_package(device.shell("Notes version", "dumpsys", "package",
                                              "com.onyx.android.note"))
    require(notes["version_code"] == 45326, "Only Notes45326 is supported")
    if any(item["component"] == "notesdrive" for item in plan["artifacts"]):
        launcher = doctor.parse_package(device.shell("Notes Settings host", "dumpsys", "package", "com.onyx"))
        require(launcher["version_code"] == 56737, "Only launcher56737 is supported for Notes Settings")
    vector = doctor.parse_module_version(device.shell(
        "Vector version", "grep", "-E", "^version(Code)?=",
        "/data/adb/modules/zygisk_vector/module.prop", root=True))
    require(vector == {"version": "2.2", "version_code": "3080"}, "Only Vector2.2/3080 is supported")
    for module in ("zygisk_vector", "boox_ams_fix_na4c_42"):
        for marker in ("disable", "remove"):
            device.shell("Active framework marker", "test", "!", "-e",
                         "/data/adb/modules/" + module + "/" + marker, root=True)
    before = device.modules()
    before_scopes = {package: device.scopes(package) for package in doctor.SCOPES if package in before}
    for item in plan["artifacts"]:
        package = item["package"]
        installed = device.installed(package, directory)
        if item["mode"] == "first_install":
            require(installed is None and package not in before, "First install requires absent package/module")
        else:
            require(installed is not None, "Update requires an installed package")
            require(installed["certificate_sha256"] == item["certificate_sha256"],
                    "Installed APK signing identity differs")
            require(installed["version_code"] <= item["version_code"], "Downgrade refused")
            require(backups[item["component"] + "_apk"] == installed["apk_sha256"],
                    "Installed APK differs from backup checkpoint")
            require(before.get(package) == "enabled", "Existing target module must already be enabled")
            require(before_scopes.get(package) == set(item["scopes"]), "Existing target scope differs")
    return before, before_scopes


def stage_inputs(root, plan, staging):
    """Copy reviewed APKs and the unchanged helper into a private temporary root."""
    copies = dict(plan["input_sha256"])
    copies.update({item["path"]: item["apk_sha256"] for item in plan["artifacts"]})
    for relative, expected in copies.items():
        source = root / relative
        require(file_hash(source) == expected, "Reviewed input changed before staging")
        target = staging / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
        require(file_hash(target) == expected, "Staged input hash differs")
    adb = staging / "tools/platform-tools/adb"
    adb.parent.mkdir(parents=True, exist_ok=True)
    adb.symlink_to((root / "tools/platform-tools/adb").resolve())


def verify_after(device, plan, before, before_scopes, directory):
    after = device.modules()
    selected = {item["package"] for item in plan["artifacts"]}
    require({k: v for k, v in before.items() if k not in selected}
            == {k: v for k, v in after.items() if k not in selected},
            "Unselected Vector module state changed")
    for package, scopes in before_scopes.items():
        if package not in selected:
            require(device.scopes(package) == scopes, "Unselected scope changed")
    for item in plan["artifacts"]:
        installed = device.installed(item["package"], directory)
        require(installed is not None and all(installed[key] == item[key] for key in
                ("apk_sha256", "certificate_sha256", "version_code", "package")),
                "Installed artifact identity differs")
        require(after.get(item["package"]) == "enabled"
                and device.scopes(item["package"]) == set(item["scopes"]),
                "Post-install module/scope verification failed")


def apply_plan(device, plan, staging, event):
    for item in plan["artifacts"]:
        name, package = item["component"], item["package"]
        apk = staging / item["path"]
        if item["mode"] == "first_install":
            event(name + ":install-first-apk", "started")
            device.install(apk, False)
            event(name + ":install-first-apk", "completed")
            for action, args in (
                ("set-exact-scope", ("scope", "set", package, *item["scopes"])),
                ("enable-module", ("modules", "enable", package)),
            ):
                event(name + ":" + action, "started")
                # Return text is not assumed to be a stable success contract.
                device.vector(*args)
                event(name + ":" + action, "completed")
            require(device.scopes(package) == set(item["scopes"])
                    and device.modules().get(package) == "enabled", "Initial module registration failed")
        if name == "notesdrive":
            event("notesdrive:delegate-verified-installer", "started")
            helper_report = staging / "helper-result.json"
            device.commands.call("Notes installer", [
                sys.executable, "-E", staging / HELPER, "--serial", device.serial,
                "--notes-closed", "--report", helper_report,
            ], timeout=180)
            result = load_json(helper_report)
            marker = "Native sync adapter v0.4 build " + item["hook_build"] + " ready for Notes 45326"
            require(result.get("apk_sha256") == item["apk_sha256"]
                    and result.get("hook_build") == item["hook_build"]
                    and result.get("loaded_marker") == marker and result.get("notes_version") == 45326,
                    "Delegated Notes hook receipt differs")
            require(result.get("launcher_version") == 56737 and result.get("launcher_marker") ==
                    "Launcher Notes Settings build " + item["hook_build"] + " ready for launcher 56737",
                    "Delegated launcher Settings hook receipt differs")
            event("notesdrive:delegate-verified-installer", "completed")
        elif item["mode"] == "update":
            event("openai:install-update", "started")
            device.install(apk, True)
            event("openai:install-update", "completed")
        if name == "openai":
            event("openai:stop-saved-native-processes", "started")
            for native in ("com.onyx.aiassistant", "com.onyx.kreader"):
                device.shell("Stop saved native process", "am", "force-stop", native, root=True)
            event("openai:stop-saved-native-processes", "completed")


def write_new_json(path, value):
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w") as stream:
        json.dump(value, stream, indent=2)
        stream.write("\n")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--workspace", type=Path, default=ROOT)
    parser.add_argument("--install", action="append", choices=sorted(APKS))
    parser.add_argument("--first-install", action="append", choices=sorted(APKS), default=[])
    phase = parser.add_mutually_exclusive_group()
    phase.add_argument("--preflight", action="store_true", help="Read device metadata; never install")
    phase.add_argument("--apply", action="store_true", help="Execute the saved reviewed plan")
    parser.add_argument("--serial")
    parser.add_argument("--write-plan", type=Path)
    parser.add_argument("--reviewed-plan", type=Path)
    parser.add_argument("--backups", type=Path)
    parser.add_argument("--editors-closed", action="store_true")
    parser.add_argument("--registration-confirmed", action="store_true")
    parser.add_argument("--report", type=Path)
    args = parser.parse_args(argv)
    report = None
    report_owned = False
    try:
        require(not (args.write_plan and (args.apply or args.preflight)),
                "Write/review the plan in a separate dry-run")
        if args.apply or args.preflight:
            require(args.install and args.serial and args.reviewed_plan and args.backups
                    and args.editors_closed, "Live phases need selection, serial, plan, backups and closed editors")
            require(not args.serial.startswith("-") and not any(c in args.serial for c in "\r\n"),
                    "Invalid device selector")
            require("notesdrive" not in args.first_install or args.registration_confirmed,
                    "First Notes install requires Google registration confirmation")
        if args.apply:
            require(args.report is not None, "Apply requires a new local report path")
        commands = Commands()
        plan = make_plan(args.workspace, args.install or sorted(APKS), args.first_install, commands)
        if not args.apply and not args.preflight:
            if args.write_plan:
                write_new_json(args.write_plan, plan)
            print(json.dumps({"mode": "dry_run", "plan": plan}, indent=2))
            return 0
        verify_reviewed_plan(plan, args.reviewed_plan)
        backups = verify_backups(args.backups, args.serial, plan)
        report = {"schema": 1, "plan_id": plan["plan_id"], "status": "preflight",
                  "events": [], "notes_hook_verified": False, "openai_hook_verified": False,
                  "sync_verified": False, "backups_hash_verified": True}
        if args.apply:
            write_new_json(args.report, report)
            report_owned = True

        def event(action, status):
            report["events"].append({"action": action, "status": status})
            if args.apply:
                args.report.write_text(json.dumps(report, indent=2) + "\n")

        with tempfile.TemporaryDirectory(prefix="boox-setup-") as directory:
            staging = Path(directory)
            stage_inputs(args.workspace, plan, staging)
            device = Device(args.workspace, args.serial, commands)
            before, scopes = preflight(device, plan, backups, staging)
            event("preflight", "completed")
            if args.apply:
                report["status"] = "applying"
                apply_plan(device, plan, staging, event)
                verify_after(device, plan, before, scopes, staging)
                event("post-install-identity-and-scopes", "completed")
                report["notes_hook_verified"] = any(item["component"] == "notesdrive"
                                                    for item in plan["artifacts"])
            report["status"] = "installed_pending_acceptance" if args.apply else "preflight_passed"
            if args.apply:
                args.report.write_text(json.dumps(report, indent=2) + "\n")
        print(json.dumps(report, indent=2))
        return 0
    except (GuardError, OSError, ValueError, KeyError, TypeError) as error:
        message = str(error) if isinstance(error, GuardError) else "Local input/output validation failed"
        if report is not None and report_owned:
            report["status"] = "failed_manual_review_required"
            report["failure"] = message
            # Do not overwrite any pre-existing file if exclusive creation failed.
            try:
                args.report.write_text(json.dumps(report, indent=2) + "\n")
            except OSError:
                pass
        print(json.dumps({"status": "blocked", "reason": message}), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
