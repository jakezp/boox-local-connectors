import AppKit
import Foundation
import SwiftUI
import UniformTypeIdentifiers

@MainActor
final class ReaderModel: ObservableObject {
    @Published var notebook: Notebook?
    @Published var pageIndex = 0
    @Published var zoom = 1.0
    @Published var showHidden = false
    @Published var source = "Open a BOOX .note export or a verified Drive revision."
    @Published var status = "Local reader ready. Google Drive library updates arrive automatically while this app is open."
    @Published var failure: String?
    @Published var busy = false
    @Published var manualBusy = false
    @Published private(set) var automaticEnabled = true
    @Published private(set) var automaticStatus = "Waiting to reconnect the saved Google grant."
    @Published private(set) var automaticError: String?
    @Published private(set) var libraryHeads: [LibraryHead] = []
    @Published var libraryNotice: String?
    @Published var account: DriveAccount?
    @Published var folders: [DriveFolder] = []
    @Published var selectedFolderID = ""
    @Published var snapshot: DriveSnapshot?
    @Published var pending: PendingPublication?
    @Published var oauthConfigured = false
    @Published var editorJournal = EditorJournal()
    @Published private(set) var editorStorageReady = false
    @Published var editorTool = EditorTool.view
    @Published var penWidth = 4.0
    @Published var penColor: UInt32 = 0xff1756aa
    @Published var editLayer = 0
    var currentPayload: Data?
    var currentLocalURL: URL?
    private let authorization = GoogleAuthorization()
    private(set) var store: LocalStore?
    private var schedule = AutomaticRefreshSchedule()
    private var timerTask: Task<Void, Never>?
    private var automaticTask: Task<Void, Never>?
    private var queuedManual: (() async throws -> Void)?
    private var queuedMessage = ""
    private var startupPending = true
    private var requiresConnection = false
    var objectCache: VerifiedObjectCache?
    private var summaries: [String: LibrarySummaryResult] = [:]
    private var previewIssues: [String: String] = [:]
    var openedRevision: OpenLibraryRevision?

    init(root: URL? = nil) {
        do {
            store = try LocalStore(root: root)
            automaticEnabled = try store!.automaticSettings().enabled
            pending = try store?.loadPending()
            editorJournal = try store!.editorJournal()
            editorStorageReady = true
            let local = store!.root.appendingPathComponent("oauth-desktop.json")
            let bundled = Bundle.main.url(forResource: "oauth-desktop", withExtension: "json")
            if FileManager.default.fileExists(atPath: local.path) {
                authorization.configure(try OAuthConfig.load(local))
                oauthConfigured = true
            } else if let bundled {
                authorization.configure(try OAuthConfig.load(bundled))
                oauthConfigured = true
            }
        } catch { failure = error.localizedDescription }
    }

    deinit {
        timerTask?.cancel()
        automaticTask?.cancel()
    }

    var page: NotePage? {
        guard let notebook, notebook.pages.indices.contains(pageIndex) else { return nil }
        let page = notebook.pages[pageIndex]
        return editorJournal.draft?.baseHash == notebook.sha256 ?
            editorJournal.draft!.projected(page) : page
    }
    var selectedFolder: DriveFolder? { folders.first { $0.id == selectedFolderID } }
    var hasPending: Bool { pending != nil && pending?.status != "verified-publication" }
    var nativeLibraryHeads: [LibraryHead] { libraryHeads.filter { !$0.isValidation && !$0.isFolder } }
    var folderHeads: [LibraryHead] { libraryHeads.filter(\.isFolder) }
    var validationHeads: [LibraryHead] { libraryHeads.filter(\.isValidation) }
    var decodedLibrarySummaries: [String: NoteSummary] {
        summaries.compactMapValues { result in
            if case .decoded(let summary) = result { return summary }
            return nil
        }
    }

    func rememberNotebookSummary(_ summary: NoteSummary) {
        summaries[summary.sha256] = .decoded(summary)
        if let snapshot {
            libraryHeads = LibraryIndexBuilder.heads(catalog: snapshot.catalog, summaries: summaries,
                                                     previewIssues: previewIssues)
        }
    }
    var currentScope: String? {
        guard let account, let selectedFolder else { return nil }
        return VerifiedObjectCache.scope(accountID: account.permissionID, folderID: selectedFolder.id)
    }

    func startAutomaticSync() {
        guard timerTask == nil else { return }
        if editorJournal.draft != nil { restoreEditorDraft() }
        timerTask = Task { [weak self] in
            while !Task.isCancelled {
                self?.automaticTick()
                do { try await Task.sleep(nanoseconds: 1_000_000_000) }
                catch { return }
            }
        }
    }

    func setAutomaticEnabled(_ enabled: Bool) {
        do {
            guard let store else { throw ReaderError("Local preferences are unavailable.") }
            try store.saveAutomaticSettings(AutomaticRefreshSettings(enabled: enabled))
            automaticEnabled = enabled
            automaticError = nil
            if enabled {
                schedule.requestSoon(now: Date())
                automaticStatus = "Automatic library updates enabled."
            } else {
                automaticTask?.cancel()
                automaticStatus = "Automatic library updates paused."
            }
        } catch { failure = error.localizedDescription }
    }

    private func automaticTick() {
        guard schedule.beginAutomatic(now: Date(), enabled: automaticEnabled || startupPending,
                                      eligible: oauthConfigured && store != nil && !requiresConnection) else { return }
        startupPending = false
        busy = true
        automaticTask = Task { [weak self] in
            guard let self else { return }
            var succeeded: Bool? = true
            do {
                if account == nil {
                    automaticStatus = "Reconnecting the saved Google grant…"
                    if try !authorization.hasSavedGrant() {
                        requiresConnection = true
                        automaticStatus = "Connect Google Drive once to enable automatic library updates."
                    } else {
                        try await loadConnection(allowInteraction: false)
                    }
                }
                try Task.checkCancellation()
                if automaticEnabled && account != nil && selectedFolder != nil {
                    automaticStatus = "Checking the Drive library…"
                    try await refreshLibrary(allowInteraction: false, automatic: true)
                    if !editorJournal.queue.isEmpty {
                        try await publishNextEdit(allowInteraction: false, allowConflict: false)
                    }
                }
                try Task.checkCancellation()
                automaticError = nil
                if !automaticEnabled { automaticStatus = "Connected. Automatic library updates are paused." }
            } catch {
                if Task.isCancelled {
                    succeeded = nil
                } else {
                    succeeded = false
                    handleAuthorizationError(error)
                    automaticError = error.localizedDescription
                }
            }
            let runManual = schedule.finishAutomatic(succeeded: succeeded, now: Date())
            automaticTask = nil
            if succeeded == false {
                let seconds = max(1, Int(schedule.nextAttempt.timeIntervalSinceNow.rounded(.up)))
                automaticStatus = "Library unchanged. Retrying in \(seconds) seconds."
            }
            if runManual { executeQueuedManual() }
            else { busy = false }
        }
    }

    private func handleAuthorizationError(_ error: Error) {
        if error.localizedDescription.contains("(HTTP 401)") { authorization.invalidateAccess() }
    }

    func perform(_ message: String, _ action: @escaping () async throws -> Void) {
        let request = schedule.requestManual()
        guard request != .alreadyBusy else { return }
        queuedManual = action
        queuedMessage = message
        manualBusy = true
        busy = true
        if request == .waitForAutomatic {
            automaticStatus = "Pausing the automatic check for your action…"
            automaticTask?.cancel()
        } else { executeQueuedManual() }
    }

    private func executeQueuedManual() {
        guard let action = queuedManual else { return }
        queuedManual = nil
        failure = nil
        status = queuedMessage
        Task {
            defer {
                busy = false
                manualBusy = false
                schedule.finishManual(now: Date())
            }
            do { try await action() }
            catch {
                handleAuthorizationError(error)
                failure = error.localizedDescription
                status = "Action failed. Local notebooks and conflicting revisions were preserved."
            }
        }
    }

    func chooseNotebook() {
        perform("Choosing a local notebook…") {
            let panel = NSOpenPanel()
            panel.allowedContentTypes = [UTType(filenameExtension: "note") ?? .data]
            panel.allowsMultipleSelection = false
            panel.message = "Open a native BOOX notebook export. The original file stays untouched."
            if panel.runModal() == .OK, let url = panel.url { try await self.loadLocal(url) }
        }
    }

    func open(_ url: URL, expectedHash: String? = nil, description: String? = nil) {
        perform("Decoding notebook coordinates…") {
            try await self.loadLocal(url, expectedHash: expectedHash, description: description)
        }
    }

    private func loadLocal(_ url: URL, expectedHash: String? = nil, description: String? = nil) async throws {
        try require(editorJournal.draft?.hasChanges != true, "Save or discard the current draft before opening another notebook.")
        let note = try await Task.detached { try NotebookLoader.read(url, expectedHash: expectedHash) }.value
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        let bytes = try handle.read(upToCount: 16 * 1024 * 1024 + 1) ?? Data()
        try require(bytes.count <= 16 * 1024 * 1024 && sha256(bytes) == note.sha256,
                    "The local export changed during reading or exceeds its size limit.")
        try clearCleanDraft()
        currentPayload = bytes
        currentLocalURL = url
        notebook = note
        openedRevision = nil
        libraryNotice = nil
        pageIndex = 0
        zoom = 1
        source = description ?? "Local export · \(url.lastPathComponent)"
        status = "Opened \(note.pages.count) pages and \(note.sample_count) recorded samples. Review fidelity notes below."
    }

    func openFixture() {
        perform("Opening the bundled synthetic fixture…") {
            let fixture = try BundledFixture.load("Target-after.note")
            try await self.loadLocal(fixture.url, expectedHash: fixture.hash,
                                        description: "Bundled disposable synthetic fixture · Target-after.note")
        }
    }

    func importOAuth() {
        perform("Choosing Desktop OAuth configuration…") {
            let panel = NSOpenPanel()
            panel.allowedContentTypes = [.json]
            panel.message = "Choose the Desktop OAuth client JSON for BOOX Notes Drive. User tokens remain in Keychain."
            if panel.runModal() != .OK { return }
            guard let url = panel.url, let store = self.store else { return }
            let bytes = try OAuthConfig.read(url)
            let config = try OAuthConfig.decode(bytes)
            try store.selection()?.validateConfigImport(config.clientID,
                                                        currentClientID: self.authorization.config?.clientID)
            try LocalStore.atomic(bytes, to: store.root.appendingPathComponent("oauth-desktop.json"))
            self.authorization.configure(config)
            self.oauthConfigured = true
            self.account = nil
            self.folders = []
            self.selectedFolderID = ""
            self.clearRemoteLibrary()
            self.requiresConnection = false
            self.status = "Desktop OAuth configuration imported. Reconnect the saved grant, or Connect Google Drive for first authorization."
        }
    }

    func connect() {
        perform("Waiting for Google sign-in in your default browser…") {
            try await self.authorization.connect()
            self.requiresConnection = false
            try await self.loadConnection()
            if self.automaticEnabled && self.selectedFolder != nil { try await self.refreshLibrary() }
        }
    }

    func reconnect() {
        perform("Restoring this Mac’s Google grant…") {
            self.authorization.invalidateAccess()
            self.requiresConnection = false
            try await self.loadConnection()
            if self.automaticEnabled && self.selectedFolder != nil { try await self.refreshLibrary() }
        }
    }

    private func loadConnection(allowInteraction: Bool = true) async throws {
        guard let config = authorization.config else { throw ReaderError("Import your Desktop OAuth configuration first.") }
        let client = DriveClient(token: try await authorization.token(allowInteraction: allowInteraction))
        let connection = try await DriveConnection.load(client: client, desktopClientID: config.clientID,
                                                         saved: store?.selection())
        if let selection = connection.selection { try store?.saveSelection(selection) }
        account = connection.account
        folders = connection.folders
        clearRemoteLibrary()
        selectedFolderID = connection.selection?.folder.id ?? ""
        if connection.selection != nil {
            status = "Connected with drive.file. Saved directory access verified."
        } else if folders.isEmpty {
            status = "Connected. No writable BOOX sync directory is authorized for this Desktop client."
        } else {
            status = "Connected. Choose an authorized BOOX sync directory."
        }
        automaticStatus = status
    }

    func selectFolder(_ id: String) {
        guard !id.isEmpty, selectedFolderID != id else { return }
        perform("Changing the library directory…") {
            guard let folder = self.folders.first(where: { $0.id == id }) else {
                throw ReaderError("Choose a verified Drive directory.")
            }
            try self.saveSelection(folder: folder)
            self.selectedFolderID = id
            self.clearRemoteLibrary()
            if self.automaticEnabled { try await self.refreshLibrary() }
        }
    }

    private func clearRemoteLibrary() {
        snapshot = nil
        libraryHeads = []
        objectCache = nil
    }

    private func saveSelection(folder: DriveFolder) throws {
        if let account {
            guard let config = authorization.config else { throw ReaderError("Import your Desktop OAuth configuration first.") }
            try store?.selection()?.validateClient(config.clientID)
            try store?.saveSelection(ConnectionSelection(account: account, folder: folder,
                                                         desktopClientID: config.clientID))
        }
    }

    func currentClient(allowInteraction: Bool = true) async throws -> (DriveClient, DriveAccount, DriveFolder) {
        guard let account, let selectedFolder else { throw ReaderError("Connect and select a verified Drive directory first.") }
        guard let config = authorization.config, let saved = try store?.selection() else {
            throw ReaderError("Reconnect and verify the saved Desktop library binding first.")
        }
        try saved.validateClient(config.clientID)
        try require(saved.account.permissionID == account.permissionID && saved.folder.id == selectedFolder.id,
                    "Saved account or directory changed. Reconnect; queued data will not be redirected.")
        let client = DriveClient(token: try await authorization.token(allowInteraction: allowInteraction))
        let actual = try await client.account()
        try require(actual.permissionID == account.permissionID, "Google account changed. Reconnect; queued data will not be redirected.")
        _ = try await client.folder(selectedFolder.id)
        return (client, actual, selectedFolder)
    }

    func refresh() {
        perform("Checking verified library revisions…") {
            try await self.refreshLibrary()
        }
    }

    func openRevision(_ id: String) {
        perform("Opening the verified notebook…") {
            try await self.displayRevision(id, preservePosition: false)
        }
    }

    func refreshLibrary(allowInteraction: Bool = true, automatic: Bool = false) async throws {
        guard let store else { throw ReaderError("Local storage is unavailable.") }
        let (client, account, folder) = try await currentClient(allowInteraction: allowInteraction)
        let scope = VerifiedObjectCache.scope(accountID: account.permissionID, folderID: folder.id)
        if objectCache?.scope != scope {
            objectCache = try VerifiedObjectCache(accountID: account.permissionID, folderID: folder.id,
                                                  directory: store.root)
        }
        let result = try await client.refresh(folderID: folder.id, cache: objectCache)
        try Task.checkCancellation()
        try await updateLibraryIndex(result)
        let count = Set(nativeLibraryHeads.map(\.notebookID)).count
        automaticStatus = "\(count) notebooks · \(result.reusedObjects) cached objects · \(result.downloadBytes) bytes downloaded."
        automaticError = nil
        if !automatic { status = "Drive library verified. \(result.catalog.revisions.count) immutable revisions; \(result.objects.count) objects." }
        if let openedRevision {
            if editorTool != .view || editorJournal.draft?.operations.isEmpty == false ||
                editorJournal.protects(notebookID: openedRevision.notebookID) {
                libraryNotice = "Your draft or queued edits remain open. Incoming versions are available in the library; automatic following is paused."
                return
            }
            switch AutomaticLibraryFollow.decision(open: openedRevision, scope: scope, catalog: result.catalog) {
            case .advance(let id):
                try await displayRevision(id, preservePosition: true)
            case .reviewDeletion:
                libraryNotice = "A newer revision requests deletion. Your open notebook and cached files are preserved; review the library entry."
            case .conflict:
                libraryNotice = "This notebook has conflicting heads. Choose a version from the library; the open version is preserved."
            case .preserveCurrent:
                libraryNotice = "The open version is not an ancestor of a single current head. It remains open for review."
            case .unchanged:
                libraryNotice = nil
            }
        }
    }

    func updateLibraryIndex(_ result: DriveSnapshot) async throws {
        guard let store else { throw ReaderError("Local storage is unavailable.") }
        var remaining = 24
        let started = Date()
        for id in result.catalog.heads.values.flatMap({ $0 }).sorted() {
            try Task.checkCancellation()
            guard let revision = result.catalog.revisions[id],
                  let hash = LibraryIndexBuilder.summaryPayload(for: revision, catalog: result.catalog),
                  summaries[hash] == nil, let bytes = result.catalog.payloads[hash] else { continue }
            // Large libraries populate titles in bounded batches; every verified head
            // is immediately visible even while its metadata awaits the next pass.
            if remaining == 0 || Date().timeIntervalSince(started) >= 10 { break }
            remaining -= 1
            do {
                if revision.notebook.hasPrefix("folder-") {
                    summaries[hash] = .folder(try FolderRecord.decode(bytes, notebookID: revision.notebook))
                    continue
                }
                let url = try store.cache(bytes, hash: hash)
                let summary = try await Task.detached { try NotebookLoader.summary(url, expectedHash: hash) }.value
                try Task.checkCancellation()
                summaries[hash] = .decoded(summary)
            } catch {
                try Task.checkCancellation()
                summaries[hash] = .unsupported(error.localizedDescription)
            }
        }
        try Task.checkCancellation()
        snapshot = result
        libraryHeads = LibraryIndexBuilder.heads(catalog: result.catalog, summaries: summaries,
                                                 previewIssues: previewIssues)
    }

    private func displayRevision(_ id: String, preservePosition: Bool) async throws {
        try require(editorJournal.draft?.hasChanges != true, "Save or discard the current draft before opening another revision.")
        guard let snapshot, let revision = snapshot.catalog.revisions[id], let hash = revision.payload,
              let bytes = snapshot.catalog.payloads[hash], let scope = currentScope, let store else {
            throw ReaderError("The selected head is unavailable or requests deletion. Existing local content is preserved.")
        }
        try require(!revision.notebook.hasPrefix("folder-"), "Folder metadata is shown in the library hierarchy, not the notebook canvas.")
        let note: Notebook
        if preservePosition, let current = notebook, current.sha256 == hash {
            note = current
        } else {
            do {
                let url = try store.cache(bytes, hash: hash)
                note = try await Task.detached { try NotebookLoader.read(url, expectedHash: hash) }.value
                try Task.checkCancellation()
            } catch {
                try Task.checkCancellation()
                previewIssues[hash] = error.localizedDescription
                libraryHeads = LibraryIndexBuilder.heads(catalog: snapshot.catalog, summaries: summaries,
                                                         previewIssues: previewIssues)
                libraryNotice = "The newer export is verified but its preview is unsupported. The open notebook remains unchanged."
                throw error
            }
        }
        try Task.checkCancellation()
        try clearCleanDraft()
        currentPayload = bytes
        currentLocalURL = nil
        previewIssues.removeValue(forKey: hash)
        libraryHeads = LibraryIndexBuilder.heads(catalog: snapshot.catalog, summaries: summaries,
                                                 previewIssues: previewIssues)
        let position = ReadingPosition(pageID: page?.id, pageIndex: pageIndex, zoom: zoom)
            .following(pageIDs: note.pages.map(\.id))
        notebook = note
        pageIndex = preservePosition ? position.pageIndex : 0
        zoom = preservePosition ? position.zoom : 1
        openedRevision = OpenLibraryRevision(scope: scope, notebookID: revision.notebook, revisionID: id)
        source = "Drive library · \(id.prefix(12)) · \(revision.device)"
        libraryNotice = nil
        status = preservePosition ? "Notebook updated from Drive. Page and zoom preserved." :
            "Opened \(note.pages.count) pages from the verified Drive library."
    }

    func publishFixture() {
        perform("Preparing a disposable fixture descendant…") {
            guard let store = self.store else { throw ReaderError("Local storage is unavailable.") }
            try require(!self.hasPending, "Retry the existing pending publication before creating another.")
            try require(self.editorJournal.queue.isEmpty, "Send queued notebook edits before publishing the validation fixture.")
            let (client, account, folder) = try await self.currentClient()
            let snapshot = try await client.refresh(folderID: folder.id)
            try await self.updateLibraryIndex(snapshot)
            let heads = snapshot.catalog.heads[validationNotebook] ?? []
            try require(heads.count == 1, "Validation publication requires exactly one existing head. Conflicts need explicit review.")
            try require(snapshot.catalog.revisions[heads[0]]?.deleted == false, "The validation head is a deletion request.")
            let fixture = try BundledFixture.load("B2.note")
            let bytes = fixture.bytes
            _ = try await Task.detached { try NotebookLoader.read(fixture.url, expectedHash: fixture.hash) }.value
            let revision = try Revision(notebook: validationNotebook, device: store.deviceID,
                                        parents: heads, payload: sha256(bytes))
            let pending = try PendingPublication(accountID: account.permissionID, folderID: folder.id,
                                                 deviceID: store.deviceID, revisionID: revision.id,
                                                 revisionBytes: revision.encode(), payloadBytes: bytes,
                                                 createdAt: Date(), status: "pending")
            try store.savePending(pending)
            self.pending = pending
            try await self.publishSaved()
        }
    }

    func retryPublication() {
        perform("Retrying the exact saved publication and parents…") { try await self.publishSaved() }
    }

    private func publishSaved() async throws {
        guard let store, var pending = try store.loadPending() else { throw ReaderError("No saved publication.") }
        try require(pending.status != "verified-publication", "This publication has already been verified.")
        do {
            let (client, account, folder) = try await currentClient()
            try require(pending.accountID == account.permissionID && pending.folderID == folder.id &&
                        pending.deviceID == store.deviceID, "Saved publication belongs to a different account, directory or Mac identity.")
            let revision = try Revision.decode(pending.revisionBytes)
            let before = try await client.refresh(folderID: folder.id)
            for parent in revision.parents {
                try require(before.catalog.revisions[parent]?.notebook == revision.notebook,
                            "Saved publication parent is missing or foreign; publication paused.")
            }
            status = "Publishing the saved payload, then its immutable revision…"
            _ = try await client.ensureObject(type: "payload", data: pending.payloadBytes,
                                              folderID: folder.id, verifiedSnapshot: before)
            _ = try await client.ensureObject(type: "revision", data: pending.revisionBytes,
                                              folderID: folder.id, verifiedSnapshot: before)
            let after = try await client.refresh(folderID: folder.id)
            try require(after.catalog.revisions[pending.revisionID] == revision,
                        "Publication was not visible in the verified final catalog. Retry the saved job.")
            try store.completePending(pending)
            self.pending = try store.loadPending()
            try await updateLibraryIndex(after)
            status = "Disposable fixture revision \(pending.revisionID.prefix(12)) published and verified. Android can now refresh; no native notebook was applied."
        } catch {
            pending.status = "pending-error"
            try? store.savePending(pending)
            self.pending = pending
            throw error
        }
    }

    func forgetGrant() {
        perform("Forgetting this Mac’s Google grant…") {
            try self.authorization.forget()
            self.account = nil
            self.folders = []
            self.selectedFolderID = ""
            self.clearRemoteLibrary()
            self.requiresConnection = true
            self.automaticStatus = "Connect Google Drive to resume automatic library updates."
            self.status = "This Mac’s stored Google grant was removed. Saved publications and local notebooks remain."
        }
    }
}
