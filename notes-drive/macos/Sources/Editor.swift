import AppKit
import Foundation

enum EditorTool: String, CaseIterable { case view = "Read", pen = "Pen", eraser = "Erase stroke" }

struct EditBinding: Codable, Equatable {
    let accountID: String
    let folderID: String
    let notebookID: String
    var baseRevision: String?
    var scope: String { VerifiedObjectCache.scope(accountID: accountID, folderID: folderID) }
}

struct PenOperation: Codable {
    let kind: String
    let page_id: String
    var id: String?
    var layer: Int?
    var width: Double?
    var color: UInt32?
    var points: [[Double]]?
    var ids: [String]?

    static func add(page: NotePage, layer: Int, width: Double, color: UInt32, points: [[Double]]) -> Self {
        Self(kind: "add", page_id: page.id, id: UUID().uuidString.lowercased(),
             layer: layer, width: width, color: color, points: points)
    }
    static func erase(page: NotePage, ids: [String]) -> Self {
        Self(kind: "erase", page_id: page.id, ids: ids)
    }
}

struct EditorDraft: Codable {
    let editID: String
    let baseBytes: Data
    let baseHash: String
    let documentID: String
    let editedAt: Int64
    var binding: EditBinding?
    var operations: [PenOperation] = []
    var cursor = 0

    init(bytes: Data, documentID: String, binding: EditBinding?) {
        editID = UUID().uuidString.lowercased()
        baseBytes = bytes
        baseHash = sha256(bytes)
        self.documentID = documentID
        self.binding = binding
        editedAt = Int64(Date().timeIntervalSince1970 * 1000)
    }
    var hasChanges: Bool { cursor > 0 }
    var canUndo: Bool { cursor > 0 }
    var canRedo: Bool { cursor < operations.count }
    var activeOperations: [PenOperation] { Array(operations.prefix(cursor)) }

    func validate() throws {
        try require(!baseBytes.isEmpty && baseBytes.count <= 4 * 1024 * 1024 && sha256(baseBytes) == baseHash,
                    "Saved draft base failed its checksum/size check.")
        try require(UUID(uuidString: editID) != nil && !documentID.isEmpty &&
                    operations.count <= 500 && (0...operations.count).contains(cursor),
                    "Saved edit journal is invalid.")
        var samples = 0
        for operation in operations {
            try require(!operation.page_id.isEmpty, "Edit page is missing.")
            if operation.kind == "add" {
                guard let id = operation.id, UUID(uuidString: id) != nil, let layer = operation.layer,
                      let width = operation.width, let points = operation.points,
                      operation.color != nil else { throw ReaderError("Saved pen is incomplete.") }
                try require(layer >= 0 && width.isFinite && (0.5...40).contains(width) &&
                            (2...5000).contains(points.count) && points.allSatisfy {
                                $0.count == 2 && $0.allSatisfy { $0.isFinite && abs($0) <= 100000 }
                            }, "Saved pen exceeds editing bounds.")
                samples += points.count
            } else {
                try require(operation.kind == "erase" && operation.ids?.isEmpty == false &&
                            (operation.ids?.count ?? 0) <= 1000, "Invalid saved erasure.")
            }
        }
        try require(samples <= 50000, "Draft exceeds 50,000 new samples.")
        if let binding {
            try require(!binding.accountID.isEmpty && !binding.folderID.isEmpty &&
                        matches(binding.notebookID, "^[A-Za-z0-9_-]{1,128}$") &&
                        (binding.baseRevision == nil || matches(binding.baseRevision!, "^[a-f0-9]{64}$")),
                        "Invalid draft destination or ancestry.")
        }
    }

    mutating func append(_ operation: PenOperation) throws {
        operations = Array(operations.prefix(cursor)) + [operation]
        cursor = operations.count
        try validate()
    }

    func plan() throws -> Data {
        try validate()
        let encoded = try JSONEncoder().encode(activeOperations)
        let object: [String: Any] = [
            "base_sha256": baseHash, "document_id": documentID, "edit_id": editID,
            "edited_at_ms": editedAt, "operations": try JSONSerialization.jsonObject(with: encoded)
        ]
        let data = try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])
        try require(data.count <= 4 * 1024 * 1024, "Edit plan exceeds 4 MiB.")
        return data
    }

    func projected(_ page: NotePage) -> NotePage {
        var strokes = page.strokes
        for operation in activeOperations where operation.page_id == page.id {
            if operation.kind == "erase" {
                let ids = Set(operation.ids ?? [])
                strokes.removeAll { ids.contains($0.id) }
            } else if let id = operation.id, let layer = operation.layer, let width = operation.width,
                      let color = operation.color, let points = operation.points {
                strokes.append(NoteStroke(id: id, type: 2, layer: layer, width: width, color: color,
                                          supported: true, points: points, point_hash: nil,
                                          sample_count: points.count, bounds: nil, label: "", target_page: nil))
            }
        }
        return NotePage(id: page.id, width: page.width, height: page.height, layers: page.layers,
                        strokes: strokes, warnings: page.warnings)
    }
}

struct EditReceipt: Codable {
    let revisionID: String
    let accountID: String
    let folderID: String
    let verifiedAt: Date
}

struct EditorJournal: Codable {
    var draft: EditorDraft?
    var queue: [PendingPublication] = []
    var receipts: [EditReceipt] = []

    func validate(deviceID: String) throws {
        try draft?.validate()
        try require(queue.count <= 16 && receipts.count <= 32, "Offline edit journal exceeds its queue limit.")
        var seen = Set<String>()
        for job in queue {
            let revision = try job.checkedRevision()
            try require(job.deviceID == deviceID && revision.device == deviceID &&
                        !job.accountID.isEmpty && !job.folderID.isEmpty &&
                        seen.insert(job.revisionID).inserted, "Queued edit failed its identity/checksum checks.")
        }
    }

    private mutating func appendPublication(bytes: Data?, binding: EditBinding, deviceID: String) throws -> Revision {
        try require(queue.count < 16, "The offline queue holds 16 saves. Send queued edits before saving more.")
        let revision = try Revision(notebook: binding.notebookID, device: deviceID,
                                    parents: binding.baseRevision.map { [$0] } ?? [],
                                    payload: bytes.map(sha256), deleted: bytes == nil)
        let id = try revision.id
        try require(!queue.contains { $0.revisionID == id }, "This exact edit is already queued.")
        let job = PendingPublication(accountID: binding.accountID, folderID: binding.folderID,
                                     deviceID: deviceID, revisionID: id, revisionBytes: try revision.encode(),
                                     payloadBytes: bytes ?? Data(), createdAt: Date(),
                                     status: revision.notebook.hasPrefix("folder-") ? "queued-folder-change" : "queued-edit")
        _ = try job.checkedRevision()
        queue.append(job)
        return revision
    }

    mutating func enqueue(bytes: Data, documentID: String, binding: EditBinding,
                          deviceID: String) throws -> Revision {
        let revision = try appendPublication(bytes: bytes, binding: binding, deviceID: deviceID)
        var next = binding
        next.baseRevision = try revision.id
        draft = EditorDraft(bytes: bytes, documentID: documentID, binding: next)
        try validate(deviceID: deviceID)
        return revision
    }

    mutating func enqueueFolder(_ record: FolderRecord?, binding: EditBinding, deviceID: String) throws -> Revision {
        try require(binding.notebookID.hasPrefix("folder-"), "Folder change requires a folder identity.")
        let revision = try appendPublication(bytes: record?.encode(), binding: binding, deviceID: deviceID)
        // Folder changes share durable ordering but never replace the notebook draft.
        try validate(deviceID: deviceID)
        return revision
    }

    mutating func enqueueNotebookChange(_ bytes: Data?, binding: EditBinding, deviceID: String) throws -> Revision {
        try require(binding.notebookID.hasPrefix("boox-"), "Notebook change requires a native identity.")
        try require(!(draft?.operations.isEmpty == false && draft?.documentID == String(binding.notebookID.dropFirst(5))),
                    "Save or discard this notebook’s pen draft and undo/redo history before changing its metadata.")
        let revision = try appendPublication(bytes: bytes, binding: binding, deviceID: deviceID)
        // A clean draft of this notebook becomes stale after metadata/deletion.
        // Drafts of every other notebook, including their undo history, survive.
        if draft?.documentID == String(binding.notebookID.dropFirst(5)) { draft = nil }
        try validate(deviceID: deviceID)
        return revision
    }

    mutating func completeFirst() {
        guard !queue.isEmpty else { return }
        let job = queue.removeFirst()
        receipts.append(EditReceipt(revisionID: job.revisionID, accountID: job.accountID,
                                    folderID: job.folderID, verifiedAt: Date()))
        receipts = Array(receipts.suffix(32))
    }

    func protects(notebookID: String) -> Bool {
        (draft?.hasChanges == true && draft?.binding?.notebookID == notebookID) ||
            queue.contains { (try? Revision.decode($0.revisionBytes).notebook) == notebookID }
    }
}

extension PendingPublication {
    func checkedRevision() throws -> Revision {
        let revision = try Revision.decode(revisionBytes)
        try require(revision.device == deviceID && (try revision.id) == revisionID,
                    "Saved publication identity failed verification.")
        if revision.deleted {
            try require((revision.notebook.hasPrefix("folder-") || revision.notebook.hasPrefix("boox-")) &&
                        !revision.parents.isEmpty && payloadBytes.isEmpty,
                        "A recoverable deletion requires an existing native identity, ancestry and no payload bytes.")
        } else {
            try require(!payloadBytes.isEmpty && payloadBytes.count <= 4 * 1024 * 1024 &&
                        revision.payload == sha256(payloadBytes), "Saved payload failed its checksum/size check.")
            if revision.notebook.hasPrefix("folder-") {
                _ = try FolderRecord.decode(payloadBytes, notebookID: revision.notebook)
            }
        }
        return revision
    }
}

extension LocalStore {
    func editorJournal() throws -> EditorJournal {
        let url = root.appendingPathComponent("editor-journal.json")
        guard FileManager.default.fileExists(atPath: url.path) else { return EditorJournal() }
        try require((try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? Int.max) <= 100 * 1024 * 1024,
                    "Offline journal exceeds 100 MiB.")
        let value = try JSONDecoder().decode(EditorJournal.self, from: Data(contentsOf: url))
        try value.validate(deviceID: deviceID)
        return value
    }
    func saveEditorJournal(_ value: EditorJournal) throws {
        try value.validate(deviceID: deviceID)
        let bytes = try JSONEncoder().encode(value)
        try require(bytes.count <= 100 * 1024 * 1024, "Offline journal exceeds 100 MiB.")
        try Self.atomic(bytes, to: root.appendingPathComponent("editor-journal.json"))
    }
}

enum EditPublicationPolicy {
    static func needsConflictReview(_ job: PendingPublication, catalog: RevisionCatalog) throws -> Bool {
        let revision = try Revision.decode(job.revisionBytes)
        if let existing = catalog.revisions[job.revisionID] {
            try require(existing == revision, "Publication ID has conflicting contents.")
            return false
        }
        for parent in revision.parents {
            try require(catalog.revisions[parent]?.notebook == revision.notebook,
                        "An edit's captured parent is unavailable. The saved branch is preserved.")
        }
        return (catalog.heads[revision.notebook] ?? []).sorted() != revision.parents
    }
}

struct EditConflictError: LocalizedError {
    let snapshot: DriveSnapshot
    var errorDescription: String? {
        "Incoming edits conflict with this saved branch. Review the library, then explicitly publish this branch if desired."
    }
}

enum EditedSnapshotPublisher {
    static func publish(_ job: PendingPublication, client: DriveClient, accountID: String,
                        folderID: String, deviceID: String, allowConflict: Bool,
                        cache: VerifiedObjectCache? = nil,
                        nativeSummaries: [String: NoteSummary] = [:]) async throws -> DriveSnapshot {
        let revision = try job.checkedRevision()
        try require(job.accountID == accountID && job.folderID == folderID &&
                    job.deviceID == deviceID,
                    "Saved edits belong to another destination or failed their checksum checks.")
        let before = try await client.refresh(folderID: folderID, cache: cache)
        if try EditPublicationPolicy.needsConflictReview(job, catalog: before.catalog) && !allowConflict {
            throw EditConflictError(snapshot: before)
        }
        if revision.notebook.hasPrefix("folder-"), before.catalog.revisions[job.revisionID] == nil {
            try FolderMutationPolicy.validate(revision: revision, bytes: job.payloadBytes,
                                              catalog: before.catalog, nativeSummaries: nativeSummaries)
        }
        if revision.notebook.hasPrefix("boox-"), before.catalog.revisions[job.revisionID] == nil {
            let summary = try revision.payload.map {
                try nativeSummaries[$0] ?? NativeNotebookLibrary.summary(job.payloadBytes)
            }
            try NotebookMutationPolicy.validate(revision: revision, summary: summary, catalog: before.catalog)
        }
        if !revision.deleted {
            _ = try await client.ensureObject(type: "payload", data: job.payloadBytes,
                                              folderID: folderID, verifiedSnapshot: before)
        }
        _ = try await client.ensureObject(type: "revision", data: job.revisionBytes,
                                          folderID: folderID, verifiedSnapshot: before)
        let after = try await client.refresh(folderID: folderID, cache: cache)
        try require(after.catalog.revisions[job.revisionID] == revision,
                    "Edited revision is not yet verified in Drive. Saved bytes remain queued.")
        return after
    }
}

enum EditorGeometry {
    static func hit(_ point: [Double], page: NotePage, radius: Double = 12) -> String? {
        guard point.count == 2 else { return nil }
        let writable = Set(page.layers.filter { $0.visible && !$0.locked }.map(\.id))
        func distance(_ a: [Double], _ b: [Double]) -> Double {
            let dx = b[0] - a[0], dy = b[1] - a[1]
            let length = dx * dx + dy * dy
            let t = length == 0 ? 0 : max(0, min(1, ((point[0]-a[0])*dx + (point[1]-a[1])*dy) / length))
            return hypot(point[0] - a[0] - t*dx, point[1] - a[1] - t*dy)
        }
        for stroke in page.strokes.reversed() where stroke.supported && stroke.type == 2 && writable.contains(stroke.layer) {
            if let first = stroke.points.first, distance(first, first) <= radius + stroke.width/2 { return stroke.id }
            for (a, b) in zip(stroke.points, stroke.points.dropFirst()) {
                if distance(a, b) <= radius + stroke.width/2 { return stroke.id }
            }
        }
        return nil
    }
}

enum NativeNoteEditor {
    static func command(input: URL, plan: URL, output: URL, helper: String = "note_editor") throws -> Data {
        guard let script = Bundle.main.url(forResource: helper, withExtension: "py") else {
            throw ReaderError("The native editor helper is missing from this bundle.")
        }
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/python3")
        process.arguments = [script.path, input.path, plan.path, output.path]
        process.environment = ["PATH": "/usr/bin:/bin", "PYTHONDONTWRITEBYTECODE": "1", "PYTHONIOENCODING": "utf-8"]
        let pipe = Pipe()
        process.standardOutput = pipe
        process.standardError = FileHandle.nullDevice
        try process.run()
        let timeout = DispatchWorkItem { if process.isRunning { process.terminate() } }
        DispatchQueue.global().asyncAfter(deadline: .now() + 25, execute: timeout)
        defer { timeout.cancel() }
        var result = Data()
        while let part = try pipe.fileHandleForReading.read(upToCount: 4096), !part.isEmpty {
            result.append(part)
            if result.count > 65536 { process.terminate(); break }
        }
        process.waitUntilExit()
        if process.terminationStatus != 0 {
            let object = (try? JSONSerialization.jsonObject(with: result)) as? [String: Any]
            throw ReaderError(object?["error"] as? String ?? "Native pen edit failed or timed out.")
        }
        try require(result.count <= 65536, "Editor reply exceeded its limit.")
        return result
    }

    static func apply(_ draft: EditorDraft, directory: URL) throws -> (Data, Notebook) {
        let work = directory.appendingPathComponent("edit-" + UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: work, withIntermediateDirectories: true,
                                               attributes: [.posixPermissions: 0o700])
        defer { try? FileManager.default.removeItem(at: work) }
        let input = work.appendingPathComponent("base.note"), plan = work.appendingPathComponent("plan.json")
        let output = work.appendingPathComponent("edited.note")
        try LocalStore.atomic(draft.baseBytes, to: input)
        try LocalStore.atomic(draft.plan(), to: plan)
        _ = try command(input: input, plan: plan, output: output)
        try require((try output.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? Int.max) <= 4 * 1024 * 1024,
                    "Edited notebook exceeds 4 MiB.")
        let bytes = try Data(contentsOf: output)
        let note = try NotebookLoader.read(output, expectedHash: sha256(bytes))
        try require(note.document_id == draft.documentID, "Editor readback changed notebook identity.")
        return (bytes, note)
    }
}
