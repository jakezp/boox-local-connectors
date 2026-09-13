import Foundation

let validationNotebook = "boox-validation-notebook-v1"

struct DriveAccount: Codable, Equatable {
    let permissionID: String
    let displayName: String
    let email: String
}

struct DriveFolder: Codable, Equatable, Identifiable {
    let id: String
    let name: String
}

struct DriveConnection {
    let account: DriveAccount
    let folders: [DriveFolder]
    let selection: ConnectionSelection?

    static func load(client: DriveClient, desktopClientID: String,
                     saved: ConnectionSelection?) async throws -> DriveConnection {
        try saved?.validateClient(desktopClientID, allowLegacy: true)
        let account = try await client.account()
        if let saved {
            try require(saved.account.permissionID == account.permissionID,
                        "Google account differs from the saved library. Reconnect its original account; queued data will not be redirected.")
        }
        // Verify the exact saved destination, even when it is absent from listing.
        // Failure never falls back to a different directory.
        let retained: DriveFolder?
        if let saved { retained = try await client.folder(saved.folder.id) }
        else { retained = nil }
        var folders = try await client.folders()
        if let retained, !folders.contains(where: { $0.id == retained.id }) { folders.append(retained) }
        let selected = retained ?? (folders.count == 1 ? folders[0] : nil)
        let selection = selected.map {
            ConnectionSelection(account: account, folder: $0, desktopClientID: desktopClientID)
        }
        return DriveConnection(account: account, folders: folders, selection: selection)
    }
}

struct DriveObject: Codable, Equatable {
    let id: String
    let type: String
    let hash: String
    let size: Int
    let md5: String
    var version: String? = nil
}

struct DriveSnapshot {
    let catalog: RevisionCatalog
    let objects: [DriveObject]
    let downloadBytes: Int
    let verifiedAt: Date
    var reusedObjects: Int = 0
}

struct DriveClient {
    let token: String
    var transport: (URLRequest, Int) async throws -> HTTPResult = { request, limit in
        try await BoundedHTTP.send(request, limit: limit)
    }
    private let objectFields = "id,name,mimeType,trashed,parents,appProperties,size,md5Checksum,version"

    private func send(_ path: String, query: [String: String] = [:],
                      method: String = "GET", body: Data? = nil,
                      contentType: String? = nil, limit: Int = 4 * 1024 * 1024) async throws -> Data {
        try Task.checkCancellation()
        var url = URLComponents(string: "https://www.googleapis.com" + path)!
        url.queryItems = query.sorted { $0.key < $1.key }.map { URLQueryItem(name: $0.key, value: $0.value) }
        var request = URLRequest(url: url.url!)
        request.httpMethod = method
        request.httpBody = body
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        if let contentType { request.setValue(contentType, forHTTPHeaderField: "Content-Type") }
        let result = try await transport(request, limit)
        try Task.checkCancellation()
        try require(result.data.count <= limit, "Drive response exceeds its limit.")
        if result.status == 401 { throw ReaderError("Drive authorization expired (HTTP 401). Reconnect and retry the saved publication.") }
        if result.status == 403 || result.status == 404 {
            throw ReaderError("Drive access denied or object unavailable (HTTP \(result.status)). A folder ID alone does not grant drive.file access.")
        }
        try require((200..<300).contains(result.status), "Drive request failed (HTTP \(result.status)). Pending publication is retained.")
        return result.data
    }

    func account() async throws -> DriveAccount {
        let root = try jsonObject(await send("/drive/v3/about", query: [
            "fields": "user(permissionId,displayName,emailAddress)"
        ]))
        guard let user = root["user"] as? [String: Any],
              let permission = user["permissionId"] as? String, !permission.isEmpty else {
            throw ReaderError("Google did not return an account permission ID.")
        }
        return DriveAccount(permissionID: permission, displayName: user["displayName"] as? String ?? "",
                            email: user["emailAddress"] as? String ?? "")
    }

    func folder(_ id: String) async throws -> DriveFolder {
        try require(matches(id, "^[A-Za-z0-9_-]{1,256}$"), "Invalid Drive folder ID.")
        let object = try jsonObject(await send("/drive/v3/files/\(id)", query: [
            "fields": "id,name,mimeType,trashed,appProperties,capabilities(canEdit,canAddChildren)"
        ]))
        guard object["id"] as? String == id,
              object["mimeType"] as? String == "application/vnd.google-apps.folder",
              object["trashed"] as? Bool == false,
              (object["appProperties"] as? [String: String])?["booxNotesProtocol"] == "1",
              let capabilities = object["capabilities"] as? [String: Any],
              capabilities["canEdit"] as? Bool == true,
              capabilities["canAddChildren"] as? Bool == true else {
            throw ReaderError("Directory must be writable, untrashed and marked booxNotesProtocol=1.")
        }
        return DriveFolder(id: id, name: object["name"] as? String ?? id)
    }

    func folders() async throws -> [DriveFolder] {
        let files = try await list(query: "trashed=false and mimeType='application/vnd.google-apps.folder' and appProperties has { key='booxNotesProtocol' and value='1' }",
                                   fields: "id")
        var result: [DriveFolder] = []
        for file in files {
            guard let id = file["id"] as? String else { throw ReaderError("Missing folder ID.") }
            result.append(try await folder(id))
        }
        return result.sorted { $0.name == $1.name ? $0.id < $1.id : $0.name < $1.name }
    }

    private func list(query: String, fields: String) async throws -> [[String: Any]] {
        var objects: [[String: Any]] = []
        var ids = Set<String>()
        var fingerprints: [String: Data] = [:]
        var tokens = Set<String>()
        var pageToken: String?
        for _ in 0..<100 {
            var queryItems = ["q": query, "spaces": "drive", "pageSize": "100",
                              "fields": "nextPageToken,incompleteSearch,files(\(fields))"]
            if let pageToken { queryItems["pageToken"] = pageToken }
            let page = try jsonObject(await send("/drive/v3/files", query: queryItems))
            try require(page["incompleteSearch"] as? Bool == false, "Drive returned an incomplete or invalid listing.")
            guard let files = page["files"] as? [[String: Any]] else { throw ReaderError("Drive listing is missing files.") }
            for file in files {
                guard let id = file["id"] as? String, matches(id, "^[A-Za-z0-9_-]{1,256}$") else {
                    throw ReaderError("Invalid Drive object ID.")
                }
                let fingerprint = try JSONSerialization.data(withJSONObject: file, options: .sortedKeys)
                if let previous = fingerprints[id] {
                    try require(previous == fingerprint, "Drive object changed during pagination.")
                } else {
                    fingerprints[id] = fingerprint
                    ids.insert(id)
                    objects.append(file)
                    try require(ids.count <= 1000, "Drive catalog exceeds 1,000 objects.")
                }
            }
            guard let next = page["nextPageToken"] as? String, !next.isEmpty else { return objects }
            try require(next.count <= 8192 && tokens.insert(next).inserted, "Repeated or invalid Drive page token.")
            pageToken = next
        }
        throw ReaderError("Drive listing exceeds 100 pages.")
    }

    private func parseObject(_ file: [String: Any], folderID: String) throws -> DriveObject {
        guard let id = file["id"] as? String, matches(id, "^[A-Za-z0-9_-]{1,256}$"),
              file["trashed"] as? Bool == false,
              let parents = file["parents"] as? [String], parents == [folderID],
              let properties = file["appProperties"] as? [String: String],
              properties["booxNotesProtocol"] == "1",
              let type = properties["booxObjectType"], ["payload", "revision"].contains(type),
              let hash = properties["booxObjectSha256"], matches(hash, "^[a-f0-9]{64}$"),
              let sizeText = file["size"] as? String, let size = Int(sizeText), size > 0,
              let checksum = file["md5Checksum"] as? String, matches(checksum, "^[a-f0-9]{32}$") else {
            throw ReaderError("Drive object has invalid parent, properties, size or checksum.")
        }
        try require(size <= (type == "payload" ? 4 * 1024 * 1024 : 16384),
                    "Drive object exceeds its protocol size limit.")
        let version = file["version"] as? String
        if let version {
            try require(matches(version, "^[0-9]{1,30}$"), "Invalid Drive version metadata.")
        }
        return DriveObject(id: id, type: type, hash: hash, size: size, md5: checksum, version: version)
    }

    private func download(_ object: DriveObject, remaining: Int = 4 * 1024 * 1024) async throws -> Data {
        try require(object.size <= remaining, "Refresh would exceed the 64 MiB download budget.")
        let limit = min(remaining, object.type == "payload" ? 4 * 1024 * 1024 : 16384)
        let data = try await send("/drive/v3/files/\(object.id)", query: ["alt": "media"], limit: limit)
        try require(data.count == object.size && md5(data) == object.md5 && sha256(data) == object.hash,
                    "Drive object failed size/MD5/SHA-256 verification.")
        return data
    }

    func refresh(folderID: String, cache: VerifiedObjectCache? = nil) async throws -> DriveSnapshot {
        try await withThrowingTaskGroup(of: DriveSnapshot.self) { group in
            group.addTask { try await self.refreshObjects(folderID: folderID, cache: cache) }
            group.addTask {
                try await Task.sleep(nanoseconds: 120_000_000_000)
                throw ReaderError("Drive refresh exceeded two minutes; the previous library is preserved.")
            }
            defer { group.cancelAll() }
            return try await group.next()!
        }
    }

    private func refreshObjects(folderID: String, cache: VerifiedObjectCache?) async throws -> DriveSnapshot {
        try require(cache == nil || cache?.folderID == folderID, "Object cache directory does not match.")
        _ = try await folder(folderID)
        let files = try await list(query: "'\(folderID)' in parents and trashed=false and appProperties has { key='booxNotesProtocol' and value='1' }",
                                   fields: objectFields)
        var records: [String: Data] = [:], payloads: [String: Data] = [:]
        var objects: [DriveObject] = []
        var bytes = 0
        var residentBytes = 0
        var reusedObjects = 0
        for file in files {
            try Task.checkCancellation()
            let object = try parseObject(file, folderID: folderID)
            let data: Data
            if let cached = cache?.data(for: object) {
                data = cached
                reusedObjects += 1
            } else {
                data = try await download(object, remaining: 64 * 1024 * 1024 - bytes)
                bytes += data.count
                if cache != nil {
                    let current = try parseObject(jsonObject(await send("/drive/v3/files/\(object.id)",
                        query: ["fields": objectFields])), folderID: folderID)
                    try require(current == object, "Drive object changed during download; cached state was not advanced.")
                }
            }
            objects.append(object)
            if object.type == "payload" {
                if let existing = payloads[object.hash] { try require(existing == data, "Duplicate payload bytes differ.") }
                else { residentBytes += data.count }
                payloads[object.hash] = data
            } else {
                if let existing = records[object.hash] { try require(existing == data, "Duplicate revision bytes differ.") }
                else { residentBytes += data.count }
                records[object.hash] = data
            }
            try require(residentBytes <= VerifiedObjectCache.maximumBytes, "Verified library exceeds the 64 MiB resident-cache limit.")
        }
        let catalog = try RevisionCatalog(records: records, payloads: payloads)
        try Task.checkCancellation()
        try cache?.commit(objects: objects, records: records, payloads: payloads)
        return DriveSnapshot(catalog: catalog, objects: objects, downloadBytes: bytes,
                             verifiedAt: Date(), reusedObjects: reusedObjects)
    }

    func ensureObject(type: String, data: Data, folderID: String,
                      verifiedSnapshot: DriveSnapshot) async throws -> DriveObject {
        try require(["payload", "revision"].contains(type), "Unknown object type.")
        let hash = sha256(data)
        try require(!data.isEmpty && data.count <= (type == "payload" ? 4 * 1024 * 1024 : 16384),
                    "Publication object must be nonempty and within its protocol size limit.")
        if let existing = verifiedSnapshot.objects.first(where: { $0.type == type && $0.hash == hash }) {
            // The snapshot has already downloaded and verified every duplicate.
            return existing
        }
        _ = try await folder(folderID)
        let suffix = type == "payload" ? ".note" : ".json"
        let metadata: [String: Any] = [
            "name": "\(type)-\(hash)\(suffix)", "parents": [folderID],
            "appProperties": ["booxNotesProtocol": "1", "booxObjectType": type, "booxObjectSha256": hash]
        ]
        let boundary = "boox_" + UUID().uuidString
        var body = Data("--\(boundary)\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n".utf8)
        body.append(try JSONSerialization.data(withJSONObject: metadata, options: .sortedKeys))
        body.append(Data("\r\n--\(boundary)\r\nContent-Type: application/octet-stream\r\n\r\n".utf8))
        body.append(data)
        body.append(Data("\r\n--\(boundary)--\r\n".utf8))
        let created = try jsonObject(await send("/upload/drive/v3/files", query: [
            "uploadType": "multipart", "fields": objectFields
        ], method: "POST", body: body, contentType: "multipart/related; boundary=\(boundary)", limit: 32768))
        let object = try parseObject(created, folderID: folderID)
        try require(object.type == type && object.hash == hash && object.size == data.count && object.md5 == md5(data),
                    "Uploaded Drive metadata does not match publication.")
        let returned = try await download(object)
        try require(returned == data, "Uploaded object readback differs.")
        return object
    }
}
