"""Synthetic, offline archive checks. Never archive the real workspace or keys."""

import contextlib
import io
import json
import os
from pathlib import Path
import random
import tarfile
import tempfile
import unittest
from unittest import mock

import archive_private_workspace as archive


class ArchiveTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.root = self.base / "source"
        self.root.mkdir()
        (self.root / ".git").mkdir()
        (self.root / ".git/config").write_text("synthetic repository metadata\n")
        (self.root / ".gitignore").write_text("*.p12\nignored/\n")
        (self.root / "ignored").mkdir()
        (self.root / "ignored/key.p12").write_bytes(b"SYNTHETIC_PRIVATE_SENTINEL" * 25)
        (self.root / "large.bin").write_bytes(bytes(range(256)) * 100)
        (self.root / "empty").mkdir()
        (self.root / "executable.sh").write_text("#!/bin/sh\nexit 0\n")
        (self.root / "executable.sh").chmod(0o751)
        os.utime(self.root / "executable.sh", ns=(1_600_000_000_123456789,) * 2)
        os.link(self.root / "large.bin", self.root / "hardlink.bin")
        (self.root / "internal-link").symlink_to("large.bin")
        self.external = self.base / "external-private"
        self.external.write_bytes(b"OUTSIDE_SCOPE_SENTINEL")
        (self.root / "external-link").symlink_to(self.external)
        (self.root / "broken-link").symlink_to("../missing")
        self.output = self.base / "archive"

    def create(self):
        return archive.create(self.root, self.output, chunk_size=8192)

    def members(self):
        manifest = json.loads((self.output / "archive-manifest.json").read_text())
        data = b"".join((self.output / part["name"]).read_bytes() for part in manifest["chunks"])
        return tarfile.open(fileobj=io.BytesIO(data), mode="r:")

    def test_plan_never_opens_file_payloads_and_lists_no_values(self):
        out = io.StringIO()
        with mock.patch.object(Path, "open", side_effect=AssertionError("payload opened")), \
                contextlib.redirect_stdout(out):
            result = archive.main(["--root", str(self.root)])
        self.assertEqual(result, 0)
        report = json.loads(out.getvalue())
        self.assertEqual(report["symlinks"], 3)
        self.assertEqual(report["direct_external_symlinks"], 2)
        self.assertEqual(report["exclusions"], [])
        self.assertNotIn("SENTINEL", out.getvalue())
        self.assertNotIn("key.p12", out.getvalue())
        self.assertFalse(self.output.exists())

    def test_complete_ignored_hidden_content_links_modes_and_inventory(self):
        result = self.create()
        self.assertGreater(result["chunks"], 1)
        report = archive.verify(self.output)
        self.assertEqual(report["status"], "verified_all_chunks_and_inventory")
        self.assertEqual(self.output.stat().st_mode & 0o777, 0o700)
        with self.members() as tar:
            names = set(tar.getnames())
            expected = {"workspace" if row["path"] == "." else "workspace/" + row["path"]
                        for row in archive.scan(self.root)}
            self.assertEqual(names, expected | {"__archive__/inventory.json"})
            self.assertEqual(tar.extractfile("workspace/ignored/key.p12").read(),
                             (self.root / "ignored/key.p12").read_bytes())
            self.assertEqual(tar.getmember("workspace/executable.sh").mode, 0o751)
            self.assertTrue(tar.getmember("workspace/external-link").issym())
            self.assertEqual(tar.getmember("workspace/external-link").linkname, str(self.external))
            self.assertTrue(tar.getmember("workspace/large.bin").islnk())
            self.assertEqual(tar.extractfile("workspace/large.bin").read(),
                             (self.root / "large.bin").read_bytes())
            self.assertNotIn(b"OUTSIDE_SCOPE_SENTINEL", tar.extractfile("__archive__/inventory.json").read())

    def test_source_is_unchanged_after_create(self):
        before = archive.scan(self.root)
        self.create()
        self.assertEqual(archive.scan(self.root), before)

    def test_deterministic_bytes_for_same_source(self):
        a = self.create()
        b = archive.create(self.root, self.base / "second", chunk_size=8192)
        self.assertEqual(a["archive_sha256"], b["archive_sha256"])

    def test_interruption_resume_reuses_completed_chunks(self):
        original = archive.SplitWriter.commit
        committed = []

        def stop_after_two(writer):
            original(writer)
            committed.append(writer.index)
            if writer.index == 3:
                raise RuntimeError("synthetic interruption")

        with mock.patch.object(archive.SplitWriter, "commit", stop_after_two):
            with self.assertRaises(RuntimeError):
                self.create()
        self.assertFalse((self.output / "archive-manifest.json").exists())
        first = self.output / archive.part_name(1)
        first_stat, first_bytes = first.stat(), first.read_bytes()
        result = archive.create(self.root, self.output, 8192, resume=True)
        self.assertEqual(result["status"], "complete_unencrypted_local_archive")
        self.assertEqual(first.read_bytes(), first_bytes)
        self.assertEqual(first.stat().st_mtime_ns, first_stat.st_mtime_ns)
        self.assertEqual(archive.verify(self.output)["status"], "verified_all_chunks_and_inventory")

    def test_partial_interruption_resume(self):
        original = archive.SplitWriter.write
        stopped = False

        def stop(writer, data):
            nonlocal stopped
            if not stopped:
                stopped = True
                original(writer, data[:3000])
                raise RuntimeError("partial interruption")
            return original(writer, data)

        with mock.patch.object(archive.SplitWriter, "write", stop):
            with self.assertRaises(RuntimeError):
                self.create()
        archive.create(self.root, self.output, 8192, resume=True)
        archive.verify(self.output)
        self.assertFalse(list(self.output.glob("*.partial")))

    def test_resume_refuses_changed_source_and_bad_chunk(self):
        self.create()
        (self.root / "large.bin").write_bytes(b"changed")
        with self.assertRaises(archive.ArchiveError):
            archive.create(self.root, self.output, 8192, resume=True)
        chunk = self.output / archive.part_name(1)
        chunk.write_bytes(b"corrupt")
        with self.assertRaises(archive.ArchiveError):
            archive.verify(self.output)

    def test_source_change_mid_archive_never_gets_complete_receipt(self):
        original = archive.stream_archive

        def mutate(root, entries, writer, compress=False):
            (root / "new-file").write_bytes(b"new")
            return original(root, entries, writer, compress)

        with mock.patch.object(archive, "stream_archive", mutate):
            with self.assertRaises(archive.ArchiveError):
                self.create()
        self.assertFalse((self.output / "archive-manifest.json").exists())

    def test_special_entry_errors_instead_of_silent_omission(self):
        os.mkfifo(self.root / "unsupported")
        with self.assertRaises(archive.ArchiveError):
            archive.scan(self.root)

    def test_inside_output_and_existing_directory_rejected(self):
        with self.assertRaises(archive.ArchiveError):
            archive.create(self.root, self.root / "recursive")
        self.output.mkdir()
        sentinel = self.output / "keep"
        sentinel.write_text("preserve")
        with self.assertRaises(FileExistsError):
            self.create()
        self.assertEqual(sentinel.read_text(), "preserve")

    def test_symlink_control_or_chunk_never_followed(self):
        self.create()
        manifest = self.output / "archive-manifest.json"
        manifest.unlink()
        manifest.symlink_to(self.external)
        with self.assertRaises(OSError):
            archive.verify(self.output)

    def test_cli_error_does_not_expose_paths_or_values(self):
        os.mkfifo(self.root / "sensitive-file-name")
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = archive.main(["--root", str(self.root)])
        self.assertEqual(code, 2)
        self.assertNotIn("sensitive-file-name", err.getvalue())
        self.assertNotIn(str(self.root), err.getvalue())
        self.assertNotIn("SENTINEL", out.getvalue() + err.getvalue())

    def test_gzip_stream_is_deterministic_and_fully_verifies(self):
        a = archive.create(self.root, self.output, 8192, compress=True)
        b = archive.create(self.root, self.base / "second-gzip", 8192, compress=True)
        self.assertEqual(a["archive_sha256"], b["archive_sha256"])
        manifest = json.loads((self.output / "archive-manifest.json").read_text())
        self.assertEqual(manifest["format"], "split-gzip-pax-tar")
        self.assertEqual(archive.verify(self.output)["status"], "verified_all_chunks_and_inventory")
        with self.assertRaises(archive.ArchiveError):
            archive.create(self.root, self.output, 8192, resume=True, compress=False)

    def test_gzip_interruption_resume_preserves_completed_parts(self):
        (self.root / "incompressible").write_bytes(random.Random(42).randbytes(100000))
        original = archive.SplitWriter.commit

        def stop(writer):
            original(writer)
            if writer.index == 3:
                raise RuntimeError("gzip interruption")

        with mock.patch.object(archive.SplitWriter, "commit", stop):
            with self.assertRaises(RuntimeError):
                archive.create(self.root, self.output, 8192, compress=True)
        first = self.output / archive.part_name(1)
        before = first.read_bytes(), first.stat().st_mtime_ns
        archive.create(self.root, self.output, 8192, resume=True, compress=True)
        self.assertEqual((first.read_bytes(), first.stat().st_mtime_ns), before)
        archive.verify(self.output)

    def test_resume_adopts_complete_chunk_after_ledger_write_interruption(self):
        original = archive.replace_state

        def fail_first_commit(directory, state):
            if state["chunks"]:
                raise RuntimeError("ledger interruption")
            return original(directory, state)

        with mock.patch.object(archive, "replace_state", fail_first_commit):
            with self.assertRaises(RuntimeError):
                self.create()
        first = self.output / archive.part_name(1)
        before = first.read_bytes(), first.stat().st_mtime_ns
        archive.create(self.root, self.output, 8192, resume=True)
        self.assertEqual((first.read_bytes(), first.stat().st_mtime_ns), before)
        archive.verify(self.output)

    def test_deep_inventory_rejects_changed_payload_even_with_new_transport_hashes(self):
        self.create()
        manifest_path = self.output / "archive-manifest.json"
        manifest = json.loads(manifest_path.read_text())
        data = bytearray(b"".join((self.output / c["name"]).read_bytes() for c in manifest["chunks"]))
        with tarfile.open(fileobj=io.BytesIO(data), mode="r:") as tar:
            offset = tar.getmember("workspace/ignored/key.p12").offset_data
        data[offset] ^= 1
        position = 0
        for chunk in manifest["chunks"]:
            part = bytes(data[position:position + chunk["bytes"]])
            (self.output / chunk["name"]).write_bytes(part)
            chunk["sha256"] = archive.digest(part)
            position += len(part)
        manifest["archive_sha256"] = archive.digest(data)
        manifest_path.write_bytes(archive.encoded(manifest))
        with self.assertRaisesRegex(archive.ArchiveError, "Per-entry"):
            archive.verify(self.output)

    def test_recovery_needs_only_parts_and_manifest_and_refuses_overwrite(self):
        archive.create(self.root, self.output, 8192, compress=True)
        for name in ("source-index.json", "state.json", ".archive.lock"):
            (self.output / name).unlink()
        # Unknown extra files must not enter reassembly through a wildcard.
        (self.output / "part-999999.tarpart").write_bytes(b"unlisted")
        target = self.base / "restored.tar.gz"
        result = archive.reassemble(self.output, target)
        self.assertEqual(result["status"], "reassembled_and_verified")
        with tarfile.open(target, "r:gz") as tar:
            self.assertEqual(tar.extractfile("workspace/ignored/key.p12").read(),
                             (self.root / "ignored/key.p12").read_bytes())
        before = target.read_bytes()
        with self.assertRaises(FileExistsError):
            archive.reassemble(self.output, target)
        self.assertEqual(target.read_bytes(), before)

    def test_reassembly_rejects_corrupt_parts_before_creating_output(self):
        self.create()
        (self.output / archive.part_name(1)).write_bytes(b"damaged")
        target = self.base / "refused.tar"
        with self.assertRaises(archive.ArchiveError):
            archive.reassemble(self.output, target)
        self.assertFalse(target.exists())


if __name__ == "__main__":
    unittest.main()
