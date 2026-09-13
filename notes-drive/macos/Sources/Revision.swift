import CoreFoundation
import Foundation

struct Revision: Equatable {
    let notebook: String
    let device: String
    let parents: [String]
    let payload: String?
    let deleted: Bool

    init(notebook: String, device: String, parents: [String], payload: String?,
         deleted: Bool = false) throws {
        try require([notebook, device].allSatisfy { matches($0, "^[A-Za-z0-9_-]{1,128}$") },
                    "Invalid notebook/device identifier.")
        try require(deleted == (payload == nil), "Only deletion revisions omit payloads.")
        try require(parents.count <= 64 && parents == Array(Set(parents)).sorted(),
                    "Parents must be sorted, unique and bounded.")
        try require((parents + (payload.map { [$0] } ?? [])).allSatisfy {
            matches($0, "^[a-f0-9]{64}$")
        }, "Invalid revision hash.")
        self.notebook = notebook
        self.device = device
        self.parents = parents
        self.payload = payload
        self.deleted = deleted
    }

    func encode() throws -> Data {
        let object: [String: Any] = [
            "deleted": deleted, "device": device, "notebook": notebook,
            "parents": parents, "payload": payload as Any? ?? NSNull(), "schema": 1
        ]
        return try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys, .withoutEscapingSlashes])
    }

    var id: String { get throws { sha256(try encode()) } }

    static func decode(_ data: Data) throws -> Revision {
        try require(data.count <= 16384, "Revision exceeds 16 KiB.")
        guard let o = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw ReaderError("Invalid revision JSON.")
        }
        try require(Set(o.keys) == ["deleted", "device", "notebook", "parents", "payload", "schema"],
                    "Revision has unknown or missing fields.")
        guard let deletedNumber = o["deleted"] as? NSNumber,
              CFGetTypeID(deletedNumber) == CFBooleanGetTypeID(),
              let schema = o["schema"] as? NSNumber,
              CFGetTypeID(schema) != CFBooleanGetTypeID(), schema.intValue == 1,
              let notebook = o["notebook"] as? String, let device = o["device"] as? String,
              let parents = o["parents"] as? [String] else {
            throw ReaderError("Wrong revision field types.")
        }
        let payload: String?
        if o["payload"] is NSNull { payload = nil }
        else if let value = o["payload"] as? String { payload = value }
        else { throw ReaderError("Invalid revision payload.") }
        let revision = try Revision(notebook: notebook, device: device, parents: parents,
                                    payload: payload, deleted: deletedNumber.boolValue)
        // Exact canonical bytes also reject duplicate JSON keys and 1.0/1e0 schema forms.
        try require(try revision.encode() == data, "Revision JSON is not canonical.")
        return revision
    }
}

struct RevisionCatalog {
    let revisions: [String: Revision]
    let payloads: [String: Data]
    let heads: [String: [String]]

    func isAncestor(_ ancestor: String, of descendant: String) -> Bool {
        var pending = [descendant]
        var seen = Set<String>()
        while let id = pending.popLast() {
            if id == ancestor { return true }
            if seen.insert(id).inserted { pending.append(contentsOf: revisions[id]?.parents ?? []) }
        }
        return false
    }

    init(records: [String: Data], payloads: [String: Data]) throws {
        var revisions: [String: Revision] = [:]
        for (id, bytes) in records {
            try require(sha256(bytes) == id, "Revision checksum mismatch.")
            revisions[id] = try Revision.decode(bytes)
        }
        var verifiedPayloads = Set<String>()
        for (_, revision) in revisions {
            for parent in revision.parents {
                try require(revisions[parent]?.notebook == revision.notebook,
                            "Missing or foreign revision ancestry.")
            }
            if let hash = revision.payload, verifiedPayloads.insert(hash).inserted {
                guard let bytes = payloads[hash] else { throw ReaderError("Missing notebook payload.") }
                try require(sha256(bytes) == hash, "Notebook payload checksum mismatch.")
            }
        }
        var complete = Set<String>()
        func visit(_ id: String, path: Set<String>) throws {
            if complete.contains(id) { return }
            try require(!path.contains(id), "Revision ancestry cycle.")
            var next = path
            next.insert(id)
            for parent in revisions[id]!.parents { try visit(parent, path: next) }
            complete.insert(id)
        }
        for id in revisions.keys { try visit(id, path: []) }
        let parents = Set(revisions.values.flatMap(\.parents))
        var heads: [String: [String]] = [:]
        for (id, revision) in revisions where !parents.contains(id) {
            heads[revision.notebook, default: []].append(id)
        }
        self.revisions = revisions
        self.payloads = payloads
        self.heads = heads.mapValues { $0.sorted() }
    }
}
