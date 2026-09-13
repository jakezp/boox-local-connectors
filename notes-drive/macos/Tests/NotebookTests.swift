import Foundation

@MainActor
enum NotebookTests {
    static func python(_ helper: String, arguments: [String]) throws -> Data {
        let resources = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
            .deletingLastPathComponent().appendingPathComponent("Resources")
        let process = Process(), pipe = Pipe()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/python3")
        process.arguments = [resources.appendingPathComponent(helper + ".py").path] + arguments
        process.environment = ["PATH": "/usr/bin:/bin", "PYTHONDONTWRITEBYTECODE": "1", "PYTHONIOENCODING": "utf-8"]
        process.standardOutput = pipe
        process.standardError = FileHandle.nullDevice
        try process.run()
        let data = pipe.fileHandleForReading.readDataToEndOfFile()
        process.waitUntilExit()
        try require(process.terminationStatus == 0, "Fixture helper: " + String(decoding: data, as: UTF8.self))
        return data
    }

    static func run(rootDirectory: URL, passed: (String) -> Void) async throws {
        let directory = rootDirectory.appendingPathComponent("notebooks-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let document = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
        let page = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
        let notebook = "boox-" + document
        let folder = FolderRecord(id: "aabbccdd001122334455667788990011", parent: nil, title: "Folder — é")
        let folderBytes = try folder.encode()
        let folderRevision = try Revision(notebook: "folder-" + folder.id, device: "android",
                                          parents: [], payload: sha256(folderBytes))
        let folderID = try folderRevision.id
        let catalog = try RevisionCatalog(records: [folderID: folderRevision.encode()], payloads: [sha256(folderBytes): folderBytes])
        var summaries: [String: NoteSummary] = [:]
        var notebooks: [String: Notebook] = [:]
        func make(_ plan: [String: Any], source: Data? = nil) throws -> Data {
            let name = UUID().uuidString
            let input = directory.appendingPathComponent(name + ".input.note")
            let output = directory.appendingPathComponent(name + ".note")
            let planURL = directory.appendingPathComponent(name + ".json")
            if let source { try LocalStore.atomic(source, to: input) }
            try LocalStore.atomic(JSONSerialization.data(withJSONObject: plan), to: planURL)
            _ = try python("note_library", arguments: [source == nil ? "/-" : input.path, planURL.path, output.path])
            let bytes = try Data(contentsOf: output)
            let summary = try JSONDecoder().decode(NoteSummary.self, from:
                python("note_reader", arguments: [output.path, sha256(bytes), "--metadata"]))
            summaries[sha256(bytes)] = summary
            notebooks[sha256(bytes)] = try JSONDecoder().decode(Notebook.self, from:
                python("note_reader", arguments: [output.path, sha256(bytes)]))
            return bytes
        }
        let original = try make(["operation": "create", "document_id": document, "page_ids": [page],
                                 "width": 1860, "height": 2480, "title": "Created", "parent": NSNull(),
                                 "edited_at_ms": 1789250000000 as Int64])
        let renamed = try make(["operation": "metadata", "document_id": document, "base_sha256": sha256(original),
                                "title": "Renamed — é/😀", "parent": NSNull(), "edited_at_ms": 1789250001000 as Int64], source: original)
        let moved = try make(["operation": "metadata", "document_id": document, "base_sha256": sha256(renamed),
                              "title": "Renamed — é/😀", "parent": folder.id, "edited_at_ms": 1789250002000 as Int64], source: renamed)
        try require(Set(summaries.values.map(\.document_id)) == [document] &&
                    summaries[sha256(moved)]?.parent_id == folder.id &&
                    summaries[sha256(moved)]?.title == "Renamed — é/😀", "Real native helper lifecycle failed.")
        passed("native create rename and move helpers produce hash-verified snapshots with the same document and page IDs")

        var store: LocalStore? = try LocalStore(root: directory.appendingPathComponent("state"))
        let device = store!.deviceID
        var binding = EditBinding(accountID: "account-one", folderID: "test_folder", notebookID: notebook, baseRevision: nil)
        var journal = EditorJournal()
        for payload in [original, renamed, moved] {
            let overlay = try FolderMutationPolicy.includingQueued(catalog, queue: journal.queue,
                                                                   accountID: binding.accountID, folderID: binding.folderID)
            let proposed = try Revision(notebook: notebook, device: device, parents: binding.baseRevision.map { [$0] } ?? [],
                                        payload: sha256(payload))
            try NotebookMutationPolicy.validate(revision: proposed, summary: summaries[sha256(payload)], catalog: overlay)
            let added = try journal.enqueueNotebookChange(payload, binding: binding, deviceID: device)
            binding.baseRevision = try added.id
        }
        let preDelete = try FolderMutationPolicy.includingQueued(catalog, queue: journal.queue,
                                                                accountID: binding.accountID, folderID: binding.folderID)
        let deleted = try journal.enqueueNotebookChange(nil, binding: binding, deviceID: device)
        let deletedID = try deleted.id
        try NotebookMutationPolicy.validate(revision: deleted, summary: nil, catalog: preDelete)
        let postDelete = try FolderMutationPolicy.includingQueued(catalog, queue: journal.queue,
                                                                 accountID: binding.accountID, folderID: binding.folderID)
        let restoredBytes = try NotebookMutationPolicy.restorePayload(from: deletedID, catalog: postDelete)
        try require(restoredBytes == moved, "Restore changed the retained archive bytes.")
        binding.baseRevision = deletedID
        let restored = try journal.enqueueNotebookChange(restoredBytes, binding: binding, deviceID: device)
        let restoredID = try restored.id
        try NotebookMutationPolicy.validate(revision: restored, summary: summaries[sha256(moved)], catalog: postDelete)
        try store!.saveEditorJournal(journal)
        store = nil
        store = try LocalStore(root: directory.appendingPathComponent("state"))
        let reopened = try store!.editorJournal()
        try require(reopened.queue.count == 5 && reopened.queue[3].payloadBytes.isEmpty &&
                    restored.parents == [deletedID] && reopened.protects(notebookID: notebook),
                    "Offline lifecycle lost tombstone, ancestry or restore snapshot.")
        for pair in zip(reopened.queue, reopened.queue.dropFirst()) {
            try require(try pair.1.checkedRevision().parents == [pair.0.revisionID], "Offline lifecycle broke captured ancestry.")
        }
        passed("create rename move delete and restore chain exact immutable parents across durable restart")

        let fake = FakeDrive()
        await fake.add(folderBytes, type: "payload")
        await fake.add(try folderRevision.encode(), type: "revision")
        var latest: DriveSnapshot?
        for job in reopened.queue {
            if job.revisionID == deletedID { await fake.interruptRecord() }
            do {
                latest = try await EditedSnapshotPublisher.publish(job, client: fake.client(),
                    accountID: "account-one", folderID: "test_folder", deviceID: device, allowConflict: false,
                    nativeSummaries: summaries)
            } catch {
                try require(job.revisionID == deletedID, "Unexpected lifecycle publication failure: \(error)")
                let beforeRetry = await fake.writeCount()
                latest = try await EditedSnapshotPublisher.publish(job, client: fake.client(),
                    accountID: "account-one", folderID: "test_folder", deviceID: device, allowConflict: false,
                    nativeSummaries: summaries)
                try require(await fake.writeCount() == beforeRetry, "Tombstone retry duplicated a committed record.")
            }
        }
        try require(latest?.catalog.heads[notebook] == [restoredID] &&
                    latest?.catalog.payloads[sha256(moved)] == moved &&
                    latest?.catalog.revisions[deletedID]?.deleted == true, "Lifecycle did not retain delete/restore history.")
        let order = await fake.writeOrder()
        try require(order == ["payload", "revision", "payload", "revision", "payload", "revision", "revision", "revision"],
                    "Lifecycle published payloads in the wrong order or uploaded tombstone bytes.")
        passed("real native lifecycle uses payload-before-record publication and recovers lost deletion responses without duplicates")

        var other = EditorDraft(bytes: original, documentID: "anotherNotebook", binding: nil)
        other.operations = [PenOperation(kind: "erase", page_id: "page", ids: ["stroke"])]
        other.cursor = 1
        var otherJournal = EditorJournal(draft: other)
        _ = try otherJournal.enqueueNotebookChange(moved, binding: binding, deviceID: device)
        try require(otherJournal.draft?.editID == other.editID && otherJournal.draft?.cursor == 1, "Lifecycle replaced another dirty draft.")
        var same = EditorDraft(bytes: original, documentID: document, binding: binding)
        same.operations = other.operations; same.cursor = 1
        var blocked = EditorJournal(draft: same)
        try expectFailure { _ = try blocked.enqueueNotebookChange(nil, binding: binding, deviceID: device) }
        try require(blocked.queue.isEmpty && blocked.draft?.baseBytes == original, "Rejected deletion changed the draft.")
        passed("metadata and deletion protect same-notebook dirty drafts and preserve other notebooks’ undo history")

        let folderDeletion = try Revision(notebook: folderRevision.notebook, device: "android",
                                          parents: [folderID], payload: nil, deleted: true)
        let missingFolder = try RevisionCatalog(records: [:], payloads: [:])
        let removedFolder = try RevisionCatalog(records: [folderID: folderRevision.encode(), folderDeletion.id: folderDeletion.encode()],
                                                payloads: [sha256(folderBytes): folderBytes])
        let newMoved = try Revision(notebook: notebook, device: device, parents: [], payload: sha256(moved))
        for invalidCatalog in [missingFolder, removedFolder] {
            try expectFailure { try NotebookMutationPolicy.validate(revision: newMoved, summary: summaries[sha256(moved)], catalog: invalidCatalog) }
        }
        let otherServer = FakeDrive()
        let movedBinding = EditBinding(accountID: "account-one", folderID: "test_folder", notebookID: notebook, baseRevision: nil)
        var moving = EditorJournal()
        _ = try moving.enqueueNotebookChange(moved, binding: movedBinding, deviceID: device)
        try await expectAsyncFailure {
            _ = try await EditedSnapshotPublisher.publish(moving.queue[0], client: otherServer.client(),
                accountID: "account-one", folderID: "test_folder", deviceID: device, allowConflict: true, nativeSummaries: summaries)
        }
        try require(await otherServer.writeCount() == 0, "Missing destination folder was ignored at publication.")
        passed("fresh publication refuses missing or deleted native destination folders even with explicit branch authorization")

        let root = try reopened.queue[0].checkedRevision(), rootID = reopened.queue[0].revisionID
        let remote = try Revision(notebook: notebook, device: "android", parents: [rootID], payload: sha256(moved))
        let staleCatalog = try RevisionCatalog(records: [rootID: root.encode(), remote.id: remote.encode()],
                                               payloads: [sha256(original): original, sha256(moved): moved])
        try require(try EditPublicationPolicy.needsConflictReview(reopened.queue[1], catalog: staleCatalog),
                    "Concurrent native change was silently rebased.")
        var foreignSummary = summaries[sha256(moved)]!
        foreignSummary = NoteSummary(title: foreignSummary.title, document_id: "foreign", sha256: foreignSummary.sha256,
                                     byte_count: moved.count, page_count: 1, page_ids: [page])
        try expectFailure { try NotebookMutationPolicy.validate(revision: newMoved, summary: foreignSummary, catalog: catalog) }
        passed("lifecycle preserves concurrent heads and rejects payload/native identity mismatch")

        let rename = try reopened.queue[1].checkedRevision(), renameID = reopened.queue[1].revisionID
        let ambiguous = try Revision(notebook: notebook, device: "android", parents: [rootID, renameID].sorted(),
                                      payload: nil, deleted: true)
        let ambiguousCatalog = try RevisionCatalog(records: [rootID: root.encode(), renameID: rename.encode(), ambiguous.id: ambiguous.encode()],
                                                   payloads: [sha256(original): original, sha256(renamed): renamed])
        try expectFailure { _ = try NotebookMutationPolicy.restorePayload(from: ambiguous.id, catalog: ambiguousCatalog) }
        let noParents = try Revision(notebook: notebook, device: device, parents: [], payload: nil, deleted: true)
        try expectFailure { try NotebookMutationPolicy.validate(revision: noParents, summary: nil, catalog: catalog) }
        passed("recovery refuses ambiguous retained snapshots and deletion without a native ancestor")

        let model = ReaderModel(root: directory.appendingPathComponent("controller"))
        model.account = DriveAccount(permissionID: "account-one", displayName: "Synthetic", email: "synthetic.invalid")
        model.folders = [DriveFolder(id: "test_folder", name: "Synthetic")]
        model.selectedFolderID = "test_folder"
        model.snapshot = DriveSnapshot(catalog: catalog, objects: [], downloadBytes: 0, verifiedAt: Date())
        let modelBinding = EditBinding(accountID: "account-one", folderID: "test_folder", notebookID: notebook, baseRevision: nil)
        try model.commitJournal(EditorJournal(draft: other))
        let creation = try model.queueNotebookChange(original, summary: summaries[sha256(original)], binding: modelBinding, catalog: catalog)
        try require(model.queuedNotebookChanges.count == 1 && model.hasQueuedNotebook &&
                    model.editorJournal.draft?.editID == other.editID, "Controller failed to preserve a separate draft.")
        let modelOverlay = try model.folderWorkspace().0
        let modelDeleteBinding = EditBinding(accountID: "account-one", folderID: "test_folder", notebookID: notebook,
                                             baseRevision: try creation.id)
        try model.queueNotebookChange(nil, summary: nil, binding: modelDeleteBinding, catalog: modelOverlay)
        model.openedRevision = OpenLibraryRevision(scope: model.currentScope!, notebookID: notebook, revisionID: try creation.id)
        try expectFailure { try model.requireNotebookEditable() }
        let disk = try model.store!.editorJournal()
        try require(disk.queue.count == 2 && disk.queue[1].payloadBytes.isEmpty && disk.draft?.editID == other.editID,
                    "Actual lifecycle controller lost durable content.")
        passed("actual lifecycle controller preserves drafts persists recoverable deletion and blocks accidental editing of a deleted notebook")

        var saved = model.editorJournal
        saved.draft = nil
        try model.commitJournal(saved)
        model.notebook = notebooks[sha256(original)]
        model.pageIndex = 0
        model.zoom = 1.25
        let foreign = EditBinding(accountID: "another-account", folderID: "another-folder",
                                  notebookID: notebook, baseRevision: try creation.id)
        try model.displaySavedNotebook(renamed, note: notebooks[sha256(renamed)]!,
                                        binding: foreign, revisionID: renameID)
        let displayDraft = try model.store!.editorJournal().draft!
        try require(model.zoom == 1.25 && model.page?.id == page && model.notebook?.title == "Renamed — é/😀" &&
                    displayDraft.binding?.accountID == "another-account" &&
                    displayDraft.binding?.folderID == "another-folder" &&
                    displayDraft.binding?.baseRevision == renameID &&
                    displayDraft.baseBytes == renamed,
                    "Displaying saved metadata lost view position, restart draft or original destination binding.")
        passed("opening saved metadata preserves page and zoom and durably retains original account folder and parent")

        var undone = same
        undone.cursor = 0
        var undoneJournal = EditorJournal(draft: undone)
        try expectFailure { _ = try undoneJournal.enqueueNotebookChange(renamed, binding: binding, deviceID: device) }
        try require(undoneJournal.queue.isEmpty && undoneJournal.draft?.canRedo == true,
                    "Metadata erased a fully undone draft’s redo history.")
        undone = other
        undone.cursor = 0
        try model.commitJournal(EditorJournal(draft: undone))
        try require(!model.canDisplayManagedNotebook(document), "Creation would displace another notebook’s redo history.")
        _ = try model.queueNotebookChange(original, summary: summaries[sha256(original)],
                                          binding: modelBinding, catalog: catalog)
        try require(model.editorJournal.draft?.canRedo == true && !model.canDisplayManagedNotebook(document),
                    "Queued creation displaced another notebook’s fully undone draft.")
        passed("fully undone pen drafts retain redo history across notebook creation metadata and deletion attempts")
    }
}
