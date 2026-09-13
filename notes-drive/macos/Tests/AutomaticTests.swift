import Foundation
import Network

final class StalledHTTPServer: @unchecked Sendable {
    let listener: NWListener
    let queue = DispatchQueue(label: "boox.tests.stalled-http")
    var connections: [NWConnection] = []
    var pending: CheckedContinuation<UInt16, Error>?

    init() throws {
        let parameters = NWParameters.tcp
        parameters.requiredLocalEndpoint = .hostPort(host: "127.0.0.1", port: .any)
        listener = try NWListener(using: parameters)
    }

    func start() async throws -> UInt16 {
        try await withCheckedThrowingContinuation { continuation in
            pending = continuation
            listener.stateUpdateHandler = { state in
                if case .ready = state {
                    self.pending?.resume(returning: self.listener.port!.rawValue)
                    self.pending = nil
                } else if case .failed(let error) = state {
                    self.pending?.resume(throwing: error)
                    self.pending = nil
                }
            }
            listener.newConnectionHandler = { connection in
                self.connections.append(connection)
                connection.start(queue: self.queue)
            }
            listener.start(queue: queue)
        }
    }

    func stop() {
        queue.async {
            self.listener.cancel()
            self.connections.forEach { $0.cancel() }
        }
    }
}

@MainActor
enum AutomaticTests {
    static func run(rootDirectory: URL, passed: (String) -> Void) async throws {
        let bytes = Data("native notebook".utf8)
        let hash = sha256(bytes)
        let root = try Revision(notebook: "boox-native-notebook", device: "android", parents: [], payload: hash)
        let fake = FakeDrive()
        let payloadID = await fake.add(bytes, type: "payload")
        await fake.add(try root.encode(), type: "revision")
        let cache = try VerifiedObjectCache(accountID: "account-one", folderID: "test_folder")
        let client = fake.client()
        _ = try await client.refresh(folderID: "test_folder", cache: cache)
        let reads = await fake.mediaReadCount()
        let reused = try await client.refresh(folderID: "test_folder", cache: cache)
        let readsAfterReuse = await fake.mediaReadCount()
        try require(reused.downloadBytes == 0 && reused.reusedObjects == 2 &&
                    readsAfterReuse == reads, "Unchanged poll redownloaded immutable bytes.")
        passed("unchanged automatic poll revalidates metadata with zero media downloads")

        await fake.rename(payloadID, to: "A renamed native notebook")
        let renamed = try await client.refresh(folderID: "test_folder", cache: cache)
        try require(renamed.downloadBytes == bytes.count && renamed.reusedObjects == 1 &&
                    renamed.catalog.heads == reused.catalog.heads,
                    "Changed version did not receive full validation.")
        passed("renamed/version-changed ID is fully reverified without changing notebook identity")

        let duplicateID = await fake.add(bytes, type: "payload")
        let duplicate = try await client.refresh(folderID: "test_folder", cache: cache)
        try require(duplicate.downloadBytes == bytes.count && duplicate.reusedObjects == 2,
                    "New duplicate ID incorrectly reused another ID's receipt.")
        await fake.bumpVersion(duplicateID)
        await fake.configure(corrupt: duplicateID)
        try await expectAsyncFailure { _ = try await client.refresh(folderID: "test_folder", cache: cache) }
        passed("new duplicate ID downloads fully; changed corrupt duplicate rejects cached catalog")
        await fake.configure()

        await fake.configure(incomplete: true)
        try await expectAsyncFailure { _ = try await client.refresh(folderID: "test_folder", cache: cache) }
        await fake.configure(wrongParent: true)
        try await expectAsyncFailure { _ = try await client.refresh(folderID: "test_folder", cache: cache) }
        await fake.configure(denied: true)
        try await expectAsyncFailure { _ = try await client.refresh(folderID: "test_folder", cache: cache) }
        await fake.configure()
        passed("warm cache cannot bypass incomplete listing, changed parent or lost access")

        let unknownVersion = FakeDrive()
        await unknownVersion.add(bytes, type: "payload")
        await unknownVersion.add(try root.encode(), type: "revision")
        await unknownVersion.omitVersionMetadata()
        let unknownCache = try VerifiedObjectCache(accountID: "account", folderID: "test_folder")
        _ = try await unknownVersion.client().refresh(folderID: "test_folder", cache: unknownCache)
        _ = try await unknownVersion.client().refresh(folderID: "test_folder", cache: unknownCache)
        try require(await unknownVersion.mediaReadCount() == 4, "Missing Drive version allowed cache reuse.")
        passed("missing version metadata falls back to full object verification")

        let changing = FakeDrive()
        let changingID = await changing.add(bytes, type: "payload")
        await changing.add(try root.encode(), type: "revision")
        await changing.mutateDuringRead(changingID)
        let changingCache = try VerifiedObjectCache(accountID: "account", folderID: "test_folder")
        try await expectAsyncFailure {
            _ = try await changing.client().refresh(folderID: "test_folder", cache: changingCache)
        }
        passed("version change during download refuses a verified-cache receipt")

        let directory = rootDirectory.appendingPathComponent("automatic-tests-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let diskCache = try VerifiedObjectCache(accountID: "account-one", folderID: "test_folder", directory: directory)
        _ = try await client.refresh(folderID: "test_folder", cache: diskCache)
        let reopened = try VerifiedObjectCache(accountID: "account-one", folderID: "test_folder", directory: directory)
        let restored = try await client.refresh(folderID: "test_folder", cache: reopened)
        try require(restored.downloadBytes == 0 && restored.reusedObjects == 3,
                    "Verified cache did not survive reopening.")
        let differentAccount = try VerifiedObjectCache(accountID: "account-two", folderID: "test_folder", directory: directory)
        let foreign = try await client.refresh(folderID: "test_folder", cache: differentAccount)
        try require(foreign.reusedObjects == 0, "Another account reused cached authorization receipts.")
        passed("persistent verified cache survives reopen and is isolated by account/folder")

        let file = directory.appendingPathComponent("verified-objects-\(diskCache.scope).plist")
        var plist = try PropertyListSerialization.propertyList(from: Data(contentsOf: file), format: nil) as! [String: Any]
        var blobs = plist["blobs"] as! [String: Data]
        blobs["payload:" + hash] = Data("damaged cache".utf8)
        plist["blobs"] = blobs
        let damaged = try PropertyListSerialization.data(fromPropertyList: plist, format: .binary, options: 0)
        try damaged.write(to: file)
        try expectFailure {
            _ = try VerifiedObjectCache(accountID: "account-one", folderID: "test_folder", directory: directory)
        }
        try require(try Data(contentsOf: file) == damaged, "Damaged cache evidence was overwritten.")
        passed("corrupt persistent bytes rejected and preserved before reuse")

        var schedule = AutomaticRefreshSchedule()
        var now = Date(timeIntervalSince1970: 1000)
        try require(!schedule.beginAutomatic(now: now, enabled: false, eligible: true),
                    "Disabled automatic preference started a poll.")
        try require(!schedule.beginAutomatic(now: now, enabled: true, eligible: false),
                    "Unconfigured session started a poll.")
        for delay in [24.0, 48, 96, 192, 300, 300] {
            try require(schedule.beginAutomatic(now: now, enabled: true, eligible: true), "Due retry did not start.")
            try require(!schedule.beginAutomatic(now: now, enabled: true, eligible: true), "Overlapping poll started.")
            _ = schedule.finishAutomatic(succeeded: false, now: now)
            try require(schedule.nextAttempt.timeIntervalSince(now) == delay, "Incorrect bounded failure backoff.")
            try require(!schedule.beginAutomatic(now: now.addingTimeInterval(delay-1), enabled: true, eligible: true),
                        "Backoff retry started too early.")
            now = schedule.nextAttempt
        }
        try require(schedule.beginAutomatic(now: now, enabled: true, eligible: true), "Recovery poll did not start.")
        _ = schedule.finishAutomatic(succeeded: true, now: now)
        try require(schedule.consecutiveFailures == 0 && schedule.nextAttempt.timeIntervalSince(now) == 12,
                    "Successful recovery did not restore the 12-second schedule.")
        passed("automatic preference, no-overlap gate and 24–300 second backoff/reset")

        let slow = FakeDrive()
        await slow.delayRequests(1_000_000_000)
        var gate = AutomaticRefreshSchedule()
        try require(gate.beginAutomatic(now: now, enabled: true, eligible: true), "Cannot start automatic request.")
        let automatic = Task { try await slow.client().refresh(folderID: "test_folder") }
        for _ in 0..<100 {
            if await slow.inFlight() == 1 { break }
            try await Task.sleep(nanoseconds: 1_000_000)
        }
        try require(await slow.inFlight() == 1, "Automatic request did not enter transport.")
        try require(gate.requestManual() == .waitForAutomatic && gate.requestManual() == .alreadyBusy,
                    "Manual action did not wait for the active poll.")
        automatic.cancel()
        try await expectAsyncFailure { _ = try await automatic.value }
        try require(await slow.inFlight() == 0, "Cancelled poll still owns an active request.")
        try require(gate.finishAutomatic(succeeded: nil, now: now) && gate.operation == .manual,
                    "Cancellation did not hand the guard to manual work.")
        try require(!gate.beginAutomatic(now: now.addingTimeInterval(1000), enabled: true, eligible: true),
                    "Automatic poll overlapped foreground work/auth.")
        await slow.delayRequests(0)
        _ = try await slow.client().refresh(folderID: "test_folder")
        gate.finishManual(now: now)
        try require(await slow.maximumInFlight() == 1 && gate.consecutiveFailures == 0,
                    "Manual handoff overlapped requests or counted cancellation as failure.")
        passed("real async cancellation releases poll before manual action; foreground guard stays exclusive")

        let stalled = try StalledHTTPServer()
        let port = try await stalled.start()
        defer { stalled.stop() }
        let request = URLRequest(url: URL(string: "http://127.0.0.1:\(port)/pending")!)
        let requestTask = Task { try await BoundedHTTP.send(request, limit: 1024) }
        try await Task.sleep(nanoseconds: 100_000_000)
        let cancelledAt = Date()
        requestTask.cancel()
        try await expectAsyncFailure { _ = try await requestTask.value }
        try require(Date().timeIntervalSince(cancelledAt) < 2, "Cancellation did not abort a stalled URLSession request.")
        passed("actual stalled HTTP request cancels promptly without waiting for timeout")

        let rootID = try root.id
        let child = try Revision(notebook: root.notebook, device: "android",
                                 parents: [rootID], payload: sha256(Data("new native snapshot".utf8)))
        let childID = try child.id
        let payloads = [hash: bytes, child.payload!: Data("new native snapshot".utf8)]
        let single = try RevisionCatalog(records: [rootID: root.encode(), childID: child.encode()], payloads: payloads)
        let selection = OpenLibraryRevision(scope: "account-folder", notebookID: root.notebook, revisionID: rootID)
        try require(AutomaticLibraryFollow.decision(open: selection, scope: "account-folder", catalog: single) == .advance(childID),
                    "A verified descendant did not follow.")
        let position = ReadingPosition(pageID: "page-b", pageIndex: 1, zoom: 2.5)
        try require(position.following(pageIDs: ["page-b", "page-a"]) ==
                    ReadingPosition(pageID: "page-b", pageIndex: 0, zoom: 2.5), "Page reorder lost page identity or zoom.")
        try require(position.following(pageIDs: ["page-a"]) ==
                    ReadingPosition(pageID: "page-a", pageIndex: 0, zoom: 2.5), "Removed page fallback lost zoom or valid index.")
        passed("verified descendant follows by ancestry while retaining page identity/index fallback and zoom")

        let other = try Revision(notebook: root.notebook, device: "other-client", parents: [rootID], payload: hash)
        let conflicts = try RevisionCatalog(records: [rootID: root.encode(), childID: child.encode(), other.id: other.encode()],
                                             payloads: payloads)
        try require(AutomaticLibraryFollow.decision(open: selection, scope: "account-folder", catalog: conflicts) == .conflict,
                    "Conflict auto-selected a head.")
        let deletion = try Revision(notebook: root.notebook, device: "android", parents: [childID], payload: nil, deleted: true)
        let deleted = try RevisionCatalog(records: [rootID: root.encode(), childID: child.encode(), deletion.id: deletion.encode()],
                                           payloads: payloads)
        try require(try AutomaticLibraryFollow.decision(open: selection, scope: "account-folder", catalog: deleted) == .reviewDeletion(deletion.id),
                    "Deletion did not request review.")
        try require(AutomaticLibraryFollow.decision(open: selection, scope: "another-account", catalog: single) == .preserveCurrent,
                    "Another account silently replaced the current view.")
        let missing = try RevisionCatalog(records: [:], payloads: [:])
        try require(AutomaticLibraryFollow.decision(open: selection, scope: "account-folder", catalog: missing) == .preserveCurrent,
                    "Empty remote catalog removed current content.")
        passed("conflicts, deletion, account change and empty catalogs preserve the current view")

        let native = NoteSummary(title: "Field notes", document_id: "native-notebook", sha256: child.payload!,
                                 byte_count: 19, page_count: 3, page_ids: ["a", "b", "c"])
        let summaryResults: [String: LibrarySummaryResult] = [
            hash: .decoded(NoteSummary(title: "Field notes", document_id: "native-notebook", sha256: hash,
                                      byte_count: bytes.count, page_count: 2, page_ids: ["a", "b"])),
            child.payload!: .decoded(native)
        ]
        let rows = LibraryIndexBuilder.heads(catalog: conflicts, summaries: summaryResults)
        try require(rows.count == 2 && rows.allSatisfy { $0.isConflict && !$0.isValidation && $0.title == "Field notes" },
                    "Native library lost decoded titles or a conflicting head.")
        let deletionRows = LibraryIndexBuilder.heads(catalog: deleted, summaries: summaryResults)
        try require(deletionRows[0].title == "Field notes" && deletionRows[0].pageCount == 3 &&
                    deletionRows[0].revision.deleted, "Deletion review lost retained notebook metadata.")
        let fixture = try Revision(notebook: validationNotebook, device: "mac", parents: [], payload: hash)
        let fixtureCatalog = try RevisionCatalog(records: [fixture.id: fixture.encode()], payloads: [hash: bytes])
        try require(LibraryIndexBuilder.heads(catalog: fixtureCatalog, summaries: summaryResults)[0].isValidation,
                    "Disposable fixture leaked into the native library.")
        passed("native title/page library retains each conflict and separates fixtures/deletion review")

        let settingsDirectory = directory.appendingPathComponent("settings")
        var settingsStore: LocalStore? = try LocalStore(root: settingsDirectory)
        try require(settingsStore!.automaticSettings().enabled, "New connected-user preference must default on.")
        let deviceID = settingsStore!.deviceID
        try settingsStore!.saveAutomaticSettings(AutomaticRefreshSettings(enabled: false))
        settingsStore = nil
        let restoredSettings = try LocalStore(root: settingsDirectory)
        try require(!restoredSettings.automaticSettings().enabled && restoredSettings.deviceID == deviceID,
                    "Restart discarded an explicit off preference or device identity.")
        passed("automatic default-on and explicit persisted-off survive reopen without changing device identity")
    }
}
