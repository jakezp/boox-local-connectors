import Foundation

/// Typed opaque payload carried by the unchanged schema-1 Revision envelope.
struct FolderRecord: Equatable {
    let id: String
    let parent: String?
    let title: String

    private static func quote(_ string: String) -> String {
        // Shared explicit canonical quoting: preserve slash and non-control UTF-8.
        var result = "\""
        for scalar in string.unicodeScalars {
            switch scalar.value {
            case 0x22: result += "\\\""
            case 0x5c: result += "\\\\"
            case 8: result += "\\b"
            case 9: result += "\\t"
            case 10: result += "\\n"
            case 12: result += "\\f"
            case 13: result += "\\r"
            case 0..<32: result += String(format: "\\u%04x", scalar.value)
            default: result.unicodeScalars.append(scalar)
            }
        }
        return result + "\""
    }

    func encode() throws -> Data {
        Data(("{\"id\":" + Self.quote(id) + ",\"kind\":\"folder\",\"parent\":" +
              (parent.map(Self.quote) ?? "null") + ",\"schema\":1,\"title\":" + Self.quote(title) + "}").utf8)
    }

    static func decode(_ data: Data, notebookID: String) throws -> FolderRecord {
        try require(data.count <= 16384, "Folder metadata exceeds 16 KiB.")
        let object = try jsonObject(data)
        try require(Set(object.keys) == ["id", "kind", "parent", "schema", "title"],
                    "Folder metadata has unknown/missing fields.")
        guard let id = object["id"] as? String, let title = object["title"] as? String,
              object["kind"] as? String == "folder", object["schema"] as? Int == 1 else {
            throw ReaderError("Unsupported folder metadata.")
        }
        let parent: String?
        if object["parent"] is NSNull { parent = nil }
        else if let value = object["parent"] as? String { parent = value }
        else { throw ReaderError("Invalid folder parent.") }
        try require(notebookID == "folder-" + id && matches(id, "^[A-Za-z0-9_-]{1,100}$") &&
                    parent != id && (parent == nil || matches(parent!, "^[A-Za-z0-9_-]{1,128}$")) &&
                    !title.isEmpty && title.utf16.count <= 1000 && !title.contains("\0"),
                    "Invalid folder identity/title/parent.")
        let record = FolderRecord(id: id, parent: parent, title: title)
        try require(record.encode() == data, "Folder metadata is not canonical JSON.")
        return record
    }
}

enum FolderHierarchy {
    static func path(parentID: String?, heads: [LibraryHead]) -> String? {
        guard let parentID, !parentID.isEmpty else { return nil }
        let groups = Dictionary(grouping: heads.filter(\.isFolder), by: \.notebookID)
        var visited = Set<String>(), parts: [String] = [], next: String? = parentID
        while let id = next {
            guard visited.count < 64, visited.insert(id).inserted else { return "Folder hierarchy needs review: parent cycle or excessive depth." }
            guard let matches = groups["folder-" + id], matches.count == 1, let head = matches.first,
                  !head.revision.deleted, let record = head.folder else {
                return "Folder hierarchy needs review: missing, deleted or conflicting parent."
            }
            parts.insert(record.title, at: 0)
            next = record.parent
        }
        return parts.joined(separator: " / ")
    }
}
