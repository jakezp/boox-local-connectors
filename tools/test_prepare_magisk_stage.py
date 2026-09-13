#!/usr/bin/env python3
"""Offline safety tests; original private input comparison requires explicit paths."""

import argparse
from contextlib import contextmanager, redirect_stderr
from io import BytesIO, StringIO
import json
import os
from pathlib import Path
import stat
import sys
import tempfile
import unittest
from unittest import mock
import warnings
import zipfile

import prepare_magisk_stage as stage


ORIGINAL_INPUTS = None


def make_zip(members):
    buffer = BytesIO()
    with warnings.catch_warnings():
        warnings.simplefilter("ignore", UserWarning)
        with zipfile.ZipFile(buffer, "w") as archive:
            for name, data in members:
                archive.writestr(name, data)
    return buffer.getvalue()


class SafetyTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.apk = self.root / "input.apk"
        self.boot = self.root / "original.img"
        self.output = self.root / "new-stage"

    @contextmanager
    def synthetic_inputs(self, members=None):
        """Test-only pins: production exposes no digest/ABI override argument."""
        data = b"#!/system/bin/sh\nexit 99\n"
        member = "assets/boot_patch.sh"
        apk = make_zip(members if members is not None else [(member, data)])
        boot = b"synthetic original boot"
        self.apk.write_bytes(apk)
        self.boot.write_bytes(boot)
        with mock.patch.multiple(
            stage,
            APK_SHA256=stage.sha256(apk),
            APK_SIZE=len(apk),
            BOOT_SHA256=stage.sha256(boot),
            BOOT_SIZE=len(boot),
            ASSETS=((member, "boot_patch.sh", stage.sha256(data), len(data)),),
        ):
            yield

    def prepare(self):
        return stage.prepare_stage(self.apk, self.boot, self.output)

    def assert_refused(self, message):
        with self.assertRaisesRegex(stage.StageError, message):
            self.prepare()
        self.assertFalse(self.output.exists())

    def test_production_pins_reject_arbitrary_inputs(self):
        self.apk.write_bytes(b"not the pinned APK")
        self.boot.write_bytes(b"not the pinned boot")
        self.assert_refused("APK: size")

    def test_same_size_wrong_apk_hash(self):
        with self.synthetic_inputs():
            data = bytearray(self.apk.read_bytes())
            data[-1] ^= 1
            self.apk.write_bytes(data)
            self.assert_refused("APK: SHA-256")

    def test_same_size_wrong_boot_hash(self):
        with self.synthetic_inputs():
            self.boot.write_bytes(b"x" * self.boot.stat().st_size)
            self.assert_refused("original boot B: SHA-256")

    def test_wrong_boot_size(self):
        with self.synthetic_inputs():
            self.boot.write_bytes(b"short")
            self.assert_refused("original boot B: size")

    def test_rejects_symlink_inputs(self):
        with self.synthetic_inputs():
            for path in (self.apk, self.boot):
                with self.subTest(input=path.name):
                    original = path.read_bytes()
                    target = path.with_suffix(".target")
                    path.rename(target)
                    path.symlink_to(target)
                    self.assert_refused("symbolic-link inputs")
                    path.unlink()
                    path.write_bytes(original)

    @unittest.skipUnless(hasattr(os, "mkfifo"), "host has no FIFO support")
    def test_rejects_fifo_without_blocking(self):
        fifo = self.root / "pipe"
        os.mkfifo(fifo)
        with self.assertRaisesRegex(stage.StageError, "regular file"):
            stage.read_input(fifo, 0, stage.sha256(b""), "test")

    def test_existing_directory_is_untouched_before_reading_inputs(self):
        self.output.mkdir()
        sentinel = self.output / "keep"
        sentinel.write_bytes(b"preserve")
        with mock.patch.object(stage, "read_input", side_effect=AssertionError("unexpected input read")):
            with self.assertRaisesRegex(stage.StageError, "already exists"):
                self.prepare()
        self.assertEqual(sentinel.read_bytes(), b"preserve")
        self.assertEqual(list(self.output.iterdir()), [sentinel])

    def test_existing_file_is_untouched(self):
        self.output.write_bytes(b"preserve")
        with self.assertRaisesRegex(stage.StageError, "already exists"):
            self.prepare()
        self.assertEqual(self.output.read_bytes(), b"preserve")

    def test_dangling_output_symlink_is_untouched(self):
        target = self.root / "absent"
        self.output.symlink_to(target)
        with self.assertRaisesRegex(stage.StageError, "already exists"):
            self.prepare()
        self.assertTrue(self.output.is_symlink())
        self.assertFalse(target.exists())

    def test_missing_parent_is_not_created(self):
        self.output = self.root / "missing" / "stage"
        with self.assertRaises(OSError):
            self.prepare()
        self.assertFalse(self.output.parent.exists())

    def test_output_parent_traversal_is_rejected(self):
        self.output = self.root / "child" / ".." / "stage"
        self.assert_refused("parent traversal")

    def test_unsafe_zip_paths_fail_before_output_creation(self):
        for name in ("../escape", "/absolute", "a/../escape", "a\\escape", "C:/escape", "a//b", "a//", "./a", "bad\nname"):
            with self.subTest(name=name), self.synthetic_inputs([(name, b"invalid")]):
                self.assert_refused("unsafe ZIP")
        self.assertFalse((self.root / "escape").exists())

    def test_nul_member_path_is_rejected(self):
        info = zipfile.ZipInfo("a")
        info.orig_filename = "a\0hidden"
        archive = mock.Mock()
        archive.infolist.return_value = [info]
        with self.assertRaisesRegex(stage.StageError, "unsafe ZIP"):
            stage.validate_zip(archive)

    def test_duplicate_and_case_collisions_are_rejected(self):
        for names in (("a", "a"), ("assets/boot_patch.sh", "assets/BOOT_PATCH.SH"), ("a", "a/")):
            with self.subTest(names=names), self.synthetic_inputs([(name, b"") for name in names]):
                self.assert_refused("duplicate or ambiguous")

    def test_case_distinct_unused_android_resources_are_not_extracted(self):
        members = [
            ("assets/boot_patch.sh", b"#!/system/bin/sh\nexit 99\n"),
            ("res/2F.xml", b"first"),
            ("res/2f.xml", b"second"),
        ]
        with self.synthetic_inputs(members):
            self.prepare()
        self.assertFalse((self.output / "res").exists())
        self.assertEqual(
            {path.name for path in (self.output / "payload").iterdir()},
            {"boot_patch.sh", "boot_b.img"},
        )

    def test_file_directory_collision_is_rejected(self):
        with self.synthetic_inputs([("a", b""), ("a/b", b"")]):
            self.assert_refused("file/directory collision")

    def test_zip_symlinks_and_special_files_are_rejected(self):
        for kind in (stat.S_IFLNK, stat.S_IFIFO, stat.S_IFCHR, stat.S_IFBLK, stat.S_IFSOCK):
            info = zipfile.ZipInfo("unused")
            info.create_system = 3
            info.external_attr = (kind | 0o777) << 16
            with self.subTest(kind=kind), self.synthetic_inputs([(info, b"target")]):
                self.assert_refused("links and special")

    def test_encrypted_compression_and_expansion_guards(self):
        for attr, value, message in (
            ("flag_bits", 1, "encrypted"),
            ("compress_type", zipfile.ZIP_BZIP2, "compression"),
            ("file_size", 33 * 1024 * 1024, "expansion"),
        ):
            info = zipfile.ZipInfo("a")
            setattr(info, attr, value)
            archive = mock.Mock()
            archive.infolist.return_value = [info]
            with self.subTest(attr=attr), self.assertRaisesRegex(stage.StageError, message):
                stage.validate_zip(archive)
        entries = [zipfile.ZipInfo(str(number)) for number in range(5)]
        for info in entries:
            info.file_size = 32 * 1024 * 1024
        archive.infolist.return_value = entries
        with self.assertRaisesRegex(stage.StageError, "expansion"):
            stage.validate_zip(archive)

    def test_missing_and_corrupt_required_assets(self):
        for members in ([], [("assets/boot_patch.sh", b"x" * 25)]):
            with self.subTest(members=members), self.synthetic_inputs(members):
                self.assert_refused("required asset")

    def test_asset_hash_guard_independent_of_apk_pin(self):
        original = b"#!/system/bin/sh\nexit 99\n"
        corrupt = b"x" * len(original)
        with self.synthetic_inputs([("assets/boot_patch.sh", corrupt)]):
            self.assert_refused("asset SHA-256")

    def test_invalid_zip_reports_sanitized_error_without_output(self):
        with self.synthetic_inputs():
            data = b"x" * self.apk.stat().st_size
            self.apk.write_bytes(data)
            with mock.patch.object(stage, "APK_SHA256", stage.sha256(data)), redirect_stderr(StringIO()) as errors:
                status = stage.main(["--apk", str(self.apk), "--original-boot", str(self.boot), "--output", str(self.output)])
        self.assertEqual(status, 1)
        self.assertNotIn(str(self.root), errors.getvalue())
        self.assertFalse(self.output.exists())

    def test_verified_snapshots_are_written_without_rereading_inputs(self):
        with self.synthetic_inputs():
            expected = self.boot.read_bytes()
            original_reader = stage.read_assets

            def change_input_after_verification(apk):
                self.boot.write_bytes(b"x" * len(expected))
                return original_reader(apk)

            with mock.patch.object(stage, "read_assets", side_effect=change_input_after_verification):
                self.prepare()
            self.assertEqual((self.output / "payload/boot_b.img").read_bytes(), expected)

    def test_output_race_does_not_overwrite(self):
        with self.synthetic_inputs():
            original_reader = stage.read_assets

            def create_destination(apk):
                self.output.mkdir()
                (self.output / "keep").write_bytes(b"preserve")
                return original_reader(apk)

            with mock.patch.object(stage, "read_assets", side_effect=create_destination):
                with self.assertRaises(FileExistsError):
                    self.prepare()
        self.assertEqual((self.output / "keep").read_bytes(), b"preserve")
        self.assertEqual(len(list(self.output.iterdir())), 1)

    def test_write_failure_does_not_claim_completion(self):
        with self.synthetic_inputs(), mock.patch.object(stage, "write_new", side_effect=OSError("disk full")):
            with self.assertRaises(OSError):
                self.prepare()
        self.assertTrue(self.output.is_dir())
        self.assertFalse((self.output / "manifest.json").exists())

    def test_success_is_inert_private_checksummed_and_explicitly_unpatched(self):
        with self.synthetic_inputs(), mock.patch("subprocess.Popen", side_effect=AssertionError("execution forbidden")), mock.patch("os.system", side_effect=AssertionError("execution forbidden")):
            before = (self.apk.read_bytes(), self.boot.read_bytes())
            manifest = self.prepare()
            self.assertEqual(before, (self.apk.read_bytes(), self.boot.read_bytes()))
        self.assertEqual(manifest["status"], "prepared_not_patched")
        self.assertFalse(manifest["historical_comparison"]["new_patch_verified"])
        self.assertEqual(manifest["patch_flags"], stage.PATCH_FLAGS)
        self.assertEqual(json.loads((self.output / "manifest.json").read_text()), manifest)
        self.assertNotIn(str(self.root), json.dumps(manifest))
        for record in manifest["files"]:
            path = self.output / record["path"]
            self.assertEqual(stage.sha256(path.read_bytes()), record["sha256"])
            self.assertEqual(path.stat().st_size, record["bytes"])
        for line in (self.output / "SHA256SUMS").read_text().splitlines():
            digest, name = line.split("  ", 1)
            self.assertEqual(stage.sha256((self.output / name).read_bytes()), digest)
        self.assertFalse((self.output / "payload/new-boot.img").exists())
        if os.name == "posix":
            for path in self.output.rglob("*"):
                self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o700 if path.is_dir() else 0o600)
        instructions = (self.output / "DEVICE-PATCH.md").read_text()
        self.assertIn("TMPDIR=\"$PWD/patch-tmp\"", instructions)
        self.assertIn("env -i", instructions)
        for key, value in stage.PATCH_FLAGS.items():
            self.assertIn(f"{key}={value}", instructions)

    def test_repeated_stages_have_identical_manifests(self):
        with self.synthetic_inputs():
            first = self.prepare()
            self.output = self.root / "another-stage"
            self.assertEqual(first, self.prepare())

    def test_cli_has_no_hash_or_abi_bypass(self):
        for option in ("--expected-sha256", "--skip-verification", "--abi", "--force"):
            with self.subTest(option=option), redirect_stderr(StringIO()):
                with self.assertRaises(SystemExit) as error:
                    stage.main(["--apk", "a", "--original-boot", "b", "--output", "c", option, "value"])
                self.assertEqual(error.exception.code, 2)


class OriginalInputTests(unittest.TestCase):
    def test_explicit_original_inputs_against_retained_environment(self):
        if ORIGINAL_INPUTS is None:
            self.skipTest("private original inputs not requested")
        apk, boot, reference = ORIGINAL_INPUTS
        # No private inputs are copied into the repository. The normal strict
        # production pins remain active; this is a temporary host-only stage.
        with tempfile.TemporaryDirectory(prefix="boox-magisk-offline-") as directory:
            output = Path(directory) / "stage"
            manifest = stage.prepare_stage(apk, boot, output)
            self.assertEqual(manifest["status"], "prepared_not_patched")
            self.assertEqual(len(stage.ASSETS), 15)
            for _, destination, digest, size in stage.ASSETS:
                with self.subTest(asset=destination):
                    retained = stage.read_input(reference / destination, size, digest, "retained asset")
                    self.assertEqual((output / "payload" / destination).read_bytes(), retained)
            staged_boot = stage.read_input(output / "payload/boot_b.img", stage.BOOT_SIZE, stage.BOOT_SHA256, "staged boot")
            self.assertEqual(staged_boot, stage.read_input(boot, stage.BOOT_SIZE, stage.BOOT_SHA256, "original boot"))
            stage.read_input(apk, stage.APK_SIZE, stage.APK_SHA256, "original APK")
            self.assertFalse((output / "payload/new-boot.img").exists())


def main():
    global ORIGINAL_INPUTS
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--original-boot", type=Path)
    parser.add_argument("--reference-env", type=Path)
    args = parser.parse_args()
    provided = (args.apk, args.original_boot, args.reference_env)
    if any(provided) and not all(provided):
        parser.error("the optional original-input comparison requires all three paths")
    ORIGINAL_INPUTS = provided if all(provided) else None
    suite = unittest.defaultTestLoader.loadTestsFromModule(sys.modules[__name__])
    return 0 if unittest.TextTestRunner(verbosity=2).run(suite).wasSuccessful() else 1


if __name__ == "__main__":
    raise SystemExit(main())
