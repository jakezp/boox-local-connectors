"""Offline source packaging safety tests; all inputs are synthetic temporary files."""

import contextlib
import io
import json
import os
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest import mock

import package_source as package


class SourcePackageTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.root = self.base / "source"
        self.root.mkdir()
        self.files = {
            "README.md": b"# Synthetic source\n",
            "AGENTS.md": b"# Synthetic maintainer instructions\n",
            ".gitignore": b"*.note\n*.p12\n",
            "docs/ROOT-STAGING.md": b"# Synthetic manual root staging guide\n",
            "tools/prepare_magisk_stage.py": b'"""Synthetic staging helper."""\n',
            "tools/test_prepare_magisk_stage.py": b'"""Synthetic staging tests."""\n',
            "notes-drive/macos/test.sh": b"#!/bin/sh\nexit 0\n",
            "notes-drive/macos/Tests/protocol-vectors.json": b'{"synthetic":true}\n',
            "notes-drive/macos/Tests/synthetic-manifest.json": b'{"schema":1}\n',
            "notes-drive/macos/Tests/native-apply-manifest.json": b'{"synthetic_only":true}\n',
            "openai-adapter/res/values/strings.xml": b"<resources/>\n",
        }
        for name, data in self.files.items():
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
        self.inventory = self.root / package.INVENTORY
        self.inventory.parent.mkdir(parents=True)
        self.entries = [
            {"path": name, "bytes": len(data), "sha256": package.sha(data)}
            for name, data in self.files.items()
        ]
        self.save_inventory()

    def save_inventory(self):
        self.inventory.write_bytes(package.canonical({"schema": 1, "files": self.entries}))

    def cli(self, *args):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            result = package.main(["--root", str(self.root), *map(str, args)])
        return result, out.getvalue(), err.getvalue()

    def test_default_is_read_only_and_has_no_subprocess(self):
        before = {p.relative_to(self.root): p.read_bytes()
                  for p in self.root.rglob("*") if p.is_file()}
        with mock.patch("subprocess.run", side_effect=AssertionError("subprocess forbidden")):
            result, out, err = self.cli()
        self.assertEqual(result, 0, err)
        self.assertEqual(json.loads(out)["status"], "ready_for_content_review")
        self.assertEqual(before, {p.relative_to(self.root): p.read_bytes()
                                 for p in self.root.rglob("*") if p.is_file()})

    def test_plan_stable_and_includes_inventory_and_named_inputs(self):
        a, _ = package.plan_source(self.root)
        b, _ = package.plan_source(self.root)
        self.assertEqual(package.canonical(a), package.canonical(b))
        self.assertEqual({entry["path"] for entry in a["files"]},
                         set(self.files) | {package.INVENTORY})

    def test_validates_entire_allowlist_before_any_source_read(self):
        self.entries.append({"path": "tools/credentials.py", "bytes": 1,
                             "sha256": "0" * 64})
        self.save_inventory()
        original = package.read_regular
        names = []

        def reading(root, name):
            names.append(name)
            return original(root, name)

        with mock.patch.object(package, "read_regular", side_effect=reading):
            with self.assertRaises(package.PackageError):
                package.plan_source(self.root)
        self.assertEqual(names, [package.INVENTORY])

    def test_rejects_traversal_absolute_hidden_and_private_paths(self):
        for name in (
            "../README.md", "/README.md", "tools/../README.md",
            "tools\\README.md", "tools//boox_doctor.py",
            "notes-drive/macos/.test-runs/test.sh",
            "notes-drive/macos/Resources/oauth-desktop.json",
            "notes-drive/android/local.properties", "notes-drive/tests/artifacts/a.note",
            "tools/edl/edl.py", "assistant-decompiled/sources/X.java",
            "openai-adapter/src/evidence/X.java",
            "notes-drive/macos/Resources/Local.app/a.py",
        ):
            with self.subTest(name=name):
                self.assertFalse(package.allowed(name))

    def test_rejects_duplicate_and_case_collision(self):
        for name in ("README.md", "readme.md"):
            self.entries.append({"path": name, "bytes": 0, "sha256": "0" * 64})
            self.save_inventory()
            with self.assertRaises(package.PackageError):
                package.plan_source(self.root)
            self.entries.pop()

    def test_rejects_bad_digest_or_size(self):
        for key, value in (("sha256", "bad"), ("bytes", -1), ("bytes", True)):
            old = self.entries[0][key]
            self.entries[0][key] = value
            self.save_inventory()
            with self.assertRaises(package.PackageError):
                package.plan_source(self.root)
            self.entries[0][key] = old

    def test_symlink_file_and_directory_never_followed(self):
        path = self.root / "README.md"
        path.unlink()
        outside = self.base / "outside"
        outside.write_text("private synthetic marker")
        path.symlink_to(outside)
        plan, payloads = package.plan_source(self.root)
        self.assertNotIn("README.md", payloads)
        self.assertIn("unreadable_or_unsafe_input", [x["rule"] for x in plan["findings"]])
        nested = self.root / "openai-adapter/res"
        original = nested.with_name("saved-res")
        nested.rename(original)
        nested.symlink_to(original, target_is_directory=True)
        _, payloads = package.plan_source(self.root)
        self.assertNotIn("openai-adapter/res/values/strings.xml", payloads)

    def test_regular_hardlink_is_copied_as_bytes_and_fifo_is_refused(self):
        path = self.root / "README.md"
        os.link(path, self.base / "second-link")
        plan, payloads = package.plan_source(self.root)
        self.assertEqual(plan["status"], "ready_for_content_review")
        self.assertEqual(payloads["README.md"], self.files["README.md"])
        path.unlink()
        os.mkfifo(path)
        plan, _ = package.plan_source(self.root)
        self.assertEqual(plan["status"], "blocked")

    def test_changed_missing_and_binary_inputs_block(self):
        for content in (b"changed\n", b"\x00\xff"):
            (self.root / "README.md").write_bytes(content)
            plan, _ = package.plan_source(self.root)
            self.assertEqual(plan["status"], "blocked")
            self.assertIn("inventory_digest_or_size_mismatch",
                          [x["rule"] for x in plan["findings"]])
        self.assertIn("non_text_source", [x["rule"] for x in plan["findings"]])
        (self.root / "README.md").unlink()
        plan, _ = package.plan_source(self.root)
        self.assertEqual(plan["file_count"], len(self.files))

    def test_privacy_diagnostics_never_echo_matched_value(self):
        secret = "sk-" + "SYNTHETIC" * 5
        body = ("test\n" + secret + "\n").encode()
        (self.root / "README.md").write_bytes(body)
        self.entries[0].update(bytes=len(body), sha256=package.sha(body))
        self.save_inventory()
        result, out, err = self.cli()
        self.assertEqual(result, 1)
        self.assertNotIn(secret, out + err)
        self.assertIn("api_key_literal", out)
        self.assertIn('"line": 2', out)

    def test_reserved_synthetic_email_domains_are_not_private_findings(self):
        body = b"fixture@example.invalid\nfixture@example.test\n"
        (self.root / "README.md").write_bytes(body)
        self.entries[0].update(bytes=len(body), sha256=package.sha(body))
        self.save_inventory()
        plan, _ = package.plan_source(self.root)
        self.assertEqual(plan["status"], "ready_for_content_review")

    def test_size_limit_blocks(self):
        with mock.patch.object(package, "MAX_TOTAL", 1):
            with self.assertRaises(package.PackageError):
                package.plan_source(self.root)

    def test_export_requires_matching_plan_and_content_review(self):
        plan, _ = package.plan_source(self.root)
        review = self.base / "plan.json"
        review.write_bytes(package.canonical(plan))
        destination = self.base / "source.tar"
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            self.cli("--export", destination)
        (self.root / "README.md").write_text("changed")
        result, _, _ = self.cli("--export", destination, "--reviewed-plan", review,
                                "--content-reviewed")
        self.assertEqual(result, 2)
        self.assertFalse(destination.exists())

    def test_matching_blocked_plan_still_cannot_export(self):
        (self.root / "README.md").write_text("changed")
        plan, _ = package.plan_source(self.root)
        review = self.base / "blocked.json"
        review.write_bytes(package.canonical(plan))
        target = self.base / "blocked.tar"
        result, _, _ = self.cli("--export", target, "--reviewed-plan", review,
                                "--content-reviewed")
        self.assertEqual(result, 2)
        self.assertFalse(target.exists())

    def test_export_deterministic_and_contains_only_checked_bytes(self):
        plan, payloads = package.plan_source(self.root)
        a, b = self.base / "a.tar", self.base / "b.tar"
        package.export_tar(plan, payloads, a)
        # Source mutations after planning cannot alter the retained byte snapshot.
        (self.root / "README.md").write_text("changed")
        package.export_tar(plan, payloads, b)
        self.assertEqual(a.read_bytes(), b.read_bytes())
        self.assertEqual(a.stat().st_mode & 0o777, 0o600)
        with tarfile.open(a) as archive:
            self.assertEqual(archive.getnames(), sorted(payloads))
            for member in archive.getmembers():
                self.assertTrue(member.isfile())
                self.assertEqual(member.mtime, 0)
                self.assertEqual(member.uid, 0)
                self.assertEqual(archive.extractfile(member).read(), payloads[member.name])
            self.assertEqual(archive.getmember("notes-drive/macos/test.sh").mode, 0o755)

    def test_cli_exports_accepted_review(self):
        plan, _ = package.plan_source(self.root)
        review = self.base / "review.json"
        review.write_bytes(package.canonical(plan))
        target = self.base / "accepted.tar"
        result, _, err = self.cli("--export", target, "--reviewed-plan", review,
                                  "--content-reviewed")
        self.assertEqual(result, 0, err)
        self.assertTrue(target.exists())

    def test_never_overwrites_output_or_exports_into_source(self):
        target = self.base / "existing.json"
        target.write_bytes(b"preserve")
        result, _, _ = self.cli("--write-plan", target)
        self.assertEqual(result, 2)
        self.assertEqual(target.read_bytes(), b"preserve")
        result, _, _ = self.cli("--write-plan", self.root / "new.json")
        self.assertEqual(result, 2)
        self.assertFalse((self.root / "new.json").exists())

    def test_error_messages_do_not_expose_external_paths(self):
        self.entries.append({"path": "../sensitive-marker", "bytes": 0, "sha256": "0" * 64})
        self.save_inventory()
        result, out, err = self.cli()
        self.assertEqual(result, 2)
        self.assertNotIn("sensitive-marker", out + err)
        self.assertNotIn(str(self.root), out + err)


if __name__ == "__main__":
    unittest.main()
