import Foundation

/// Receipts are bound to the independently authorized account and directory.
/// Reuse requires fresh metadata matching the same Drive ID and version.
final class VerifiedObjectCache {
    private struct StoredCache: Codable {
        let schema: Int
        let accountID: String
        let folderID: String
        let objects: [DriveObject]
        let blobs: [String: Data]
    }

    let accountID: String
    let folderID: String
    let scope: String
    private let file: URL?
    private var objects: [String: DriveObject] = [:]
    private var blobs: [String: Data] = [:]
    static let maximumBytes = 64 * 1024 * 1024

    init(accountID: String, folderID: String, directory: URL? = nil) throws {
        self.accountID = accountID
        self.folderID = folderID
        scope = Self.scope(accountID: accountID, folderID: folderID)
        file = directory?.appendingPathComponent("verified-objects-\(scope).plist")
        if let file, FileManager.default.fileExists(atPath: file.path) {
            let size = try file.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? Int.max
            try require(size <= Self.maximumBytes + 4 * 1024 * 1024, "Saved object cache exceeds its limit.")
            let saved = try PropertyListDecoder().decode(StoredCache.self, from: Data(contentsOf: file))
            try require(saved.schema == 1 && saved.accountID == accountID && saved.folderID == folderID,
                        "Saved object cache belongs to another account or directory.")
            try validate(objects: saved.objects, blobs: saved.blobs)
            objects = Dictionary(uniqueKeysWithValues: saved.objects.map { ($0.id, $0) })
            blobs = saved.blobs
        }
    }

    static func scope(accountID: String, folderID: String) -> String {
        sha256(Data((accountID + "\0" + folderID).utf8))
    }

    static func key(type: String, hash: String) -> String { type + ":" + hash }

    func data(for object: DriveObject) -> Data? {
        guard object.version != nil, objects[object.id] == object else { return nil }
        return blobs[Self.key(type: object.type, hash: object.hash)]
    }

    func commit(objects newObjects: [DriveObject], records: [String: Data], payloads: [String: Data]) throws {
        let newBlobs = Dictionary(uniqueKeysWithValues:
            records.map { (Self.key(type: "revision", hash: $0.key), $0.value) } +
            payloads.map { (Self.key(type: "payload", hash: $0.key), $0.value) })
        try validate(objects: newObjects, blobs: newBlobs)
        let byID = Dictionary(uniqueKeysWithValues: newObjects.map { ($0.id, $0) })
        let unchanged = byID == objects && Set(newBlobs.keys) == Set(blobs.keys)
        if !unchanged, let file {
            let saved = StoredCache(schema: 1, accountID: accountID, folderID: folderID,
                                    objects: newObjects, blobs: newBlobs)
            let encoder = PropertyListEncoder()
            encoder.outputFormat = .binary
            try LocalStore.atomic(encoder.encode(saved), to: file)
        }
        objects = byID
        blobs = newBlobs
    }

    private func validate(objects: [DriveObject], blobs: [String: Data]) throws {
        try require(objects.count <= 1000 && Set(objects.map(\.id)).count == objects.count,
                    "Invalid cached object identities.")
        try require(blobs.values.reduce(0) { $0 + $1.count } <= Self.maximumBytes,
                    "Verified library exceeds the 64 MiB resident-cache limit.")
        let checksums = blobs.mapValues { (sha256($0), md5($0)) }
        for object in objects {
            let key = Self.key(type: object.type, hash: object.hash)
            guard ["payload", "revision"].contains(object.type),
                  let bytes = blobs[key], let checksum = checksums[key] else {
                throw ReaderError("Saved object cache is missing bytes.")
            }
            try require(bytes.count == object.size && !bytes.isEmpty &&
                        bytes.count <= (object.type == "payload" ? 4 * 1024 * 1024 : 16384) &&
                        checksum.0 == object.hash && checksum.1 == object.md5,
                        "Saved object cache checksum mismatch; preserved for inspection.")
        }
        try require(Set(objects.map { Self.key(type: $0.type, hash: $0.hash) }) == Set(blobs.keys),
                    "Saved object cache has unrelated blobs.")
    }
}
