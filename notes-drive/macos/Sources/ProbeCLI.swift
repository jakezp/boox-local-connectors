import Darwin
import Foundation
import Security

enum ProbeCommand {
    case help
    case publish(fixture: URL, expectedBase: String, journal: URL)
    case retry(expectedBase: String, journal: URL)

    static let usage = """
    Supplemental disposable-probe transport validation (no interactive UI validation).
      --validate-probe-publish --fixture /absolute/edited.note --expected-base REVISION_SHA256 --journal-dir /absolute/new-directory
      --validate-probe-retry --expected-base SAME_REVISION_SHA256 --journal-dir /absolute/existing-directory
      --validate-probe-help

    Uses only the app's saved Desktop config, own Keychain grant, Mac identity and exact
    saved Drive directory. Keychain interaction is disabled; no browser is opened.
    Only an existing GDrive-Sync-Probe[-...] notebook can be changed. The expected
    base must be its sole current head. Exact candidate bytes and revision are
    persisted outside app state before upload. Retry preserves that identity.
    No account/folder/token overrides. The live UI journal, cache, preferences,
    selection and device ID are never written. Receipt means verified transport,
    not native application or interactive editor validation. Deadline: 300 seconds.
    """

    static func parse(_ args: [String]) throws -> ProbeCommand {
        if args == ["--validate-probe-help"] { return .help }
        guard let verb = args.first, ["--validate-probe-publish", "--validate-probe-retry"].contains(verb),
              args.count % 2 == 1 else { throw ReaderError(usage) }
        var values: [String: String] = [:]
        for index in stride(from: 1, to: args.count, by: 2) {
            let key = args[index]
            try require(values[key] == nil, "Duplicate validation option.\n" + usage)
            values[key] = args[index + 1]
        }
        let keys: Set<String> = verb == "--validate-probe-publish" ?
            ["--fixture", "--expected-base", "--journal-dir"] : ["--expected-base", "--journal-dir"]
        try require(Set(values.keys) == keys, "Unknown or missing validation option.\n" + usage)
        let base = values["--expected-base"]!, directory = values["--journal-dir"]!
        try require(matches(base, "^[a-f0-9]{64}$"), "Expected base must be a complete lowercase revision SHA-256.")
        try require(directory.hasPrefix("/"), "Validation journal directory must be an absolute path.")
        let journal = URL(fileURLWithPath: directory, isDirectory: true)
        if verb == "--validate-probe-retry" { return .retry(expectedBase: base, journal: journal) }
        let fixture = values["--fixture"]!
        try require(fixture.hasPrefix("/"), "Fixture must be an absolute path.")
        return .publish(fixture: URL(fileURLWithPath: fixture), expectedBase: base, journal: journal)
    }
}

func probeRead(_ url: URL, limit: Int) throws -> Data {
    let values = try url.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey])
    try require(values.isRegularFile == true && (values.fileSize ?? Int.max) <= limit,
                "Validation input is not a regular file within its size limit.")
    let file = try FileHandle(forReadingFrom: url)
    defer { try? file.close() }
    let bytes = try file.read(upToCount: limit + 1) ?? Data()
    try require(bytes.count <= limit, "Validation input exceeds its size limit.")
    return bytes
}

/// Read-only snapshots; deliberately does not initialize LocalStore or acquire
/// the UI's client.lock. The running reader keeps its own journal and process.
struct ProbeContext {
    let root: URL
    let config: OAuthConfig
    let selection: ConnectionSelection
    let deviceID: String
    private let files: [(URL, Data)]

    static var appRoot: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("BOOX Notes Reader", isDirectory: true)
    }

    static func load(root: URL = appRoot, bundledConfig: URL? = Bundle.main.url(forResource: "oauth-desktop", withExtension: "json")) throws -> ProbeContext {
        let connectionURL = root.appendingPathComponent("connection.json")
        let deviceURL = root.appendingPathComponent("device-id")
        let imported = root.appendingPathComponent("oauth-desktop.json")
        guard let configURL = FileManager.default.fileExists(atPath: imported.path) ? imported : bundledConfig else {
            throw ReaderError("Saved Desktop OAuth configuration is unavailable.")
        }
        let connectionBytes = try probeRead(connectionURL, limit: 32768)
        let deviceBytes = try probeRead(deviceURL, limit: 256)
        let configBytes = try probeRead(configURL, limit: 32768)
        let selection = try JSONDecoder().decode(ConnectionSelection.self, from: connectionBytes)
        let deviceID = String(decoding: deviceBytes, as: UTF8.self)
        try require(!selection.account.permissionID.isEmpty &&
                    matches(selection.folder.id, "^[A-Za-z0-9_-]{1,256}$") &&
                    matches(deviceID, "^mac-[A-Za-z0-9_-]{1,124}$"),
                    "Saved connection or Mac identity is invalid.")
        let config = try OAuthConfig.decode(configBytes)
        try selection.validateClient(config.clientID)
        let context = ProbeContext(root: root, config: config, selection: selection, deviceID: deviceID,
                                   files: [(connectionURL, connectionBytes), (deviceURL, deviceBytes), (configURL, configBytes)])
        try context.assertUnchanged()
        return context
    }

    func assertUnchanged() throws {
        for (url, bytes) in files {
            try require(try probeRead(url, limit: 32768) == bytes,
                        "Saved connection/configuration changed during validation. No pending data will be redirected.")
        }
        // Creating an imported config must also invalidate a bundled-config snapshot.
        let imported = root.appendingPathComponent("oauth-desktop.json")
        if !files.contains(where: { $0.0 == imported }) {
            try require(!FileManager.default.fileExists(atPath: imported.path),
                        "Desktop OAuth configuration changed during validation.")
        }
    }
}

struct ProbeRequest: Codable {
    let desktopClientID: String
    let expectedBase: String
    let job: PendingPublication

    func validate(context: ProbeContext, expectedBase: String) throws {
        let revision = try job.checkedRevision()
        try require(self.expectedBase == expectedBase && revision.parents == [expectedBase] &&
                    !revision.deleted && revision.notebook.hasPrefix("boox-") &&
                    job.accountID == context.selection.account.permissionID &&
                    job.folderID == context.selection.folder.id && job.deviceID == context.deviceID &&
                    desktopClientID == context.config.clientID,
                    "Saved validation request differs from the explicit base or current saved destination/identity.")
    }
}

final class ProbeJournal {
    let root: URL
    private var lockFD: Int32 = -1

    static func checkedPath(_ url: URL, liveRoot: URL) throws -> URL {
        func resolved(_ value: URL) -> URL {
            // Foundation can retain an ancestor symlink if the final component
            // does not exist. Resolve the nearest existing ancestor first.
            var existing = value.standardizedFileURL
            var suffix: [String] = []
            while !FileManager.default.fileExists(atPath: existing.path) && existing.path != "/" {
                suffix.append(existing.lastPathComponent)
                existing.deleteLastPathComponent()
            }
            return suffix.reversed().reduce(existing.resolvingSymlinksInPath()) { $0.appendingPathComponent($1) }
        }
        let path = resolved(url), live = resolved(liveRoot)
        let a = path.path.precomposedStringWithCanonicalMapping.lowercased()
        let b = live.path.precomposedStringWithCanonicalMapping.lowercased()
        try require(a != "/" && a != b && !a.hasPrefix(b + "/") && !b.hasPrefix(a + "/") &&
                    !path.pathComponents.contains(where: { $0.lowercased().hasSuffix(".app") }),
                    "Validation journal must be separate from live app state and all application bundles.")
        return path
    }

    init(root: URL, liveRoot: URL, create: Bool) throws {
        self.root = try Self.checkedPath(root, liveRoot: liveRoot)
        if create {
            try require(mkdir(self.root.path, S_IRWXU) == 0,
                        "Validation requires a new journal directory with an existing parent; it never reuses or overwrites one.")
        } else {
            let info = try self.root.resourceValues(forKeys: [.isDirectoryKey, .isSymbolicLinkKey])
            try require(info.isDirectory == true && info.isSymbolicLink != true, "Validation journal directory is unavailable.")
            let request = try self.root.appendingPathComponent("request.json").resourceValues(forKeys: [.isRegularFileKey])
            try require(request.isRegularFile == true, "No isolated validation request exists in this directory.")
        }
        let fd = open(self.root.appendingPathComponent("probe.lock").path,
                      (create ? O_CREAT | O_EXCL : 0) | O_RDWR | O_NOFOLLOW, S_IRUSR | S_IWUSR)
        try require(fd >= 0, "Cannot open isolated validation lock.")
        if flock(fd, LOCK_EX | LOCK_NB) != 0 {
            close(fd)
            throw ReaderError("Another validation command is using this isolated journal.")
        }
        lockFD = fd
    }

    deinit { if lockFD >= 0 { flock(lockFD, LOCK_UN); close(lockFD) } }

    func saveRequest(_ request: ProbeRequest) throws {
        let path = root.appendingPathComponent("request.json")
        try require(!FileManager.default.fileExists(atPath: path.path), "Validation request already exists. Use the retry command.")
        let bytes = try JSONEncoder().encode(request)
        try require(bytes.count <= 6 * 1024 * 1024, "Validation request exceeds 6 MiB.")
        try LocalStore.atomic(bytes, to: path)
    }

    func loadRequest() throws -> ProbeRequest {
        try JSONDecoder().decode(ProbeRequest.self, from: probeRead(root.appendingPathComponent("request.json"), limit: 6 * 1024 * 1024))
    }

    func immutableCopy(_ bytes: Data, name: String) throws -> URL {
        let path = root.appendingPathComponent(name)
        if FileManager.default.fileExists(atPath: path.path) {
            try require(try probeRead(path, limit: 4 * 1024 * 1024) == bytes, "Isolated validation copy is corrupt; preserved for review.")
        } else { try LocalStore.atomic(bytes, to: path) }
        return path
    }
}

struct ProbeReceipt: Codable {
    let status: String
    let validationEnvironment: String
    let desktopClientID: String
    let accountPermissionID: String
    let folderID: String
    let deviceID: String
    let notebookID: String
    let title: String
    let expectedBase: String
    let revisionID: String
    let payloadSHA256: String
    let observedHeads: [String]
    let alreadyPublished: Bool
    let headReviewRequired: Bool
    let interactiveUIValidated: Bool
    let nativeApplyValidated: Bool
}

@MainActor
struct ProbeDependencies {
    var validationEnvironment = "app-own-desktop-oauth"
    var token: (OAuthConfig, Bool) async throws -> String = { config, allowInteraction in
        let authorization = GoogleAuthorization()
        authorization.configure(config)
        return try await authorization.token(allowInteraction: allowInteraction)
    }
    var client: (String) -> DriveClient = { DriveClient(token: $0) }
    var readNote: (URL, String) async throws -> Notebook = { url, hash in
        try await Task.detached { try NotebookLoader.read(url, expectedHash: hash) }.value
    }
}

enum ProbePolicy {
    static func checkCandidate(_ note: Notebook, hash: String) throws {
        try require(note.sha256 == hash && matches(note.document_id, "^[A-Za-z0-9_-]{1,123}$") &&
                    (note.title == "GDrive-Sync-Probe" || note.title.hasPrefix("GDrive-Sync-Probe-")),
                    "Only an actual GDrive-Sync-Probe[-...] notebook is eligible for this validation command.")
    }

    static func compare(_ note: Notebook, base: Notebook) throws {
        try checkCandidate(base, hash: base.sha256)
        try require(note.document_id == base.document_id && note.title == base.title &&
                    note.pages.map(\.id) == base.pages.map(\.id),
                    "Candidate and expected base must have the same probe title, native document ID and page identities.")
        for (page, original) in zip(note.pages, base.pages) {
            try require(page.width == original.width && page.height == original.height &&
                        page.layers.map(\.id) == original.layers.map(\.id) &&
                        page.layers.map(\.visible) == original.layers.map(\.visible) &&
                        page.layers.map(\.locked) == original.layers.map(\.locked),
                        "Probe validation cannot change page dimensions or layers.")
        }
        try require(note.sha256 != base.sha256 && penSignature(note) != penSignature(base),
                    "Candidate needs a real pen change from the expected base; identical/no-op exports are refused.")
    }

    private static func penSignature(_ note: Notebook) throws -> Data {
        let pages: [[String: Any]] = note.pages.map { page in
            ["id": page.id, "pens": page.strokes.filter { $0.supported && $0.type == 2 }.sorted { $0.id < $1.id }.map { stroke in
                ["id": stroke.id, "points": stroke.points, "layer": stroke.layer,
                 "width": stroke.width, "color": stroke.color] as [String: Any]
            }]
        }
        return try JSONSerialization.data(withJSONObject: pages, options: [.sortedKeys])
    }
}

@MainActor
enum ProbePublisher {
    static func run(_ command: ProbeCommand, context: ProbeContext,
                    dependencies: ProbeDependencies) async throws -> ProbeReceipt {
        let expectedBase: String, journal: ProbeJournal, request: ProbeRequest, candidate: Notebook
        switch command {
        case .help: throw ReaderError("Help does not publish.")
        case .publish(let fixture, let base, let directory):
            expectedBase = base
            _ = try ProbeJournal.checkedPath(directory, liveRoot: context.root)
            let bytes = try probeRead(fixture, limit: 4 * 1024 * 1024)
            try require(!bytes.isEmpty, "Probe export is empty.")
            journal = try ProbeJournal(root: directory, liveRoot: context.root, create: true)
            let hash = sha256(bytes)
            let copy = try journal.immutableCopy(bytes, name: "candidate.note")
            candidate = try await dependencies.readNote(copy, hash)
            try ProbePolicy.checkCandidate(candidate, hash: hash)
            let revision = try Revision(notebook: "boox-" + candidate.document_id, device: context.deviceID,
                                        parents: [base], payload: hash)
            request = ProbeRequest(desktopClientID: context.config.clientID, expectedBase: base,
                                   job: PendingPublication(accountID: context.selection.account.permissionID,
                                        folderID: context.selection.folder.id, deviceID: context.deviceID,
                                        revisionID: try revision.id, revisionBytes: try revision.encode(),
                                        payloadBytes: bytes, createdAt: Date(), status: "isolated-probe-validation"))
            try request.validate(context: context, expectedBase: base)
            try journal.saveRequest(request) // Durable exact bytes before any authenticated network operation.
        case .retry(let base, let directory):
            expectedBase = base
            journal = try ProbeJournal(root: directory, liveRoot: context.root, create: false)
            request = try journal.loadRequest()
            try request.validate(context: context, expectedBase: base)
            let hash = sha256(request.job.payloadBytes)
            let copy = try journal.immutableCopy(request.job.payloadBytes, name: "candidate.note")
            candidate = try await dependencies.readNote(copy, hash)
            try ProbePolicy.checkCandidate(candidate, hash: hash)
        }
        let revision = try request.job.checkedRevision()
        try require(revision.notebook == "boox-" + candidate.document_id, "Saved request and native probe identity differ.")
        try context.assertUnchanged()
        let client = dependencies.client(try await dependencies.token(context.config, false))
        let actual = try await client.account()
        try require(actual.permissionID == request.job.accountID, "Mac grant account differs from the exact saved account.")
        _ = try await client.folder(request.job.folderID)
        try context.assertUnchanged()
        let cache = try VerifiedObjectCache(accountID: actual.permissionID, folderID: request.job.folderID)
        let before = try await client.refresh(folderID: request.job.folderID, cache: cache)
        guard let base = before.catalog.revisions[expectedBase], base.notebook == revision.notebook,
              !base.deleted, let baseHash = base.payload, let baseBytes = before.catalog.payloads[baseHash] else {
            throw ReaderError("Explicit expected base is missing, deleted or belongs to another notebook.")
        }
        let baseCopy = try journal.immutableCopy(baseBytes, name: "base-" + baseHash + ".note")
        let baseNote = try await dependencies.readNote(baseCopy, baseHash)
        try ProbePolicy.compare(candidate, base: baseNote)

        func verifyHead(_ snapshot: DriveSnapshot) throws -> Bool {
            if let existing = snapshot.catalog.revisions[request.job.revisionID] {
                try require(existing == revision, "Existing validation revision contents differ.")
                return true
            }
            try require(snapshot.catalog.heads[revision.notebook] == [expectedBase],
                        "Probe head advanced or conflicts. Refresh and prepare an edit from the current head; this command never rebases or publishes a conflicting branch.")
            return false
        }
        let alreadyPublished = try verifyHead(before)
        var after = before
        if !alreadyPublished {
            try context.assertUnchanged()
            _ = try await client.ensureObject(type: "payload", data: request.job.payloadBytes,
                                              folderID: request.job.folderID, verifiedSnapshot: before)
            // Check again after uploading the payload. An orphan payload is safe;
            // it is not a publication if another client advanced in the meantime.
            let ready = try await client.refresh(folderID: request.job.folderID, cache: cache)
            if try !verifyHead(ready) {
                try context.assertUnchanged()
                _ = try await client.ensureObject(type: "revision", data: request.job.revisionBytes,
                                                  folderID: request.job.folderID, verifiedSnapshot: ready)
            }
            after = try await client.refresh(folderID: request.job.folderID, cache: cache)
        }
        try require(after.catalog.revisions[request.job.revisionID] == revision,
                    "Publication is not yet verified; retry the same isolated request after an unknown result.")
        let heads = after.catalog.heads[revision.notebook] ?? []
        let review = heads != [request.job.revisionID]
        let receipt = ProbeReceipt(status: review ? "verified-transport-head-review" : "verified-transport-only",
                                   validationEnvironment: dependencies.validationEnvironment,
                                   desktopClientID: request.desktopClientID, accountPermissionID: request.job.accountID,
                                   folderID: request.job.folderID, deviceID: request.job.deviceID,
                                   notebookID: revision.notebook, title: candidate.title, expectedBase: expectedBase,
                                   revisionID: request.job.revisionID, payloadSHA256: sha256(request.job.payloadBytes),
                                   observedHeads: heads, alreadyPublished: alreadyPublished, headReviewRequired: review,
                                   interactiveUIValidated: false, nativeApplyValidated: false)
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        try LocalStore.atomic(encoder.encode(receipt), to: journal.root.appendingPathComponent("receipt.json"))
        return receipt
    }
}

@MainActor
enum ProbeCLI {
    static func main(_ arguments: [String]) async {
        do {
            let command = try ProbeCommand.parse(arguments)
            if case .help = command {
                FileHandle.standardOutput.write(Data((ProbeCommand.usage + "\n").utf8))
                exit(0)
            }
            let deadline = DispatchWorkItem {
                FileHandle.standardError.write(Data("Probe validation reached its 300-second deadline. Isolated request bytes are retained; retry without changing its base.\n".utf8))
                exit(124)
            }
            DispatchQueue.global().asyncAfter(deadline: .now() + 300, execute: deadline)
            defer { deadline.cancel() }
            // LAContext.interactionNotAllowed covers Data Protection items. The
            // SDK notes legacy keychain items may still prompt, so disable that
            // process-local UI path too. This runs only in this standalone CLI.
            try require(SecKeychainSetUserInteractionAllowed(false) == errSecSuccess,
                        "Cannot disable legacy Keychain interaction. Validation stopped before reading the grant.")
            let context = try ProbeContext.load()
            let receipt = try await ProbePublisher.run(command, context: context, dependencies: ProbeDependencies())
            let encoder = JSONEncoder()
            encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
            FileHandle.standardOutput.write(try encoder.encode(receipt) + Data("\n".utf8))
            exit(receipt.headReviewRequired ? 2 : 0)
        } catch {
            let message = (error as? ReaderError)?.message ??
                "Validation failed while reading local state or accessing Google. The isolated request, if prepared, remains available for retry."
            FileHandle.standardError.write(Data((message + "\n").utf8))
            exit(1)
        }
    }
}
