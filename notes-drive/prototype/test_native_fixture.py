"""Regression evidence from actual disposable notebook exports."""

from copy import deepcopy
from io import BytesIO
from pathlib import Path
import os
import struct
import unittest
from zipfile import BadZipFile, ZipFile

from native_fixture import bounded_zip, compare, inspect_archive, point_records, protobuf


PRIVATE_ROOT = os.environ.get("BOOX_PRIVATE_WORKSPACE")
ARTIFACTS = Path(PRIVATE_ROOT) / "notes-drive/tests/artifacts" if PRIVATE_ROOT else None


@unittest.skipUnless(PRIVATE_ROOT, "Historical live corpus: set BOOX_PRIVATE_WORKSPACE explicitly")
class NativeFixtureTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.archives = {name: inspect_archive(ARTIFACTS / f"{name}.note")
                        for name in ("A1", "B1", "B2", "C1", "D1")}

    def comparison(self, source, destination):
        return compare(self.archives[source], self.archives[destination])["pages"]

    def test_native_copy_preserves_eight_pen_strokes_and_style(self):
        source = self.archives["A1"]
        self.assertEqual([len(p["shapes"]) for p in source["pages"]], [4, 4])
        self.assertEqual(sum(s["point_count"] for p in source["pages"]
                             for s in p["shapes"]), 248)
        self.assertTrue(all(p["pen_data_and_style_equal"]
                            for p in self.comparison("A1", "B1")))
        self.assertNotEqual(source["document_id"], self.archives["B1"]["document_id"])
        self.assertTrue(all(a["id"] != b["id"] for a, b in
                            zip(source["pages"], self.archives["B1"]["pages"])))

    def test_imported_copy_remains_editable(self):
        pages = self.comparison("B1", "B2")
        self.assertTrue(all(p["source_pens_preserved"] for p in pages))
        self.assertEqual([p["added_pens"] for p in pages], [0, 3])
        self.assertEqual([len(p["layers"]) for p in self.archives["B2"]["pages"]], [1, 2])
        self.assertEqual(self.archives["B1"]["document_id"],
                         self.archives["B2"]["document_id"])

    def test_stock_import_reproduces_document_page_link_mismatch(self):
        pages = self.comparison("B2", "C1")
        self.assertTrue(all(p["pen_data_and_style_equal"] for p in pages))
        self.assertTrue(all(p["layers_equal"] for p in pages))
        self.assertFalse(pages[1]["internal_links_remapped"])
        link = next(s["link"] for s in self.archives["C1"]["pages"][1]["shapes"]
                    if "link" in s)
        self.assertEqual(link["document_id"], self.archives["B2"]["document_id"])
        self.assertEqual(link["page_id"], self.archives["C1"]["pages"][0]["id"])

    def test_probe_repairs_link_and_preserves_pen_data_and_layers(self):
        pages = self.comparison("B2", "D1")
        for page in pages:
            self.assertTrue(page["pen_data_and_style_equal"])
            self.assertTrue(page["layers_equal"])
            self.assertTrue(page["dimensions_equal"])
            self.assertTrue(page["internal_links_remapped"])
        self.assertEqual(pages[1]["destination_links"], 1)
        destination = self.archives["D1"]
        link = next(s["link"] for s in destination["pages"][1]["shapes"] if "link" in s)
        self.assertEqual(link["document_id"], destination["document_id"])

    def test_reordered_pages_are_not_mistaken_for_fidelity(self):
        changed = deepcopy(self.archives["D1"])
        changed["pages"].reverse()
        self.assertFalse(all(p["pen_data_and_style_equal"] for p in
                             compare(self.archives["B2"], changed)["pages"]))

    def test_wrong_link_page_index_is_rejected(self):
        changed = deepcopy(self.archives["D1"])
        link = next(s["link"] for s in changed["pages"][1]["shapes"] if "link" in s)
        link["page_index"] = 1
        self.assertFalse(compare(self.archives["B2"], changed)["pages"][1]
                         ["internal_links_remapped"])

    def test_missing_pen_stroke_is_detected(self):
        changed = deepcopy(self.archives["D1"])
        changed["pages"][0]["shapes"].pop()
        self.assertFalse(compare(self.archives["B2"], changed)["pages"][0]
                         ["source_pens_preserved"])

    def test_truncated_archive_is_rejected(self):
        with self.assertRaises(BadZipFile):
            inspect_archive(ARTIFACTS / "Corrupt.note")

    def point_data(self):
        with ZipFile(ARTIFACTS / "A1.note") as archive:
            name = next(n for n in archive.namelist()
                        if "/point/" in n and n.endswith("#points"))
            return archive.read(name)

    def test_corrupt_point_offsets_are_rejected(self):
        data = bytearray(self.point_data())
        xref = struct.unpack_from(">I", data, len(data) - 4)[0]
        struct.pack_into(">I", data, xref + 36, 0)
        with self.assertRaisesRegex(ValueError, "point block"):
            point_records(data)

    def test_unknown_point_version_is_rejected(self):
        data = bytearray(self.point_data())
        struct.pack_into(">h", data, 2, 2)
        with self.assertRaisesRegex(ValueError, "point version"):
            point_records(data)

    def test_pressure_sample_change_is_detected(self):
        data = bytearray(self.point_data())
        _, before = point_records(data)
        # Header 76, attributes 4, first x/y 8: mutate a sample short.
        data[88] ^= 1
        _, after = point_records(data)
        self.assertNotEqual(before, after)

    def test_truncated_protobuf_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "Truncated"):
            protobuf(b"\x0a\x05a")

    def test_unsafe_archive_path_is_rejected_without_extraction(self):
        data = BytesIO()
        with ZipFile(data, "w") as archive:
            archive.writestr("../escape", b"fixture")
        with self.assertRaisesRegex(ValueError, "Unsafe"):
            bounded_zip(data.getvalue())


if __name__ == "__main__":
    unittest.main()
