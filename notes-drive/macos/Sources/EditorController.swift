import AppKit
import Foundation
import UniformTypeIdentifiers

@MainActor
extension ReaderModel {
    var draftChanged: Bool { editorJournal.draft?.hasChanges == true }
    var writableLayers: [NoteLayer] { page?.layers.filter { $0.visible && !$0.locked } ?? [] }
    var queueStatus: String {
        guard let first = editorJournal.queue.first else { return "All saved changes sent, or none queued." }
        return "\(editorJournal.queue.count) saved change(s) · \(first.status)"
    }
    var queuedNotebookID: String? {
        editorJournal.queue.first.flatMap { try? Revision.decode($0.revisionBytes).notebook }
    }

    func commitJournal(_ journal: EditorJournal) throws {
        guard let store, editorStorageReady else {
            throw ReaderError("Saved editor state could not be loaded. It is preserved; writing a replacement is disabled.")
        }
        try store.saveEditorJournal(journal)
        editorJournal = journal
    }

    func clearCleanDraft() throws {
        try require(!draftChanged, "Save or discard the current draft first.")
        if editorJournal.draft != nil {
            var journal = editorJournal
            journal.draft = nil
            try commitJournal(journal)
        }
        editorTool = .view
    }

    func restoreEditorDraft() {
        perform("Restoring the offline notebook draft…") {
            guard let draft = self.editorJournal.draft, let store = self.store else { return }
            let url = try store.cache(draft.baseBytes, hash: draft.baseHash)
            let note = try await Task.detached { try NotebookLoader.read(url, expectedHash: draft.baseHash) }.value
            self.notebook = note
            self.currentPayload = draft.baseBytes
            self.currentLocalURL = nil
            self.pageIndex = 0
            if let binding = draft.binding, let revision = binding.baseRevision {
                self.openedRevision = OpenLibraryRevision(scope: binding.scope, notebookID: binding.notebookID,
                                                         revisionID: revision)
            }
            self.editorTool = draft.hasChanges ? .pen : .view
            self.editLayer = self.writableLayers.first?.id ?? 0
            self.source = "Recovered local notebook · \(draft.baseHash.prefix(12))"
            self.status = draft.hasChanges ? "Recovered draft and undo/redo history. Nothing was overwritten remotely." :
                "Recovered saved notebook. \(self.queueStatus)"
        }
    }

    func setEditorTool(_ tool: EditorTool) {
        perform("Preparing native pen editing…") {
            if tool != .view {
                guard let note = self.notebook, let bytes = self.currentPayload else {
                    throw ReaderError("Open a notebook before editing.")
                }
                try self.requireNotebookEditable()
                try require(bytes.count <= 4 * 1024 * 1024, "Editing is bounded to 4 MiB exports.")
                try require(!self.writableLayers.isEmpty, "This page has no visible unlocked layer.")
                if self.editorJournal.draft == nil {
                    var binding: EditBinding?
                    if let open = self.openedRevision, let account = self.account, let folder = self.selectedFolder,
                       open.scope == self.currentScope {
                        binding = EditBinding(accountID: account.permissionID, folderID: folder.id,
                                              notebookID: open.notebookID, baseRevision: open.revisionID)
                    }
                    var journal = self.editorJournal
                    journal.draft = EditorDraft(bytes: bytes, documentID: note.document_id, binding: binding)
                    try self.commitJournal(journal)
                }
                if !self.writableLayers.contains(where: { $0.id == self.editLayer }) {
                    self.editLayer = self.writableLayers[0].id
                }
            }
            self.editorTool = tool
            self.status = tool == .eraser ? "Click a pen stroke to erase it whole. Other content and locked layers are preserved." :
                tool == .pen ? "Draw with the mouse or tablet. Each completed stroke is saved to the offline draft." :
                "Reading. Draft edits remain saved locally until queued, exported or discarded."
        }
    }

    func acceptGesture(points: [[Double]], pageID: String, tool: EditorTool) {
        guard !manualBusy, let page, page.id == pageID, tool != .view else { return }
        let operation: PenOperation
        if tool == .eraser {
            guard let point = points.last, let id = EditorGeometry.hit(point, page: page) else { return }
            operation = .erase(page: page, ids: [id])
        } else {
            guard points.count >= 2 else { return }
            let layer = writableLayers.contains(where: { $0.id == editLayer }) ? editLayer : writableLayers.first?.id
            guard let layer else { failure = "This page has no visible unlocked layer."; return }
            operation = .add(page: page, layer: layer, width: penWidth, color: penColor, points: points)
        }
        perform("Saving the completed gesture locally…") {
            guard var draft = self.editorJournal.draft else { throw ReaderError("Begin editing before drawing.") }
            try require(self.page?.id == pageID, "The page changed while drawing; gesture was not applied.")
            try draft.append(operation)
            var journal = self.editorJournal
            journal.draft = draft
            try self.commitJournal(journal)
            self.status = "Draft saved offline · \(draft.cursor) action(s). Save to library when ready."
        }
    }

    func undoEdit(redo: Bool = false) {
        perform(redo ? "Redoing the last edit…" : "Undoing the last edit…") {
            guard var draft = self.editorJournal.draft else { return }
            if redo {
                guard draft.canRedo else { return }
                draft.cursor += 1
            } else {
                guard draft.canUndo else { return }
                draft.cursor -= 1
            }
            var journal = self.editorJournal
            journal.draft = draft
            try self.commitJournal(journal)
            self.status = "Draft history saved offline."
        }
    }

    func discardDraft() {
        perform("Discarding draft edits…") {
            var journal = self.editorJournal
            journal.draft = nil
            try self.commitJournal(journal)
            self.editorTool = .view
            self.status = "Draft edits discarded. Previously queued saves are retained."
        }
    }

    private func publicationBinding(_ draft: EditorDraft) throws -> EditBinding {
        if let binding = draft.binding { return binding }
        guard let account, let folder = selectedFolder, let snapshot else {
            throw ReaderError("Connect and verify the library once before queuing a local export. Export a .note copy for offline use.")
        }
        let notebookID = "boox-" + draft.documentID
        try require(matches(notebookID, "^[A-Za-z0-9_-]{1,128}$"), "Unsupported native notebook ID.")
        let heads = snapshot.catalog.heads[notebookID] ?? []
        try require(heads.count <= 1, "The library has conflicting versions. Open the version you intend to edit.")
        if let head = heads.first {
            try require(snapshot.catalog.revisions[head]?.payload == draft.baseHash,
                        "This local export differs from the library head. Open a verified library version before editing it.")
        }
        return EditBinding(accountID: account.permissionID, folderID: folder.id,
                           notebookID: notebookID, baseRevision: heads.first)
    }

    func saveDraftToLibrary() {
        perform("Encoding and verifying the edited native notebook…") {
            guard let draft = self.editorJournal.draft, draft.hasChanges, let store = self.store else {
                throw ReaderError("There are no new draft edits to save.")
            }
            let binding = try self.publicationBinding(draft)
            try self.requireNotebookEditable()
            let (bytes, note) = try await Task.detached { try NativeNoteEditor.apply(draft, directory: store.root) }.value
            _ = try store.cache(bytes, hash: sha256(bytes))
            var journal = self.editorJournal
            if bytes == draft.baseBytes {
                journal.draft = EditorDraft(bytes: bytes, documentID: note.document_id, binding: binding)
                try self.commitJournal(journal)
                self.status = "Edits cancel each other out; no revision was queued."
                return
            }
            let revision = try journal.enqueue(bytes: bytes, documentID: note.document_id,
                                               binding: binding, deviceID: store.deviceID)
            try self.commitJournal(journal) // Draft transition and outgoing bytes commit together.
            self.notebook = note
            self.currentPayload = bytes
            self.currentLocalURL = nil
            self.openedRevision = OpenLibraryRevision(scope: binding.scope, notebookID: binding.notebookID,
                                                     revisionID: try revision.id)
            self.source = "Saved offline · \(try revision.id.prefix(12))"
            self.status = "Native notebook saved and queued. Automatic sync will send it using the captured parent revision."
        }
    }

    func exportDraftCopy() {
        perform("Preparing an edited .note copy…") {
            guard let draft = self.editorJournal.draft, let store = self.store else {
                throw ReaderError("Begin editing a notebook first.")
            }
            let (bytes, _) = try await Task.detached { try NativeNoteEditor.apply(draft, directory: store.root) }.value
            let panel = NSSavePanel()
            panel.allowedContentTypes = [UTType(filenameExtension: "note") ?? .data]
            panel.nameFieldStringValue = (self.notebook?.title ?? "Notebook") + "-Mac.note"
            panel.message = "Export an edited copy with the same notebook/page IDs. The draft and original remain available."
            guard panel.runModal() == .OK, let url = panel.url else { return }
            try require(url.resolvingSymlinksInPath() != self.currentLocalURL?.resolvingSymlinksInPath(),
                        "Choose a new file; the original notebook is preserved.")
            try LocalStore.atomic(bytes, to: url)
            self.status = "Edited .note copy exported. The draft remains available for Save to library."
        }
    }

    func openLatestQueuedEdit(first: Bool = false) {
        if first, let job = editorJournal.queue.first,
           (try? Revision.decode(job.revisionBytes).notebook.hasPrefix("folder-")) == true {
            reviewQueuedFolder(job)
            return
        }
        if first, let job = editorJournal.queue.first,
           (try? job.checkedRevision().deleted) == true {
            openQueuedNotebook(job)
            return
        }
        perform("Opening the saved offline snapshot…") {
            try require(!self.draftChanged, "Save or discard the current draft first.")
            let notebooks = self.editorJournal.queue.filter {
                guard let revision = try? $0.checkedRevision() else { return false }
                return !revision.notebook.hasPrefix("folder-") && !revision.deleted
            }
            guard let job = first ? notebooks.first : notebooks.last,
                  let store = self.store else { return }
            let revision = try Revision.decode(job.revisionBytes)
            let hash = sha256(job.payloadBytes)
            let url = try store.cache(job.payloadBytes, hash: hash)
            let note = try await Task.detached { try NotebookLoader.read(url, expectedHash: hash) }.value
            let binding = EditBinding(accountID: job.accountID, folderID: job.folderID,
                                      notebookID: revision.notebook, baseRevision: job.revisionID)
            var journal = self.editorJournal
            journal.draft = EditorDraft(bytes: job.payloadBytes, documentID: note.document_id, binding: binding)
            try self.commitJournal(journal)
            self.notebook = note
            self.currentPayload = job.payloadBytes
            self.currentLocalURL = nil
            self.pageIndex = 0
            self.editorTool = .view
            self.openedRevision = OpenLibraryRevision(scope: binding.scope, notebookID: binding.notebookID,
                                                     revisionID: job.revisionID)
            self.source = "Offline queued snapshot · \(job.revisionID.prefix(12))"
            self.status = "Saved offline notebook opened. Its pending publication is unchanged."
        }
    }

    func sendQueuedEdits(allowConflict: Bool = false) {
        perform(allowConflict ? "Publishing the saved conflicting branch…" : "Sending the next saved edit…") {
            try await self.publishNextEdit(allowInteraction: true, allowConflict: allowConflict)
        }
    }

    func publishNextEdit(allowInteraction: Bool, allowConflict: Bool) async throws {
        guard let job = editorJournal.queue.first, let store else { return }
        do {
            let (client, account, folder) = try await currentClient(allowInteraction: allowInteraction)
            let after = try await EditedSnapshotPublisher.publish(job, client: client, accountID: account.permissionID,
                                                                  folderID: folder.id, deviceID: store.deviceID,
                                                                  allowConflict: allowConflict, cache: objectCache,
                                                                  nativeSummaries: folderSafetySummaries)
            var journal = editorJournal
            try require(journal.queue.first?.revisionID == job.revisionID, "Outgoing queue changed during publication.")
            journal.completeFirst()
            try commitJournal(journal)
            try await updateLibraryIndex(after)
            status = "Saved revision \(job.revisionID.prefix(12)) published and verified. Native application is confirmed separately on BOOX."
        } catch let conflict as EditConflictError {
            var journal = editorJournal
            journal.queue[0].status = "conflict-review"
            try commitJournal(journal)
            try await updateLibraryIndex(conflict.snapshot)
            throw conflict
        } catch {
            if editorJournal.queue.first?.revisionID == job.revisionID &&
                editorJournal.queue.first?.status != "conflict-review" {
                var journal = editorJournal
                journal.queue[0].status = "pending-error"
                try? commitJournal(journal)
            }
            throw error
        }
    }
}
