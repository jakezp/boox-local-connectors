"""Synthetic native exports with repeated notebook/ancestor NoteModel rows.

No private exports, titles, account IDs or handwriting are inputs to this suite.
Existing pinned synthetic profiles are reused unchanged.
"""
from hashlib import sha256
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "Resources"))
import note_editor as editor
import note_library as library
import note_reader as reader
from synthetic_fixtures import field, fixture_directory, identifier, zip_bytes
from test_editor import members, plan_for

FOLDER_A = identifier("nested-metadata/folder-a", compact=True)
FOLDER_B = identifier("nested-metadata/folder-b")
EXTERNAL = "synthetic-external-folder"
OPAQUE = field(301, b"\x00opaque wrapper\xff") + field(301, b"second unknown value")


def folder(identity, parent=None, kind=0):
    # Stock proto3 may omit the folder's zero-valued type.
    return (field(1, identity) + field(6, "Synthetic folder — é/😀") +
            (field(4, parent) if parent else b"") +
            (field(8, kind) if kind else b"") +
            field(300, b"\x00opaque ancestor\xff"))


def nested(profile="history-five-pen.note", order=(1, 0, 2)):
    data = (fixture_directory() / profile).read_bytes()
    entries = members(data)
    path = next(n for n in entries if n.endswith("/note/pb/note_info"))
    raw = reader.single(reader.protobuf(entries[path]), 1)
    raw = editor.patch(raw, {4: field(4, FOLDER_B)}) + field(300, b"\x00opaque notebook\xff")
    records = [raw, folder(FOLDER_B, FOLDER_A), folder(FOLDER_A)]
    entries[path] = OPAQUE + b"".join(field(1, records[i]) for i in order) + field(302, 17)
    return zip_bytes(entries)


def wrapper(data):
    entries = members(data)
    path = next(n for n in entries if n.endswith("/note/pb/note_info"))
    return path, entries[path]


def without_document(outer, document_id):
    selected, _ = reader.note_record(outer, document_id)
    return [raw for n, w, v, raw in editor.wire_fields(outer) if not (n == 1 and v == selected)]


def metadata_plan(data, parent):
    return dict(operation="metadata", document_id=reader.inspect_bytes(data)["document_id"],
                base_sha256=sha256(data).hexdigest(), title="Synthetic renamed notebook — é/😀",
                parent=parent, edited_at_ms=1789250000000)


class NestedMetadataTests(unittest.TestCase):
    def test_selects_archive_identity_in_any_order_for_compact_and_hyphenated_ids(self):
        for profile in ("history-five-pen.note", "two-page.note"):
            original = reader.inspect_bytes((fixture_directory() / profile).read_bytes())
            for order in ((0, 1, 2), (1, 0, 2), (2, 1, 0)):
                data = nested(profile, order)
                note = reader.inspect_bytes(data)
                summary = reader.inspect_bytes(data, metadata_only=True)
                self.assertEqual(note["document_id"], original["document_id"])
                self.assertEqual(note["pages"], original["pages"])
                self.assertEqual(summary["title"], original["title"])
                self.assertEqual(summary["parent_id"], FOLDER_B)
                self.assertEqual(summary["page_count"], len(original["pages"]))
                self.assertTrue(any("2 embedded folder" in w for w in note["warnings"]))

    def test_rename_move_restore_and_noop_preserve_raw_folder_and_unknown_wrapper_records(self):
        data = nested()
        for parent in (FOLDER_B, EXTERNAL, None):
            path, outer = wrapper(data)
            identity = reader.inspect_bytes(data)["document_id"]
            plan = metadata_plan(data, parent)
            changed = library.change_bytes(data, plan)
            self.assertEqual(without_document(outer, identity),
                             without_document(wrapper(changed)[1], identity))
            self.assertEqual(reader.inspect_bytes(data)["pages"], reader.inspect_bytes(changed)["pages"])
            self.assertEqual(reader.inspect_bytes(changed, metadata_only=True)["parent_id"], parent)
            for name, value in members(data).items():
                if name != path:
                    self.assertEqual(members(changed)[name], value)
            old_doc, _ = reader.note_record(outer, identity)
            new_doc, _ = reader.note_record(wrapper(changed)[1], identity)
            self.assertEqual([raw for n, w, v, raw in editor.wire_fields(old_doc) if n not in {3, 4, 6, 31}],
                             [raw for n, w, v, raw in editor.wire_fields(new_doc) if n not in {3, 4, 6, 31}])
            self.assertEqual(library.change_bytes(changed, metadata_plan(changed, parent)), changed)
            data = changed
        identity = reader.inspect_bytes(data)["document_id"]
        path, outer = wrapper(data)
        raw, _ = reader.note_record(outer, identity)
        removed = editor.replace_note_record(outer, identity, editor.patch(raw, {31: field(31, 0)}))
        data = editor.rewrite_zip(data, {path: (path, removed)})
        restored = library.change_bytes(data, metadata_plan(data, None))
        raw, _ = reader.note_record(wrapper(restored)[1], identity)
        self.assertEqual(reader.single(reader.protobuf(raw), 31), 1)
        self.assertEqual(without_document(removed, identity), without_document(wrapper(restored)[1], identity))

    def test_pen_add_erase_keeps_ancestor_bytes_history_and_active_identities(self):
        source = nested()
        note = reader.inspect_bytes(source)
        added, report = editor.edit_bytes(source, plan_for(source))
        self.assertEqual((report["added"], report["removed"]), (1, 0))
        self.assertEqual(len(reader.inspect_bytes(added)["pages"][0]["strokes"]), 6)
        plan = plan_for(added)
        plan["operations"] = [dict(kind="erase", page_id=note["pages"][0]["id"],
                                  ids=[note["pages"][0]["strokes"][0]["id"]])]
        erased, report = editor.edit_bytes(added, plan)
        self.assertEqual((report["added"], report["removed"]), (0, 1))
        self.assertEqual(len(reader.inspect_bytes(erased)["pages"][0]["strokes"]), 5)
        for data in (added, erased):
            self.assertEqual(reader.inspect_bytes(data)["document_id"], note["document_id"])
            self.assertEqual(without_document(wrapper(source)[1], note["document_id"]),
                             without_document(wrapper(data)[1], note["document_id"]))
            for name, value in members(source).items():
                if "/shape/" not in name or "/stash/" in name:
                    if not name.endswith("/note/pb/note_info"):
                        self.assertEqual(members(data)[name], value)

    def test_large_nested_export_keeps_unsupported_shapes_and_resources(self):
        source = nested("chunked-20-page.note")
        before = reader.inspect_bytes(source)
        changed = library.change_bytes(source, metadata_plan(source, None))
        after = reader.inspect_bytes(changed)
        self.assertEqual(before["pages"], after["pages"])
        self.assertEqual(sum(not s["supported"] for p in after["pages"] for s in p["strokes"]), 14)
        path, _ = wrapper(source)
        self.assertEqual({k: v for k, v in members(source).items() if k != path},
                         {k: v for k, v in members(changed).items() if k != path})

    def test_duplicate_missing_root_foreign_notebook_and_invalid_scalar_records_rejected(self):
        data = nested()
        path, outer = wrapper(data)
        identity = reader.inspect_bytes(data)["document_id"]
        raw, _ = reader.note_record(outer, identity)
        invalid = [
            field(1, folder(FOLDER_A)),  # No matching notebook.
            field(1, raw) * 2,
            field(1, raw) + field(1, folder(FOLDER_A)) * 2,
            field(1, raw) + field(1, folder(FOLDER_A, kind=1)),
            field(1, raw) + field(1, folder(FOLDER_A, kind=2)),
            field(1, raw) + field(1, folder("../bad")),
            field(1, raw) + field(1, folder(FOLDER_A, "../bad")),
            field(1, raw + field(1, identity)),
            field(1, raw) + field(1, folder(FOLDER_A) + field(6, "duplicate")),
        ]
        for candidate in invalid:
            with self.subTest(candidate_hash=sha256(candidate).hexdigest()):
                entries = members(data)
                entries[path] = candidate
                for metadata_only in (False, True):
                    with self.assertRaises(ValueError):
                        reader.inspect_bytes(zip_bytes(entries), metadata_only)

    def test_parent_cycles_and_folder_parenting_a_notebook_rejected(self):
        data = nested()
        identity = reader.inspect_bytes(data)["document_id"]
        raw, _ = reader.note_record(wrapper(data)[1], identity)
        for folders in ([folder(FOLDER_B, FOLDER_B)],
                        [folder(FOLDER_B, FOLDER_A), folder(FOLDER_A, FOLDER_B)],
                        [folder(FOLDER_B, identity)],
                        [folder(EXTERNAL, EXTERNAL)]):
            outer = field(1, raw) + b"".join(field(1, f) for f in folders)
            with self.assertRaisesRegex(ValueError, "Cyclic"):
                reader.note_record(outer, identity)
        self_parent = editor.patch(raw, {4: field(4, identity)})
        with self.assertRaisesRegex(ValueError, "Cyclic"):
            reader.note_record(field(1, self_parent), identity)

    def test_missing_external_parent_and_retained_valid_old_folders_are_accepted(self):
        data = nested()
        identity = reader.inspect_bytes(data)["document_id"]
        path, outer = wrapper(data)
        raw, _ = reader.note_record(outer, identity)
        # Native snapshots need not contain every Drive folder; never invent one.
        outer = editor.replace_note_record(outer, identity, editor.patch(raw, {4: field(4, EXTERNAL)}))
        modified = editor.rewrite_zip(data, {path: (path, outer)})
        self.assertEqual(reader.inspect_bytes(modified, True)["parent_id"], EXTERNAL)
        self.assertEqual(len(reader.note_record(outer, identity)[1]), 2)

    def test_bounds_wire_types_and_archive_root_identity_checked(self):
        data = nested()
        identity = reader.inspect_bytes(data)["document_id"]
        raw, _ = reader.note_record(wrapper(data)[1], identity)
        candidates = [b"", field(1, 1), field(1, b""),
                      field(1, raw) + b"".join(field(1, folder("synthetic-folder-%d" % i)) for i in range(256))]
        for outer in candidates:
            with self.assertRaises(ValueError):
                reader.note_record(outer, identity)
        for wrong_root in ("../" + identity, "nested/" + identity, "", "a" * 101, FOLDER_A):
            with self.assertRaises(ValueError):
                reader.note_record(wrapper(data)[1], wrong_root)

    def test_writer_refuses_replacement_with_changed_identity_or_duplicate_scalar(self):
        data = nested()
        identity = reader.inspect_bytes(data)["document_id"]
        _, outer = wrapper(data)
        raw, _ = reader.note_record(outer, identity)
        for replacement in (editor.patch(raw, {1: field(1, EXTERNAL)}), raw + field(1, identity)):
            with self.assertRaises(ValueError):
                editor.replace_note_record(outer, identity, replacement)


if __name__ == "__main__":
    unittest.main()
