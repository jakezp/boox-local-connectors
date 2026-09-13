import Foundation

func expectFailure(_ operation: () throws -> Void) throws {
    do { try operation() }
    catch { return }
    throw ReaderError("Expected operation to reject invalid input.")
}

func expectAsyncFailure(_ operation: () async throws -> Void) async throws {
    do { try await operation() }
    catch { return }
    throw ReaderError("Expected asynchronous operation to reject invalid input.")
}

actor FakeDrive {
    var files: [String: (type: String, data: Data, advertisedHash: String)] = [:]
    var names: [String: String] = [:]
    var mediaReads = 0
    var versions: [String: Int] = [:]
    var omitVersions = false
    var changeVersionOnReadID: String?
    var delayNanoseconds: UInt64 = 0
    var activeRequests = 0
    var peakRequests = 0
    var corruptID: String?
    var wrongParent = false
    var incomplete = false
    var repeatedPage = false
    var denied = false
    var accountPermissionID = "account-one"
    var failNextRecordAfterSave = false
    var writes: [String] = []
    var counter = 0
    let folder = "test_folder"

    func configure(corrupt: String? = nil, wrongParent: Bool = false,
                   incomplete: Bool = false, repeatedPage: Bool = false, denied: Bool = false) {
        corruptID = corrupt
        self.wrongParent = wrongParent
        self.incomplete = incomplete
        self.repeatedPage = repeatedPage
        self.denied = denied
    }
    func interruptRecord() { failNextRecordAfterSave = true }
    func setAccount(_ value: String) { accountPermissionID = value }
    func writeCount() -> Int { writes.count }
    func writeOrder() -> [String] { writes }
    func mediaReadCount() -> Int { mediaReads }
    func rename(_ id: String, to name: String) { names[id] = name; bumpVersion(id) }
    func bumpVersion(_ id: String) { versions[id, default: 1] += 1 }
    func omitVersionMetadata() { omitVersions = true }
    func mutateDuringRead(_ id: String) { changeVersionOnReadID = id }
    func delayRequests(_ nanoseconds: UInt64) { delayNanoseconds = nanoseconds }
    func inFlight() -> Int { activeRequests }
    func maximumInFlight() -> Int { peakRequests }

    @discardableResult
    func add(_ data: Data, type: String, hash: String? = nil) -> String {
        counter += 1
        let id = "object_\(counter)"
        files[id] = (type, data, hash ?? sha256(data))
        return id
    }

    func metadata(_ id: String) -> [String: Any] {
        let object = files[id]!
        var result: [String: Any] = ["id": id, "name": names[id] ?? "\(object.type)-\(object.advertisedHash)\(object.type == "payload" ? ".note" : ".json")",
                "mimeType": "application/octet-stream", "trashed": false,
                "parents": [wrongParent ? "foreign_folder" : folder],
                "appProperties": ["booxNotesProtocol": "1", "booxObjectType": object.type,
                                  "booxObjectSha256": object.advertisedHash],
                "size": String(object.data.count), "md5Checksum": md5(object.data)]
        if !omitVersions { result["version"] = String(versions[id, default: 1]) }
        return result
    }

    func handle(_ request: URLRequest, limit: Int) async throws -> HTTPResult {
        activeRequests += 1
        peakRequests = max(peakRequests, activeRequests)
        defer { activeRequests -= 1 }
        if delayNanoseconds > 0 { try await Task.sleep(nanoseconds: delayNanoseconds) }
        try require(request.url?.scheme == "https" && request.url?.host == "www.googleapis.com",
                    "Unexpected HTTP destination.")
        if denied { return HTTPResult(status: 403, data: Data()) }
        let url = request.url!
        let query = Dictionary(uniqueKeysWithValues: (URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? [])
            .map { ($0.name, $0.value ?? "") })
        var response: [String: Any]
        if url.path == "/drive/v3/about" {
            response = ["user": ["permissionId": accountPermissionID, "displayName": "Synthetic test", "emailAddress": "test@example.invalid"]]
        } else if url.path == "/drive/v3/files/\(folder)" {
            response = ["id": folder, "name": "Test", "mimeType": "application/vnd.google-apps.folder",
                        "trashed": false, "appProperties": ["booxNotesProtocol": "1"],
                        "capabilities": ["canEdit": true, "canAddChildren": true]]
        } else if url.path == "/drive/v3/files" {
            response = ["incompleteSearch": incomplete, "files": files.keys.sorted().map(metadata)]
            if repeatedPage { response["nextPageToken"] = "same-token" }
        } else if url.path == "/upload/drive/v3/files" {
            try require(request.httpMethod == "POST", "Immutable upload must use POST.")
            let body = request.httpBody!
            let separator = Data("\r\n\r\n".utf8)
            let first = body.range(of: separator)!
            let metadataEnd = body.range(of: Data("\r\n--".utf8), in: first.upperBound..<body.endIndex)!
            let metadata = try jsonObject(Data(body[first.upperBound..<metadataEnd.lowerBound]))
            let second = body.range(of: separator, in: metadataEnd.upperBound..<body.endIndex)!
            let boundary = (request.value(forHTTPHeaderField: "Content-Type")!).components(separatedBy: "boundary=")[1]
            let end = body.range(of: Data("\r\n--\(boundary)--\r\n".utf8), in: second.upperBound..<body.endIndex)!
            let data = Data(body[second.upperBound..<end.lowerBound])
            let properties = metadata["appProperties"] as! [String: String]
            let type = properties["booxObjectType"]!
            try require(metadata["parents"] as? [String] == [folder], "Wrong upload parent.")
            try require(properties["booxNotesProtocol"] == "1" && properties["booxObjectSha256"] == sha256(data),
                        "Wrong upload properties.")
            let id = add(data, type: type)
            writes.append(type)
            if type == "revision" && failNextRecordAfterSave {
                failNextRecordAfterSave = false
                throw ReaderError("Injected unknown upload response after server commit.")
            }
            response = self.metadata(id)
        } else if query["alt"] == "media" {
            mediaReads += 1
            let id = url.lastPathComponent
            guard let object = files[id] else { return HTTPResult(status: 404, data: Data()) }
            if changeVersionOnReadID == id { bumpVersion(id) }
            return HTTPResult(status: 200, data: id == corruptID ? Data("corrupt".utf8) : object.data)
        } else if files[url.lastPathComponent] != nil {
            response = metadata(url.lastPathComponent)
        } else { throw ReaderError("Unexpected Drive route \(url.path)") }
        return HTTPResult(status: 200, data: try JSONSerialization.data(withJSONObject: response))
    }

    nonisolated func client() -> DriveClient {
        DriveClient(token: "test-only", transport: { request, limit in try await self.handle(request, limit: limit) })
    }
}

@main
struct CoreTests {
    static func main() async throws {
        var count = 0
        func passed(_ name: String) { count += 1; print("PASS \(name)") }
        let vectorData = try Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[2]))
        let vectors = try JSONSerialization.jsonObject(with: vectorData) as! [[String: String]]
        for vector in vectors {
            let bytes = Data(vector["json"]!.utf8)
            let revision = try Revision.decode(bytes)
            try require(try revision.encode() == bytes && revision.id == vector["id"],
                        "Shared Python protocol test vector differs.")
        }
        passed("all three shared Python protocol vectors match exact encoding and hash")
        let hash = sha256(Data("fixture".utf8))
        let root = try Revision(notebook: validationNotebook, device: "android-test", parents: [], payload: hash)
        let canonical = "{\"deleted\":false,\"device\":\"android-test\",\"notebook\":\"boox-validation-notebook-v1\",\"parents\":[],\"payload\":\"\(hash)\",\"schema\":1}"
        try require(try root.encode() == Data(canonical.utf8), "Python-compatible canonical encoding differs.")
        try require(try Revision.decode(root.encode()) == root, "Revision round trip differs.")
        passed("exact Python-compatible canonical bytes")

        for invalid in [
            canonical + "\n",
            canonical.replacingOccurrences(of: "\"schema\":1", with: "\"schema\":1.0"),
            canonical.replacingOccurrences(of: "\"schema\":1", with: "\"schema\":true"),
            canonical.replacingOccurrences(of: "\"deleted\":false", with: "\"deleted\":0"),
            canonical.replacingOccurrences(of: "\"schema\":1", with: "\"schema\":1,\"schema\":1"),
            canonical.replacingOccurrences(of: "\"schema\":1", with: "\"schema\":1,\"extra\":false")
        ] { try expectFailure { _ = try Revision.decode(Data(invalid.utf8)) } }
        passed("noncanonical, duplicate keys and wrong JSON types rejected")

        let rootID = try root.id
        let left = try Revision(notebook: validationNotebook, device: "mac-left", parents: [rootID], payload: hash)
        let right = try Revision(notebook: validationNotebook, device: "mac-right", parents: [rootID], payload: hash)
        let records = try [rootID: root.encode(), left.id: left.encode(), right.id: right.encode()]
        let catalog = try RevisionCatalog(records: records, payloads: [hash: Data("fixture".utf8)])
        try require(catalog.heads[validationNotebook]?.count == 2, "Concurrent heads were collapsed.")
        passed("concurrent heads preserved")
        try expectFailure { _ = try RevisionCatalog(records: [left.id: left.encode()], payloads: [hash: Data("fixture".utf8)]) }
        try expectFailure { _ = try RevisionCatalog(records: records, payloads: [:]) }
        try expectFailure { _ = try RevisionCatalog(records: records, payloads: [hash: Data("damaged".utf8)]) }
        passed("missing ancestry and damaged payloads rejected")
        let tombstone = try Revision(notebook: validationNotebook, device: "mac-left", parents: [rootID],
                                     payload: nil, deleted: true)
        let tombCatalog = try RevisionCatalog(records: [rootID: root.encode(), tombstone.id: tombstone.encode()],
                                             payloads: [hash: Data("fixture".utf8)])
        try require(tombCatalog.heads[validationNotebook] == [tombstone.id], "Deletion head missing.")
        passed("tombstone preserved as reviewable revision")

        let fake = FakeDrive()
        let payloadFile = await fake.add(Data("fixture".utf8), type: "payload")
        let revisionFile = await fake.add(try root.encode(), type: "revision")
        await fake.add(Data("fixture".utf8), type: "payload")
        let client = fake.client()
        let snapshot = try await client.refresh(folderID: "test_folder")
        try require(snapshot.objects.count == 3 && snapshot.catalog.revisions.count == 1, "Duplicate logical objects were not checked.")
        passed("all valid Drive duplicate IDs verified")
        await fake.rename(payloadFile, to: "Renamed notebook export")
        await fake.rename(revisionFile, to: "Renamed revision record")
        let renamed = try await client.refresh(folderID: "test_folder")
        try require(renamed.catalog.revisions == snapshot.catalog.revisions &&
                    renamed.catalog.heads == snapshot.catalog.heads &&
                    renamed.catalog.payloads == snapshot.catalog.payloads,
                    "Renaming a valid Drive object changed the verified catalog.")
        _ = try await client.ensureObject(type: "payload", data: Data("fixture".utf8),
                                          folderID: "test_folder", verifiedSnapshot: renamed)
        _ = try await client.ensureObject(type: "revision", data: root.encode(),
                                          folderID: "test_folder", verifiedSnapshot: renamed)
        try require(await fake.writeCount() == 0, "Renamed valid objects caused duplicate uploads.")
        passed("renamed valid payload/revision remain verified and reusable by properties/hash")
        let corrupt = await fake.add(Data("fixture".utf8), type: "payload")
        await fake.configure(corrupt: corrupt)
        try await expectAsyncFailure { _ = try await client.refresh(folderID: "test_folder") }
        passed("corrupt duplicate rejects entire catalog")
        await fake.configure(wrongParent: true)
        try await expectAsyncFailure { _ = try await client.refresh(folderID: "test_folder") }
        await fake.configure(incomplete: true)
        try await expectAsyncFailure { _ = try await client.refresh(folderID: "test_folder") }
        await fake.configure(repeatedPage: true)
        try await expectAsyncFailure { _ = try await client.refresh(folderID: "test_folder") }
        await fake.configure(denied: true)
        try await expectAsyncFailure { _ = try await client.refresh(folderID: "test_folder") }
        passed("wrong parent, incomplete/repeated paging and denied access rejected")
        await fake.configure()

        let newBytes = Data("fixture-descendant".utf8)
        let child = try Revision(notebook: validationNotebook, device: "mac-test", parents: [rootID], payload: sha256(newBytes))
        var before = try await client.refresh(folderID: "test_folder")
        _ = try await client.ensureObject(type: "payload", data: newBytes, folderID: "test_folder", verifiedSnapshot: before)
        await fake.interruptRecord()
        try await expectAsyncFailure {
            _ = try await client.ensureObject(type: "revision", data: child.encode(), folderID: "test_folder", verifiedSnapshot: before)
        }
        before = try await client.refresh(folderID: "test_folder")
        _ = try await client.ensureObject(type: "payload", data: newBytes, folderID: "test_folder", verifiedSnapshot: before)
        _ = try await client.ensureObject(type: "revision", data: child.encode(), folderID: "test_folder", verifiedSnapshot: before)
        try require(await fake.writeOrder() == ["payload", "revision"], "Retry duplicated immutable writes or reversed publication order.")
        try require(try before.catalog.heads[validationNotebook] == [child.id], "Saved revision did not become a descendant.")
        passed("unknown upload result recovered without duplicate writes; payload precedes record")

        let empty = try await FakeDrive().client().refresh(folderID: "test_folder")
        try require(empty.catalog.heads.isEmpty, "Empty catalog invented state.")
        passed("empty catalog never invents a deletion")
        for type in ["payload", "revision"] {
            let zero = FakeDrive()
            await zero.add(Data(), type: type)
            try await expectAsyncFailure { _ = try await zero.client().refresh(folderID: "test_folder") }
            try require(await zero.mediaReadCount() == 0, "Zero-byte object was not rejected before download.")
        }
        passed("zero-byte payload/revision metadata rejected before download")
        for type in ["payload", "revision"] {
            let zero = FakeDrive()
            try await expectAsyncFailure {
                _ = try await zero.client().ensureObject(type: type, data: Data(),
                                                         folderID: "test_folder", verifiedSnapshot: empty)
            }
            try require(await zero.writeCount() == 0, "Zero-byte publication reached Drive.")
        }
        passed("zero-byte payload/revision publication rejected before upload")

        let location = URL(fileURLWithPath: CommandLine.arguments[1])
        do {
            let store = try LocalStore(root: location)
            let pending = try PendingPublication(accountID: "account-one", folderID: "test_folder",
                                                 deviceID: store.deviceID, revisionID: child.id,
                                                 revisionBytes: child.encode(), payloadBytes: newBytes,
                                                 createdAt: Date(), status: "pending")
            // Use the actual saved installation identity in the publication.
            let own = try Revision(notebook: validationNotebook, device: store.deviceID, parents: [rootID], payload: sha256(newBytes))
            let job = try PendingPublication(accountID: pending.accountID, folderID: pending.folderID,
                                             deviceID: store.deviceID, revisionID: own.id,
                                             revisionBytes: own.encode(), payloadBytes: newBytes,
                                             createdAt: Date(), status: "pending")
            try store.savePending(job)
            let restored = try store.loadPending()!
            try require(restored.revisionBytes == job.revisionBytes && restored.payloadBytes == newBytes &&
                        restored.accountID == "account-one" && restored.folderID == "test_folder", "Queue lost immutable identity.")
            try expectFailure { _ = try LocalStore(root: location) }
            try store.completePending(restored)
            try require(store.loadPending()?.status == "verified-publication", "Verified receipt missing.")
        }
        let reopened = try LocalStore(root: location)
        try require(reopened.loadPending()?.status == "verified-publication", "Publication lost after reopen.")
        passed("fsynced publication identity survives reopen; concurrent writer blocked")

        let loopback = try LoopbackCallback(state: "state-from-secure-random-in-production")
        let port = try await loopback.start()
        let received = try await loopback.receiveCode {
            Task {
                let invalid = URL(string: "http://127.0.0.1:\(port)/oauth/callback?state=wrong&code=ignored")!
                let (_, response) = try await URLSession.shared.data(from: invalid)
                guard (response as? HTTPURLResponse)?.statusCode == 400 else { fatalError("Wrong OAuth state accepted") }
                let valid = URL(string: "http://127.0.0.1:\(port)/oauth/callback?state=state-from-secure-random-in-production&code=test-code")!
                _ = try await URLSession.shared.data(from: valid)
            }
        }
        try require(received == "test-code", "Loopback callback did not receive code.")
        passed("real loopback socket rejects wrong state and accepts matching callback")
        try await AutomaticTests.run(rootDirectory: location, passed: passed)
        try await EditorTests.run(rootDirectory: location, passed: passed)
        try await FolderTests.run(rootDirectory: location, passed: passed)
        try await NotebookTests.run(rootDirectory: location, passed: passed)
        try await ProbeTests.run(rootDirectory: location, passed: passed)
        try await PortableConfigTests.run(rootDirectory: location, passed: passed)
        print("\(count) Swift core tests passed.")
    }
}
