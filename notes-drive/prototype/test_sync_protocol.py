import json
import unittest

from sync_protocol import Catalog, IncompleteSync, MemoryStore, Revision, digest


class SyncScenarios(unittest.TestCase):
    def setUp(self):
        self.store = MemoryStore()
        self.initial = self.store.publish("notebook", "tablet-a", b"synthetic notebook v1")

    def catalog(self):
        return Catalog(self.store, "notebook")

    def test_second_device_stages_initial_notebook(self):
        self.assertEqual(self.catalog().action(None), "stage-and-verify-import")

    def test_sequential_edit_and_retry_do_not_duplicate(self):
        new = self.store.publish("notebook", "tablet-b", b"v2", [self.initial])
        retry = self.store.publish("notebook", "tablet-b", b"v2", [self.initial])
        self.assertEqual(new, retry)
        self.assertEqual(len(self.store.records), 2)
        self.assertEqual(self.catalog().action(self.initial), "stage-and-verify-import")
        self.assertEqual(self.catalog().action(new), "no-change")

    def test_offline_edits_on_two_devices_preserve_both(self):
        a = self.store.publish("notebook", "tablet-a", b"A adds a diagram", [self.initial])
        b = self.store.publish("notebook", "tablet-b", b"B adds handwriting", [self.initial])
        self.assertEqual(self.catalog().heads, {a, b})
        self.assertEqual(self.catalog().action(self.initial), "keep-conflict-copies")
        self.assertEqual(len(self.store.payloads), 3)

    def test_unpublished_local_changes_are_not_overwritten(self):
        self.store.publish("notebook", "tablet-b", b"remote edit", [self.initial])
        self.assertEqual(self.catalog().action(self.initial, local_dirty=True), "keep-conflict-copies")

    def test_local_edit_can_upload_when_remote_has_not_changed(self):
        self.assertEqual(self.catalog().action(self.initial, local_dirty=True), "upload-local")

    def test_delete_against_offline_edit_is_a_conflict(self):
        deletion = self.store.publish("notebook", "tablet-a", None, [self.initial])
        edit = self.store.publish("notebook", "tablet-b", b"offline addition", [self.initial])
        self.assertEqual(self.catalog().heads, {deletion, edit})
        self.assertEqual(self.catalog().action(self.initial), "keep-conflict-copies")

    def test_uncontested_deletion_requires_review_and_keeps_payloads(self):
        self.store.publish("notebook", "tablet-a", None, [self.initial])
        self.assertEqual(self.catalog().action(self.initial), "review-deletion")
        self.assertIn(digest(b"synthetic notebook v1"), self.store.payloads)

    def test_interrupted_upload_is_invisible_until_commit(self):
        pending = self.store.publish("notebook", "tablet-b", b"v2", [self.initial], stop_after_payload=True)
        self.assertNotIn(pending, self.store.records)
        self.assertEqual(self.catalog().action(self.initial), "no-change")
        self.store.publish("notebook", "tablet-b", b"v2", [self.initial])
        self.assertEqual(self.catalog().action(self.initial), "stage-and-verify-import")

    def test_corrupt_payload_cannot_be_imported(self):
        self.store.payloads[digest(b"synthetic notebook v1")] = b"damaged"
        with self.assertRaises(IncompleteSync):
            self.catalog()

    def test_missing_payload_cannot_be_imported(self):
        self.store.payloads.clear()
        with self.assertRaises(IncompleteSync):
            self.catalog()

    def test_missing_parent_defers_sync(self):
        self.store.publish("notebook", "tablet-b", b"v2", [self.initial])
        del self.store.records[self.initial]
        with self.assertRaises(IncompleteSync):
            self.catalog()

    def test_explicit_resolution_references_both_branches(self):
        a = self.store.publish("notebook", "tablet-a", b"left", [self.initial])
        b = self.store.publish("notebook", "tablet-b", b"right", [self.initial])
        resolved = self.store.publish("notebook", "tablet-a", b"user resolved version", [a, b])
        self.assertEqual(self.catalog().heads, {resolved})
        self.assertEqual(self.catalog().action(a), "stage-and-verify-import")
        self.assertEqual(self.catalog().action(b), "stage-and-verify-import")
        self.assertEqual(len(self.store.payloads), 4)

    def test_omitting_a_conflict_parent_does_not_hide_that_branch(self):
        a = self.store.publish("notebook", "tablet-a", b"left", [self.initial])
        b = self.store.publish("notebook", "tablet-b", b"right", [self.initial])
        a2 = self.store.publish("notebook", "tablet-a", b"left v2", [a])
        self.assertEqual(self.catalog().heads, {a2, b})

    def test_another_notebook_cannot_be_used_as_parent(self):
        foreign = self.store.publish("another-notebook", "tablet-a", b"unrelated")
        with self.assertRaises(IncompleteSync):
            self.store.publish("notebook", "tablet-a", b"v2", [foreign])
        self.assertEqual(self.catalog().heads, {self.initial})

    def test_catalog_order_does_not_choose_a_winner(self):
        a = self.store.publish("notebook", "tablet-a", b"left", [self.initial])
        b = self.store.publish("notebook", "tablet-b", b"right", [self.initial])
        self.store.records = dict(reversed(list(self.store.records.items())))
        self.assertEqual(self.catalog().heads, {a, b})

    def test_modified_revision_metadata_is_rejected(self):
        value = json.loads(self.store.records[self.initial])
        value["device"] = "tampered"
        self.store.records[self.initial] = json.dumps(value).encode()
        with self.assertRaises(IncompleteSync):
            self.catalog()

    def test_unknown_schema_is_rejected(self):
        value = json.loads(self.store.records[self.initial])
        value["schema"] = 2
        with self.assertRaises(ValueError):
            Revision.decode(json.dumps(value).encode())

    def test_empty_remote_does_not_delete_local_notebooks(self):
        self.assertEqual(Catalog(MemoryStore(), "notebook").action(self.initial), "no-change")

    def test_payload_bytes_are_preserved_exactly(self):
        binary = bytes(range(256)) * 4
        self.store.publish("notebook", "tablet-b", binary, [self.initial])
        self.assertEqual(self.store.payloads[digest(binary)], binary)


if __name__ == "__main__":
    unittest.main(verbosity=2)
