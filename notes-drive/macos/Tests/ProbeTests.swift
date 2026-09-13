import Foundation

@MainActor
enum ProbeTests {
    static func run(rootDirectory: URL, passed: (String) -> Void) async throws {
        let directory = rootDirectory.appendingPathComponent("probe-" + UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let uiRoot = directory.appendingPathComponent("fake-running-ui")
        let ui = try LocalStore(root: uiRoot) // Held open throughout: validation must not acquire this UI lock.
        try ui.saveSelection(ConnectionSelection(account: DriveAccount(permissionID: "account-one", displayName: "Test", email: ""),
                                                 folder: DriveFolder(id: "test_folder", name: "Saved folder"),
                                                 desktopClientID: "synthetic-probe-client.apps.googleusercontent.com"))
        let configPath = uiRoot.appendingPathComponent("oauth-desktop.json")
        let configBytes = Data("""
        {"installed":{"client_id":"synthetic-probe-client.apps.googleusercontent.com","client_secret":"synthetic-config-secret","project_id":"synthetic-test-project"}}
        """.utf8)
        try LocalStore.atomic(configBytes, to: configPath)
        try LocalStore.atomic(Data("opaque live editor state, never read by CLI".utf8), to: uiRoot.appendingPathComponent("editor-journal.json"))
        try LocalStore.atomic(Data("opaque live preferences".utf8), to: uiRoot.appendingPathComponent("automatic-refresh.json"))
        func uiFiles() throws -> [String: Data] {
            try Dictionary(uniqueKeysWithValues: FileManager.default.contentsOfDirectory(at: uiRoot, includingPropertiesForKeys: nil)
                .map { ($0.lastPathComponent, try Data(contentsOf: $0)) })
        }
        let originalUI = try uiFiles()
        let context = try ProbeContext.load(root: uiRoot, bundledConfig: nil)
        let baseBytes = Data("probe-base".utf8), editBytes = Data("probe-pen-edit".utf8)
        let noOpBytes = Data("probe-container-change-only".utf8), productionBytes = Data("non-probe-notebook".utf8)
        let foreignBytes = Data("probe-foreign-document".utf8)
        let docID = "0ceb05b94b2354bd8c5a816a035d2812", notebookID = "boox-0ceb05b94b2354bd8c5a816a035d2812"
        func note(_ bytes: Data) -> Notebook {
            let title = bytes == productionBytes ? "Production notebook" : "GDrive-Sync-Probe-Automatic"
            let stroke = NoteStroke(id: "native-stroke", type: 2, layer: 0, width: 4, color: 0xff000000,
                                    supported: true, points: [[0, 0], [bytes == editBytes ? 20 : 10, 10]],
                                    point_hash: nil, sample_count: 2, bounds: nil, label: "", target_page: nil)
            return Notebook(title: title, document_id: bytes == foreignBytes ? "other32nativeID" : docID,
                            sha256: sha256(bytes), byte_count: bytes.count, sample_count: 2, warnings: [],
                            pages: [NotePage(id: "page32", width: 500, height: 500,
                                             layers: [NoteLayer(id: 0, visible: true, locked: false)], strokes: [stroke], warnings: [])])
        }
        let root = try Revision(notebook: notebookID, device: "android", parents: [], payload: sha256(baseBytes))
        let rootID = try root.id
        func server(base: Data = baseBytes, revision: Revision = root) async throws -> FakeDrive {
            let fake = FakeDrive()
            await fake.add(base, type: "payload")
            await fake.add(try revision.encode(), type: "revision")
            return fake
        }
        var tokenCalls: [Bool] = []
        func dependencies(_ fake: FakeDrive) -> ProbeDependencies {
            var result = ProbeDependencies()
            result.validationEnvironment = "synthetic-unit-test-no-live-grant"
            result.token = { config, interaction in
                try require(config.clientID == context.config.clientID, "Wrong Desktop config.")
                tokenCalls.append(interaction)
                return "synthetic-access-token"
            }
            result.client = { token in
                DriveClient(token: token, transport: { request, limit in
                    try require(request.value(forHTTPHeaderField: "Authorization") == "Bearer synthetic-access-token",
                                "Transport did not use the selected Mac grant provider.")
                    return try await fake.handle(request, limit: limit)
                })
            }
            result.readNote = { url, expected in
                let bytes = try probeRead(url, limit: 4 * 1024 * 1024)
                try require(sha256(bytes) == expected, "Mock native reader received unverified bytes.")
                return note(bytes)
            }
            return result
        }
        func command(_ name: String, bytes: Data = editBytes, base: String = rootID) throws -> ProbeCommand {
            let fixture = directory.appendingPathComponent(name + ".note")
            try LocalStore.atomic(bytes, to: fixture)
            return .publish(fixture: fixture, expectedBase: base, journal: directory.appendingPathComponent(name))
        }
        _ = try ProbeCommand.parse(["--validate-probe-help"])
        for args in [
            ["--validate-probe-publish", "--fixture", "/a.note", "--expected-base", rootID, "--journal-dir", "/new", "--token", "forbidden"],
            ["--validate-probe-retry", "--journal-dir", "/new"],
            ["--validate-probe-retry", "--journal-dir", "/new", "--expected-base", "abc"],
            ["--validate-probe-retry", "--journal-dir", "/new", "--expected-base", rootID, "--expected-base", rootID],
            ["--validate-probe-retry", "--journal-dir", "relative", "--expected-base", rootID]
        ] { try expectFailure { _ = try ProbeCommand.parse(args) } }
        passed("probe CLI requires explicit full base absolute paths and exact options without token or destination overrides")

        for url in [uiRoot, uiRoot.appendingPathComponent("validation"), directory,
                    URL(fileURLWithPath: "/"), directory.appendingPathComponent("Reader.app/validation")] {
            try expectFailure { _ = try ProbeJournal.checkedPath(url, liveRoot: uiRoot) }
        }
        let link = directory.appendingPathComponent("alias-to-ui")
        try FileManager.default.createSymbolicLink(at: link, withDestinationURL: uiRoot)
        try expectFailure { _ = try ProbeJournal.checkedPath(link.appendingPathComponent("validation"), liveRoot: uiRoot) }
        let unrelated = directory.appendingPathComponent("unrelated")
        try FileManager.default.createDirectory(at: unrelated, withIntermediateDirectories: false)
        try expectFailure { _ = try ProbeJournal(root: unrelated, liveRoot: uiRoot, create: false) }
        try require(try FileManager.default.contentsOfDirectory(atPath: unrelated.path).isEmpty, "Retry created files in an unrelated directory.")
        passed("probe journals reject live state ancestors symlink aliases and app bundles without modifying unrelated directories")

        let happyServer = try await server()
        let happyCommand = try command("happy")
        var happyDependencies = dependencies(happyServer)
        happyDependencies.token = { _, interaction in
            tokenCalls.append(interaction)
            let prepared = directory.appendingPathComponent("happy/request.json")
            try require(FileManager.default.fileExists(atPath: prepared.path), "Authenticated work began before durable request.")
            let request = try JSONDecoder().decode(ProbeRequest.self, from: Data(contentsOf: prepared))
            try require(request.job.payloadBytes == editBytes && request.expectedBase == rootID, "Prepared bytes or ancestry differ.")
            return "synthetic-access-token"
        }
        let receipt = try await ProbePublisher.run(happyCommand, context: context, dependencies: happyDependencies)
        let writtenOrder = await happyServer.writeOrder()
        let output = String(decoding: try JSONEncoder().encode(receipt), as: UTF8.self)
        try require(receipt.status == "verified-transport-only" && receipt.deviceID == ui.deviceID &&
                    !receipt.interactiveUIValidated && !receipt.nativeApplyValidated && !receipt.headReviewRequired &&
                    writtenOrder == ["payload", "revision"] && tokenCalls.allSatisfy({ !$0 }) &&
                    !output.contains("synthetic-access-token") && !output.contains("synthetic-config-secret"),
                    "Probe receipt, silent grant or immutable ordering failed.")
        try require(try uiFiles() == originalUI, "Validation changed running UI state.")
        passed("probe publication uses silent own-grant dependency and exact saved identity with durable bytes and no UI-state writes")
        try await expectAsyncFailure { _ = try await ProbePublisher.run(happyCommand, context: context, dependencies: happyDependencies) }
        passed("a fresh probe run cannot overwrite an existing isolated journal")

        let lost = try await server()
        await lost.interruptRecord()
        try await expectAsyncFailure { _ = try await ProbePublisher.run(command("lost"), context: context, dependencies: dependencies(lost)) }
        let savedPath = directory.appendingPathComponent("lost/request.json")
        let saved = try Data(contentsOf: savedPath), writesBefore = await lost.writeCount()
        let retry = ProbeCommand.retry(expectedBase: rootID, journal: directory.appendingPathComponent("lost"))
        let recovered = try await ProbePublisher.run(retry, context: context, dependencies: dependencies(lost))
        let writesAfter = await lost.writeCount()
        try require(recovered.alreadyPublished && writesBefore == writesAfter &&
                    Data(contentsOf: savedPath) == saved, "Unknown-result retry replaced request bytes or repeated writes.")
        let badRetry = ProbeCommand.retry(expectedBase: String(repeating: "0", count: 64), journal: directory.appendingPathComponent("lost"))
        try await expectAsyncFailure { _ = try await ProbePublisher.run(badRetry, context: context, dependencies: dependencies(lost)) }
        passed("probe retry recovers a lost remote commit without duplicate writes and refuses a changed explicit base")

        let advanced = try await server()
        let newerBytes = Data("newer-native-head".utf8)
        let newer = try Revision(notebook: notebookID, device: "android", parents: [rootID], payload: sha256(newerBytes))
        await advanced.add(newerBytes, type: "payload")
        await advanced.add(try newer.encode(), type: "revision")
        try await expectAsyncFailure { _ = try await ProbePublisher.run(command("stale"), context: context, dependencies: dependencies(advanced)) }
        try require(await advanced.writeCount() == 0, "An obsolete probe base uploaded data.")
        passed("probe validation refuses an obsolete expected base before any upload")

        let privateServer = try await server()
        let callsBefore = tokenCalls.count
        try await expectAsyncFailure { _ = try await ProbePublisher.run(command("non-probe", bytes: productionBytes), context: context, dependencies: dependencies(privateServer)) }
        let privateWrites = await privateServer.writeCount()
        try require(tokenCalls.count == callsBefore && privateWrites == 0, "Non-probe candidate accessed OAuth or wrote data.")
        let privateRevision = try Revision(notebook: notebookID, device: "android", parents: [], payload: sha256(productionBytes))
        let privateBaseServer = try await server(base: productionBytes, revision: privateRevision)
        try await expectAsyncFailure { _ = try await ProbePublisher.run(command("private-base", base: privateRevision.id), context: context, dependencies: dependencies(privateBaseServer)) }
        try require(await privateBaseServer.writeCount() == 0, "Renaming a production notebook to Probe bypassed base validation.")
        try await expectAsyncFailure { _ = try await ProbePublisher.run(command("foreign-native", bytes: foreignBytes), context: context, dependencies: dependencies(privateServer)) }
        try await expectAsyncFailure { _ = try await ProbePublisher.run(command("no-op", bytes: noOpBytes), context: context, dependencies: dependencies(privateServer)) }
        passed("both native probe identities and titles are checked; foreign notebooks and container-only changes cannot publish")

        let accountChanged = try await server()
        await accountChanged.setAccount("foreign-account")
        try await expectAsyncFailure { _ = try await ProbePublisher.run(command("wrong-account"), context: context, dependencies: dependencies(accountChanged)) }
        try require(await accountChanged.writeCount() == 0, "Grant account mismatch redirected publication.")
        let configChanged = try await server()
        var changingDeps = dependencies(configChanged)
        changingDeps.token = { _, interaction in
            tokenCalls.append(interaction)
            try LocalStore.atomic(configBytes + Data("\n".utf8), to: configPath)
            return "synthetic-access-token"
        }
        try await expectAsyncFailure { _ = try await ProbePublisher.run(command("changed-config"), context: context, dependencies: changingDeps) }
        try LocalStore.atomic(configBytes, to: configPath)
        try require(await configChanged.writeCount() == 0, "Changed configuration was ignored before publication.")
        passed("probe grant account and read-only config snapshot are rechecked before destination writes")

        let silentFailure = try await server()
        var deniedDeps = dependencies(silentFailure)
        deniedDeps.token = { _, interaction in
            try require(!interaction, "Validation allowed Keychain interaction.")
            throw ReaderError("Synthetic Keychain interaction required.")
        }
        try await expectAsyncFailure { _ = try await ProbePublisher.run(command("silent-denial"), context: context, dependencies: deniedDeps) }
        let silentWrites = await silentFailure.writeCount()
        try require(FileManager.default.fileExists(atPath: directory.appendingPathComponent("silent-denial/request.json").path) &&
                    silentWrites == 0 && (try uiFiles()) == originalUI,
                    "Silent-grant denial damaged state or lost the retry request.")
        passed("Keychain interaction-required failure leaves an isolated durable request and performs no cloud or UI-state writes")

        let race = try await server()
        var racingDeps = dependencies(race)
        racingDeps.client = { token in
            DriveClient(token: token, transport: { request, limit in
                let result = try await race.handle(request, limit: limit)
                if request.url?.path == "/upload/drive/v3/files", request.httpBody?.range(of: editBytes) != nil {
                    await race.add(newerBytes, type: "payload")
                    await race.add(try newer.encode(), type: "revision")
                }
                return result
            })
        }
        try await expectAsyncFailure { _ = try await ProbePublisher.run(command("payload-race"), context: context, dependencies: racingDeps) }
        let raceWrites = await race.writeOrder()
        try require(raceWrites == ["payload"], "Mid-upload head change published an implicit conflicting revision.")
        passed("a head change after payload upload stops the revision publication and preserves the isolated request")

        let lateRace = try await server()
        let published = try Revision(notebook: notebookID, device: ui.deviceID, parents: [rootID], payload: sha256(editBytes))
        let publishedBytes = try published.encode()
        var lateDependencies = dependencies(lateRace)
        lateDependencies.client = { token in
            DriveClient(token: token, transport: { request, limit in
                let result = try await lateRace.handle(request, limit: limit)
                if request.url?.path == "/upload/drive/v3/files", request.httpBody?.range(of: publishedBytes) != nil {
                    await lateRace.add(newerBytes, type: "payload")
                    await lateRace.add(try newer.encode(), type: "revision")
                }
                return result
            })
        }
        let lateReceipt = try await ProbePublisher.run(command("late-race"), context: context, dependencies: lateDependencies)
        try require(lateReceipt.status == "verified-transport-head-review" && lateReceipt.headReviewRequired &&
                    lateReceipt.observedHeads.count == 2 && !lateReceipt.nativeApplyValidated && !lateReceipt.interactiveUIValidated,
                    "A post-publication race falsely reported a sole winning head or native/UI validation.")
        passed("post-publication races produce an explicit head-review receipt and preserve both conflicting heads")
        try require(try uiFiles() == originalUI, "Supplemental validation altered any saved UI files.")
    }
}
