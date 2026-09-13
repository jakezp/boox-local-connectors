import Darwin
import Foundation

struct PendingPublication: Codable {
    let accountID: String
    let folderID: String
    let deviceID: String
    let revisionID: String
    let revisionBytes: Data
    let payloadBytes: Data
    let createdAt: Date
    var status: String
}

struct ConnectionSelection: Codable {
    let account: DriveAccount
    let folder: DriveFolder
    var desktopClientID: String? = nil

    func validateClient(_ clientID: String, allowLegacy: Bool = false) throws {
        if let desktopClientID {
            try require(desktopClientID == clientID,
                        "Saved library belongs to another Desktop OAuth client. Import its original configuration; queued data will not be redirected.")
        } else {
            try require(allowLegacy, "Reconnect the saved grant in the app once to bind this library to its Desktop OAuth client.")
        }
    }

    func validateConfigImport(_ clientID: String, currentClientID: String?) throws {
        try validateClient(clientID, allowLegacy: true)
        if desktopClientID == nil, let currentClientID {
            try require(clientID == currentClientID,
                        "Reconnect the existing Desktop configuration before changing clients. The saved library was not changed.")
        }
    }
}

final class LocalStore {
    let root: URL
    let deviceID: String
    private var lockFD: Int32 = -1

    init(root: URL? = nil) throws {
        self.root = root ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("BOOX Notes Reader", isDirectory: true)
        try FileManager.default.createDirectory(at: self.root, withIntermediateDirectories: true,
                                               attributes: [.posixPermissions: 0o700])
        let lockURL = self.root.appendingPathComponent("client.lock")
        let descriptor = open(lockURL.path, O_CREAT | O_RDWR, S_IRUSR | S_IWUSR)
        try require(descriptor >= 0, "Cannot open the Mac client lock.")
        if flock(descriptor, LOCK_EX | LOCK_NB) != 0 {
            close(descriptor)
            throw ReaderError("Another BOOX Notes Reader instance is using this cache.")
        }
        lockFD = descriptor
        let deviceURL = self.root.appendingPathComponent("device-id")
        if FileManager.default.fileExists(atPath: deviceURL.path) {
            deviceID = String(decoding: try Data(contentsOf: deviceURL), as: UTF8.self)
            try require(matches(deviceID, "^mac-[A-Za-z0-9_-]+$"), "Invalid persisted Mac device ID.")
        } else {
            deviceID = "mac-" + UUID().uuidString.lowercased()
            try Self.atomic(Data(deviceID.utf8), to: deviceURL)
        }
    }

    deinit { if lockFD >= 0 { flock(lockFD, LOCK_UN); close(lockFD) } }

    static func atomic(_ bytes: Data, to url: URL) throws {
        let temporary = url.deletingLastPathComponent().appendingPathComponent(".pending-" + UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: temporary) }
        let fd = open(temporary.path, O_CREAT | O_EXCL | O_WRONLY, S_IRUSR | S_IWUSR)
        try require(fd >= 0, "Cannot create durable local file.")
        defer { close(fd) }
        try bytes.withUnsafeBytes { raw in
            var offset = 0
            while offset < raw.count {
                let written = Darwin.write(fd, raw.baseAddress!.advanced(by: offset), raw.count-offset)
                if written < 0 && errno == EINTR { continue }
                try require(written > 0, "Cannot write durable local file.")
                offset += written
            }
        }
        try require(fsync(fd) == 0, "Cannot flush durable local file.")
        try require(rename(temporary.path, url.path) == 0, "Cannot commit durable local file.")
        let directory = open(url.deletingLastPathComponent().path, O_RDONLY)
        defer { if directory >= 0 { close(directory) } }
        try require(directory >= 0 && fsync(directory) == 0, "Cannot flush local directory.")
    }

    func loadPending() throws -> PendingPublication? {
        let url = root.appendingPathComponent("pending-publication.json")
        guard FileManager.default.fileExists(atPath: url.path) else { return nil }
        let data = try Data(contentsOf: url)
        try require(data.count <= 6 * 1024 * 1024, "Saved publication exceeds its limit.")
        let pending = try JSONDecoder().decode(PendingPublication.self, from: data)
        let revision = try Revision.decode(pending.revisionBytes)
        try require(try revision.id == pending.revisionID && revision.device == pending.deviceID &&
                    revision.payload == sha256(pending.payloadBytes) && !revision.deleted,
                    "Saved publication failed its checksums.")
        return pending
    }

    func savePending(_ pending: PendingPublication) throws {
        try Self.atomic(JSONEncoder().encode(pending), to: root.appendingPathComponent("pending-publication.json"))
    }

    func completePending(_ pending: PendingPublication) throws {
        var verified = pending
        verified.status = "verified-publication"
        try Self.atomic(JSONEncoder().encode(verified), to: root.appendingPathComponent("last-publication.json"))
        // Retain the saved job and bytes as verified instead of deleting the journal.
        try savePending(verified)
    }

    func saveSelection(_ selection: ConnectionSelection) throws {
        try Self.atomic(JSONEncoder().encode(selection), to: root.appendingPathComponent("connection.json"))
    }

    func selection() throws -> ConnectionSelection? {
        let url = root.appendingPathComponent("connection.json")
        guard FileManager.default.fileExists(atPath: url.path) else { return nil }
        return try JSONDecoder().decode(ConnectionSelection.self, from: Data(contentsOf: url))
    }

    func automaticSettings() throws -> AutomaticRefreshSettings {
        let url = root.appendingPathComponent("automatic-refresh.json")
        guard FileManager.default.fileExists(atPath: url.path) else { return AutomaticRefreshSettings() }
        let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? Int.max
        try require(size <= 4096, "Automatic refresh preferences exceed their limit.")
        return try JSONDecoder().decode(AutomaticRefreshSettings.self, from: Data(contentsOf: url))
    }

    func saveAutomaticSettings(_ settings: AutomaticRefreshSettings) throws {
        try Self.atomic(JSONEncoder().encode(settings), to: root.appendingPathComponent("automatic-refresh.json"))
    }

    func cache(_ bytes: Data, hash: String) throws -> URL {
        try require(matches(hash, "^[a-f0-9]{64}$") && sha256(bytes) == hash, "Cache checksum mismatch.")
        let directory = root.appendingPathComponent("payloads", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true,
                                               attributes: [.posixPermissions: 0o700])
        let url = directory.appendingPathComponent(hash + ".note")
        if FileManager.default.fileExists(atPath: url.path) {
            try require(sha256(Data(contentsOf: url)) == hash, "Previously cached notebook is corrupt; preserved for inspection.")
        } else { try Self.atomic(bytes, to: url) }
        return url
    }
}
