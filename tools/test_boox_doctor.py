"""Offline parser/privacy/command tests; never invokes ADB or reads a real key."""

import contextlib
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import boox_doctor as doctor


class DoctorTests(unittest.TestCase):
    def test_package_output_is_allowlisted(self):
        result = doctor.parse_package(
            " versionCode=4 minSdk=28\n versionName=0.4\n"
            " account=private@example.invalid\n token=secret-sentinel\n")
        self.assertEqual(result, {"version_code": 4, "version_name": "0.4"})

    def test_absent_package_is_unknown(self):
        self.assertEqual(doctor.parse_package("Unable to find package"),
                         {"version_code": None, "version_name": None})

    def test_rejects_multiline_property(self):
        self.assertIsNone(doctor.exact_value("NoteAir4C\nsecret-sentinel", doctor.PROPERTIES["model"][1]))

    def test_sha256_accepts_hash_without_exposing_path(self):
        self.assertEqual(doctor.parse_sha256("a" * 64 + "  /private/example"), "a" * 64)
        self.assertIsNone(doctor.parse_sha256("a" * 64 + "\nsecret-sentinel"))

    def test_apk_path_rejects_injection_and_splits(self):
        good = "package:/data/app/~~random/local.boox.openai-abc/base.apk"
        self.assertTrue(doctor.parse_apk_path(good).endswith("/base.apk"))
        accepted = "package:/data/app/random/local.boox.openai-abc/base.apk"
        self.assertTrue(doctor.parse_apk_path(accepted).endswith("/base.apk"))
        for bad in (accepted + ";id", accepted + "\npackage:/data/app/split.apk",
                    "package:/data/app/../private/base.apk"):
            self.assertIsNone(doctor.parse_apk_path(bad))

    def test_scope_missing_or_excess_does_not_pass(self):
        expected = doctor.SCOPES["local.boox.notesdrive"]
        self.assertTrue(doctor.parse_scope("com.onyx.android.note/0", expected)["matches_expected"])
        self.assertFalse(doctor.parse_scope("error private@example.invalid", expected)["matches_expected"])
        extra = doctor.parse_scope("com.onyx.android.note/0\nandroid/0\nother.app/0", expected)
        self.assertFalse(extra["matches_expected"])
        self.assertEqual(extra["unexpected_count"], 2)
        self.assertNotIn("other.app", json.dumps(extra))
        self.assertFalse(doctor.parse_scope(
            "com.onyx.android.note/0\nsystem_server/0", expected)["matches_expected"])

    def test_actual_vector_scope_table(self):
        result = doctor.parse_scope(
            "APP_PACKAGE            USER_ID\n------------------------------\n"
            "com.onyx.android.note  0      \n",
            doctor.SCOPES["local.boox.notesdrive"])
        self.assertTrue(result["matches_expected"])
        self.assertTrue(result["parse_valid"])

    def test_actual_openai_scope_table(self):
        result = doctor.parse_scope(
            "APP_PACKAGE           USER_ID\n-----------------------------\n"
            "com.onyx.aiassistant  0\ncom.onyx.kreader      0\n",
            doctor.SCOPES["local.boox.openai"])
        self.assertTrue(result["matches_expected"])

    def test_scope_help_error_or_ambiguous_rows_never_pass(self):
        expected = doctor.SCOPES["local.boox.notesdrive"]
        good = "APP_PACKAGE USER_ID\n---\ncom.onyx.android.note 0\n"
        for raw in ("Usage: scope ls com.onyx.android.note/0", "Error com.onyx.android.note/0",
                    good + "error: not authorized", good + "com.onyx.android.note 0\n",
                    good + "Usage: cli scope ls", good + "other.app 0 extra-column",
                    "APP_PACKAGE USER_ID\n---\ncom.onyx.android.note -1"):
            with self.subTest(raw=raw):
                result = doctor.parse_scope(raw, expected)
                self.assertFalse(result["parse_valid"])
                self.assertFalse(result["matches_expected"])

    def test_scope_other_user_extra_package_and_empty_table(self):
        expected = doctor.SCOPES["local.boox.notesdrive"]
        for rows in ("com.onyx.android.note 10", "com.onyx.android.note 0\nother.app 0", ""):
            result = doctor.parse_scope("APP_PACKAGE USER_ID\n---\n" + rows, expected)
            self.assertTrue(result["parse_valid"])
            self.assertFalse(result["matches_expected"])

    def test_module_version_does_not_include_other_metadata(self):
        result = doctor.parse_module_version(
            "version=2.2\nversionCode=3080\nauthor=private@example.invalid\n")
        self.assertEqual(result, {"version": "2.2", "version_code": "3080"})

    def test_recorded_vector_version_format(self):
        result = doctor.parse_module_version(
            "version=v2.2 (3080-88f8e1fa-JingMatrix-Vector)\nversionCode=3080\n")
        self.assertEqual(result, {"version": "2.2", "version_code": "3080"})

    def test_root_inventory_uses_only_read_commands(self):
        calls = []
        class Fake:
            failures = []
            def query(self, label, *args, root=False):
                calls.append(args)
                return "0" if label == "root_uid" else ""
        doctor.device_inventory(Fake(), True, {})
        allowed = {"getprop", "sha256sum", "dumpsys", "pm", "id", "grep",
                   "/debug_ramdisk/magisk", doctor.VECTOR}
        self.assertTrue(all(args[0] in allowed for args in calls))
        self.assertEqual([args[1:] for args in calls if args[0] == doctor.VECTOR],
                         [("scope", "ls", package) for package in doctor.SCOPES])
        self.assertTrue(all(args[1:] in [("-v",), ("-V",)]
                            for args in calls if args[0] == "/debug_ramdisk/magisk"))

    def test_command_failure_never_echoes_stderr(self):
        def fail(*args, **kwargs):
            return subprocess.CompletedProcess(args[0], 1, "", "private@example.invalid secret-sentinel")
        reader = doctor.Reader("adb", "PRIVATE_SERIAL", run=fail)
        self.assertEqual(reader.query("model", "getprop", "ro.product.model"), "")
        self.assertNotIn("secret", json.dumps(reader.failures))

    def test_timeout_never_echoes_exception_command(self):
        def fail(*args, **kwargs):
            raise subprocess.TimeoutExpired(["secret-sentinel"], 1, output="private")
        reader = doctor.Reader("adb", "PRIVATE_SERIAL", run=fail)
        self.assertEqual(reader.query("model", "getprop", "ro.product.model"), "")
        self.assertEqual(reader.failures[0]["reason"], "unavailable_or_timeout")

    def test_serial_is_not_embedded_in_remote_shell(self):
        calls = []
        def run(command, **kwargs):
            calls.append(command)
            return subprocess.CompletedProcess(command, 0, "0\n", "")
        reader = doctor.Reader("adb", "PRIVATE_SERIAL;bad", run=run)
        reader.query("uid", "id", "-u", root=True)
        self.assertEqual(calls[0][2], "PRIVATE_SERIAL;bad")
        self.assertNotIn("PRIVATE_SERIAL", calls[0][-1])
        self.assertEqual(calls[0][-1], "/debug_ramdisk/su -c 'id -u'")

    def test_root_queries_are_opt_in(self):
        calls = []
        class Fake:
            failures = []
            def query(self, label, *args, root=False):
                calls.append((args, root))
                return ""
        result = doctor.device_inventory(Fake(), False, {})
        self.assertFalse(any(root for _, root in calls))
        self.assertFalse(result["hook_activation_verified"])
        self.assertFalse(result["oauth_or_sync_verified"])

    def test_full_report_never_contains_raw_diagnostics(self):
        class Fake:
            failures = []
            def query(self, label, *args, root=False):
                return "secret-sentinel private@example.invalid"
        report = doctor.device_inventory(Fake(), True, {})
        self.assertNotIn("secret-sentinel", json.dumps(report))
        self.assertNotIn("@", json.dumps(report))

    def test_no_serial_means_no_subprocess(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(
                doctor.subprocess, "run", side_effect=AssertionError("No subprocess")), \
                contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(doctor.main(["--workspace", tmp]), 0)
            self.assertEqual(json.loads(output.getvalue())["mode"], "host_only")

    def test_host_inventory_does_not_read_keys(self):
        with tempfile.TemporaryDirectory() as tmp:
            key = Path(tmp) / "openai-adapter/local-signing.p12"
            key.parent.mkdir()
            key.write_text("secret-sentinel")
            result = doctor.local_inventory(Path(tmp))
            self.assertNotIn("secret-sentinel", json.dumps(result))
            self.assertIsNone(result["local_apk_sha256"]["openai"])

    def test_serial_redaction_is_recursive(self):
        result = doctor.redact_selected_serial({"model": ["PRIVATE_SERIAL"]}, "PRIVATE_SERIAL")
        self.assertEqual(result, {"model": ["<SERIAL>"]})


if __name__ == "__main__":
    unittest.main()
