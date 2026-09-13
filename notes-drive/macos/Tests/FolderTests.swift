import Foundation

@MainActor
enum FolderTests {
    static func run(rootDirectory: URL, passed: (String) -> Void) async throws {
        let directory = rootDirectory.appendingPathComponent("folders-" + UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        var store: LocalStore? = try LocalStore(root: directory)
        let device = store!.deviceID
        let parent = FolderRecord(id: "aabbccddeeff00112233445566778899", parent: nil, title: "Notes — é/😀")
        let parentBytes = try parent.encode()
        let original = try Revision(notebook: "folder-" + parent.id, device: "android", parents: [], payload: sha256(parentBytes))
        let originalID = try original.id
        let catalog = try RevisionCatalog(records: [originalID: original.encode()], payloads: [sha256(parentBytes): parentBytes])
        let binding = EditBinding(accountID: "account-one", folderID: "test_folder", notebookID: original.notebook, baseRevision: originalID)
        let draft = EditorDraft(bytes: Data("untouched-note-draft".utf8), documentID: "native32", binding: nil)
        var journal = EditorJournal(draft: draft)
        let renamed = FolderRecord(id: parent.id, parent: nil, title: "Renamed — é/😀")
        let renamedBytes = try renamed.encode()
        let first = try journal.enqueueFolder(renamed, binding: binding, deviceID: device)
        let firstID = try first.id
        var nextBinding = binding
        nextBinding.baseRevision = firstID
        let final = FolderRecord(id: parent.id, parent: nil, title: "Final folder")
        let second = try journal.enqueueFolder(final, binding: nextBinding, deviceID: device)
        let secondID = try second.id
        try store!.saveEditorJournal(journal)
        store = nil
        store = try LocalStore(root: directory)
        let reopened = try store!.editorJournal()
        try require(reopened.draft?.editID == draft.editID && reopened.draft?.baseBytes == draft.baseBytes &&
                    reopened.queue.count == 2 && second.parents == [firstID] &&
                    reopened.queue[0].payloadBytes == renamedBytes, "Folder changes damaged draft or offline ancestry.")
        passed("folder rename saves preserve the notebook draft and sequential ancestry across restart")

        let local = try FolderMutationPolicy.includingQueued(catalog, queue: reopened.queue, accountID: "account-one", folderID: "test_folder")
        try require(try local.heads[original.notebook] == [secondID] &&
                    FolderMutationPolicy.folder(at: secondID, catalog: local) == final &&
                    catalog.heads[original.notebook] == [originalID], "Queued folder preview overwrote verified catalog state.")
        let foreign = try FolderMutationPolicy.includingQueued(catalog, queue: reopened.queue, accountID: "other", folderID: "test_folder")
        try require(foreign.heads == catalog.heads, "Folder preview redirected foreign queued changes.")
        passed("pending folder preview preserves verified Drive state and isolates account destinations")

        let child = FolderRecord(id: "child32native", parent: parent.id, title: "Child")
        let childBytes = try child.encode()
        let childRevision = try Revision(notebook: "folder-" + child.id, device: device, parents: [], payload: sha256(childBytes))
        let childID = try childRevision.id
        let childCatalog = try RevisionCatalog(records: [originalID: original.encode(), childID: childRevision.encode()],
                                               payloads: [sha256(parentBytes): parentBytes, sha256(childBytes): childBytes])
        try FolderMutationPolicy.validate(revision: childRevision, bytes: childBytes, catalog: catalog, nativeSummaries: [:])
        for invalidParent in [child.id, "missing"] {
            let record = FolderRecord(id: parent.id, parent: invalidParent, title: parent.title)
            let move = try Revision(notebook: original.notebook, device: device, parents: [originalID], payload: sha256(record.encode()))
            try expectFailure { try FolderMutationPolicy.validate(revision: move, bytes: record.encode(), catalog: childCatalog, nativeSummaries: [:]) }
        }
        let concurrent = try Revision(notebook: original.notebook, device: "other", parents: [originalID], payload: sha256(renamedBytes))
        let conflictCatalog = try RevisionCatalog(records: [originalID: original.encode(), firstID: first.encode(), concurrent.id: concurrent.encode()],
                                                  payloads: [sha256(parentBytes): parentBytes, sha256(renamedBytes): renamedBytes])
        try expectFailure { _ = try FolderMutationPolicy.singleHead(original.notebook, catalog: conflictCatalog) }
        try expectFailure { try FolderMutationPolicy.validate(revision: childRevision, bytes: childBytes, catalog: conflictCatalog, nativeSummaries: [:]) }
        passed("folder moves reject descendants missing parents and conflicting destinations")

        let deleted = try Revision(notebook: original.notebook, device: device, parents: [originalID], payload: nil, deleted: true)
        let deletedID = try deleted.id
        try FolderMutationPolicy.validate(revision: deleted, bytes: Data(), catalog: catalog, nativeSummaries: [:])
        try expectFailure { try FolderMutationPolicy.validate(revision: deleted, bytes: Data(), catalog: childCatalog, nativeSummaries: [:]) }
        let noteBytes = Data("notebook represented by verified test summary".utf8), noteHash = sha256(noteBytes)
        let noteRevision = try Revision(notebook: "boox-native32", device: "android", parents: [], payload: noteHash)
        let withNotebook = try RevisionCatalog(records: [originalID: original.encode(), noteRevision.id: noteRevision.encode()],
                                                payloads: [sha256(parentBytes): parentBytes, noteHash: noteBytes])
        var noteSummary = NoteSummary(title: "Native", document_id: "native32", sha256: noteHash,
                                      byte_count: noteBytes.count, page_count: 1, page_ids: ["page"], parent_id: parent.id)
        try expectFailure { try FolderMutationPolicy.validate(revision: deleted, bytes: Data(), catalog: withNotebook, nativeSummaries: [noteHash: noteSummary]) }
        try expectFailure { try FolderMutationPolicy.validate(revision: deleted, bytes: Data(), catalog: withNotebook, nativeSummaries: [:]) }
        noteSummary.parent_id = nil
        try FolderMutationPolicy.validate(revision: deleted, bytes: Data(), catalog: withNotebook, nativeSummaries: [noteHash: noteSummary])
        passed("empty-folder deletion refuses folder children notebook children and unknown parent metadata")

        let deletedCatalog = try RevisionCatalog(records: [originalID: original.encode(), deletedID: deleted.encode()], payloads: [sha256(parentBytes): parentBytes])
        try require(try FolderMutationPolicy.restoredRecord(from: deletedID, catalog: deletedCatalog) == parent, "Restore lost folder metadata.")
        var restoring = EditorJournal(draft: draft), deletedBinding = binding
        deletedBinding.baseRevision = deletedID
        let restored = try restoring.enqueueFolder(parent, binding: deletedBinding, deviceID: device)
        let restoredID = try restored.id
        try require(restored.parents == [deletedID] && !restored.deleted && restored.payload == original.payload &&
                    restoring.draft?.editID == draft.editID, "Restore rewound ancestry or replaced the draft.")
        try expectFailure { try FolderMutationPolicy.validate(revision: childRevision, bytes: childBytes, catalog: deletedCatalog, nativeSummaries: [:]) }
        passed("restore preserves native ID and exact metadata while parenting the deletion revision")

        let ambiguousDeletion = try Revision(notebook: original.notebook, device: "android", parents: [originalID, firstID].sorted(), payload: nil, deleted: true)
        let ambiguous = try RevisionCatalog(records: [originalID: original.encode(), firstID: first.encode(), ambiguousDeletion.id: ambiguousDeletion.encode()],
                                              payloads: [sha256(parentBytes): parentBytes, sha256(renamedBytes): renamedBytes])
        try expectFailure { _ = try FolderMutationPolicy.restoredRecord(from: ambiguousDeletion.id, catalog: ambiguous) }
        passed("restore refuses conflicting prior metadata instead of selecting an arbitrary ancestor")

        var deleting = EditorJournal(draft: draft)
        _ = try deleting.enqueueFolder(nil, binding: binding, deviceID: device)
        try store!.saveEditorJournal(deleting)
        let deletionJob = try store!.editorJournal().queue[0]
        try require(deletionJob.payloadBytes.isEmpty && (try deletionJob.checkedRevision()).deleted, "Tombstone fabricated payload.")
        let bad = PendingPublication(accountID: "account-one", folderID: "test_folder", deviceID: device, revisionID: deletionJob.revisionID,
                                      revisionBytes: deletionJob.revisionBytes, payloadBytes: Data("unexpected".utf8), createdAt: Date(), status: "queued-folder-change")
        try expectFailure { _ = try bad.checkedRevision() }
        let empty = PendingPublication(accountID: "account-one", folderID: "test_folder", deviceID: device, revisionID: firstID,
                                        revisionBytes: try first.encode(), payloadBytes: Data(), createdAt: Date(), status: "queued-folder-change")
        try expectFailure { _ = try empty.checkedRevision() }
        passed("durable tombstones omit payloads and reject fabricated bytes or empty non-deletion data")

        let fake = FakeDrive()
        await fake.add(parentBytes, type: "payload")
        await fake.add(try original.encode(), type: "revision")
        await fake.interruptRecord()
        try await expectAsyncFailure {
            _ = try await EditedSnapshotPublisher.publish(deletionJob, client: fake.client(), accountID: "account-one", folderID: "test_folder", deviceID: device, allowConflict: false)
        }
        let lostOrder = await fake.writeOrder()
        try require(lostOrder == ["revision"], "Deletion uploaded an empty payload.")
        let afterDelete = try await EditedSnapshotPublisher.publish(deletionJob, client: fake.client(), accountID: "account-one", folderID: "test_folder", deviceID: device, allowConflict: false)
        let retriedOrder = await fake.writeOrder()
        try require(afterDelete.catalog.heads[original.notebook] == [deletedID] && retriedOrder == lostOrder, "Tombstone retry duplicated writes.")
        let afterRestore = try await EditedSnapshotPublisher.publish(restoring.queue[0], client: fake.client(), accountID: "account-one", folderID: "test_folder", deviceID: device, allowConflict: false)
        let restoredOrder = await fake.writeOrder()
        try require(afterRestore.catalog.heads[original.notebook] == [restoredID] && restoredOrder == ["revision", "revision"], "Restore failed to reuse preserved payload.")
        passed("Drive adapter recovers lost tombstone responses without payload upload and restores preserved bytes")

        let changed = FakeDrive()
        await changed.add(parentBytes, type: "payload")
        await changed.add(try original.encode(), type: "revision")
        await changed.add(noteBytes, type: "payload")
        await changed.add(try noteRevision.encode(), type: "revision")
        noteSummary.parent_id = parent.id
        try await expectAsyncFailure {
            _ = try await EditedSnapshotPublisher.publish(deletionJob, client: changed.client(), accountID: "account-one",
                folderID: "test_folder", deviceID: device, allowConflict: true, nativeSummaries: [noteHash: noteSummary])
        }
        try require(await changed.writeCount() == 0, "Publication ignored a new remote child.")
        passed("fresh publication rechecks remote children even when conflict branching is explicitly allowed")

        let creationServer = FakeDrive()
        var creationQueue = EditorJournal()
        let rootBinding = EditBinding(accountID: "account-one", folderID: "test_folder",
                                      notebookID: original.notebook, baseRevision: nil)
        _ = try creationQueue.enqueueFolder(parent, binding: rootBinding, deviceID: device)
        let childBinding = EditBinding(accountID: "account-one", folderID: "test_folder",
                                       notebookID: childRevision.notebook, baseRevision: nil)
        _ = try creationQueue.enqueueFolder(child, binding: childBinding, deviceID: device)
        try await expectAsyncFailure {
            _ = try await EditedSnapshotPublisher.publish(creationQueue.queue[1], client: creationServer.client(),
                accountID: "account-one", folderID: "test_folder", deviceID: device, allowConflict: false)
        }
        try require(await creationServer.writeCount() == 0, "Child folder uploaded before its parent was available.")
        for job in creationQueue.queue {
            _ = try await EditedSnapshotPublisher.publish(job, client: creationServer.client(),
                accountID: "account-one", folderID: "test_folder", deviceID: device, allowConflict: false)
        }
        let created = try await creationServer.client().refresh(folderID: "test_folder")
        let creationOrder = await creationServer.writeOrder()
        try require(created.catalog.payloads[sha256(parentBytes)] == parentBytes &&
                    created.catalog.payloads[sha256(childBytes)] == childBytes &&
                    creationOrder == ["payload", "revision", "payload", "revision"],
                    "New nested folders lost canonical bytes or payload-before-record ordering.")
        passed("nested folder creation waits for its parent and publishes exact UTF-8 payloads before each revision")

        store = nil
        let model = ReaderModel(root: directory.appendingPathComponent("controller"))
        let modelBinding = EditBinding(accountID: "account-one", folderID: "test_folder", notebookID: original.notebook, baseRevision: nil)
        try model.commitJournal(EditorJournal(draft: draft))
        try model.queueFolder(parent, binding: modelBinding, catalog: RevisionCatalog(records: [:], payloads: [:]))
        let savedModel = try model.store!.editorJournal()
        try require(savedModel.draft?.editID == draft.editID && savedModel.queue.count == 1 &&
                    model.queuedFolderChanges.count == 1 && !model.hasQueuedNotebook, "Actual controller lost draft or treated folder as notebook.")
        passed("actual folder controller queues canonical metadata durably without replacing the notebook draft")
    }
}
