import Foundation

@MainActor
enum EditorTests {
    static func run(rootDirectory: URL, passed: (String) -> Void) async throws {
        let directory = rootDirectory.appendingPathComponent("editor-" + UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        var store: LocalStore? = try LocalStore(root: directory)
        let device = store!.deviceID
        let base = Data("native-base-fixture".utf8), firstBytes = Data("native-edited-one".utf8)
        let secondBytes = Data("native-edited-two".utf8)
        let documentID = "fee3cf5a80dc5bdbb378acd24cac5f34"
        let pageID = "40ed03e2422455d1925e394c0ab11e25"
        let nativeID = "boox-" + documentID
        let summaryCache = Dictionary(uniqueKeysWithValues: [base, firstBytes, secondBytes].map { bytes in
            (sha256(bytes), NoteSummary(title: "Synthetic editor transport", document_id: documentID,
                                       sha256: sha256(bytes), byte_count: bytes.count, page_count: 1, page_ids: [pageID]))
        })
        let root = try Revision(notebook: nativeID, device: "android", parents: [], payload: sha256(base))
        let rootID = try root.id
        let binding = EditBinding(accountID: "account-one", folderID: "test_folder",
                                  notebookID: nativeID, baseRevision: rootID)
        let pen = NoteStroke(id: "32characterNativeShapeIdentity123", type: 2, layer: 0, width: 4,
                             color: 0xff000000, supported: true, points: [[0, 50], [200, 50]],
                             point_hash: nil, sample_count: 2, bounds: nil, label: "", target_page: nil)
        let page = NotePage(id: pageID, width: 500, height: 500,
                            layers: [NoteLayer(id: 0, visible: true, locked: false)], strokes: [pen], warnings: [])
        try require(EditorGeometry.hit([100, 51], page: page) == pen.id &&
                    EditorGeometry.hit([100, 100], page: page) == nil, "Segment eraser hit test failed.")
        let locked = NotePage(id: page.id, width: 500, height: 500,
                              layers: [NoteLayer(id: 0, visible: true, locked: true)], strokes: [pen], warnings: [])
        try require(EditorGeometry.hit([100, 50], page: locked) == nil, "Eraser selected a locked layer.")
        passed("whole-stroke eraser finds segment interiors and respects layer locks")

        var draft = EditorDraft(bytes: base, documentID: documentID, binding: binding)
        let added = PenOperation.add(page: page, layer: 0, width: 4, color: 0xff000000,
                                     points: [[10, 10], [20, 20]])
        try draft.append(added)
        try draft.append(.erase(page: page, ids: [pen.id]))
        try require(draft.projected(page).strokes.map(\.id) == [added.id!], "Draw/erase projection failed.")
        draft.cursor -= 1
        try require(draft.projected(page).strokes.count == 2 && draft.canRedo, "Undo lost the original pen.")
        draft.cursor += 1
        try require(draft.projected(page).strokes[0].id == added.id, "Redo changed the added stroke identity.")
        let encoded = try jsonObject(draft.plan())
        try require(encoded["document_id"] as? String == documentID &&
                    (encoded["operations"] as? [Any])?.count == 2, "Writer plan lost native identity or edit history.")
        passed("draw erase undo redo retain identities and encode only the active edit history")

        var journal = EditorJournal(draft: draft)
        try store!.saveEditorJournal(journal)
        store = nil
        store = try LocalStore(root: directory)
        let reopenedDraft = try store!.editorJournal().draft!
        try require(reopenedDraft.baseBytes == base && reopenedDraft.cursor == 2 &&
                    reopenedDraft.operations[0].id == added.id && store!.deviceID == device,
                    "Restart lost the draft, history or Mac identity.")
        var undone = reopenedDraft
        undone.cursor -= 1
        try undone.append(.add(page: page, layer: 0, width: 2, color: 0xff000000,
                              points: [[30, 30], [40, 40]]))
        try require(!undone.canRedo && undone.operations.count == 2, "A new gesture did not replace the undone branch.")
        passed("fsynced draft and undo history survive restart; a new gesture replaces redo history")

        let first = try journal.enqueue(bytes: firstBytes, documentID: documentID, binding: binding, deviceID: device)
        let firstID = try first.id
        let second = try journal.enqueue(bytes: secondBytes, documentID: documentID,
                                        binding: journal.draft!.binding!, deviceID: device)
        try store!.saveEditorJournal(journal)
        let queued = try store!.editorJournal()
        try require(first.parents == [rootID] && second.parents == [firstID] &&
                    queued.queue.count == 2 && queued.queue[0].payloadBytes == firstBytes &&
                    queued.draft?.baseBytes == secondBytes && queued.protects(notebookID: nativeID),
                    "Offline saves lost their captured ancestry or atomic draft transition.")
        passed("two offline saves persist exact bytes and chain captured parents atomically")

        let fake = FakeDrive()
        await fake.add(base, type: "payload")
        await fake.add(try root.encode(), type: "revision")
        let client = fake.client()
        let job = queued.queue[0]
        for (account, folder, clientDevice) in [("foreign-account", "test_folder", device),
                                               ("account-one", "other_folder", device),
                                               ("account-one", "test_folder", "mac-other")] {
            try await expectAsyncFailure {
                _ = try await EditedSnapshotPublisher.publish(job, client: client, accountID: account,
                    folderID: folder, deviceID: clientDevice, allowConflict: false, nativeSummaries: summaryCache)
            }
        }
        try require(await fake.writeCount() == 0, "Destination mismatch performed a write.")
        passed("queued edits cannot redirect to another account folder or Mac identity")

        await fake.interruptRecord()
        try await expectAsyncFailure {
            _ = try await EditedSnapshotPublisher.publish(job, client: client, accountID: "account-one",
                folderID: "test_folder", deviceID: device, allowConflict: false, nativeSummaries: summaryCache)
        }
        let retained = try store!.editorJournal()
        try require(retained.queue[0].revisionBytes == job.revisionBytes &&
                    retained.queue[0].payloadBytes == job.payloadBytes, "Unknown write outcome changed the durable job.")
        let writesBeforeRetry = await fake.writeCount()
        let after = try await EditedSnapshotPublisher.publish(job, client: client, accountID: "account-one",
            folderID: "test_folder", deviceID: device, allowConflict: false, nativeSummaries: summaryCache)
        let writesAfterRetry = await fake.writeCount()
        try require(after.catalog.revisions[firstID] == first && writesAfterRetry == writesBeforeRetry,
                    "Retry after remote commit created duplicate writes.")
        var completed = retained
        completed.completeFirst()
        try store!.saveEditorJournal(completed)
        try require(try store!.editorJournal().queue[0].revisionID == second.id &&
                    completed.receipts[0].revisionID == firstID, "Completion advanced the wrong queued edit.")
        passed("edited-snapshot publisher verifies payload first and recovers lost record responses without duplicate writes")

        let concurrentBytes = Data("concurrent-android-edit".utf8)
        let concurrent = try Revision(notebook: nativeID, device: "android", parents: [firstID],
                                      payload: sha256(concurrentBytes))
        await fake.add(concurrentBytes, type: "payload")
        await fake.add(try concurrent.encode(), type: "revision")
        let nextJob = completed.queue[0]
        let writesBeforeConflict = await fake.writeCount()
        do {
            _ = try await EditedSnapshotPublisher.publish(nextJob, client: client, accountID: "account-one",
                folderID: "test_folder", deviceID: device, allowConflict: false, nativeSummaries: summaryCache)
            throw ReaderError("An incoming conflict was published without review.")
        } catch is EditConflictError {}
        try require(await fake.writeCount() == writesBeforeConflict && completed.protects(notebookID: nativeID),
                    "Conflict review changed remote data or released the local edit.")
        let branched = try await EditedSnapshotPublisher.publish(nextJob, client: client, accountID: "account-one",
            folderID: "test_folder", deviceID: device, allowConflict: true, nativeSummaries: summaryCache)
        try require(branched.catalog.heads[nativeID]?.count == 2 &&
                    branched.catalog.revisions[nextJob.revisionID]?.parents == [firstID],
                    "Explicit branch publication replaced the incoming head or rewrote ancestry.")
        passed("incoming conflicts pause writes; explicit branch publication preserves both heads and original parents")

        let tombstone = try Revision(notebook: nativeID, device: "android", parents: [firstID], payload: nil, deleted: true)
        let deletionCatalog = try RevisionCatalog(records: [rootID: root.encode(), firstID: first.encode(), tombstone.id: tombstone.encode()],
                                                 payloads: [sha256(base): base, sha256(firstBytes): firstBytes])
        try require(try EditPublicationPolicy.needsConflictReview(nextJob, catalog: deletionCatalog) &&
                    completed.protects(notebookID: nativeID), "Incoming deletion did not preserve pending local edits.")
        passed("incoming notebook deletion remains reviewable while local drafts or publications exist")

        let savedURL = directory.appendingPathComponent("editor-journal.json")
        var corrupt = try jsonObject(Data(contentsOf: savedURL))
        var jobs = corrupt["queue"] as! [[String: Any]]
        jobs[0]["payloadBytes"] = Data("corrupt".utf8).base64EncodedString()
        corrupt["queue"] = jobs
        let damagedBytes = try JSONSerialization.data(withJSONObject: corrupt)
        try LocalStore.atomic(damagedBytes, to: savedURL)
        try expectFailure { _ = try store!.editorJournal() }
        try require(try Data(contentsOf: savedURL) == damagedBytes, "Corrupt journal was overwritten.")
        passed("corrupt offline payloads fail closed and preserve the journal for recovery")
        try store!.saveEditorJournal(completed)

        var full = EditorJournal()
        var parent = binding
        for index in 0..<16 {
            let revision = try full.enqueue(bytes: Data("queued-\(index)".utf8), documentID: documentID,
                                             binding: parent, deviceID: device)
            parent.baseRevision = try revision.id
        }
        try expectFailure { _ = try full.enqueue(bytes: secondBytes, documentID: documentID, binding: parent, deviceID: device) }
        try require(full.queue.count == 16, "Queue overflow replaced an existing save.")
        passed("bounded offline queue refuses overflow without replacing prior saves")
        let folder = FolderRecord(id: "parent32character", parent: nil, title: "Work")
        let encodedFolder = try folder.encode()
        try require(try FolderRecord.decode(encodedFolder, notebookID: "folder-" + folder.id) == folder,
                    "Folder canonical payload did not round trip.")
        for data in [encodedFolder + Data("\n".utf8),
                     Data("{\"id\":\"parent32character\",\"kind\":\"folder\",\"parent\":null,\"schema\":true,\"title\":\"Work\"}".utf8),
                     Data("{\"id\":\"parent32character\",\"kind\":\"folder\",\"parent\":null,\"schema\":1,\"schema\":1,\"title\":\"Work\"}".utf8)] {
            try expectFailure { _ = try FolderRecord.decode(data, notebookID: "folder-" + folder.id) }
        }
        try expectFailure { _ = try FolderRecord.decode(encodedFolder, notebookID: "folder-foreign") }
        passed("folder payloads preserve the schema1 envelope and reject noncanonical mismatched metadata")
        let punctuation = FolderRecord(id: "f1", parent: nil, title: "A/B\t\"é😀<—\u{001b}")
        let canonicalQuoted = Data("{\"id\":\"f1\",\"kind\":\"folder\",\"parent\":null,\"schema\":1,\"title\":\"A/B\\t\\\"é😀<—\\u001b\"}".utf8)
        try require(try punctuation.encode() == canonicalQuoted &&
                    FolderRecord.decode(canonicalQuoted, notebookID: "folder-f1") == punctuation,
                    "Shared explicit slash/control/UTF-8 encoding differs.")
        let escapedSlash = Data(String(decoding: canonicalQuoted, as: UTF8.self)
            .replacingOccurrences(of: "A/B", with: "A\\/B").utf8)
        try expectFailure { _ = try FolderRecord.decode(escapedSlash, notebookID: "folder-f1") }
        passed("folder canonical strings preserve slash em dash and Unicode with only specified control escapes")

        let folderRevision = try Revision(notebook: "folder-" + folder.id, device: "android", parents: [],
                                          payload: sha256(encodedFolder))
        let childFolder = FolderRecord(id: "child", parent: folder.id, title: "Projects")
        let childBytes = try childFolder.encode()
        let childRevision = try Revision(notebook: "folder-child", device: "android", parents: [], payload: sha256(childBytes))
        let foldersCatalog = try RevisionCatalog(
            records: [folderRevision.id: folderRevision.encode(), childRevision.id: childRevision.encode()],
            payloads: [sha256(encodedFolder): encodedFolder, sha256(childBytes): childBytes])
        let folderHeads = LibraryIndexBuilder.heads(catalog: foldersCatalog,
            summaries: [sha256(encodedFolder): .folder(folder), sha256(childBytes): .folder(childFolder)])
        try require(folderHeads.count == 2 && folderHeads.allSatisfy { $0.isFolder && !$0.isValidation && $0.pageCount == nil } &&
                    FolderHierarchy.path(parentID: "child", heads: folderHeads) == "Work / Projects",
                    "Empty folders or native parent hierarchy disappeared.")
        passed("empty folders are typed separately from notebook previews and expose parent hierarchy")

        let cycleParent = FolderRecord(id: folder.id, parent: "child", title: "Work")
        var cyclic = folderHeads
        let rootIndex = cyclic.firstIndex { $0.folder?.id == folder.id }!
        cyclic[rootIndex].folder = cycleParent
        try require(FolderHierarchy.path(parentID: "child", heads: cyclic)?.contains("cycle") == true &&
                    FolderHierarchy.path(parentID: "missing", heads: folderHeads)?.contains("review") == true,
                    "Folder cycle/missing parent was treated as a valid path.")
        passed("folder parent cycles and missing parents stay visible for review")

        let removedFolder = try Revision(notebook: folderRevision.notebook, device: "android",
                                          parents: [folderRevision.id], payload: nil, deleted: true)
        let removedCatalog = try RevisionCatalog(
            records: [folderRevision.id: folderRevision.encode(), removedFolder.id: removedFolder.encode()],
            payloads: [sha256(encodedFolder): encodedFolder])
        let removedHeads = LibraryIndexBuilder.heads(catalog: removedCatalog,
                                                     summaries: [sha256(encodedFolder): .folder(folder)])
        try require(removedHeads.count == 1 && removedHeads[0].revision.deleted && removedHeads[0].title == "Work" &&
                    FolderHierarchy.path(parentID: folder.id, heads: removedHeads)?.contains("review") == true,
                    "Folder tombstone deleted local hierarchy metadata.")
        passed("folder tombstones retain prior metadata and require review rather than hiding local content")
        store = nil
        for invalidLegacyState in [false, true] {
            let modelRoot = directory.appendingPathComponent("model-" + UUID().uuidString)
            var preparation: LocalStore? = try LocalStore(root: modelRoot)
            try preparation!.saveEditorJournal(EditorJournal(draft: draft))
            let journalPath = modelRoot.appendingPathComponent("editor-journal.json")
            if invalidLegacyState {
                try LocalStore.atomic(Data("{}".utf8), to: modelRoot.appendingPathComponent("pending-publication.json"))
            } else {
                try LocalStore.atomic(Data("{}".utf8), to: journalPath)
            }
            let preserved = try Data(contentsOf: journalPath)
            preparation = nil
            let model = ReaderModel(root: modelRoot)
            try require(!model.editorStorageReady && model.failure != nil, "Damaged startup state enabled editor writes.")
            try expectFailure { try model.commitJournal(EditorJournal()) }
            try require(try Data(contentsOf: journalPath) == preserved, "A failed startup replaced existing editor recovery data.")
        }
        passed("actual editor controller blocks journal replacement after corrupt editor or legacy startup state")
    }
}
