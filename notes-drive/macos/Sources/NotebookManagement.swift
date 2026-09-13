import AppKit
import Foundation

enum NotebookMutationPolicy {
    static func restorePayload(from id: String, catalog: RevisionCatalog) throws -> Data {
        guard let deletion = catalog.revisions[id], deletion.deleted, deletion.notebook.hasPrefix("boox-") else {
            throw ReaderError("Select a deleted native notebook to restore.")
        }
        var pending = deletion.parents, visited = Set<String>(), payloads = Set<String>()
        while let id = pending.popLast() {
            guard visited.insert(id).inserted else { continue }
            guard let revision = catalog.revisions[id], revision.notebook == deletion.notebook else {
                throw ReaderError("Notebook recovery ancestry is unavailable.")
            }
            if let hash = revision.payload { payloads.insert(hash) }
            else { pending.append(contentsOf: revision.parents) }
        }
        guard payloads.count == 1, let hash = payloads.first, let bytes = catalog.payloads[hash],
              sha256(bytes) == hash else {
            throw ReaderError("Recovery has missing or conflicting snapshots. Review and export the retained versions first.")
        }
        return bytes
    }

    static func validate(revision: Revision, summary: NoteSummary?, catalog: RevisionCatalog) throws {
        try require(revision.notebook.hasPrefix("boox-") &&
                    matches(String(revision.notebook.dropFirst(5)), "^[A-Za-z0-9-]{1,100}$"),
                    "Notebook changes require a compatible native identity.")
        for parent in revision.parents {
            try require(catalog.revisions[parent]?.notebook == revision.notebook,
                        "The captured notebook parent is missing or belongs to another notebook.")
        }
        if revision.deleted {
            try require(summary == nil && !revision.parents.isEmpty &&
                        revision.parents.contains { catalog.revisions[$0]?.deleted == false },
                        "Deletion needs an existing live notebook snapshot.")
            return
        }
        guard let summary, summary.sha256 == revision.payload,
              "boox-" + summary.document_id == revision.notebook else {
            throw ReaderError("Notebook payload metadata does not match its revision identity/hash.")
        }
        try require((1...200).contains(summary.page_count), "Notebook has unsupported page metadata.")
        var next = summary.parent_id, visited = Set<String>()
        while let id = next {
            try require(id != summary.document_id && visited.count < 64 && visited.insert(id).inserted,
                        "Notebook destination is cyclic or too deep.")
            let head = try FolderMutationPolicy.singleHead("folder-" + id, catalog: catalog)
            try require(catalog.revisions[head]?.deleted == false, "Restore or choose a live destination folder.")
            next = try FolderMutationPolicy.folder(at: head, catalog: catalog).parent
        }
    }
}

enum NativeNotebookLibrary {
    static func summary(_ bytes: Data) throws -> NoteSummary {
        let work = FileManager.default.temporaryDirectory.appendingPathComponent("boox-summary-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: work, withIntermediateDirectories: false,
                                               attributes: [.posixPermissions: 0o700])
        defer { try? FileManager.default.removeItem(at: work) }
        let input = work.appendingPathComponent("note.note")
        try LocalStore.atomic(bytes, to: input)
        return try NotebookLoader.summary(input, expectedHash: sha256(bytes))
    }

    static func apply(plan: [String: Any], bytes: Data?, directory: URL) throws -> (Data, Notebook, NoteSummary) {
        let work = directory.appendingPathComponent("library-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: work, withIntermediateDirectories: false,
                                               attributes: [.posixPermissions: 0o700])
        defer { try? FileManager.default.removeItem(at: work) }
        let input = work.appendingPathComponent("base.note"), planURL = work.appendingPathComponent("plan.json")
        let output = work.appendingPathComponent("result.note")
        // The create helper ignores bytes and refuses an existing archive. The
        // shared process runner passes paths, so use the explicit /- sentinel.
        if let bytes { try LocalStore.atomic(bytes, to: input) }
        try LocalStore.atomic(JSONSerialization.data(withJSONObject: plan), to: planURL)
        _ = try NativeNoteEditor.command(input: bytes == nil ? URL(fileURLWithPath: "/-") : input,
                                         plan: planURL, output: output, helper: "note_library")
        try require((try output.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? Int.max) <= 4 * 1024 * 1024,
                    "Notebook snapshot exceeds 4 MiB.")
        let result = try Data(contentsOf: output), hash = sha256(result)
        let note = try NotebookLoader.read(output, expectedHash: hash)
        let summary = try NotebookLoader.summary(output, expectedHash: hash)
        try require(note.document_id == plan["document_id"] as? String, "Library helper changed the intended identity.")
        return (result, note, summary)
    }
}

@MainActor
extension ReaderModel {
    func canDisplayManagedNotebook(_ documentID: String) -> Bool {
        guard let draft = editorJournal.draft else { return true }
        return draft.documentID == documentID && draft.operations.isEmpty
    }

    func requireNotebookEditable() throws {
        guard let open = openedRevision, open.scope == currentScope, let snapshot, let account, let folder = selectedFolder else {
            return
        }
        let catalog = try FolderMutationPolicy.includingQueued(snapshot.catalog, queue: editorJournal.queue,
                                                               accountID: account.permissionID, folderID: folder.id)
        let heads = catalog.heads[open.notebookID] ?? []
        try require(!heads.contains { catalog.revisions[$0]?.deleted == true },
                    "This notebook has a deletion request. Restore or resolve its versions before editing.")
    }

    var queuedNotebookChanges: [PendingPublication] {
        editorJournal.queue.filter { (try? $0.checkedRevision().notebook.hasPrefix("boox-")) == true }
    }

    func notebookSummary(_ bytes: Data) throws -> NoteSummary {
        let hash = sha256(bytes)
        if let value = decodedLibrarySummaries[hash] { return value }
        guard let store else { throw ReaderError("Local storage is unavailable.") }
        let url = try store.cache(bytes, hash: hash)
        let summary = try NotebookLoader.summary(url, expectedHash: hash)
        rememberNotebookSummary(summary)
        return summary
    }

    func notebookParentMenu(catalog: RevisionCatalog, selected: String?) -> NSPopUpButton {
        let menu = NSPopUpButton(frame: .zero, pullsDown: false)
        menu.addItem(withTitle: "Library root")
        menu.lastItem?.representedObject = ""
        for (id, heads) in catalog.heads.sorted(by: { $0.key < $1.key })
        where id.hasPrefix("folder-") && heads.count == 1 {
            guard let head = heads.first, catalog.revisions[head]?.deleted == false,
                  let folder = try? FolderMutationPolicy.folder(at: head, catalog: catalog) else { continue }
            menu.addItem(withTitle: "\(folder.title) · \(folder.id.prefix(8))")
            menu.lastItem?.representedObject = folder.id
        }
        if let selected {
            if let item = menu.itemArray.first(where: { $0.representedObject as? String == selected }) {
                menu.select(item)
            } else {
                menu.addItem(withTitle: "Unavailable parent · \(selected.prefix(12)) — choose destination")
                menu.lastItem?.representedObject = selected
                menu.select(menu.lastItem)
            }
        }
        return menu
    }

    func changeNotebook(_ notebookID: String? = nil, restore: Bool = false) {
        perform(restore ? "Preparing a notebook restore…" : "Preparing a notebook change…") {
            let (catalog, account, folder) = try self.folderWorkspace()
            guard let store = self.store else { throw ReaderError("Local storage is unavailable.") }
            // Dialog actions are serialized with refresh and pen gestures. A
            // different notebook's dirty draft may remain open during metadata work.
            let id = notebookID.map { String($0.dropFirst(5)) } ??
                UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
            try require(!(self.editorJournal.draft?.operations.isEmpty == false && self.editorJournal.draft?.documentID == id),
                        "Save or discard this notebook’s pen draft and undo/redo history first.")
            let base = try notebookID.map { try FolderMutationPolicy.singleHead($0, catalog: catalog) }
            var original: Data?
            if let base {
                if restore { original = try NotebookMutationPolicy.restorePayload(from: base, catalog: catalog) }
                else {
                    guard let hash = catalog.revisions[base]?.payload, let data = catalog.payloads[hash] else {
                        throw ReaderError("This notebook is deleted. Use Restore notebook.")
                    }
                    original = data
                }
            }
            let old = try original.map { try self.notebookSummary($0) }
            let alert = NSAlert()
            alert.messageText = restore ? "Restore notebook" : old == nil ? "New notebook" : "Rename or move notebook"
            alert.informativeText = old == nil ?
                "Create blank 1860 × 2480 native pages with one unlocked pen layer. Save queues the notebook; you can draw before it is uploaded." :
                "Save preserves native IDs, pages, pen data and unknown archive contents. It queues a new revision."
            let title = NSTextField(string: old?.title ?? "")
            title.placeholderString = "Notebook title"
            let parent = self.notebookParentMenu(catalog: catalog, selected: old?.parent_id)
            let pages = NSPopUpButton(frame: .zero, pullsDown: false)
            [1, 2, 5, 10, 20, 32].forEach { pages.addItem(withTitle: "\($0)"); pages.lastItem?.representedObject = $0 }
            var views: [NSView] = [NSTextField(labelWithString: "Title"), title,
                                   NSTextField(labelWithString: "Parent folder"), parent]
            if old == nil { views += [NSTextField(labelWithString: "Blank pages"), pages] }
            let fields = NSStackView(views: views)
            fields.orientation = .vertical; fields.alignment = .leading; fields.spacing = 8
            fields.frame = NSRect(x: 0, y: 0, width: 360, height: old == nil ? 178 : 116)
            title.widthAnchor.constraint(equalToConstant: 360).isActive = true
            parent.widthAnchor.constraint(equalToConstant: 360).isActive = true
            alert.accessoryView = fields
            alert.addButton(withTitle: restore ? "Queue restore" : "Save notebook")
            alert.addButton(withTitle: "Cancel")
            guard alert.runModal() == .alertFirstButtonReturn else { return }
            let selected = parent.selectedItem?.representedObject as? String ?? ""
            var plan: [String: Any] = ["operation": old == nil ? "create" : "metadata",
                                      "document_id": id, "title": title.stringValue,
                                      "parent": selected.isEmpty ? NSNull() : selected as Any,
                                      "edited_at_ms": Int64(Date().timeIntervalSince1970 * 1000)]
            if let original { plan["base_sha256"] = sha256(original) }
            else {
                plan["width"] = 1860; plan["height"] = 2480
                plan["page_ids"] = (0..<(pages.selectedItem?.representedObject as? Int ?? 1)).map { _ in
                    UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
                }
            }
            let (bytes, note, summary) = try await Task.detached {
                try NativeNotebookLibrary.apply(plan: plan, bytes: original, directory: store.root)
            }.value
            if bytes == original && !restore { self.status = "Notebook metadata is unchanged."; return }
            let binding = EditBinding(accountID: account.permissionID, folderID: folder.id,
                                      notebookID: "boox-" + id, baseRevision: base)
            let revision = try self.queueNotebookChange(bytes, summary: summary, binding: binding, catalog: catalog)
            if self.canDisplayManagedNotebook(id) && (old == nil || self.notebook?.document_id == id) {
                try self.displaySavedNotebook(bytes, note: note, binding: binding, revisionID: revision.id)
            }
        }
    }

    @discardableResult
    func queueNotebookChange(_ bytes: Data?, summary: NoteSummary?, binding: EditBinding,
                             catalog: RevisionCatalog) throws -> Revision {
        guard let store else { throw ReaderError("Local storage is unavailable.") }
        let revision = try Revision(notebook: binding.notebookID, device: store.deviceID,
                                    parents: binding.baseRevision.map { [$0] } ?? [],
                                    payload: bytes.map(sha256), deleted: bytes == nil)
        try NotebookMutationPolicy.validate(revision: revision, summary: summary, catalog: catalog)
        if let bytes {
            try require(summary?.sha256 == sha256(bytes), "Notebook summary differs from saved bytes.")
            _ = try store.cache(bytes, hash: sha256(bytes))
        }
        var journal = editorJournal
        _ = try journal.enqueueNotebookChange(bytes, binding: binding, deviceID: store.deviceID)
        try commitJournal(journal)
        if let summary { rememberNotebookSummary(summary) }
        if revision.deleted && openedRevision?.notebookID == binding.notebookID {
            editorTool = .view
            libraryNotice = "Deletion saved. This open local copy is preserved; use Restore notebook to resume editing."
        }
        status = "Notebook change saved offline · \(try revision.id.prefix(12)). Automatic updates will publish it."
        return revision
    }

    func requestNotebookDeletion(_ notebookID: String) {
        perform("Reviewing a recoverable notebook deletion…") {
            let (catalog, account, folder) = try self.folderWorkspace()
            let base = try FolderMutationPolicy.singleHead(notebookID, catalog: catalog)
            guard let hash = catalog.revisions[base]?.payload, let bytes = catalog.payloads[hash] else {
                throw ReaderError("This notebook is already deleted.")
            }
            let summary = try self.notebookSummary(bytes)
            try require(!(self.editorJournal.draft?.operations.isEmpty == false &&
                          self.editorJournal.draft?.documentID == summary.document_id),
                        "Save or discard this notebook’s pen draft and undo/redo history before deletion.")
            let alert = NSAlert()
            alert.messageText = "Request deletion of “\(summary.title)”?"
            alert.informativeText = "This queues a recoverable deletion for BOOX’s Recycle Bin. Drive revision history and local files are retained. Restore notebook creates a new revision from retained content."
            alert.addButton(withTitle: "Queue recoverable deletion"); alert.addButton(withTitle: "Cancel")
            guard alert.runModal() == .alertFirstButtonReturn else { return }
            let binding = EditBinding(accountID: account.permissionID, folderID: folder.id,
                                      notebookID: notebookID, baseRevision: base)
            try self.queueNotebookChange(nil, summary: nil, binding: binding, catalog: catalog)
        }
    }

    func displaySavedNotebook(_ bytes: Data, note: Notebook, binding: EditBinding, revisionID: String) throws {
        try clearCleanDraft()
        var captured = binding
        captured.baseRevision = revisionID
        var journal = editorJournal
        journal.draft = EditorDraft(bytes: bytes, documentID: note.document_id, binding: captured)
        try commitJournal(journal)
        let position = ReadingPosition(pageID: page?.id, pageIndex: pageIndex, zoom: zoom)
            .following(pageIDs: note.pages.map(\.id))
        let same = notebook?.document_id == note.document_id
        notebook = note; currentPayload = bytes; currentLocalURL = nil
        pageIndex = same ? position.pageIndex : 0
        openedRevision = OpenLibraryRevision(scope: binding.scope, notebookID: binding.notebookID, revisionID: revisionID)
        source = "Saved offline · \(revisionID.prefix(12))"
        editorTool = .view
    }

    func openQueuedNotebook(_ job: PendingPublication) {
        perform("Opening a saved notebook change…") {
            try require(!self.draftChanged, "Save or discard the current pen draft first.")
            let revision = try job.checkedRevision()
            if revision.deleted {
                self.libraryNotice = "Recoverable deletion \(job.revisionID.prefix(12)) is queued. Retained content can be restored from this notebook’s history."
                return
            }
            guard let store = self.store else { throw ReaderError("Local storage is unavailable.") }
            let url = try store.cache(job.payloadBytes, hash: sha256(job.payloadBytes))
            let note = try await Task.detached { try NotebookLoader.read(url, expectedHash: revision.payload) }.value
            let binding = EditBinding(accountID: job.accountID, folderID: job.folderID,
                                      notebookID: revision.notebook, baseRevision: job.revisionID)
            try self.displaySavedNotebook(job.payloadBytes, note: note, binding: binding, revisionID: job.revisionID)
        }
    }
}
