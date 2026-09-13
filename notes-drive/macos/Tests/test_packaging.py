from hashlib import sha256
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

import package_resources as package
import synthetic_fixtures as synthetic

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "Resources"))
import note_reader as reader


class PackagingTests(unittest.TestCase):
    def setUp(self):
        root = ROOT / ".test-runs"
        root.mkdir(exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(prefix="packaging-", dir=root)
        self.directory = Path(self.temporary.name)
        self.resources = self.directory / "Resources"
        self.resources.mkdir()

    def tearDown(self):
        self.temporary.cleanup()

    def test_runtime_resource_names_contain_pinned_synthetic_profiles(self):
        manifest = package.stage(self.resources)
        self.assertEqual(set(p.name for p in self.resources.iterdir()),
                         {"Target-after.note", "B2.note", "synthetic-resources.json"})
        for name, profile in package.RESOURCE_PROFILES.items():
            data = (self.resources / name).read_bytes()
            self.assertEqual(data, synthetic.fixture_bytes(profile))
            self.assertEqual(sha256(data).hexdigest(), manifest["resources"][name]["sha256"])
            note = reader.inspect_bytes(data)
            self.assertTrue(note["title"].startswith("Synthetic "))
            self.assertEqual(note["sample_count"], 343)
            self.assertEqual(note["pages"][0]["strokes"][0]["points"][0], [120, 180])
        self.assertEqual(json.loads((self.resources / "synthetic-resources.json").read_text()), manifest)

    def test_default_never_inherits_checkout_parent_or_previous_bundle_config(self):
        checkout = self.directory / "checkout"
        (checkout / "config").mkdir(parents=True)
        previous = checkout / "build/BOOX Notes Reader.app/Contents/Resources"
        previous.mkdir(parents=True)
        decoys = [checkout / "config/oauth-desktop.json",
                  self.directory / "desktop-oauth.local.json",
                  previous / "oauth-desktop.json"]
        for path in decoys:
            path.write_bytes(b"synthetic configuration canary; never package implicitly")
        before = {path: path.read_bytes() for path in decoys}
        current = Path.cwd()
        try:
            os.chdir(checkout)
            package.stage(self.resources)
        finally:
            os.chdir(current)
        self.assertFalse((self.resources / "oauth-desktop.json").exists())
        self.assertEqual({path: path.read_bytes() for path in decoys}, before)

    def test_explicit_config_preserves_registration_bytes_privately_without_logging(self):
        config = self.directory / "explicit.json"
        data = b'{"installed":{"client_id":"synthetic-client.apps.googleusercontent.com","client_secret":"synthetic-secret-marker","project_id":"synthetic-other-project"}}\n'
        config.write_bytes(data)
        result = subprocess.run([sys.executable, "-B", str(ROOT / "Tests/package_resources.py"),
                                 "--resources", str(self.resources), "--oauth-config", str(config)],
                                capture_output=True, timeout=20)
        self.assertEqual(result.returncode, 0)
        self.assertNotIn(b"synthetic-secret-marker", result.stdout + result.stderr)
        self.assertNotIn(b"synthetic-client", result.stdout + result.stderr)
        self.assertEqual((self.resources / "oauth-desktop.json").read_bytes(), data)
        self.assertEqual((self.resources / "oauth-desktop.json").stat().st_mode & 0o777, 0o600)
        self.assertEqual(config.read_bytes(), data)
        # Preserve the user's project metadata exactly; never substitute a project.
        self.assertEqual(json.loads(data)["installed"]["project_id"], "synthetic-other-project")

    def test_invalid_or_oversized_explicit_config_does_not_stage_resources(self):
        config = self.directory / "invalid.json"
        for data in [b"{", b"x" * 32769, b'{"web":{"client_id":"web.apps.googleusercontent.com"}}',
                     b'{"installed":{"client_id":"bad-client"}}', b'{"client_id":"synthetic.apps.googleusercontent.com","client_secret":7}',
                     b'{"client_id":"synthetic.apps.googleusercontent.com","project_id":""}',
                     b'{"client_id":"synthetic.apps.googleusercontent.com","client_secret":""}']:
            config.write_bytes(data)
            with self.assertRaises(ValueError):
                package.stage(self.resources, config)
            self.assertEqual(list(self.resources.iterdir()), [])
            self.assertEqual(config.read_bytes(), data)

    def test_existing_resource_or_config_cannot_be_overwritten_or_inherited(self):
        for name in ["Target-after.note", "B2.note", "synthetic-resources.json", "oauth-desktop.json"]:
            path = self.resources / name
            path.write_bytes(b"preserve existing data")
            with self.assertRaisesRegex(ValueError, "overwrite"):
                package.stage(self.resources)
            self.assertEqual(list(self.resources.iterdir()), [path])
            self.assertEqual(path.read_bytes(), b"preserve existing data")
            path.unlink()

    def test_build_help_and_invalid_options_do_not_create_output_or_read_config(self):
        script = ROOT / "build.sh"
        destination = self.directory / "never-created"
        for args, code in [(["--help"], 0), (["--unknown"], 2),
                           (["--output-dir", str(destination), "--oauth-config"], 2),
                           (["--output-dir", "--oauth-config"], 2)]:
            result = subprocess.run(["/bin/bash", str(script)] + args, capture_output=True, timeout=10)
            self.assertEqual(result.returncode, code)
            self.assertFalse(destination.exists())


if __name__ == "__main__":
    unittest.main()
