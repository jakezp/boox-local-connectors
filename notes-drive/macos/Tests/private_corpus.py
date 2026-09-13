#!/usr/bin/python3
"""Optional local regression only; requires explicitly named private inputs.

No default paths, discovery, fixtures, titles, account IDs or private hashes are
embedded. Generated edits stay in memory. Never include supplied notebooks or
this optional suite's local logs in a public source package.
"""
import argparse
from hashlib import sha256
import io
from pathlib import Path
import sys
import unittest
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "Resources"))
import note_reader as reader
import note_editor as editor
import note_library as library
from synthetic_fixtures import EDIT_ID, ADDED_PEN_ID, NEXT_PEN_ID, ADDED_POINTS


def members(data):
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        return {name: archive.read(name) for name in archive.namelist()}


def edit_plan(data):
    note = reader.inspect_bytes(data)
    page = note["pages"][0]
    layer = next(layer["id"] for layer in page["layers"] if layer["visible"] and not layer["locked"])
    return dict(base_sha256=sha256(data).hexdigest(), document_id=note["document_id"],
                edit_id=EDIT_ID, edited_at_ms=1700000000000,
                operations=[dict(kind="add", id=ADDED_PEN_ID, page_id=page["id"], layer=layer,
                                 width=4, color=0xff1756aa, points=ADDED_POINTS)])


def suite(native20, offline, readback):
    paths = [Path(native20), Path(offline), Path(readback)]
    for path in paths:
        if not path.is_file():
            raise ValueError("An explicitly supplied private input is missing")
        if path.stat().st_size > 4 * 1024 * 1024:
            raise ValueError("Private editor fixture exceeds 4 MiB")
    original_bytes = [path.read_bytes() for path in paths]

    class PrivateCorpusTests(unittest.TestCase):
        def test_twenty_page_reader_and_pen_edit_preserve_unsupported_content(self):
            data = original_bytes[0]
            before = reader.inspect_bytes(data)
            self.assertEqual(len(before["pages"]), 20)
            self.assertEqual(before["sample_count"], 54234)
            self.assertEqual(sum(s["supported"] for p in before["pages"] for s in p["strokes"]), 319)
            self.assertEqual(sum(len(p["strokes"]) for p in before["pages"]), 333)
            edited, _ = editor.edit_bytes(data, edit_plan(data))
            after = reader.inspect_bytes(edited)
            self.assertEqual(after["document_id"], before["document_id"])
            self.assertEqual(sum(s["supported"] for p in after["pages"] for s in p["strokes"]), 320)
            self.assertEqual([s for p in before["pages"] for s in p["strokes"] if not s["supported"]],
                             [s for p in after["pages"] for s in p["strokes"] if not s["supported"]])

        def test_offline_chunk_edit_preserves_native_stash_and_points(self):
            data = original_bytes[1]
            before = reader.inspect_bytes(data)
            self.assertEqual(before["sample_count"], 154)
            self.assertEqual(len(before["pages"][0]["strokes"]), 5)
            plan = edit_plan(data)
            added, _ = editor.edit_bytes(data, plan)
            after = reader.inspect_bytes(added)
            self.assertEqual(len(after["pages"][0]["strokes"]), 6)
            plan = edit_plan(added)
            plan["operations"] = [dict(kind="erase", page_id=before["pages"][0]["id"],
                                       ids=[before["pages"][0]["strokes"][0]["id"]])]
            erased, _ = editor.edit_bytes(added, plan)
            self.assertEqual(len(reader.inspect_bytes(erased)["pages"][0]["strokes"]), 5)
            saved = members(erased)
            for name, value in members(data).items():
                if "/stash/" in name:
                    self.assertEqual(saved[name], value)

        def test_native_readback_proto3_omission_stays_editable(self):
            data = original_bytes[2]
            before = reader.inspect_bytes(data)
            pens = {s["id"]: s for p in before["pages"] for s in p["strokes"] if s["supported"]}
            self.assertEqual(len(pens), 6)
            omitted = []
            for name, value in members(data).items():
                if name.startswith(before["document_id"] + "/shape/") and name.endswith(".zip"):
                    for raw in reader.protobuf(next(iter(members(value).values()))).get(1, []):
                        fields = reader.protobuf(raw)
                        if reader.single(fields, 12, 0) == 2 and 6 not in fields:
                            omitted.append(reader.text(reader.single(fields, 1)))
            self.assertTrue(omitted)
            plan = edit_plan(data)
            plan["operations"][0]["id"] = NEXT_PEN_ID
            edited, _ = editor.edit_bytes(data, plan)
            after = {s["id"]: s for p in reader.inspect_bytes(edited)["pages"] for s in p["strokes"] if s["supported"]}
            self.assertEqual(len(after), 7)
            for key, pen in pens.items():
                self.assertEqual(after[key], pen)

        def test_metadata_changes_preserve_all_nonmetadata_members(self):
            for data in original_bytes:
                note = reader.inspect_bytes(data)
                plan = dict(operation="metadata", document_id=note["document_id"], base_sha256=sha256(data).hexdigest(),
                            title="Private local regression rename", parent=None, edited_at_ms=1700000000000)
                changed = library.change_bytes(data, plan)
                before, after = members(data), members(changed)
                for name, value in before.items():
                    if not name.endswith("/note/pb/note_info"):
                        self.assertEqual(after[name], value)
                self.assertEqual(reader.inspect_bytes(changed)["pages"], note["pages"])

        def test_private_originals_remain_byte_identical(self):
            for path, original in zip(paths, original_bytes):
                self.assertEqual(path.read_bytes(), original)

    return unittest.defaultTestLoader.loadTestsFromTestCase(PrivateCorpusTests)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--native20", required=True)
    parser.add_argument("--offline", required=True)
    parser.add_argument("--readback", required=True)
    arguments = parser.parse_args()
    result = unittest.TextTestRunner(verbosity=2).run(suite(arguments.native20, arguments.offline, arguments.readback))
    sys.exit(0 if result.wasSuccessful() else 1)
