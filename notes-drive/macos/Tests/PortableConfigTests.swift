import Foundation
import Security

private actor SyntheticConnectionServer {
    let accountID: String
    let listed: [String]
    let allowed: Set<String>
    let writable: Bool
    private var requests: [String] = []

    init(accountID: String = "synthetic-account-one", listed: [String] = ["synthetic-folder"],
         allowed: Set<String> = ["synthetic-folder"], writable: Bool = true) {
        self.accountID = accountID
        self.listed = listed
        self.allowed = allowed
        self.writable = writable
    }

    func paths() -> [String] { requests }

    nonisolated func client() -> DriveClient {
        DriveClient(token: "synthetic-access-token", transport: { try await self.handle($0, limit: $1) })
    }

    private func handle(_ request: URLRequest, limit: Int) throws -> HTTPResult {
        try require(request.httpMethod == "GET", "Connection verification must never publish.")
        try require(request.url?.host == "www.googleapis.com", "Unexpected connection destination.")
        let path = request.url!.path
        requests.append(path)
        let object: [String: Any]
        if path == "/drive/v3/about" {
            object = ["user": ["permissionId": accountID, "displayName": "Synthetic account"]]
        } else if path == "/drive/v3/files" {
            object = ["incompleteSearch": false, "files": listed.map { ["id": $0] }]
        } else {
            let id = request.url!.lastPathComponent
            guard allowed.contains(id) else { return HTTPResult(status: 403, data: Data()) }
            object = ["id": id, "name": "Synthetic folder", "mimeType": "application/vnd.google-apps.folder",
                      "trashed": false, "appProperties": ["booxNotesProtocol": "1"],
                      "capabilities": ["canEdit": writable, "canAddChildren": writable]]
        }
        let bytes = try JSONSerialization.data(withJSONObject: object)
        try require(bytes.count <= limit, "Synthetic connection response exceeds request limit.")
        return HTTPResult(status: 200, data: bytes)
    }
}

@MainActor
enum PortableConfigTests {
    static func run(rootDirectory: URL, passed: (String) -> Void) async throws {
        let client = "synthetic-owner-desktop.apps.googleusercontent.com"
        let otherClient = "synthetic-other-desktop.apps.googleusercontent.com"
        let config = Data("""
        {"installed":{"client_id":"\(client)","client_secret":"synthetic-secret","project_id":"synthetic-owner-project"}}
        """.utf8)
        try require(try OAuthConfig.decode(config).clientID == client, "User-supplied project was rejected.")
        let flat = Data("{\"client_id\":\"\(otherClient)\"}".utf8)
        try require(try OAuthConfig.decode(flat).clientID == otherClient, "Existing flat Desktop config is incompatible.")
        passed("arbitrary user Desktop projects and legacy flat configs retain their exact client identity")

        for invalid in [
            Data("{\"web\":{\"client_id\":\"\(client)\"}}".utf8),
            Data("{\"installed\":false,\"client_id\":\"\(client)\"}".utf8),
            Data("{\"client_id\":\"bad-client\"}".utf8),
            Data("{\"client_id\":\"\(client)\",\"client_secret\":7}".utf8),
            Data("{\"client_id\":\"\(client)\",\"project_id\":false}".utf8),
            Data("{\"client_id\":\"\(client)\",\"project_id\":\"\"}".utf8),
            Data(repeating: 32, count: 32769)
        ] {
            try expectFailure { _ = try OAuthConfig.decode(invalid) }
        }
        passed("Desktop config rejects web clients malformed identities field types and oversize input")

        let ownQuery = TokenKeychain.query(client), otherQuery = TokenKeychain.query(otherClient)
        try require(ownQuery[kSecAttrAccount as String] as? String == client &&
                    otherQuery[kSecAttrAccount as String] as? String == otherClient &&
                    ownQuery[kSecAttrService as String] as? String == "local.boox.notesreader.google-oauth",
                    "Own-client Keychain namespace changed.")
        passed("portable clients keep distinct entries in the existing app-only Keychain namespace without reading grants")

        let legacyBytes = Data("""
        {"account":{"permissionID":"synthetic-account-one","displayName":"Before upgrade","email":""},
         "folder":{"id":"synthetic-folder","name":"Saved before upgrade"}}
        """.utf8)
        let legacy = try JSONDecoder().decode(ConnectionSelection.self, from: legacyBytes)
        try require(legacy.desktopClientID == nil, "Legacy selection acquired an invented client.")
        let server = SyntheticConnectionServer()
        let migrated = try await DriveConnection.load(client: server.client(), desktopClientID: client, saved: legacy)
        guard let bound = migrated.selection else { throw ReaderError("Legacy destination was dropped.") }
        try require(bound.desktopClientID == client && bound.folder.id == legacy.folder.id &&
                    bound.account.permissionID == legacy.account.permissionID, "Migration changed destination.")
        passed("legacy saved selection migrates only after authenticating the same account and verifying its exact folder")

        let root = rootDirectory.appendingPathComponent("portable-config-" + UUID().uuidString)
        let store = try LocalStore(root: root)
        let untouched = ["editor-journal.json", "pending-publication.json", "automatic-refresh.json"]
        for name in untouched {
            try LocalStore.atomic(Data("synthetic opaque retained state \(name)".utf8), to: root.appendingPathComponent(name))
        }
        try LocalStore.atomic(legacyBytes, to: root.appendingPathComponent("connection.json"))
        try store.saveSelection(bound)
        try require(try store.selection()?.desktopClientID == client, "Client binding did not persist.")
        for name in untouched {
            try require(try Data(contentsOf: root.appendingPathComponent(name)) == Data("synthetic opaque retained state \(name)".utf8),
                        "Migration rewrote queued edits/preferences.")
        }
        passed("persisted connection migration leaves existing draft publication and automatic preferences byte-identical")

        let deniedClient = SyntheticConnectionServer()
        try await expectAsyncFailure {
            _ = try await DriveConnection.load(client: deniedClient.client(), desktopClientID: otherClient, saved: bound)
        }
        try require(await deniedClient.paths().isEmpty, "Client mismatch reached Drive.")
        let wrongAccount = SyntheticConnectionServer(accountID: "synthetic-account-two")
        try await expectAsyncFailure {
            _ = try await DriveConnection.load(client: wrongAccount.client(), desktopClientID: client, saved: bound)
        }
        try require(await wrongAccount.paths() == ["/drive/v3/about"], "Wrong account reached a folder.")
        passed("saved client or account mismatch fails before checking or redirecting a destination")

        let lost = SyntheticConnectionServer(listed: ["synthetic-other-folder"], allowed: ["synthetic-other-folder"])
        try await expectAsyncFailure {
            _ = try await DriveConnection.load(client: lost.client(), desktopClientID: client, saved: bound)
        }
        try require(await lost.paths() == ["/drive/v3/about", "/drive/v3/files/synthetic-folder"],
                    "Lost saved destination fell back to another folder.")
        let unwritable = SyntheticConnectionServer(writable: false)
        try await expectAsyncFailure {
            _ = try await DriveConnection.load(client: unwritable.client(), desktopClientID: client, saved: bound)
        }
        passed("lost or unwritable saved folder fails closed without probing a different directory")

        let omitted = SyntheticConnectionServer(listed: [])
        let retained = try await DriveConnection.load(client: omitted.client(), desktopClientID: client, saved: bound)
        try require(retained.selection?.folder.id == bound.folder.id && retained.folders.map(\.id) == [bound.folder.id],
                    "Exact accessible destination was lost when omitted from listing.")
        passed("an accessible saved folder omitted from listing is retained only after a direct permission check")

        let empty = SyntheticConnectionServer(listed: [], allowed: [])
        let none = try await DriveConnection.load(client: empty.client(), desktopClientID: client, saved: nil)
        let emptyPaths = await empty.paths()
        try require(none.selection == nil && none.folders.isEmpty &&
                    emptyPaths == ["/drive/v3/about", "/drive/v3/files"], "Empty discovery guessed a folder.")
        let multiple = SyntheticConnectionServer(listed: ["synthetic-a", "synthetic-b"],
                                                 allowed: ["synthetic-a", "synthetic-b"])
        let choices = try await DriveConnection.load(client: multiple.client(), desktopClientID: client, saved: nil)
        try require(choices.selection == nil && choices.folders.count == 2, "Ambiguous directories were auto-selected.")
        let single = try await DriveConnection.load(client: server.client(), desktopClientID: client, saved: nil)
        try require(single.selection?.folder.id == "synthetic-folder" && single.selection?.desktopClientID == client,
                    "Sole verified directory was not bound.")
        passed("fresh discovery uses authorized folders only with no private fallback and no ambiguous automatic selection")

        try bound.validateConfigImport(client, currentClientID: client)
        try expectFailure { try bound.validateConfigImport(otherClient, currentClientID: client) }
        try legacy.validateConfigImport(client, currentClientID: client)
        try expectFailure { try legacy.validateConfigImport(otherClient, currentClientID: client) }
        try expectFailure { try legacy.validateClient(client) }
        passed("config import protects bound and legacy destinations while unbound CLI use requires app reconnection")

        try LocalStore.atomic(config, to: root.appendingPathComponent("oauth-desktop.json"))
        _ = try ProbeContext.load(root: root, bundledConfig: nil)
        try LocalStore.atomic(legacyBytes, to: root.appendingPathComponent("connection.json"))
        try expectFailure { _ = try ProbeContext.load(root: root, bundledConfig: nil) }
        try store.saveSelection(bound)
        try LocalStore.atomic(flat, to: root.appendingPathComponent("oauth-desktop.json"))
        try expectFailure { _ = try ProbeContext.load(root: root, bundledConfig: nil) }
        try LocalStore.atomic(config, to: root.appendingPathComponent("oauth-desktop.json"))
        let context = try ProbeContext.load(root: root, bundledConfig: nil)
        try LocalStore.atomic(flat, to: root.appendingPathComponent("oauth-desktop.json"))
        try expectFailure { try context.assertUnchanged() }
        passed("CLI accepts a saved user client binding and rejects unbound mismatched or changed configs without grant access")

        let fixtureRoot = root.appendingPathComponent("synthetic-resources")
        try FileManager.default.createDirectory(at: fixtureRoot, withIntermediateDirectories: true)
        let resourceBytes = Data("synthetic fixture bytes for integrity testing".utf8)
        func manifest(hash: String = sha256(resourceBytes), size: Int = resourceBytes.count,
                      profile: String = "two-page.note", synthetic: Bool = true) throws -> Data {
            try JSONSerialization.data(withJSONObject: [
                "schema": 1, "synthetic_only": synthetic, "resources": [
                    "Target-after.note": ["profile": profile, "sha256": hash, "bytes": size],
                    "B2.note": ["profile": "two-page-variant.note", "sha256": hash, "bytes": size]
                ]
            ])
        }
        let manifestURL = fixtureRoot.appendingPathComponent("synthetic-resources.json")
        for name in ["Target-after.note", "B2.note"] {
            try LocalStore.atomic(resourceBytes, to: fixtureRoot.appendingPathComponent(name))
        }
        try LocalStore.atomic(manifest(), to: manifestURL)
        for name in ["Target-after.note", "B2.note"] {
            let fixture = try BundledFixture.load(name, resources: fixtureRoot)
            try require(fixture.bytes == resourceBytes && fixture.hash == sha256(resourceBytes), "Fixture bytes changed.")
        }
        passed("bundled fixture loading verifies signed synthetic manifest identity size and digest for both resources")
        for invalid in [try manifest(hash: String(repeating: "0", count: 64)), try manifest(size: 0),
                        try manifest(size: 4 * 1024 * 1024 + 1), try manifest(size: resourceBytes.count + 1),
                        try manifest(profile: "../foreign.note"), try manifest(synthetic: false)] {
            try LocalStore.atomic(invalid, to: manifestURL)
            try expectFailure { _ = try BundledFixture.load("Target-after.note", resources: fixtureRoot) }
        }
        try LocalStore.atomic(manifest(), to: manifestURL)
        try expectFailure { _ = try BundledFixture.load("../Target-after.note", resources: fixtureRoot) }
        try LocalStore.atomic(Data("corrupt synthetic bytes".utf8), to: fixtureRoot.appendingPathComponent("B2.note"))
        try expectFailure { _ = try BundledFixture.load("B2.note", resources: fixtureRoot) }
        passed("fixture manifest rejects renamed profiles corrupt bytes wrong sizes and traversal without weakening digest guards")

        guard let generatedPath = ProcessInfo.processInfo.environment["BOOX_MAC_TEST_BUNDLE_RESOURCES_DIR"] else {
            throw ReaderError("Generated bundle test resources are missing.")
        }
        let generated = URL(fileURLWithPath: generatedPath)
        for name in ["Target-after.note", "B2.note"] {
            let fixture = try BundledFixture.load(name, resources: generated)
            try require(fixture.bytes.starts(with: Data([0x50, 0x4b, 0x03, 0x04])),
                        "Generated bundle fixture is not an archive.")
        }
        passed("production fixture verifier accepts the actual build packager output generated without private inputs")
    }
}
