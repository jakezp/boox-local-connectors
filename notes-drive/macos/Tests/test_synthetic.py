import ast
from hashlib import sha256
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile

import synthetic_fixtures as synthetic

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "Resources"))
import note_reader as reader


class SyntheticFixtureTests(unittest.TestCase):
    def test_pinned_hashes_repeat_with_no_time_or_compression_variance(self):
        manifest = json.loads((Path(__file__).parent / "synthetic-manifest.json").read_text())
        for name, expected in manifest.items():
            first = synthetic.fixture_bytes(name)
            synthetic.fixture_bytes.cache_clear()
            second = synthetic.fixture_bytes(name)
            self.assertEqual(first, second)
            self.assertEqual(sha256(first).hexdigest(), expected["sha256"])
            self.assertEqual(len(first), expected["bytes"])
            with zipfile.ZipFile(io.BytesIO(first)) as archive:
                for info in archive.infolist():
                    self.assertEqual(info.date_time, (2020, 1, 1, 0, 0, 0))
                    self.assertEqual(info.compress_type, zipfile.ZIP_STORED)

    def test_generator_needs_no_file_or_network_inputs_after_import(self):
        source = Path(synthetic.__file__).resolve()
        script = """
import importlib.util, sys
spec = importlib.util.spec_from_file_location("synthetic", sys.argv[1])
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
def deny(event, arguments):
    if event in ("open", "os.listdir", "os.scandir") or event.startswith("socket."):
        raise RuntimeError("Generator attempted external input: " + event)
sys.addaudithook(deny)
for name in module.TITLES:
    assert module.fixture_bytes(name)
print("generated-without-file-or-network-input")
"""
        result = subprocess.run([sys.executable, "-B", "-c", script, str(source)],
                                capture_output=True, text=True, timeout=20)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), "generated-without-file-or-network-input")
        imports = []
        for node in ast.walk(ast.parse(source.read_text())):
            if isinstance(node, ast.Import):
                imports += [v.name for v in node.names]
            elif isinstance(node, ast.ImportFrom):
                imports.append(node.module)
        self.assertFalse(any(value.startswith("note_") for value in imports))

    def test_account_fields_absent_titles_synthetic_and_coordinates_arithmetic(self):
        for name in synthetic.TITLES:
            data = synthetic.fixture_bytes(name)
            note = reader.inspect_bytes(data)
            self.assertEqual(note["title"], synthetic.TITLES[name])
            self.assertTrue(note["title"].startswith("Synthetic "))
            first = note["pages"][0]["strokes"][0]
            self.assertEqual(first["points"][:3], [[120, 180], [125, 201], [130, 222]])
            with zipfile.ZipFile(io.BytesIO(data)) as archive:
                raw = archive.read(note["document_id"] + "/note/pb/note_info")
                fields = reader.protobuf(reader.single(reader.protobuf(raw), 1))
                self.assertNotIn(39, fields)  # No ONYX account identity.
                for path in archive.namelist():
                    self.assertTrue(path.startswith(note["document_id"] + "/"))
                    if path.endswith("/extra/pb/extra"):
                        self.assertNotIn(3, reader.protobuf(archive.read(path)))

    def test_output_reuse_checks_exact_bytes_and_never_overwrites_a_different_file(self):
        root = Path(__file__).resolve().parents[1] / ".test-runs"
        root.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=root) as directory:
            first = synthetic.write_fixtures(directory)
            second = synthetic.write_fixtures(directory)
            self.assertEqual(first, second)
            path = Path(directory) / next(iter(synthetic.TITLES))
            path.write_bytes(b"preserve unrelated data")
            with self.assertRaisesRegex(ValueError, "Refusing to overwrite"):
                synthetic.write_fixtures(directory)
            self.assertEqual(path.read_bytes(), b"preserve unrelated data")


if __name__ == "__main__":
    unittest.main()
