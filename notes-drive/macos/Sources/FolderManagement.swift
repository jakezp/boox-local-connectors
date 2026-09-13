import AppKit
import Foundation

/// A local preview of durable queued revisions, never presented as verified Drive state.
enum FolderMutationPolicy {
    static func includingQueued(_ catalog: RevisionCatalog, queue: [PendingPublication],
                                accountID: String, folderID: String) throws -> RevisionCatalog {
        var records = try catalog.revisions.mapValues { try $0.encode() }
        var payloads = catalog.payloads
        for job in queue where job.accountID == accountID && job.folderID == folderID {
            let revision = try job.checkedRevision()
            records[job.revisionID] = job.revisionBytes
            if let hash = revision.payload { payloads[hash] = job.payloadBytes }
        }
        return try RevisionCatalog(records: records, payloads: payloads)
    }

    static func folder(at revisionID: String, catalog: RevisionCatalog) throws -> FolderRecord {
        guard let revision = catalog.revisions[revisionID], let hash = revision.payload,
              let bytes = catalog.payloads[hash] else { throw ReaderError("Folder metadata is unavailable.") }
        return try FolderRecord.decode(bytes, notebookID: revision.notebook)
    }

    static func singleHead(_ notebookID: String, catalog: RevisionCatalog) throws -> String {
        guard let heads = catalog.heads[notebookID], heads.count == 1, let head = heads.first else {
            throw ReaderError("This item has missing or conflicting heads. Review the versions before changing it.")
        }
        return head
    }

    static func restoredRecord(from id: String, catalog: RevisionCatalog) throws -> FolderRecord {
        guard let revision = catalog.revisions[id], revision.deleted else {
            throw ReaderError("Choose a deleted folder to restore.")
        }
        var pending = revision.parents, visited = Set<String>(), candidates: [FolderRecord] = []
        while let ancestorID = pending.popLast() {
            guard visited.insert(ancestorID).inserted, let ancestor = catalog.revisions[ancestorID] else { continue }
            if ancestor.payload != nil { candidates.append(try folder(at: ancestorID, catalog: catalog)) }
            else { pending.append(contentsOf: ancestor.parents) }
        }
        guard let first = candidates.first, candidates.allSatisfy({ $0 == first }) else {
            throw ReaderError("Deleted folder history has missing or conflicting metadata; automatic restore is unavailable.")
        }
        return first
    }

    static func validate(revision: Revision, bytes: Data, catalog: RevisionCatalog,
                         nativeSummaries: [String: NoteSummary]) throws {
        try require(revision.notebook.hasPrefix("folder-"), "Not a folder change.")
        let id = String(revision.notebook.dropFirst(7))
        try require(matches(id, "^[A-Za-z0-9_-]{1,100}$") &&
                    (revision.deleted ? bytes.isEmpty : revision.payload == sha256(bytes)),
                    "Folder change failed its identity/payload check.")
        for parent in revision.parents {
            try require(catalog.revisions[parent]?.notebook == revision.notebook,
                        "The captured folder revision is missing or belongs to another folder.")
        }
        if revision.deleted {
            try require(bytes.isEmpty && !revision.parents.isEmpty, "Folder deletion needs existing ancestry and no payload.")
            // Never imply recursive deletion. Every live child, including conflicting
            // versions, must be accounted for before an empty-folder request is sent.
            for heads in catalog.heads.values {
                for headID in heads {
                    guard let head = catalog.revisions[headID], !head.deleted, head.notebook != revision.notebook else { continue }
                    if head.notebook.hasPrefix("folder-") {
                        let child = try folder(at: headID, catalog: catalog)
                        try require(child.parent != id, "This folder contains a folder. Move its contents before requesting deletion.")
                    } else {
                        guard let hash = head.payload, let summary = nativeSummaries[hash], summary.sha256 == hash else {
                            throw ReaderError("Folder deletion needs verified parent metadata for every live notebook. Refresh the library and resolve unsupported metadata first.")
                        }
                        try require(summary.parent_id != id, "This folder contains a notebook. Move its contents before requesting deletion.")
                    }
                }
            }
            return
        }
        let proposed = try FolderRecord.decode(bytes, notebookID: revision.notebook)
        var next = proposed.parent, visited: Set<String> = [id]
        while let parent = next {
            try require(visited.count < 64 && visited.insert(parent).inserted,
                        "This move would create a folder cycle or exceed 64 levels.")
            let parentHead = try singleHead("folder-" + parent, catalog: catalog)
            guard catalog.revisions[parentHead]?.deleted == false else {
                throw ReaderError("Restore the destination folder before moving into it.")
            }
            next = try folder(at: parentHead, catalog: catalog).parent
        }
    }
}

@MainActor
extension ReaderModel {
    var queuedFolderChanges: [PendingPublication] {
        editorJournal.queue.filter { (try? Revision.decode($0.revisionBytes).notebook.hasPrefix("folder-")) == true }
    }
    var hasQueuedNotebook: Bool {
        editorJournal.queue.contains {
            guard let revision = try? $0.checkedRevision() else { return false }
            return !revision.notebook.hasPrefix("folder-") && !revision.deleted
        }
    }
    func isLatestQueuedFolder(_ job: PendingPublication) -> Bool {
        guard job.accountID == account?.permissionID, job.folderID == selectedFolder?.id,
              let revision = try? job.checkedRevision() else { return false }
        return editorJournal.queue.last(where: {
            $0.accountID == job.accountID && $0.folderID == job.folderID &&
                (try? Revision.decode($0.revisionBytes).notebook) == revision.notebook
        })?.revisionID == job.revisionID
    }

    func folderWorkspace() throws -> (RevisionCatalog, DriveAccount, DriveFolder) {
        guard editorStorageReady, let snapshot, let account, let folder = selectedFolder else {
            throw ReaderError("Connect and verify the library before changing folders.")
        }
        let catalog = try FolderMutationPolicy.includingQueued(snapshot.catalog, queue: editorJournal.queue,
                                                               accountID: account.permissionID, folderID: folder.id)
        return (catalog, account, folder)
    }

    /// Current in-memory summaries are keyed by verified payload hash. A fresh
    /// publication catalog may require more metadata; deletion then stays queued.
    var folderSafetySummaries: [String: NoteSummary] {
        decodedLibrarySummaries
    }

    func changeFolder(_ notebookID: String? = nil, restore: Bool = false) {
        perform(restore ? "Reviewing a folder restore…" : "Preparing a folder change…") {
            let (catalog, account, folder) = try self.folderWorkspace()
            let id: String, base: String?, original: FolderRecord?
            if let notebookID {
                base = try FolderMutationPolicy.singleHead(notebookID, catalog: catalog)
                if restore {
                    original = try FolderMutationPolicy.restoredRecord(from: base!, catalog: catalog)
                } else {
                    original = try FolderMutationPolicy.folder(at: base!, catalog: catalog)
                }
                id = original!.id
            } else {
                id = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
                base = nil
                original = nil
            }
            let alert = NSAlert()
            alert.messageText = restore ? "Restore folder" : (original == nil ? "New folder" : "Rename or move folder")
            alert.informativeText = "Save queues an immutable folder revision for automatic publication. Existing notebooks and their contents remain intact."
            let title = NSTextField(string: original?.title ?? "")
            title.placeholderString = "Folder title"
            let parent = NSPopUpButton(frame: .zero, pullsDown: false)
            parent.addItem(withTitle: "Library root")
            parent.lastItem?.representedObject = ""
            for (key, heads) in catalog.heads.sorted(by: { $0.key < $1.key })
            where key.hasPrefix("folder-") && heads.count == 1 && key != "folder-" + id {
                guard let head = heads.first, catalog.revisions[head]?.deleted == false,
                      let record = try? FolderMutationPolicy.folder(at: head, catalog: catalog) else { continue }
                parent.addItem(withTitle: "\(record.title) · \(record.id.prefix(8))")
                parent.lastItem?.representedObject = record.id
            }
            if let selected = original?.parent {
                if let item = parent.itemArray.first(where: { $0.representedObject as? String == selected }) {
                    parent.select(item)
                } else {
                    parent.addItem(withTitle: "Unavailable parent · \(selected.prefix(12)) — choose a destination")
                    parent.lastItem?.representedObject = selected
                    parent.select(parent.lastItem)
                }
            }
            let fields = NSStackView(views: [NSTextField(labelWithString: "Title"), title,
                                             NSTextField(labelWithString: "Parent folder"), parent])
            fields.orientation = .vertical
            fields.alignment = .leading
            fields.spacing = 8
            fields.frame = NSRect(x: 0, y: 0, width: 360, height: 116)
            title.widthAnchor.constraint(equalToConstant: 360).isActive = true
            parent.widthAnchor.constraint(equalToConstant: 360).isActive = true
            alert.accessoryView = fields
            alert.addButton(withTitle: restore ? "Queue restore" : "Save folder")
            alert.addButton(withTitle: "Cancel")
            guard alert.runModal() == .alertFirstButtonReturn else { return }
            let selected = parent.selectedItem?.representedObject as? String ?? ""
            let record = FolderRecord(id: id, parent: selected.isEmpty ? nil : selected, title: title.stringValue)
            if record == original && !restore {
                self.status = "Folder metadata is unchanged."
                return
            }
            let binding = EditBinding(accountID: account.permissionID, folderID: folder.id,
                                      notebookID: "folder-" + id, baseRevision: base)
            try self.queueFolder(record, binding: binding, catalog: catalog)
        }
    }

    func requestFolderDeletion(_ notebookID: String) {
        perform("Reviewing an empty-folder deletion request…") {
            let (catalog, account, folder) = try self.folderWorkspace()
            let base = try FolderMutationPolicy.singleHead(notebookID, catalog: catalog)
            let record = try FolderMutationPolicy.folder(at: base, catalog: catalog)
            let binding = EditBinding(accountID: account.permissionID, folderID: folder.id,
                                      notebookID: notebookID, baseRevision: base)
            let revision = try Revision(notebook: notebookID, device: self.store!.deviceID,
                                        parents: [base], payload: nil, deleted: true)
            try FolderMutationPolicy.validate(revision: revision, bytes: Data(), catalog: catalog,
                                              nativeSummaries: self.folderSafetySummaries)
            let alert = NSAlert()
            alert.messageText = "Request deletion of “\(record.title)”?"
            alert.informativeText = "This queues a recoverable deletion revision for this empty folder. Drive history and local files remain available. Native application is confirmed separately on BOOX."
            alert.addButton(withTitle: "Queue deletion request")
            alert.addButton(withTitle: "Cancel")
            guard alert.runModal() == .alertFirstButtonReturn else { return }
            try self.queueFolder(nil, binding: binding, catalog: catalog)
        }
    }

    func queueFolder(_ record: FolderRecord?, binding: EditBinding, catalog: RevisionCatalog) throws {
        guard let store else { throw ReaderError("Local storage is unavailable.") }
        let revision = try Revision(notebook: binding.notebookID, device: store.deviceID,
                                    parents: binding.baseRevision.map { [$0] } ?? [],
                                    payload: try record.map { sha256(try $0.encode()) }, deleted: record == nil)
        try FolderMutationPolicy.validate(revision: revision, bytes: record?.encode() ?? Data(), catalog: catalog,
                                          nativeSummaries: folderSafetySummaries)
        var journal = editorJournal
        _ = try journal.enqueueFolder(record, binding: binding, deviceID: store.deviceID)
        try commitJournal(journal)
        status = "Folder change saved offline · \(try revision.id.prefix(12)). Automatic updates will publish it; native application is confirmed separately."
    }

    func reviewQueuedFolder(_ job: PendingPublication) {
        perform("Reviewing the saved folder revision…") {
            let revision = try job.checkedRevision()
            try require(revision.notebook.hasPrefix("folder-"), "This saved change is a notebook.")
            let alert = NSAlert()
            alert.messageText = revision.deleted ? "Saved folder deletion request" : "Saved folder metadata"
            alert.informativeText = revision.deleted ? "This revision requests recoverable deletion; it has no payload." :
                String(decoding: job.payloadBytes, as: UTF8.self)
            alert.informativeText += "\n\nRevision: \(job.revisionID)\nParents: \(revision.parents.joined(separator: ", "))\nStatus: \(job.status)\n\nPublication and native application are separate."
            alert.addButton(withTitle: "Close")
            alert.runModal()
        }
    }
}
