import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile

import generate_native_apply_fixtures as generator
from synthetic_fixtures import NEXT_PEN_ID, identifier

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "Resources"))
import note_editor as editor
import note_reader as reader


def members(data):
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        return {name: archive.read(name) for name in archive.namelist()}


class NativeApplyFixtureTests(unittest.TestCase):
    def setUp(self):
        (ROOT / ".test-runs").mkdir(exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(prefix="native-apply-", dir=ROOT / ".test-runs")
        self.directory = Path(self.temporary.name)

    def tearDown(self):
        self.temporary.cleanup()

    def test_pinned_public_variants_and_production_cli_reproduction(self):
        manifest = generator.write(self.directory)
        expected = json.loads((Path(__file__).parent / "native-apply-manifest.json").read_text())
        self.assertEqual(manifest, expected)
        for input_name, plan, reference, output in [
                ("history-five-pen.note", "add-plan.json", "history-add.note", "reproduced-add.note"),
                ("reproduced-add.note", "erase-plan.json", "history-erase.note", "reproduced-erase.note")]:
            result = subprocess.run([sys.executable, "-B", str(ROOT / "Resources/note_editor.py"),
                                     str(self.directory / input_name), str(self.directory / plan),
                                     str(self.directory / output)], capture_output=True, timeout=25)
            self.assertEqual(result.returncode, 0, result.stdout)
            self.assertEqual((self.directory / output).read_bytes(), (self.directory / reference).read_bytes())

    def test_normalized_add_only_omits_zero_layer_fields_and_remains_editable(self):
        files, manifest = generator.generated()
        original, normalized = files["history-add.note"], files["history-add-normalized.note"]
        old = {reader.text(reader.single(reader.protobuf(s), 1)): s for s in generator.active_shapes(original)}
        new = {reader.text(reader.single(reader.protobuf(s), 1)): s for s in generator.active_shapes(normalized)}
        self.assertEqual(set(old), set(new))
        for key in old:
            self.assertEqual([raw for number, _, _, raw in editor.wire_fields(old[key]) if number != 6],
                             [raw for number, _, _, raw in editor.wire_fields(new[key])])
            self.assertNotIn(6, reader.protobuf(new[key]))
        root = manifest["document_id"] + "/"
        saved = members(normalized)
        for name, data in members(original).items():
            if not name.startswith(root + "shape/"):
                self.assertEqual(saved[name], data)
        self.assertEqual(reader.inspect_bytes(original)["pages"], reader.inspect_bytes(normalized)["pages"])
        plan = json.loads(files["add-plan.json"])
        from hashlib import sha256
        plan["base_sha256"] = sha256(normalized).hexdigest()
        plan["edit_id"] = identifier("native-apply/next-edit")
        plan["operations"][0]["id"] = NEXT_PEN_ID
        next_bytes, _ = editor.edit_bytes(normalized, plan)
        self.assertEqual(len(reader.inspect_bytes(next_bytes)["pages"][0]["strokes"]), 7)

    def test_erase_keeps_six_shape_identities_original_points_and_one_tombstone(self):
        files, manifest = generator.generated()
        added, erased = files["history-add.note"], files["history-erase.note"]
        old = {reader.text(reader.single(reader.protobuf(s), 1)): s for s in generator.active_shapes(added)}
        new = {reader.text(reader.single(reader.protobuf(s), 1)): s for s in generator.active_shapes(erased)}
        self.assertEqual(len(new), 6)
        self.assertEqual(set(old), set(new))
        removed = [key for key, raw in new.items() if reader.single(reader.protobuf(raw), 15, 0) == 1]
        self.assertEqual(removed, [manifest["erased_shape_id"]])
        self.assertEqual(len(reader.inspect_bytes(erased)["pages"][0]["strokes"]), 5)
        saved = members(erased)
        for name, data in members(added).items():
            if "/point/" in name or "/stash/" in name:
                self.assertEqual(saved[name], data)

    def test_generation_is_repeatable_and_refuses_different_existing_outputs(self):
        first = generator.write(self.directory)
        self.assertEqual(generator.write(self.directory), first)
        changed = self.directory / "history-add.note"
        changed.write_bytes(b"retain unrelated local bytes")
        with self.assertRaisesRegex(ValueError, "Refusing to overwrite"):
            generator.write(self.directory)
        self.assertEqual(changed.read_bytes(), b"retain unrelated local bytes")


if __name__ == "__main__":
    unittest.main()
