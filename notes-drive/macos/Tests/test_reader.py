import io
import json
from hashlib import sha256
from pathlib import Path
import struct
import sys
import unittest
from unittest import mock
import zipfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "Resources"))
import note_reader as reader
from synthetic_fixtures import fixture_directory, TITLES, identifier

FIXTURES = fixture_directory()
CHUNKED_FIXTURE = FIXTURES / "chunked-20-page.note"
HISTORY_FIXTURE = FIXTURES / "history-five-pen.note"
MANIFEST = json.loads((Path(__file__).parent / "synthetic-manifest.json").read_text())
SECOND_REVISION = "00000000-0000-4000-8000-000000000002"


def zip_entries(data):
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        return {name: z.read(name) for name in z.namelist()}


def zip_bytes(entries):
    result = io.BytesIO()
    with zipfile.ZipFile(result, "w", zipfile.ZIP_DEFLATED) as z:
        for name, data in entries.items():
            z.writestr(name, data)
    return result.getvalue()


def raw_point_blocks(data):
    xref = struct.unpack_from(">I", data, len(data) - 4)[0]
    result = {}
    for pos in range(xref, len(data) - 4, 44):
        shape_id, offset, length = struct.unpack_from(">36sII", data, pos)
        result[shape_id] = data[offset:offset + length]
    return result


def point_chunk(header, revision, blocks):
    """Repackage exact fixture blocks using the native fixed-width index."""
    result, index = bytearray(header[:40] + revision.encode("ascii")), bytearray()
    for shape_id, block in blocks.items():
        index.extend(struct.pack(">36sII", shape_id, len(result), len(block)))
        result.extend(block)
    xref = len(result)
    result.extend(index)
    result.extend(struct.pack(">I", xref))
    return bytes(result)


def split_fixture():
    """Model the stock exporter's >20k-sample rollover with existing pen data."""
    entries = zip_entries((FIXTURES / "two-page-variant.note").read_bytes())
    point_name = next(n for n in entries if n.endswith("#points"))
    data = entries[point_name]
    page_id, old_revision = data[4:40].decode().strip(), data[40:76].decode()
    blocks = raw_point_blocks(data)
    keys = list(blocks)
    first = {k: blocks[k] for k in keys[:len(keys) // 2]}
    second = {k: blocks[k] for k in keys[len(keys) // 2:]}
    entries[point_name] = point_chunk(data, old_revision, first)
    second_name = point_name.replace(old_revision, SECOND_REVISION)
    entries[second_name] = point_chunk(data, SECOND_REVISION, second)
    shape_name = next(n for n in entries if "/shape/" + page_id + "#" in n)
    inner = zip_entries(entries[shape_name])
    inner_name, raw = next(iter(inner.items()))
    for shape in reader.protobuf(raw)[1]:
        if reader.single(reader.protobuf(shape), 1) in second:
            raw = raw.replace(shape, shape.replace(old_revision.encode(),
                                                   SECOND_REVISION.encode()), 1)
    inner[inner_name] = raw
    entries[shape_name] = zip_bytes(inner)
    return entries, second_name


def shape_document(records):
    """Wrap existing raw protobuf shapes as repeated length-delimited field 1."""
    result = bytearray()
    for record in records:
        result.append(10)
        size = len(record)
        while size >= 128:
            result.append((size & 127) | 128)
            size >>= 7
        result.append(size)
        result.extend(record)
    return bytes(result)


def split_shape_fixture():
    entries, _ = split_fixture()
    shape_name = next(n for n in entries if "/shape/" in n and n.endswith(".zip"))
    inner_name, raw = next(iter(zip_entries(entries[shape_name]).items()))
    records = reader.protobuf(raw)[1]
    first, second = records[:2], records[2:]
    old_revision = shape_name.split("#")[1]
    second = [r.replace(old_revision.encode(), SECOND_REVISION.encode()) for r in second]
    entries[shape_name] = zip_bytes({inner_name: shape_document(first)})
    second_name = shape_name.replace(old_revision, SECOND_REVISION)
    second_inner_name = inner_name.replace(old_revision, SECOND_REVISION)
    entries[second_name] = zip_bytes({second_inner_name: shape_document(second)})
    return entries, shape_name, second_name


class ReaderTests(unittest.TestCase):
    def test_synthetic_fixture_counts_hashes_and_link(self):
        note = reader.inspect_path(FIXTURES / "two-page.note")
        self.assertEqual(note["sha256"], MANIFEST["two-page.note"]["sha256"])
        self.assertEqual(note["sample_count"], 343)
        self.assertEqual([len(p["layers"]) for p in note["pages"]], [1, 2])
        self.assertEqual([sum(s["supported"] for s in p["strokes"]) for p in note["pages"]], [4, 7])
        links = [s for p in note["pages"] for s in p["strokes"] if s["type"] == 33]
        self.assertEqual(len(links), 1)
        self.assertEqual(links[0]["target_page"], note["pages"][0]["id"])
        self.assertFalse(links[0]["supported"])
        self.assertTrue(any("pressure" in w for w in note["warnings"]))

    def test_second_fixture_preserves_coordinate_sequences(self):
        target = reader.inspect_path(FIXTURES / "two-page.note")
        other = reader.inspect_path(FIXTURES / "two-page-variant.note")
        for left, right in zip(target["pages"], other["pages"]):
            self.assertEqual(sorted(s["point_hash"] for s in left["strokes"] if s["type"] == 2),
                             sorted(s["point_hash"] for s in right["strokes"] if s["type"] == 2))
        first = target["pages"][0]["strokes"][0]["points"][0]
        self.assertEqual(first, [120.0, 180.0])

    def test_originals_match_before_after(self):
        path = FIXTURES / "two-page-variant.note"
        before = path.read_bytes()
        reader.inspect_path(path)
        self.assertEqual(before, path.read_bytes())

    def test_native_library_summary_uses_actual_title_and_pages(self):
        path = FIXTURES / "two-page.note"
        note = reader.inspect_path(path)
        summary = reader.inspect_path(path, note["sha256"], metadata_only=True)
        self.assertEqual(summary["title"], note["title"])
        self.assertEqual(summary["document_id"], note["document_id"])
        self.assertEqual(summary["page_count"], 2)
        self.assertEqual(summary["page_ids"], [p["id"] for p in note["pages"]])
        self.assertNotIn("pages", summary)

    def test_metadata_available_when_point_version_unsupported(self):
        changed = io.BytesIO()
        with zipfile.ZipFile(FIXTURES / "two-page-variant.note") as original, zipfile.ZipFile(changed, "w") as output:
            for info in original.infolist():
                content = original.read(info)
                if info.filename.endswith("#points"):
                    content = struct.pack(">hh", 0, 2) + content[4:]
                output.writestr(info, content)
        summary = reader.inspect_bytes(changed.getvalue(), metadata_only=True)
        self.assertEqual(summary["title"], TITLES["two-page-variant.note"])
        with self.assertRaisesRegex(ValueError, "Unsupported point version"):
            reader.inspect_bytes(changed.getvalue())

    def test_expected_hash_rejected_before_zip_decode(self):
        with self.assertRaisesRegex(ValueError, "before archive decoding"):
            reader.inspect_path(FIXTURES / "two-page-variant.note", "0" * 64)

    def test_nested_budget_and_traversal_rejected(self):
        for name in ("../bad", "/absolute", "x\\y"):
            output = io.BytesIO()
            with zipfile.ZipFile(output, "w") as z:
                z.writestr(name, b"x")
            with self.assertRaisesRegex(ValueError, "Unsafe"):
                reader.archive(output.getvalue(), [0])
        output = io.BytesIO()
        with zipfile.ZipFile(output, "w") as z:
            z.writestr("safe", b"abc")
        with self.assertRaisesRegex(ValueError, "Expanded"):
            reader.archive(output.getvalue(), [reader.MAX_BYTES-2])

    def test_duplicate_zip_entries_rejected(self):
        output = io.BytesIO()
        import warnings
        with warnings.catch_warnings():
            warnings.simplefilter("ignore")
            with zipfile.ZipFile(output, "w") as z:
                z.writestr("same", b"1")
                z.writestr("same", b"2")
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            reader.archive(output.getvalue(), [0])

    def test_nan_coordinate_and_bad_index_rejected(self):
        with zipfile.ZipFile(FIXTURES / "two-page.note") as z:
            name = next(n for n in z.namelist() if n.endswith("#points"))
            data = bytearray(z.read(name))
        struct.pack_into(">f", data, 80, float("nan"))
        with self.assertRaisesRegex(ValueError, "Invalid pen coordinate"):
            reader.point_records(data, [0])
        data[-4:] = struct.pack(">I", 77)
        with self.assertRaisesRegex(ValueError, "Invalid point index"):
            reader.point_records(data, [0])

    def test_truncated_and_overflow_protobuf(self):
        for value in (b"\x0a\x05hi", b"\x08" + b"\xff"*10, b"\0"):
            with self.assertRaises(ValueError):
                reader.protobuf(value)

    def test_non_fixture_title_supported(self):
        # Viewer has no disposable-title gate; publication is restricted separately.
        data = (FIXTURES / "two-page-variant.note").read_bytes()
        changed = io.BytesIO()
        with zipfile.ZipFile(io.BytesIO(data)) as original, zipfile.ZipFile(changed, "w") as output:
            for info in original.infolist():
                content = original.read(info)
                if info.filename.endswith("/note/pb/note_info"):
                    old = TITLES["two-page-variant.note"].encode()
                    content = content.replace(old, b"My notebook".ljust(len(old), b" "))
                output.writestr(info, content)
        self.assertTrue(reader.inspect_bytes(changed.getvalue())["title"].startswith("My notebook"))

    def test_synthetic_twenty_page_fixture_and_both_point_chunks(self):
        original = CHUNKED_FIXTURE.read_bytes()
        note = reader.inspect_path(CHUNKED_FIXTURE, MANIFEST[CHUNKED_FIXTURE.name]["sha256"])
        self.assertEqual(note["title"], TITLES[CHUNKED_FIXTURE.name])
        self.assertEqual(len(note["pages"]), 20)
        self.assertEqual(note["sample_count"], 54234)
        self.assertEqual([sum(s["supported"] for s in p["strokes"]) for p in note["pages"]],
                         [197, 108, 8, 1, 1, 1, 0, 1, 2] + [0] * 11)
        unsupported = [s["type"] for p in note["pages"] for s in p["strokes"] if not s["supported"]]
        self.assertEqual(unsupported, [2000] * 14)
        first_page = note["pages"][0]
        chunks = [data for name, data in zip_entries(original).items()
                  if name.startswith(note["document_id"] + "/point/") and name.endswith("#points") and
                  data[4:40].decode().strip() == first_page["id"]]
        self.assertEqual(len(chunks), 2)
        self.assertEqual([len(raw_point_blocks(c)) for c in chunks], [151, 48])
        expected_hashes = sorted(sha256(b).hexdigest() for c in chunks
                                 for b in raw_point_blocks(c).values())
        self.assertEqual(sorted(s["point_hash"] for s in first_page["strokes"]), expected_hashes)
        self.assertFalse(any("missing" in w or "unreferenced" in w
                             for p in note["pages"] for w in p["warnings"]))
        self.assertTrue(any("infinite" in w for w in note["warnings"]))
        self.assertTrue(any("historical shapes" in w for w in note["warnings"]))
        self.assertTrue(any("Resource records" in w for w in note["warnings"]))
        self.assertEqual(CHUNKED_FIXTURE.read_bytes(), original)

    def test_multiple_point_chunks_preserve_every_fixture_stroke(self):
        entries, _ = split_fixture()
        note = reader.inspect_bytes(zip_bytes(entries))
        original = reader.inspect_path(FIXTURES / "two-page-variant.note")
        self.assertEqual(note["pages"], original["pages"])
        self.assertEqual(note["sample_count"], 343)

    def test_conflicting_duplicate_point_revision_rejected(self):
        entries, second_name = split_fixture()
        conflicting = bytearray(entries[second_name])
        struct.pack_into(">f", conflicting, 80, 12345)
        entries[second_name + ".duplicate#points"] = conflicting
        with self.assertRaisesRegex(ValueError, "Duplicate point revision"):
            reader.inspect_bytes(zip_bytes(entries))

    def test_retired_revision_cannot_replace_referenced_points(self):
        original = reader.inspect_path(FIXTURES / "two-page-variant.note")
        entries = zip_entries((FIXTURES / "two-page-variant.note").read_bytes())
        point_name = next(n for n in entries if n.endswith("#points"))
        retired = bytearray(entries[point_name])
        retired[40:76] = SECOND_REVISION.encode()
        struct.pack_into(">f", retired, 80, 12345)
        entries[point_name + ".retired#points"] = retired
        note = reader.inspect_bytes(zip_bytes(entries))
        self.assertEqual(note["pages"][0]["strokes"], original["pages"][0]["strokes"])
        self.assertTrue(any("4 unreferenced point blocks" in w
                            for w in note["pages"][0]["warnings"]))

    def test_missing_point_revision_never_borrows_another_chunks_shape(self):
        entries, second_name = split_fixture()
        changed = bytearray(entries[second_name])
        changed[40:76] = b"00000000-0000-4000-8000-000000000099"
        entries[second_name] = changed
        note = reader.inspect_bytes(zip_bytes(entries))
        strokes = note["pages"][0]["strokes"]
        self.assertEqual(sum(s["supported"] for s in strokes), 2)
        self.assertTrue(all(s["point_hash"] is None and s["points"] == []
                            for s in strokes if not s["supported"]))
        self.assertTrue(any("recorded revision" in w for w in note["pages"][0]["warnings"]))

    def test_chunked_page_retains_notebook_point_budget(self):
        entries, _ = split_fixture()
        with mock.patch.object(reader, "MAX_POINTS", 342):
            with self.assertRaisesRegex(ValueError, "point preview limit"):
                reader.inspect_bytes(zip_bytes(entries))

    def test_unknown_point_pages_remain_rejected(self):
        entries, second_name = split_fixture()
        changed = bytearray(entries[second_name])
        changed[4:40] = b"00000000-0000-4000-8000-000000000099"
        entries[second_name] = changed
        with self.assertRaisesRegex(ValueError, "Unknown point page"):
            reader.inspect_bytes(zip_bytes(entries))

    def test_invalid_additional_chunk_still_rejected(self):
        entries, second_name = split_fixture()
        changed = bytearray(entries[second_name])
        struct.pack_into(">f", changed, 80, float("nan"))
        entries[second_name] = changed
        with self.assertRaisesRegex(ValueError, "Invalid pen coordinate"):
            reader.inspect_bytes(zip_bytes(entries))

    def test_active_shape_id_conflict_still_rejected(self):
        entries, _ = split_fixture()
        shape_name = next(n for n in entries if "/shape/" in n and n.endswith(".zip"))
        inner = zip_entries(entries[shape_name])
        inner_name, raw = next(iter(inner.items()))
        shapes = reader.protobuf(raw)[1]
        first_id = reader.single(reader.protobuf(shapes[0]), 1)
        second_id = reader.single(reader.protobuf(shapes[1]), 1)
        inner[inner_name] = raw.replace(second_id, first_id)
        entries[shape_name] = zip_bytes(inner)
        with self.assertRaisesRegex(ValueError, "Duplicate shape ID"):
            reader.inspect_bytes(zip_bytes(entries))

    def test_synthetic_history_keeps_five_active_pens_and_omits_stash_duplicate(self):
        original = HISTORY_FIXTURE.read_bytes()
        note = reader.inspect_path(HISTORY_FIXTURE, MANIFEST[HISTORY_FIXTURE.name]["sha256"])
        self.assertEqual(len(note["pages"]), 1)
        self.assertEqual(note["sample_count"], 154)
        strokes = note["pages"][0]["strokes"]
        self.assertEqual(len(strokes), 5)
        self.assertTrue(all(s["supported"] for s in strokes))
        self.assertEqual(sum(s["id"] == identifier("history/pen/4")
                             for s in strokes), 1)
        active_points = [b for n, b in zip_entries(original).items()
                         if n.startswith(note["document_id"] + "/point/") and n.endswith("#points")]
        self.assertEqual(sorted(s["point_hash"] for s in strokes),
                         sorted(sha256(b).hexdigest() for chunk in active_points
                                for b in raw_point_blocks(chunk).values()))
        self.assertFalse(any("missing" in w or "unreferenced" in w for w in note["pages"][0]["warnings"]))
        self.assertTrue(any("historical shapes" in w for w in note["warnings"]))
        self.assertTrue(any("1 point/shape files outside" in w for w in note["warnings"]))
        self.assertEqual(HISTORY_FIXTURE.read_bytes(), original)

    def test_disjoint_active_shape_chunks_keep_all_fixture_strokes(self):
        entries, _, _ = split_shape_fixture()
        original = reader.inspect_path(FIXTURES / "two-page-variant.note")
        for ordered in (entries, dict(reversed(list(entries.items())))):
            note = reader.inspect_bytes(zip_bytes(ordered))
            self.assertEqual(note["sample_count"], original["sample_count"])
            for left, right in zip(note["pages"], original["pages"]):
                self.assertEqual(sorted(left["strokes"], key=lambda s: s["id"]),
                                 sorted(right["strokes"], key=lambda s: s["id"]))
                self.assertEqual(left["layers"], right["layers"])
            self.assertTrue(any("drawing order is approximate" in w
                                for w in note["pages"][0]["warnings"]))

    def test_stashed_point_and_shape_copies_cannot_collide_with_active_records(self):
        entries = zip_entries((FIXTURES / "two-page-variant.note").read_bytes())
        shape_name = next(n for n in entries if "/shape/" in n and n.endswith(".zip"))
        point_name = next(n for n in entries if n.endswith("#points"))
        entries[shape_name.replace("/shape/", "/stash/shape/")] = entries[shape_name]
        entries[point_name.replace("/point/", "/stash/point/")] = entries[point_name]
        note = reader.inspect_bytes(zip_bytes(entries))
        original = reader.inspect_path(FIXTURES / "two-page-variant.note")
        self.assertEqual(note["pages"], original["pages"])
        self.assertEqual(note["sample_count"], original["sample_count"])
        self.assertTrue(any("2 point/shape files outside" in w for w in note["warnings"]))

    def test_stashed_shapes_never_fill_a_missing_active_shape_archive(self):
        entries = zip_entries((FIXTURES / "two-page-variant.note").read_bytes())
        shape_name = next(n for n in entries if "/shape/" in n and n.endswith(".zip"))
        entries[shape_name.replace("/shape/", "/stash/shape/")] = entries.pop(shape_name)
        note = reader.inspect_bytes(zip_bytes(entries))
        self.assertEqual(note["pages"][0]["strokes"], [])
        self.assertTrue(any("unreferenced point blocks" in w for w in note["pages"][0]["warnings"]))
        self.assertTrue(any("historical shapes" in w for w in note["warnings"]))

    def test_active_shape_id_collision_across_chunks_rejected(self):
        entries, first_name, second_name = split_shape_fixture()
        first_raw = next(iter(zip_entries(entries[first_name]).values()))
        inner_name, second_raw = next(iter(zip_entries(entries[second_name]).items()))
        first_id = reader.single(reader.protobuf(reader.protobuf(first_raw)[1][0]), 1)
        second_id = reader.single(reader.protobuf(reader.protobuf(second_raw)[1][0]), 1)
        entries[second_name] = zip_bytes({inner_name: second_raw.replace(second_id, first_id)})
        with self.assertRaisesRegex(ValueError, "Duplicate shape ID"):
            reader.inspect_bytes(zip_bytes(entries))

    def test_active_shape_revision_collision_rejected_even_for_disjoint_ids(self):
        entries, first_name, second_name = split_shape_fixture()
        first_revision = first_name.split("#")[1]
        colliding_name = second_name.replace(SECOND_REVISION, first_revision)
        colliding_name = colliding_name.rsplit("#", 1)[0] + "#9999999999999.zip"
        entries[colliding_name] = entries.pop(second_name)
        with self.assertRaisesRegex(ValueError, "Duplicate shape revision"):
            reader.inspect_bytes(zip_bytes(entries))

    def test_malformed_additional_active_shape_chunk_rejected(self):
        entries, _, second_name = split_shape_fixture()
        inner_name = next(iter(zip_entries(entries[second_name])))
        entries[second_name] = zip_bytes({inner_name: b"\x0a\x80"})
        with self.assertRaisesRegex(ValueError, "Truncated protobuf integer"):
            reader.inspect_bytes(zip_bytes(entries))

    def test_shape_limit_accumulates_across_chunks(self):
        entries, _, _ = split_shape_fixture()
        with mock.patch.object(reader, "MAX_SHAPES", 11):
            with self.assertRaisesRegex(ValueError, "Too many shapes"):
                reader.inspect_bytes(zip_bytes(entries))

    def test_unknown_shape_document_fields_are_flagged(self):
        entries, _, second_name = split_shape_fixture()
        inner_name, raw = next(iter(zip_entries(entries[second_name]).items()))
        entries[second_name] = zip_bytes({inner_name: raw + b"\x10\x01"})
        note = reader.inspect_bytes(zip_bytes(entries))
        self.assertEqual(sum(s["supported"] for p in note["pages"] for s in p["strokes"]), 11)
        self.assertTrue(any("Unknown shape archive fields" in w for w in note["pages"][0]["warnings"]))


if __name__ == "__main__":
    unittest.main()
