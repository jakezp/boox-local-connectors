"""Offline bootstrap tests using tiny synthetic archives; never fetch or execute."""

import contextlib
import hashlib
import io
import json
from pathlib import Path
import stat
import tempfile
import unittest
from unittest import mock
import zipfile

import bootstrap_dependencies as bootstrap


class BootstrapTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.archive_root = self.root / "archives"
        (self.archive_root / "tools").mkdir(parents=True)
        self.archive = self.archive_root / "tools/synthetic.zip"
        self.make_archive()
        self.item = {
            "name": "synthetic", "version": "1",
            "source_url": "https://dl.google.com/synthetic.zip",
            "bytes": self.archive.stat().st_size,
            "sha256": hashlib.sha256(self.archive.read_bytes()).hexdigest(),
            "local_archive": "tools/synthetic.zip",
            "bootstrap": {"zip_layouts": [
                {"archive_prefix": "android-15/", "destination": "tools/android-sdk/android-15"},
                {"archive_prefix": "android-15/", "destination": "tools/android-sdk/build-tools/35.0.0"},
            ]},
        }
        self.manifest = self.root / "manifest.json"
        self.save_manifest()

    def make_archive(self, name="android-15/bin/tool", mode=stat.S_IFREG | 0o755):
        with zipfile.ZipFile(self.archive, "w") as z:
            info = zipfile.ZipInfo(name)
            info.external_attr = mode << 16
            z.writestr(info, b"synthetic bytes, never executed\n")

    def save_manifest(self):
        self.manifest.write_bytes(bootstrap.encoded({"schema": 1, "artifacts": [self.item]}))

    def plan(self):
        return bootstrap.plan_dependencies(self.manifest, ["synthetic"])

    def test_dry_run_deterministic_without_network(self):
        with mock.patch("urllib.request.build_opener", side_effect=AssertionError("network forbidden")):
            a = self.plan()
            b = self.plan()
        self.assertEqual(a, b)
        self.assertEqual(a["status"], "planned_not_acquired")

    def test_offline_apply_checks_archives_and_materializes_both_sdk_layouts(self):
        target = self.root / "destination"
        opener = mock.Mock()
        opener.open.side_effect = AssertionError("network forbidden")
        with mock.patch("urllib.request.build_opener", return_value=opener):
            result = bootstrap.materialize(self.plan(), target, self.archive_root, True)
        self.assertEqual(result["status"], "dependencies_materialized_not_executed")
        legacy = target / "tools/android-sdk/android-15/bin/tool"
        standard = target / "tools/android-sdk/build-tools/35.0.0/bin/tool"
        self.assertEqual(legacy.read_bytes(), standard.read_bytes())
        self.assertEqual(legacy.stat().st_mode & 0o777, 0o755)
        self.assertFalse(legacy.is_symlink())

    def test_wrong_hash_blocks_all_extraction(self):
        self.item["sha256"] = "0" * 64
        self.save_manifest()
        target = self.root / "destination"
        with self.assertRaises(bootstrap.BootstrapError):
            bootstrap.materialize(self.plan(), target, self.archive_root, True)
        self.assertFalse((target / "tools/android-sdk").exists())
        self.assertFalse((target / "dependency-receipt.json").exists())

    def test_existing_destination_is_untouched(self):
        target = self.root / "existing"
        target.mkdir()
        sentinel = target / "keep"
        sentinel.write_text("unchanged")
        with self.assertRaises(FileExistsError):
            bootstrap.materialize(self.plan(), target, self.archive_root, True)
        self.assertEqual(sentinel.read_text(), "unchanged")
        self.assertEqual(list(target.iterdir()), [sentinel])

    def test_unpinned_unknown_and_duplicate_selections_fail(self):
        for names in (["missing"], ["synthetic", "synthetic"], []):
            with self.assertRaises(bootstrap.BootstrapError):
                bootstrap.plan_dependencies(self.manifest, names)
        self.item["sha256"] = None
        self.save_manifest()
        with self.assertRaises(bootstrap.BootstrapError):
            self.plan()

    def test_invalid_urls_paths_and_overlaps_fail(self):
        for url in ("http://dl.google.com/a", "https://" + "user:pass@" + "dl.google.com/a",
                    "https://127.0.0.1/a", "file:///tmp/a"):
            with self.assertRaises(bootstrap.BootstrapError):
                bootstrap.check_url(url)
        self.item["local_archive"] = "../outside"
        self.save_manifest()
        with self.assertRaises(bootstrap.BootstrapError):
            self.plan()
        self.item["local_archive"] = "tools/synthetic.zip"
        self.item["bootstrap"]["zip_layouts"][1]["destination"] = "tools/android-sdk/android-15"
        self.save_manifest()
        with self.assertRaises(bootstrap.BootstrapError):
            self.plan()

    def test_traversal_symlink_and_unexpected_root_are_rejected_before_extraction(self):
        for name, mode in (("../outside", stat.S_IFREG), ("android-15/link", stat.S_IFLNK),
                           ("/absolute", stat.S_IFREG), ("wrong-root/a", stat.S_IFREG)):
            self.make_archive(name, mode)
            with self.assertRaises(bootstrap.BootstrapError):
                bootstrap.inspect_zip(self.archive, "android-15/")

    def test_duplicate_and_oversized_archives_rejected(self):
        with zipfile.ZipFile(self.archive, "w") as z:
            z.writestr("android-15/A", b"a")
            z.writestr("android-15/a", b"b")
        with self.assertRaises(bootstrap.BootstrapError):
            bootstrap.inspect_zip(self.archive, "android-15/")
        self.make_archive()
        with mock.patch.object(bootstrap, "MAX_EXPANDED", 1):
            with self.assertRaises(bootstrap.BootstrapError):
                bootstrap.inspect_zip(self.archive, "android-15/")

    def test_symlink_local_archive_never_followed(self):
        other = self.root / "opaque"
        self.archive.rename(other)
        self.archive.symlink_to(other)
        with self.assertRaises(OSError):
            bootstrap.open_local(self.archive_root, "tools/synthetic.zip")

    def test_cli_requires_matching_review_and_sanitizes_errors(self):
        review = self.root / "review.json"
        review.write_text("{}")
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            result = bootstrap.main([
                "--manifest", str(self.manifest), "--select", "synthetic",
                "--apply", "--destination", str(self.root / "result"),
                "--reviewed-plan", str(review), "--offline",
                "--archive-root", str(self.archive_root),
            ])
        self.assertEqual(result, 2)
        self.assertNotIn(str(self.root), out.getvalue() + err.getvalue())
        self.assertFalse((self.root / "result").exists())


if __name__ == "__main__":
    unittest.main()
