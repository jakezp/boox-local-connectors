import copy
import io
import json
from pathlib import Path
import sys
import struct
import unittest
import uuid
import zipfile
from hashlib import sha256

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "Resources"))
import note_editor as editor
import note_reader as reader

from synthetic_fixtures import fixture_directory, ADDED_PEN_ID, EDIT_ID, NEXT_PEN_ID, identifier

FIXTURES = fixture_directory()


def plan_for(data):
    note = reader.inspect_bytes(data)
    page = note["pages"][0]
    layer = next(l["id"] for l in page["layers"] if l["visible"] and not l["locked"])
    return dict(base_sha256=sha256(data).hexdigest(), document_id=note["document_id"],
                edit_id=EDIT_ID, edited_at_ms=1789250000000,
                operations=[dict(kind="add", page_id=page["id"],
                                 id=ADDED_PEN_ID, layer=layer, width=4.0,
                                 color=0xff1756aa, points=[[100, 100], [140, 120], [180, 100], [220, 130]])])


def members(data):
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        return {n: z.read(n) for n in z.namelist()}


class EditorTests(unittest.TestCase):
    def test_add_preserves_native_identities_existing_points_and_links(self):
        for filename in ("two-page-variant.note", "two-page.note"):
            data = (FIXTURES / filename).read_bytes()
            plan = plan_for(data)
            result, report = editor.edit_bytes(data, plan)
            before, after = reader.inspect_bytes(data), reader.inspect_bytes(result)
            self.assertEqual(before["document_id"], after["document_id"])
            self.assertEqual([p["id"] for p in before["pages"]], [p["id"] for p in after["pages"]])
            self.assertEqual(report["added"], 1)
            for old, new in zip(before["pages"], after["pages"]):
                saved = {s["id"]: s for s in new["strokes"]}
                for stroke in old["strokes"]:
                    self.assertEqual(stroke, saved[stroke["id"]])
                self.assertEqual(old["layers"], new["layers"])
            self.assertEqual((FIXTURES / filename).read_bytes(), data)
            old_members, new_members = members(data), members(result)
            for name, content in old_members.items():
                if "/shape/" not in name and not name.endswith("/note/pb/note_info"):
                    self.assertEqual(new_members[name], content)

    def test_native_erasure_retains_tombstone_uuid_point_bytes_and_other_pens(self):
        data = (FIXTURES / "two-page-variant.note").read_bytes()
        original = reader.inspect_bytes(data)
        plan = plan_for(data)
        victim = original["pages"][0]["strokes"][0]["id"]
        plan["operations"] = [dict(kind="erase", page_id=original["pages"][0]["id"], ids=[victim])]
        result, report = editor.edit_bytes(data, plan)
        note = reader.inspect_bytes(result)
        self.assertEqual(report["removed"], 1)
        self.assertNotIn(victim, [s["id"] for p in note["pages"] for s in p["strokes"]])
        found = []
        for name, content in members(result).items():
            if "/shape/" in name and name.endswith(".zip"):
                for raw in reader.protobuf(next(iter(members(content).values()))).get(1, []):
                    fields = reader.protobuf(raw)
                    if reader.text(reader.single(fields, 1)) == victim:
                        found.append(reader.single(fields, 15))
        self.assertEqual(found, [1])
        for name, content in members(data).items():
            if name.endswith("#points"):
                self.assertEqual(members(result)[name], content)

    def test_unknown_members_and_wire_fields_preserved(self):
        data = (FIXTURES / "two-page-variant.note").read_bytes()
        entries = members(data)
        name = next(n for n in entries if "/shape/" in n and n.endswith(".zip"))
        inner_name, raw = next(iter(members(entries[name]).items()))
        unknown = editor.field(310, b"\x00opaque shape field\xff")
        fields = editor.wire_fields(raw)
        raw = b"".join(editor.field(1, value + unknown) if number == 1 else encoded
                       for number, _, value, encoded in fields)
        outer_unknown = editor.field(311, b"\x00opaque document field\xff")
        new_inner = editor.rewrite_zip(entries[name], {inner_name: (inner_name, raw + outer_unknown)})
        data = editor.rewrite_zip(data, {name: (name, new_inner)}, {"unknown/vendor.bin": b"\x00\xffpreserve"})
        result, _ = editor.edit_bytes(data, plan_for(data))
        self.assertEqual(members(result)["unknown/vendor.bin"], b"\x00\xffpreserve")
        changed = next(content for n, content in members(result).items()
                       if "/shape/" in n and n.endswith(".zip") and n not in entries)
        edited_raw = next(iter(members(changed).values()))
        self.assertIn(outer_unknown, edited_raw)
        self.assertEqual(edited_raw.count(unknown), 4)

    def test_deterministic_output_and_add_then_erase_noop(self):
        data = (FIXTURES / "two-page-variant.note").read_bytes()
        plan = plan_for(data)
        self.assertEqual(editor.edit_bytes(data, plan), editor.edit_bytes(data, plan))
        pen = plan["operations"][0]
        plan["operations"].append(dict(kind="erase", page_id=pen["page_id"], ids=[pen["id"]]))
        output, report = editor.edit_bytes(data, plan)
        self.assertEqual(output, data)
        self.assertFalse(report["changed"])

    def test_existing_32_character_shape_id_can_be_erased_without_remapping(self):
        data = (FIXTURES / "two-page-variant.note").read_bytes()
        original_id = reader.inspect_bytes(data)["pages"][0]["strokes"][0]["id"]
        short_id = original_id.replace("-", "")
        replacements = {}
        for name, content in members(data).items():
            if name.endswith("#points"):
                changed = bytearray(content)
                xref = struct.unpack_from(">I", changed, len(changed) - 4)[0]
                for pos in range(xref, len(changed) - 4, 44):
                    if changed[pos:pos+36].decode().strip() == original_id:
                        changed[pos:pos+36] = short_id.encode().ljust(36, b" ")
                replacements[name] = (name, bytes(changed))
            if "/shape/" in name and name.endswith(".zip"):
                inner, raw = next(iter(members(content).items()))
                edited = []
                for number, _, value, encoded in editor.wire_fields(raw):
                    if number == 1 and reader.single(reader.protobuf(value), 1) == original_id.encode():
                        edited.append(editor.field(1, editor.patch(value, {1: editor.field(1, short_id)})))
                    else:
                        edited.append(encoded)
                replacements[name] = (name, editor.rewrite_zip(content, {inner: (inner, b"".join(edited))}))
        data = editor.rewrite_zip(data, replacements)
        before = reader.inspect_bytes(data)
        self.assertIn(short_id, [s["id"] for s in before["pages"][0]["strokes"]])
        plan = plan_for(data)
        plan["operations"] = [dict(kind="erase", page_id=before["pages"][0]["id"], ids=[short_id])]
        result, _ = editor.edit_bytes(data, plan)
        self.assertNotIn(short_id, [s["id"] for s in reader.inspect_bytes(result)["pages"][0]["strokes"]])

    def test_rejects_wrong_base_identity_collision_and_invalid_coordinates(self):
        data = (FIXTURES / "two-page-variant.note").read_bytes()
        original = plan_for(data)
        cases = []
        wrong = copy.deepcopy(original); wrong["base_sha256"] = "0" * 64; cases.append(wrong)
        wrong = copy.deepcopy(original); wrong["document_id"] = str(uuid.uuid4()); cases.append(wrong)
        wrong = copy.deepcopy(original); wrong["operations"][0]["points"][0][0] = float("nan"); cases.append(wrong)
        wrong = copy.deepcopy(original); wrong["operations"][0]["points"][0][0] = -1; cases.append(wrong)
        wrong = copy.deepcopy(original); wrong["operations"][0]["layer"] = 999; cases.append(wrong)
        wrong = copy.deepcopy(original); wrong["operations"][0]["id"] = reader.inspect_bytes(data)["pages"][0]["strokes"][0]["id"]; cases.append(wrong)
        for plan in cases:
            with self.assertRaises(ValueError):
                editor.edit_bytes(data, plan)

    def test_cannot_erase_link_or_hidden_locked_content(self):
        data = (FIXTURES / "two-page-variant.note").read_bytes()
        note = reader.inspect_bytes(data)
        page = note["pages"][1]
        link = next(s for s in page["strokes"] if s["type"] == 33)
        plan = plan_for(data)
        plan["operations"] = [dict(kind="erase", page_id=page["id"], ids=[link["id"]])]
        with self.assertRaisesRegex(ValueError, "active editable pen"):
            editor.edit_bytes(data, plan)
        entries = members(data)
        path = next(n for n in entries if n.endswith("/note/pb/note_info"))
        raw_note = reader.single(reader.protobuf(entries[path]), 1)
        info = json.loads(reader.single(reader.protobuf(raw_note), 12))
        for layer in info["pageInfoMap"][note["pages"][0]["id"]]["layerList"]:
            layer["lock"] = True
        raw_note = editor.patch(raw_note, {12: editor.field(12, editor.json_bytes(info))})
        data = editor.rewrite_zip(data, {path: (path, editor.patch(entries[path], {1: editor.field(1, raw_note)}))})
        plan = plan_for((FIXTURES / "two-page-variant.note").read_bytes())
        plan["base_sha256"] = sha256(data).hexdigest()
        with self.assertRaisesRegex(ValueError, "hidden/locked/unknown"):
            editor.edit_bytes(data, plan)

    def test_synthetic_history_add_then_erase_preserves_stash(self):
        data = (FIXTURES / "history-five-pen.note").read_bytes()
        plan = plan_for(data)
        added, _ = editor.edit_bytes(data, plan)
        self.assertEqual(sum(p["supported"] for p in reader.inspect_bytes(added)["pages"][0]["strokes"]), 6)
        erased_plan = plan_for(added)
        erased_plan["edit_id"] = "c2e78e9c-10ca-4ffd-bb7f-3997e4d90dc3"
        erased_plan["operations"] = [dict(kind="erase", page_id=plan["operations"][0]["page_id"],
                                          ids=[identifier("history/pen/0")])]
        erased, _ = editor.edit_bytes(added, erased_plan)
        self.assertEqual(sum(p["supported"] for p in reader.inspect_bytes(erased)["pages"][0]["strokes"]), 5)
        for n, content in members(data).items():
            if "/stash/" in n:
                self.assertEqual(members(erased)[n], content)

    def test_synthetic_twenty_pages_preserve_unsupported_content(self):
        data = (FIXTURES / "chunked-20-page.note").read_bytes()
        result, _ = editor.edit_bytes(data, plan_for(data))
        old, new = reader.inspect_bytes(data), reader.inspect_bytes(result)
        self.assertEqual(len(new["pages"]), 20)
        self.assertEqual(sum(s["supported"] for p in new["pages"] for s in p["strokes"]), 320)
        self.assertEqual([s for p in old["pages"] for s in p["strokes"] if not s["supported"]],
                         [s for p in new["pages"] for s in p["strokes"] if not s["supported"]])

    def test_synthetic_proto3_zero_omission_renders_and_accepts_next_edit(self):
        source = FIXTURES / "proto3-reexport.note"
        data = source.read_bytes()
        before = reader.inspect_bytes(data)
        pens = {s["id"]: s for p in before["pages"] for s in p["strokes"] if s["supported"]}
        self.assertEqual(len(pens), 6)
        added_id = ADDED_PEN_ID
        self.assertEqual(pens[added_id]["layer"], 0)
        self.assertEqual(pens[added_id]["points"], [[100, 100], [140, 120], [180, 100], [220, 130]])
        entries = members(data)
        root = next(n[:-len("note/pb/note_info")] for n in entries if n.endswith("/note/pb/note_info"))
        records = []
        for name, content in entries.items():
            if name.startswith(root + "shape/") and name.endswith(".zip"):
                records.extend(reader.protobuf(next(iter(members(content).values()))).get(1, []))
        # Native proto3 serialization omitted the explicitly encoded z-order zero.
        fields = next(reader.protobuf(raw) for raw in records
                      if reader.text(reader.single(reader.protobuf(raw), 1)) == added_id)
        self.assertNotIn(6, fields)
        handoff, _ = editor.edit_bytes((FIXTURES / "history-five-pen.note").read_bytes(),
                                       plan_for((FIXTURES / "history-five-pen.note").read_bytes()))
        expected = {s["id"]: s for p in reader.inspect_bytes(handoff)["pages"] for s in p["strokes"] if s["supported"]}
        self.assertEqual(set(expected), set(pens))
        for shape_id, pen in pens.items():
            for key in ("points", "point_hash", "type", "layer", "color", "width"):
                self.assertEqual(pen[key], expected[shape_id][key])
        plan = plan_for(data)
        plan["edit_id"] = "bbcefd85-c305-445e-96da-8658a79725d0"
        plan["operations"][0]["id"] = NEXT_PEN_ID
        edited, _ = editor.edit_bytes(data, plan)
        after = {s["id"]: s for p in reader.inspect_bytes(edited)["pages"] for s in p["strokes"] if s["supported"]}
        self.assertEqual(len(after), 7)
        for shape_id, pen in pens.items():
            self.assertEqual(pen, after[shape_id])
        self.assertEqual(source.read_bytes(), data)


if __name__ == "__main__":
    unittest.main()
