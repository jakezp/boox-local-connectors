from copy import deepcopy
import json
import os
from pathlib import Path
import sqlite3
import tempfile
import unittest
from uuid import uuid4

from prepare_apply_fixture import prepare, replacements, tree_hash


ROOT = Path(__file__).resolve().parents[1]
PRIVATE_ROOT = os.environ.get("BOOX_PRIVATE_WORKSPACE")
FIXTURE = Path(PRIVATE_ROOT) / "notes-drive/research/apply" if PRIVATE_ROOT else None


@unittest.skipUnless(PRIVATE_ROOT, "Historical live corpus: set BOOX_PRIVATE_WORKSPACE explicitly")
class PrepareApplyTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.target = json.loads((FIXTURE / "target-map.json").read_text())
        cls.staged = json.loads((FIXTURE / "staged-map.json").read_text())

    def test_existing_page_and_stroke_identities_are_retained(self):
        mapping, identities = replacements(self.target, self.staged)
        self.assertEqual(identities["pages"], self.target["pages"])
        for source, local in self.target["shapes"].items():
            self.assertEqual(identities["shapes"][source], local)
            self.assertEqual(mapping[self.staged["shapes"][source]], local)
        self.assertEqual(len(identities["shapes"]), 12)
        self.assertEqual(len(set(mapping.values())), len(mapping))

    def test_unknown_lineage_is_rejected(self):
        staged = deepcopy(self.staged)
        staged["source"] = str(uuid4())
        with self.assertRaisesRegex(ValueError, "lineages"):
            replacements(self.target, staged)

    def test_page_deletion_is_not_silently_applied(self):
        staged = deepcopy(self.staged)
        staged["pages"].pop(next(iter(staged["pages"])))
        with self.assertRaisesRegex(ValueError, "Page addition/deletion"):
            replacements(self.target, staged)

    def test_stroke_deletion_is_not_silently_applied(self):
        staged = deepcopy(self.staged)
        staged["shapes"].pop(next(iter(self.target["shapes"])))
        with self.assertRaisesRegex(ValueError, "Shape deletion"):
            replacements(self.target, staged)

    def test_identity_collisions_are_rejected(self):
        target = deepcopy(self.target)
        first, second = list(target["shapes"])[:2]
        target["shapes"][second] = target["shapes"][first]
        with self.assertRaisesRegex(ValueError, "collision"):
            replacements(target, self.staged)

    def test_native_candidate_keeps_local_identity_and_settings(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "candidate"
            plan = prepare(FIXTURE / "target-snapshot", FIXTURE / "staged-snapshot",
                           FIXTURE / "target-map.json", FIXTURE / "staged-map.json",
                           Path(PRIVATE_ROOT) / "notes-drive/tests/artifacts/B2.note", output)
            for key in ("uniqueId", "title", "parentUniqueId", "userId", "id",
                        "createdAt", "favorite", "encryptionType", "notePenInfo"):
                self.assertEqual(plan["expectedRow"][key], plan["afterRow"][key])
            with sqlite3.connect(output / "new/database") as database:
                self.assertEqual(database.execute("PRAGMA integrity_check").fetchone(), ("ok",))
                documents = {row[0] for row in database.execute(
                    "SELECT DISTINCT documentUniqueId FROM NewShapeModel")}
                self.assertEqual(documents, {self.target["destination"]})
                pages = {row[0] for row in database.execute("SELECT uniqueId FROM NotePageModel")}
                self.assertEqual(pages, set(self.target["pages"].values()))
                link_json = database.execute("SELECT text FROM NewShapeModel WHERE shapeType=33").fetchone()[0]
                link = json.loads(link_json)["docBean"]
                self.assertEqual(link["documentId"], self.target["destination"])
                self.assertIn(link["pageId"], pages)
            for part in ("database", "document", "point"):
                self.assertEqual(tree_hash(output / "new" / part), plan["newHashes"][part])

    def test_empty_directories_are_included_in_transfer_manifest(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "file").write_bytes(b"fixture")
            before = tree_hash(root)
            (root / "empty").mkdir()
            self.assertNotEqual(before, tree_hash(root))

    def test_linked_components_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "file").write_bytes(b"fixture")
            (root / "link").symlink_to(root / "file")
            with self.assertRaisesRegex(ValueError, "Linked"):
                tree_hash(root)


if __name__ == "__main__":
    unittest.main()
