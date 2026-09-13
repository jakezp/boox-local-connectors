import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest
import uuid
from hashlib import sha256

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "Resources"))
sys.path.insert(0, str(ROOT / "Tests"))
import note_library as library
import note_editor as editor
import note_reader as reader
from test_editor import members, plan_for
from synthetic_fixtures import fixture_directory


def creation(count=1):
    return dict(operation="create", document_id=uuid.uuid4().hex,
                page_ids=[uuid.uuid4().hex for _ in range(count)], title="Mac — é/😀",
                parent=None, width=1860, height=2480, edited_at_ms=1789250000000)


def metadata(data, parent=None):
    return dict(operation="metadata", base_sha256=sha256(data).hexdigest(),
                document_id=reader.inspect_bytes(data)["document_id"], title="Renamed — é/😀",
                parent=parent, edited_at_ms=1789250000000)


class LibraryTests(unittest.TestCase):
    def test_new_blank_has_fresh_identities_pages_and_editable_layers_without_cloned_records(self):
        for count in (1, 32):
            plan = creation(count)
            data = library.create_bytes(plan)
            note = reader.inspect_bytes(data)
            self.assertEqual(note["document_id"], plan["document_id"])
            self.assertEqual([p["id"] for p in note["pages"]], plan["page_ids"])
            self.assertEqual(note["sample_count"], 0)
            for page in note["pages"]:
                self.assertEqual(page["strokes"], [])
                self.assertEqual(page["layers"], [dict(id=0, visible=True, locked=False)])
                self.assertEqual((page["width"], page["height"]), (1860, 2480))
            files = members(data)
            self.assertTrue(all(n.startswith(plan["document_id"] + "/") for n in files))
            raw = reader.single(reader.protobuf(files[plan["document_id"] + "/note/pb/note_info"]), 1)
            fields = reader.protobuf(raw)
            self.assertEqual(reader.single(fields, 8), 1)
            self.assertEqual(reader.single(fields, 31), 1)
            self.assertNotIn(39, fields)
            self.assertFalse(any("/resource/" in n or "/stash/" in n or "/virtual/" in n for n in files))

    def test_new_blank_add_erase_and_second_page_edits_use_existing_pen_writer(self):
        data = library.create_bytes(creation(2))
        plan = plan_for(data)
        plan["operations"][0]["page_id"] = reader.inspect_bytes(data)["pages"][1]["id"]
        added, _ = editor.edit_bytes(data, plan)
        after = reader.inspect_bytes(added)
        self.assertEqual(len(after["pages"][0]["strokes"]), 0)
        self.assertEqual(len(after["pages"][1]["strokes"]), 1)
        plan = plan_for(added)
        plan["operations"] = [dict(kind="erase", page_id=after["pages"][1]["id"],
                                    ids=[after["pages"][1]["strokes"][0]["id"]])]
        erased, _ = editor.edit_bytes(added, plan)
        self.assertTrue(all(not p["strokes"] for p in reader.inspect_bytes(erased)["pages"]))

    def test_rename_move_and_root_preserve_all_native_pen_unknown_archive_and_wire_bytes(self):
        paths = [fixture_directory() / name for name in ("two-page-variant.note", "two-page.note",
                                                        "history-five-pen.note", "chunked-20-page.note")]
        for path in paths:
            original = path.read_bytes()
            old = members(original)
            name = next(n for n in old if n.endswith("/note/pb/note_info"))
            raw = reader.single(reader.protobuf(old[name]), 1)
            raw += editor.field(300, b"\x00unknown nested\xff")
            outer = editor.field(1, raw) + editor.field(301, b"\x00unknown wrapper\xff")
            source = editor.rewrite_zip(original, {name: (name, outer)})
            plan = metadata(source, "aabbccdd001122334455667788990011")
            renamed = library.change_bytes(source, plan)
            final = members(renamed)
            for key, value in members(source).items():
                if key != name:
                    self.assertEqual(final[key], value)
            self.assertIn(editor.field(301, b"\x00unknown wrapper\xff"), final[name])
            new_raw = reader.single(reader.protobuf(final[name]), 1)
            ignored = {3, 4, 6, 31}
            self.assertEqual([raw for n, w, v, raw in editor.wire_fields(raw) if n not in ignored],
                             [raw for n, w, v, raw in editor.wire_fields(new_raw) if n not in ignored])
            before_note, after_note = reader.inspect_bytes(source), reader.inspect_bytes(renamed)
            self.assertEqual(before_note["pages"], after_note["pages"])
            self.assertEqual(reader.inspect_bytes(renamed, metadata_only=True)["parent_id"], plan["parent"])
            moved = library.change_bytes(renamed, metadata(renamed))
            self.assertIsNone(reader.inspect_bytes(moved, metadata_only=True)["parent_id"])
            self.assertEqual(path.read_bytes(), original)

    def test_metadata_noop_is_byte_identical_and_restore_reactivates_native_status(self):
        source = library.create_bytes(creation())
        plan = metadata(source)
        plan["title"] = reader.inspect_bytes(source)["title"]
        self.assertEqual(library.change_bytes(source, plan), source)
        name = next(n for n in members(source) if n.endswith("/note/pb/note_info"))
        outer = members(source)[name]
        removed = editor.patch(reader.single(reader.protobuf(outer), 1), {31: editor.field(31, 0)})
        source = editor.rewrite_zip(source, {name: (name, editor.patch(outer, {1: editor.field(1, removed)}))})
        plan["base_sha256"] = sha256(source).hexdigest()
        result = library.change_bytes(source, plan)
        raw = reader.single(reader.protobuf(members(result)[name]), 1)
        self.assertEqual(reader.single(reader.protobuf(raw), 31), 1)

    def test_invalid_creation_metadata_limits_and_identity_checks(self):
        for changes in [dict(page_ids=[]), dict(page_ids=["bad"]), dict(width=0), dict(height=10001),
                        dict(title=""), dict(title="\0"), dict(title="\ud800"), dict(title="😀"*501),
                        dict(parent="../folder"), dict(edited_at_ms=True)]:
            plan = creation()
            plan.update(changes)
            with self.assertRaises((ValueError, UnicodeError)):
                library.create_bytes(plan)
        plan = creation()
        plan["page_ids"] = [plan["document_id"]]
        with self.assertRaises(ValueError):
            library.create_bytes(plan)
        source = library.create_bytes(creation())
        for changes in [dict(base_sha256="0"*64), dict(document_id="wrong"), dict(extra=1),
                        dict(parent=reader.inspect_bytes(source)["document_id"])]:
            plan = metadata(source)
            plan.update(changes)
            with self.assertRaises(ValueError):
                library.change_bytes(source, plan)

    def test_locked_associated_wrong_type_and_duplicate_edited_field_rejected(self):
        source = library.create_bytes(creation())
        name = next(n for n in members(source) if n.endswith("/note/pb/note_info"))
        raw = reader.single(reader.protobuf(members(source)[name]), 1)
        for field_number in (8, 27, 30, 6):
            changed = (raw + editor.field(6, "duplicate")) if field_number == 6 else editor.patch(
                raw, {field_number: editor.field(field_number, 0 if field_number == 8 else 1)})
            candidate = editor.rewrite_zip(source, {name: (name, editor.field(1, changed))})
            with self.assertRaises(ValueError):
                library.change_bytes(candidate, metadata(candidate))

    def test_cli_exclusive_output_and_existing_original_preserved(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            plan_path, output = root / "plan.json", root / "blank.note"
            plan_path.write_text(json.dumps(creation()))
            report = library.command("/-", str(plan_path), str(output))
            original = output.read_bytes()
            self.assertEqual(sha256(original).hexdigest(), report["sha256"])
            self.assertEqual(output.stat().st_mode & 0o777, 0o600)
            with self.assertRaises(FileExistsError):
                library.command("/-", str(plan_path), str(output))
            self.assertEqual(original, output.read_bytes())


if __name__ == "__main__":
    unittest.main()
