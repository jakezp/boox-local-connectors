"""Offline setup/AMS guard tests with synthetic files and fake devices only."""

import contextlib
from datetime import datetime, timezone, timedelta
import hashlib
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import boox_setup as setup
import build_ams_fix as ams


CERT256 = "a" * 64
CERT1 = "b" * 40
SERIAL = "SYNTHETIC_DEVICE"
NOW = datetime(2026, 9, 12, 12, tzinfo=timezone.utc)


class LocalCommands:
    """Fake local apksigner/aapt; any other command is a test failure."""
    def __init__(self):
        self.calls = []

    def call(self, label, args, timeout=30):
        self.calls.append(tuple(map(str, args)))
        if "apksigner" in str(args[0]):
            return ("Signer #1 certificate SHA-256 digest: " + CERT256 + "\n"
                    "Signer #1 certificate SHA-1 digest: " + CERT1 + "\n")
        if "aapt" in str(args[0]):
            name = "openai" if "openai" in str(args[-1]) else "notesdrive"
            version, code = ("0.9", 9) if name == "openai" else ("0.4", 4)
            return f"package: name='local.boox.{name}' versionCode='{code}' versionName='{version}'\n"
        raise AssertionError("Unexpected command: " + label)


class FakeDevice:
    def __init__(self, root, plan):
        self.root, self.serial = root, SERIAL
        self.calls = []
        self.commands = self
        self.module_state = {}
        self.scope_state = {}
        self.identities = {}
        self.bad_property = None
        self.helper_fail = False
        self.launcher_version = 56737
        self.plan = plan
        for item in plan["artifacts"]:
            if item["mode"] == "update":
                package = item["package"]
                self.identities[package] = dict(item)
                self.module_state[package] = "enabled"
                self.scope_state[package] = set(item["scopes"])

    def shell(self, label, *args, root=False):
        self.calls.append(("shell", args, root))
        if args[0] == "getprop":
            value = {"ro.product.model": "NoteAir4C", "ro.build.display.id": setup.doctor.FIRMWARE,
                     "ro.build.version.sdk": "33", "ro.boot.slot_suffix": "_b",
                     "sys.boot_completed": "1"}[args[1]]
            return "wrong" if self.bad_property == args[1] else value
        if args == ("id", "-u"):
            return "0"
        if args == ("/debug_ramdisk/magisk", "-V"):
            return "30700"
        if args[0] == "sha256sum":
            return setup.doctor.PATCHED_FRAMEWORK
        if args[0] == "dumpsys":
            if args[-1] == "com.onyx":
                return f"versionCode={self.launcher_version} minSdk=24\n"
            return "versionCode=45326 minSdk=28\n"
        if args[0] == "grep":
            return "version=v2.2 (3080-88f8e1fa-JingMatrix-Vector)\nversionCode=3080\n"
        if args[0] in ("test", "am"):
            return ""
        raise AssertionError("Unknown fake device read")

    def installed(self, package, directory):
        return self.identities.get(package)

    def modules(self):
        return dict(self.module_state)

    def scopes(self, package):
        return set(self.scope_state.get(package, set()))

    def install(self, path, update):
        name = "openai" if "openai" in str(path) else "notesdrive"
        self.calls.append(("install", name, update))
        item = next(item for item in self.plan["artifacts"] if item["component"] == name)
        self.identities[item["package"]] = dict(item)

    def vector(self, *args):
        self.calls.append(("vector", args))
        if args[:2] == ("scope", "set"):
            self.scope_state[args[2]] = set(args[3:])
        elif args[:2] == ("modules", "enable"):
            self.module_state[args[2]] = "enabled"
        else:
            raise AssertionError("Unexpected mutating Vector command")
        return "Completed\n"

    def call(self, label, args, timeout=30):
        self.calls.append(("helper", tuple(map(str, args))))
        if self.helper_fail:
            raise setup.GuardError("Notes installer: command failed")
        if label != "Notes installer":
            raise AssertionError("Unexpected delegated command")
        item = next(item for item in self.plan["artifacts"] if item["component"] == "notesdrive")
        self.identities[item["package"]] = dict(item)
        report_path = Path(args[args.index("--report") + 1])
        report_path.write_text(json.dumps({
            "apk_sha256": item["apk_sha256"], "hook_build": item["hook_build"],
            "loaded_marker": "Native sync adapter v0.4 build " + item["hook_build"]
                             + " ready for Notes 45326", "notes_version": 45326,
            "launcher_version": 56737,
            "launcher_marker": "Launcher Notes Settings build " + item["hook_build"]
                               + " ready for launcher 56737",
        }))
        return "unreported helper stdout"


class SetupTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.commands = LocalCommands()
        for relative in [*setup.APKS.values(), setup.HELPER, "tools/boox_setup.py",
                         "tools/boox_doctor.py", "tools/platform-tools/adb",
                         "notes-drive/android/app/src/main/java/example/Test.java"]:
            path = self.root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(("synthetic:" + relative).encode())
        self.registration = {
            "package_name": "local.boox.notesdrive",
            "apk_sha256": setup.file_hash(self.root / setup.APKS["notesdrive"]),
            "signing_certificate_sha1": CERT1,
            "scope": "https://www.googleapis.com/auth/drive.file",
            "version": "0.4", "hook_build": setup.source_digest(self.root),
        }
        (self.root / setup.REGISTRATION).write_text(json.dumps(self.registration))

    def plan(self, components=("notesdrive", "openai"), first=()):
        return setup.make_plan(self.root, components, first, self.commands)

    def receipt(self, plan):
        roles = {"vector_state"}
        for item in plan["artifacts"]:
            name = item["component"]
            roles.add("notes_native" if name == "notesdrive" else "ai_native")
            if item["mode"] == "update":
                roles.update((name + "_apk", name + "_state"))
        files = []
        for role in sorted(roles):
            path = self.root / (role + (".apk" if role.endswith("_apk") else ".tar"))
            name = role.removesuffix("_apk")
            content = (self.root / setup.APKS[name]).read_bytes() if role.endswith("_apk") else b"synthetic archive"
            path.write_bytes(content)
            files.append({"role": role, "path": path.name, "bytes": len(content),
                          "sha256": setup.file_hash(path)})
        receipt = {
            "schema": 1, "device_serial_sha256": hashlib.sha256(SERIAL.encode()).hexdigest(),
            "model": "NoteAir4C", "firmware": setup.doctor.FIRMWARE,
            "created_at": NOW.isoformat(),
            "attestations": {name: True for name in ("editors_closed", "restore_procedure_reviewed",
                                                    "signing_keys_preserved", "keystore_limit_understood")},
            "files": files,
        }
        path = self.root / "receipt.json"
        path.write_text(json.dumps(receipt))
        return path, receipt

    def test_plan_is_deterministic_and_local_only(self):
        a = self.plan()
        self.assertEqual(a, self.plan())
        self.assertIn(setup.HELPER, a["input_sha256"])
        self.assertTrue(all("apksigner" in call[0] or "aapt" in call[0] for call in self.commands.calls))
        self.assertNotIn(SERIAL, json.dumps(a))

    def test_default_main_is_dry_run_without_device(self):
        with patch.object(setup, "Commands", return_value=self.commands), \
                contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(setup.main(["--workspace", str(self.root)]), 0)
        self.assertEqual(json.loads(output.getvalue())["mode"], "dry_run")

    def test_apply_missing_contract_never_runs_a_command(self):
        with patch.object(setup, "Commands", side_effect=AssertionError("must not run")), \
                contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(setup.main(["--apply", "--install", "notesdrive"]), 1)

    def test_apply_requires_report_before_commands(self):
        with patch.object(setup, "Commands", side_effect=AssertionError("must not run")), \
                contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(setup.main(["--apply", "--install", "openai", "--serial", SERIAL,
                                         "--reviewed-plan", "p", "--backups", "b", "--editors-closed"]), 1)

    def test_first_notes_requires_registration_attestation(self):
        with patch.object(setup, "Commands", side_effect=AssertionError("must not run")), \
                contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(setup.main(["--preflight", "--install", "notesdrive", "--first-install",
                                         "notesdrive", "--serial", SERIAL, "--reviewed-plan", "p",
                                         "--backups", "b", "--editors-closed"]), 1)

    def test_source_change_blocks_notes_plan(self):
        (self.root / "notes-drive/android/app/src/main/java/example/Test.java").write_text("changed")
        with self.assertRaisesRegex(setup.GuardError, "source changed"):
            self.plan()

    def test_registration_digest_scope_and_signer_mismatch_block(self):
        for key, value in (("apk_sha256", "0" * 64), ("signing_certificate_sha1", "0" * 40),
                           ("scope", "unexpected")):
            registration = dict(self.registration)
            registration[key] = value
            (self.root / setup.REGISTRATION).write_text(json.dumps(registration))
            with self.assertRaises(setup.GuardError):
                self.plan()

    def test_changed_reviewed_plan_blocks(self):
        plan = self.plan()
        path = self.root / "plan.json"
        setup.write_new_json(path, plan)
        setup.verify_reviewed_plan(plan, path)
        changed = dict(plan, actions=[])
        with self.assertRaises(setup.GuardError):
            setup.verify_reviewed_plan(changed, path)

    def test_plan_and_report_creation_never_overwrite(self):
        path = self.root / "keep.json"
        path.write_text("keep")
        with self.assertRaises(FileExistsError):
            setup.write_new_json(path, {})
        self.assertEqual(path.read_text(), "keep")

    def test_verified_backup_hashes(self):
        plan = self.plan()
        path, _ = self.receipt(plan)
        hashes = setup.verify_backups(path, SERIAL, plan, NOW)
        self.assertIn("notesdrive_apk", hashes)
        self.assertIn("openai_state", hashes)

    def test_backup_wrong_device_stale_future_or_missing_attestation(self):
        plan = self.plan()
        path, receipt = self.receipt(plan)
        for change in (
            {"device_serial_sha256": "0" * 64},
            {"created_at": (NOW - timedelta(hours=25)).isoformat()},
            {"created_at": (NOW + timedelta(minutes=1)).isoformat()},
            {"attestations": {}},
        ):
            path.write_text(json.dumps({**receipt, **change}))
            with self.assertRaises(setup.GuardError):
                setup.verify_backups(path, SERIAL, plan, NOW)

    def test_backup_hash_and_missing_role_reject(self):
        plan = self.plan()
        path, receipt = self.receipt(plan)
        (self.root / receipt["files"][0]["path"]).write_text("different")
        with self.assertRaises(setup.GuardError):
            setup.verify_backups(path, SERIAL, plan, NOW)
        path, receipt = self.receipt(plan)
        receipt["files"].pop()
        path.write_text(json.dumps(receipt))
        with self.assertRaisesRegex(setup.GuardError, "roles missing"):
            setup.verify_backups(path, SERIAL, plan, NOW)

    def test_backup_path_escape_and_symlink_reject(self):
        plan = self.plan()
        path, receipt = self.receipt(plan)
        original = receipt["files"][0]["path"]
        for escape in ("../outside", "/private/path"):
            receipt["files"][0]["path"] = escape
            path.write_text(json.dumps(receipt))
            with self.assertRaises(setup.GuardError):
                setup.verify_backups(path, SERIAL, plan, NOW)
        (self.root / "link.tar").symlink_to(self.root / original)
        receipt["files"][0]["path"] = "link.tar"
        path.write_text(json.dumps(receipt))
        with self.assertRaises(setup.GuardError):
            setup.verify_backups(path, SERIAL, plan, NOW)

    def test_backup_raw_key_file_and_bad_timestamp_types_reject(self):
        plan = self.plan()
        path, receipt = self.receipt(plan)
        receipt["created_at"] = 123
        path.write_text(json.dumps(receipt))
        with self.assertRaises(setup.GuardError):
            setup.verify_backups(path, SERIAL, plan, NOW)
        receipt["created_at"] = NOW.isoformat()
        receipt["files"][0]["path"] = "local-signing.p12"
        path.write_text(json.dumps(receipt))
        with self.assertRaisesRegex(setup.GuardError, "opaque archive"):
            setup.verify_backups(path, SERIAL, plan, NOW)

    def test_preflight_accepts_only_supported_baseline(self):
        plan = self.plan()
        path, _ = self.receipt(plan)
        backups = setup.verify_backups(path, SERIAL, plan, NOW)
        device = FakeDevice(self.root, plan)
        setup.preflight(device, plan, backups, self.root)
        self.assertTrue(all(call[0] == "shell" for call in device.calls))
        for prop in ("ro.product.model", "ro.build.display.id", "ro.boot.slot_suffix"):
            device.bad_property = prop
            with self.assertRaises(setup.GuardError):
                setup.preflight(device, plan, backups, self.root)

    def test_preflight_installed_signer_downgrade_and_backup_guards(self):
        plan = self.plan(("openai",))
        path, _ = self.receipt(plan)
        backups = setup.verify_backups(path, SERIAL, plan, NOW)
        for key, value in (("certificate_sha256", "0" * 64), ("version_code", 10),
                           ("apk_sha256", "0" * 64)):
            device = FakeDevice(self.root, plan)
            device.identities["local.boox.openai"][key] = value
            with self.assertRaises(setup.GuardError):
                setup.preflight(device, plan, backups, self.root)

    def test_preflight_root_magisk_framework_notes_and_vector_guards(self):
        plan = self.plan(("openai",))
        path, _ = self.receipt(plan)
        backups = setup.verify_backups(path, SERIAL, plan, NOW)
        for label, wrong in (("Existing root", "2000"), ("Magisk version", "99999"),
                             ("Framework hash", "0" * 64), ("Notes version", "versionCode=123"),
                             ("Vector version", "version=3.0\nversionCode=4000")):
            device = FakeDevice(self.root, plan)
            original = device.shell
            def shell(query, *args, root=False):
                return wrong if query == label else original(query, *args, root=root)
            with patch.object(device, "shell", side_effect=shell):
                with self.subTest(label=label), self.assertRaises(setup.GuardError):
                    setup.preflight(device, plan, backups, self.root)
            self.assertFalse(any(call[0] in ("install", "vector", "helper") for call in device.calls))

    def test_notes_settings_requires_supported_launcher_before_any_installation(self):
        plan = self.plan(("notesdrive",))
        path, _ = self.receipt(plan)
        backups = setup.verify_backups(path, SERIAL, plan, NOW)
        device = FakeDevice(self.root, plan)
        device.launcher_version = 56738
        with self.assertRaisesRegex(setup.GuardError, "launcher56737"):
            setup.preflight(device, plan, backups, self.root)
        self.assertFalse(any(call[0] in ("install", "vector", "helper") for call in device.calls))

    def test_preflight_scope_disabled_and_first_install_guards(self):
        plan = self.plan(("openai",))
        path, _ = self.receipt(plan)
        backups = setup.verify_backups(path, SERIAL, plan, NOW)
        device = FakeDevice(self.root, plan)
        device.scope_state["local.boox.openai"].add("android/0")
        with self.assertRaises(setup.GuardError):
            setup.preflight(device, plan, backups, self.root)
        device = FakeDevice(self.root, plan)
        device.module_state["local.boox.openai"] = "disabled"
        with self.assertRaises(setup.GuardError):
            setup.preflight(device, plan, backups, self.root)
        first = self.plan(("openai",), ("openai",))
        with self.assertRaises(setup.GuardError):
            setup.preflight(device, first, backups, self.root)

    def test_staging_copies_exact_helper_and_freezes_apk(self):
        plan = self.plan()
        stage = self.root / "staged"
        stage.mkdir()
        setup.stage_inputs(self.root, plan, stage)
        self.assertEqual((stage / setup.HELPER).read_bytes(), (self.root / setup.HELPER).read_bytes())
        before = (stage / setup.APKS["notesdrive"]).read_bytes()
        (self.root / setup.APKS["notesdrive"]).write_text("concurrent rebuild")
        self.assertEqual((stage / setup.APKS["notesdrive"]).read_bytes(), before)
        other = self.root / "other-stage"
        other.mkdir()
        with self.assertRaises(setup.GuardError):
            setup.stage_inputs(self.root, plan, other)

    def test_notes_update_delegates_without_duplicate_cache_commands(self):
        plan = self.plan(("notesdrive",))
        device = FakeDevice(self.root, plan)
        events = []
        setup.apply_plan(device, plan, self.root, lambda *e: events.append(e))
        helper = next(call for call in device.calls if call[0] == "helper")
        self.assertIn(str(self.root / setup.HELPER), helper[1])
        self.assertIn("-E", helper[1])
        self.assertIn("--notes-closed", helper[1])
        self.assertFalse(any(call[0] in ("vector", "install") for call in device.calls))
        self.assertIn(("notesdrive:delegate-verified-installer", "completed"), events)

    def test_notes_helper_failure_stops_before_other_component(self):
        plan = self.plan()
        device = FakeDevice(self.root, plan)
        device.helper_fail = True
        with self.assertRaises(setup.GuardError):
            setup.apply_plan(device, plan, self.root, lambda *e: None)
        self.assertFalse(any(call[0] == "install" for call in device.calls))

    def test_openai_update_does_not_change_scope_or_other_apps(self):
        plan = self.plan(("openai",))
        device = FakeDevice(self.root, plan)
        setup.apply_plan(device, plan, self.root, lambda *e: None)
        self.assertEqual([call for call in device.calls if call[0] == "install"],
                         [("install", "openai", True)])
        self.assertFalse(any(call[0] == "vector" for call in device.calls))
        stopped = [call[1][-1] for call in device.calls if call[0] == "shell"]
        self.assertEqual(stopped, ["com.onyx.aiassistant", "com.onyx.kreader"])

    def test_first_openai_uses_only_documented_targeted_module_commands(self):
        plan = self.plan(("openai",), ("openai",))
        device = FakeDevice(self.root, plan)
        setup.apply_plan(device, plan, self.root, lambda *e: None)
        vectors = [call[1] for call in device.calls if call[0] == "vector"]
        self.assertEqual(vectors, [
            ("scope", "set", "local.boox.openai", "com.onyx.aiassistant/0", "com.onyx.kreader/0"),
            ("modules", "enable", "local.boox.openai")])
        self.assertIn(("install", "openai", False), device.calls)

    def test_first_notes_bootstraps_then_delegates(self):
        plan = self.plan(("notesdrive",), ("notesdrive",))
        device = FakeDevice(self.root, plan)
        setup.apply_plan(device, plan, self.root, lambda *e: None)
        self.assertEqual(device.calls[0], ("install", "notesdrive", False))
        self.assertEqual(sum(call[0] == "helper" for call in device.calls), 1)
        self.assertTrue(all("disable" not in call[1]
                            for call in device.calls if call[0] == "vector"))

    def test_postcheck_detects_foreign_module_and_scope_changes(self):
        plan = self.plan(("openai",))
        device = FakeDevice(self.root, plan)
        before = device.modules()
        scopes = {"local.boox.openai": device.scopes("local.boox.openai")}
        setup.verify_after(device, plan, before, scopes, self.root)
        device.module_state["other.module"] = "enabled"
        with self.assertRaises(setup.GuardError):
            setup.verify_after(device, plan, before, scopes, self.root)
        device.module_state.pop("other.module")
        device.scope_state["local.boox.openai"].add("android/0")
        with self.assertRaises(setup.GuardError):
            setup.verify_after(device, plan, before, scopes, self.root)

    def test_postcheck_preserves_other_companion_scope(self):
        plan = self.plan(("notesdrive",))
        device = FakeDevice(self.root, plan)
        other = "local.boox.openai"
        device.module_state[other] = "enabled"
        device.scope_state[other] = set(setup.doctor.SCOPES[other])
        before = device.modules()
        scopes = {package: device.scopes(package) for package in before}
        device.scope_state[other].add("android/0")
        with self.assertRaisesRegex(setup.GuardError, "Unselected scope changed"):
            setup.verify_after(device, plan, before, scopes, self.root)

    def test_scope_and_module_tables_reject_help_and_errors(self):
        self.assertEqual(setup.scope_table("APP_PACKAGE USER_ID\n---\ncom.onyx.android.note 0"),
                         {"com.onyx.android.note/0"})
        for text in ("Usage: scope", "Error: com.onyx.android.note/0"):
            with self.assertRaises(setup.GuardError):
                setup.scope_table(text)
        self.assertEqual(setup.module_table("PACKAGE UID STATUS\n---\nlocal.boox.openai 123 enabled"),
                         {"local.boox.openai": "enabled"})
        with self.assertRaises(setup.GuardError):
            setup.module_table("Usage: modules")

    def test_subprocess_failure_never_echoes_private_output(self):
        def fail(*args, **kwargs):
            return subprocess.CompletedProcess(args[0], 1, "secret-sentinel", "private@example.invalid")
        with self.assertRaisesRegex(setup.GuardError, "^APK signature: command failed$"):
            setup.Commands(fail).call("APK signature", ["apksigner"])

    def test_package_absence_uses_successful_list_not_failing_pm_path(self):
        calls = []
        class Commands:
            def call(self, label, args, timeout=30):
                calls.append(label)
                return "package:local.boox.openai.tests\n"
        device = setup.Device(self.root, SERIAL, Commands())
        self.assertIsNone(device.installed("local.boox.openai", self.root))
        self.assertEqual(calls, ["Installed package list"])

    def test_main_mocked_apply_records_pending_acceptance(self):
        plan = self.plan(("openai",))
        reviewed = self.root / "reviewed.json"
        setup.write_new_json(reviewed, plan)
        backup_path, _ = self.receipt(plan)
        device = FakeDevice(self.root, plan)
        report_path = self.root / "result.json"
        real_verify = setup.verify_backups
        with patch.object(setup, "Commands", return_value=self.commands), \
                patch.object(setup, "Device", return_value=device), \
                patch.object(setup, "verify_backups",
                             side_effect=lambda p, s, v: real_verify(p, s, v, NOW)), \
                contextlib.redirect_stdout(io.StringIO()):
            result = setup.main([
                "--workspace", str(self.root), "--install", "openai", "--apply",
                "--serial", SERIAL, "--reviewed-plan", str(reviewed), "--backups", str(backup_path),
                "--editors-closed", "--report", str(report_path)])
        self.assertEqual(result, 0)
        report = json.loads(report_path.read_text())
        self.assertEqual(report["status"], "installed_pending_acceptance")
        self.assertFalse(report["sync_verified"])
        self.assertFalse(report["openai_hook_verified"])
        self.assertNotIn(SERIAL, report_path.read_text())

    def test_main_preflight_failure_writes_owned_report_and_does_not_install(self):
        plan = self.plan(("openai",))
        reviewed = self.root / "reviewed.json"
        setup.write_new_json(reviewed, plan)
        backup_path, _ = self.receipt(plan)
        device = FakeDevice(self.root, plan)
        device.bad_property = "ro.product.model"
        report_path = self.root / "result.json"
        real_verify = setup.verify_backups
        with patch.object(setup, "Commands", return_value=self.commands), \
                patch.object(setup, "Device", return_value=device), \
                patch.object(setup, "verify_backups",
                             side_effect=lambda p, s, v: real_verify(p, s, v, NOW)), \
                contextlib.redirect_stderr(io.StringIO()):
            result = setup.main([
                "--workspace", str(self.root), "--install", "openai", "--apply",
                "--serial", SERIAL, "--reviewed-plan", str(reviewed), "--backups", str(backup_path),
                "--editors-closed", "--report", str(report_path)])
        self.assertEqual(result, 1)
        self.assertEqual(json.loads(report_path.read_text())["status"], "failed_manual_review_required")
        self.assertFalse(any(call[0] in ("install", "vector", "helper") for call in device.calls))


class AmsTests(unittest.TestCase):
    def test_wrong_original_rejected_before_dependencies(self):
        with self.assertRaisesRegex(ValueError, "Unsupported original"):
            ams.patch_jar(b"synthetic wrong input")

    def test_existing_output_and_input_alias_are_not_overwritten(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source, output, patched = root / "original.jar", root / "module.zip", root / "patched.jar"
            source.write_bytes(b"original")
            output.write_bytes(b"preserve")
            with self.assertRaisesRegex(ValueError, "already exist"):
                ams.build(source, output, patched)
            self.assertEqual(output.read_bytes(), b"preserve")
            with self.assertRaisesRegex(ValueError, "distinct"):
                ams.build(source, source, patched)
            self.assertFalse(patched.exists())

    def test_unsigned_module_payload_contract(self):
        data = ams.module_bytes(b"synthetic patched jar")
        with zipfile.ZipFile(io.BytesIO(data)) as module:
            self.assertEqual(module.namelist(),
                             ["module.prop", "customize.sh", "system/framework/services.jar"])
            self.assertEqual(module.read("customize.sh").decode(), ams.CUSTOMIZE)
            self.assertIn(ams.SUPPORTED_ORIGINAL, module.read("customize.sh").decode())
            self.assertEqual(module.read("module.prop").decode(), ams.MODULE_PROP)
            self.assertEqual(module.read("system/framework/services.jar"), b"synthetic patched jar")

    def test_guard_not_an_assert(self):
        with self.assertRaises(ValueError):
            ams.require(False, "guard")


if __name__ == "__main__":
    unittest.main()
