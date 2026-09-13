import AppKit
import SwiftUI

struct PaperView: NSViewRepresentable {
    let page: NotePage
    let showHidden: Bool
    let tool: EditorTool
    let penWidth: Double
    let penColor: UInt32
    let layer: Int
    let enabled: Bool
    let completed: ([[Double]], String, EditorTool) -> Void
    func makeNSView(context: Context) -> PaperNSView { PaperNSView() }
    func updateNSView(_ nsView: PaperNSView, context: Context) {
        nsView.page = page
        nsView.showHidden = showHidden
        nsView.tool = tool
        nsView.penWidth = penWidth
        nsView.penColor = penColor
        nsView.editorLayer = layer
        nsView.editEnabled = enabled
        nsView.completed = completed
        nsView.needsDisplay = true
    }
}

final class PaperNSView: NSView {
    var page: NotePage?
    var showHidden = false
    var tool = EditorTool.view
    var penWidth = 4.0
    var penColor: UInt32 = 0xff1756aa
    var editorLayer = 0
    var editEnabled = false
    var completed: (([[Double]], String, EditorTool) -> Void)?
    private var samples: [[Double]] = []
    private var gesturePage: String?
    private var gestureTool = EditorTool.view
    override var isFlipped: Bool { true }
    override var acceptsFirstResponder: Bool { true }
    override func draw(_ dirtyRect: NSRect) {
        guard let page, let context = NSGraphicsContext.current?.cgContext else { return }
        var shown = page
        if gesturePage == page.id, gestureTool == .pen, !samples.isEmpty {
            let stroke = NoteStroke(id: "drawing", type: 2, layer: editorLayer, width: penWidth, color: penColor,
                                    supported: true, points: samples, point_hash: nil, sample_count: samples.count,
                                    bounds: nil, label: "", target_page: nil)
            shown = NotePage(id: page.id, width: page.width, height: page.height, layers: page.layers,
                             strokes: page.strokes + [stroke], warnings: page.warnings)
        }
        PaperRenderer.draw(shown, in: context, scale: bounds.width / page.width, showHidden: showHidden)
    }
    private func point(_ event: NSEvent) -> [Double]? {
        guard let page, bounds.width > 0 else { return nil }
        let location = convert(event.locationInWindow, from: nil)
        let scale = page.width / bounds.width
        return [max(0, min(page.width, location.x * scale)), max(0, min(page.height, location.y * scale))]
    }
    override func mouseDown(with event: NSEvent) {
        guard editEnabled, tool != .view, let page, let p = point(event) else { super.mouseDown(with: event); return }
        gesturePage = page.id
        gestureTool = tool
        samples = [p]
        needsDisplay = true
    }
    override func mouseDragged(with event: NSEvent) {
        guard gesturePage == page?.id, samples.count < 5000, let p = point(event) else { return }
        if let last = samples.last, hypot(last[0]-p[0], last[1]-p[1]) < 0.5 { return }
        samples.append(p)
        needsDisplay = true
    }
    override func mouseUp(with event: NSEvent) {
        guard let id = gesturePage else { return }
        defer { samples = []; gesturePage = nil; needsDisplay = true }
        guard id == page?.id else { return }
        if gestureTool == .pen && samples.count == 1, let page {
            let p = samples[0]
            samples.append([p[0] < page.width ? min(page.width, p[0]+0.5) : max(0, p[0]-0.5), p[1]])
        }
        completed?(samples, id, gestureTool)
    }
}

struct ReaderView: View {
    @ObservedObject var model: ReaderModel
    @State private var showInspector = true

    var body: some View {
        HSplitView {
            sidebar.frame(minWidth: 260, idealWidth: 290, maxWidth: 370)
            VStack(spacing: 0) {
                viewerToolbar
                editorToolbar
                Divider()
                if let notice = model.libraryNotice {
                    Label(notice, systemImage: "exclamationmark.triangle")
                        .font(.callout).foregroundStyle(.orange)
                        .padding(12).frame(maxWidth: .infinity, alignment: .leading)
                    Divider()
                }
                if let page = model.page {
                    GeometryReader { geometry in
                        let fit = max(0.02, min((geometry.size.width - 52) / page.width,
                                                (geometry.size.height - 48) / page.height))
                        let width = page.width * fit * model.zoom
                        ScrollView([.horizontal, .vertical]) {
                            PaperView(page: page, showHidden: model.showHidden,
                                      tool: model.editorTool, penWidth: model.penWidth, penColor: model.penColor,
                                      layer: model.editLayer, enabled: !model.manualBusy,
                                      completed: model.acceptGesture)
                                .frame(width: width, height: width * page.height / page.width)
                                .shadow(color: .black.opacity(0.18), radius: 7, y: 2)
                                .padding(24)
                                .frame(minWidth: geometry.size.width, minHeight: geometry.size.height)
                        }
                        .background(Color(nsColor: .underPageBackgroundColor))
                    }
                } else {
                    VStack(spacing: 18) {
                        Image(systemName: "book.pages").font(.system(size: 54)).foregroundStyle(.secondary)
                        Text("BOOX Notes Reader").font(.title)
                        Text("Choose a notebook from your Drive library.\nNew BOOX revisions appear automatically while this app is open.")
                            .multilineTextAlignment(.center).foregroundStyle(.secondary)
                        HStack {
                            Button("Open notebook…") { model.chooseNotebook() }
                        }
                    }.frame(maxWidth: .infinity, maxHeight: .infinity)
                }
                Divider()
                HStack(alignment: .top, spacing: 10) {
                    if model.busy { ProgressView().controlSize(.small) }
                    Image(systemName: model.failure == nil ? "info.circle" : "exclamationmark.triangle")
                        .foregroundStyle(model.failure == nil ? Color.secondary : Color.orange)
                    Text(model.failure ?? model.status).font(.callout).textSelection(.enabled)
                    Spacer(minLength: 0)
                }.padding(12).frame(minHeight: 50)
            }.frame(minWidth: 500)
            if showInspector {
                inspector.frame(minWidth: 250, idealWidth: 280, maxWidth: 350)
            }
        }
        .frame(minWidth: 1060, minHeight: 720)
        .toolbar {
            ToolbarItemGroup {
                Button { model.chooseNotebook() } label: { Label("Open notebook", systemImage: "folder") }
                    .keyboardShortcut("o").disabled(model.manualBusy)
                Button { showInspector.toggle() } label: { Label("Notebook information", systemImage: "sidebar.right") }
            }
        }
        .onOpenURL { model.open($0) }
        .onAppear { model.startAutomaticSync() }
    }

    private var sidebar: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text("BOOX NOTES").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                Text("Your library").font(.title2.weight(.semibold))
                Toggle("Automatic library updates", isOn: Binding(
                    get: { model.automaticEnabled }, set: { model.setAutomaticEnabled($0) }))
                    .toggleStyle(.switch)
                Text(model.automaticStatus).font(.caption).foregroundStyle(.secondary)
                if let error = model.automaticError {
                    Label(error, systemImage: "exclamationmark.triangle")
                        .font(.caption).foregroundStyle(.orange).textSelection(.enabled)
                }
                if let snapshot = model.snapshot {
                    Text("Checked \(snapshot.verifiedAt.formatted(date: .omitted, time: .standard))")
                        .font(.caption2).foregroundStyle(.secondary)
                }
                Divider()
                if model.snapshot != nil {
                    Label("Folders", systemImage: "folder").font(.headline)
                    Button("New folder…") { model.changeFolder() }
                        .disabled(!model.editorStorageReady)
                    ForEach(model.folderHeads) { head in libraryRow(head) }
                    if !model.queuedFolderChanges.isEmpty {
                        Text("Folder changes saved locally").font(.caption.weight(.semibold))
                        ForEach(model.queuedFolderChanges, id: \.revisionID) { job in queuedFolderRow(job) }
                    }
                    Divider()
                }
                Label("Native notebooks", systemImage: "books.vertical").font(.headline)
                Button("New notebook…") { model.changeNotebook() }
                    .disabled(model.snapshot == nil || !model.editorStorageReady)
                if model.nativeLibraryHeads.isEmpty {
                    Text(model.snapshot == nil ? "Connecting to your saved Drive library…" :
                        "No native notebooks have arrived yet. BOOX exports will appear here automatically.")
                        .font(.callout).foregroundStyle(.secondary)
                }
                ForEach(model.nativeLibraryHeads) { head in libraryRow(head) }
                Divider()
                Label("Saved changes", systemImage: "square.and.arrow.up").font(.headline)
                Text(model.queueStatus).font(.caption).textSelection(.enabled)
                ForEach(model.queuedNotebookChanges, id: \.revisionID) { job in queuedNotebookRow(job) }
                if let id = model.queuedNotebookID {
                    Text("Next: \(id)").font(.caption2.monospaced()).textSelection(.enabled)
                }
                Text("Drafts survive closing the app. Save to library queues an immutable native snapshot; automatic updates send saved edits.")
                    .font(.caption).foregroundStyle(.secondary)
                if model.draftChanged {
                    Label("Draft has unsaved library changes", systemImage: "pencil").font(.caption)
                }
                Button("Send next queued edit") { model.sendQueuedEdits() }
                    .disabled(model.editorJournal.queue.isEmpty || model.manualBusy)
                Button("Open latest queued snapshot") { model.openLatestQueuedEdit() }
                    .disabled(!model.hasQueuedNotebook || model.manualBusy || model.draftChanged)
                if model.editorJournal.queue.first?.status == "conflict-review" {
                    Text("Remote changes exist. This branch keeps its original parent; publishing preserves both conflicting heads.")
                        .font(.caption).foregroundStyle(.orange)
                    Button("Review first queued change") { model.openLatestQueuedEdit(first: true) }
                        .disabled(model.manualBusy || model.draftChanged)
                    Button("Publish saved branch with conflict") { model.sendQueuedEdits(allowConflict: true) }
                        .disabled(model.manualBusy)
                }
                Divider()
                Button("Open local .note file…") { model.chooseNotebook() }
                DisclosureGroup("Google Drive connection") {
                    VStack(alignment: .leading, spacing: 10) {
                        Text(model.account?.email ?? "Authorize this Mac independently.")
                            .font(.callout).foregroundStyle(.secondary).textSelection(.enabled)
                        if !model.oauthConfigured {
                            Text("A Desktop OAuth configuration is required.").font(.caption)
                        }
                        Button("Connect Google Drive…") { model.connect() }.disabled(!model.oauthConfigured)
                        Button("Reconnect saved grant") { model.reconnect() }.disabled(!model.oauthConfigured)
                        Button("Import OAuth configuration…") { model.importOAuth() }
                        if !model.folders.isEmpty {
                            Picker("Directory", selection: Binding(
                                get: { model.selectedFolderID }, set: { model.selectFolder($0) })) {
                                Text("Choose a directory").tag("")
                                ForEach(model.folders) { folder in
                                    Text("\(folder.name) · \(folder.id.prefix(6))").tag(folder.id)
                                }
                            }
                            Text(model.selectedFolderID).font(.caption2.monospaced()).textSelection(.enabled)
                            Button("Check library now") { model.refresh() }
                        }
                        Button("Forget this Mac’s Google grant") { model.forgetGrant() }
                    }
                    .padding(.top, 8)
                }
                Divider()
                DisclosureGroup("Disposable validation") {
                    VStack(alignment: .leading, spacing: 12) {
                        Button("Open bundled test notebook") { model.openFixture() }
                        ForEach(model.validationHeads) { head in libraryRow(head) }
                        Text("Publishes the existing B2 test fixture as a descendant of the single validation head. This does not edit a native notebook.")
                        .font(.caption).foregroundStyle(.secondary)
                        Button("Publish B2 fixture descendant") { model.publishFixture() }
                            .disabled(model.selectedFolder == nil || model.hasPending ||
                                      model.snapshot?.catalog.heads[validationNotebook]?.count != 1)
                        if let pending = model.pending {
                            Text("Publication: \(pending.status)").font(.caption).textSelection(.enabled)
                            Text(String(pending.revisionID.prefix(16))).font(.caption2.monospaced())
                        }
                        Button("Retry saved publication") { model.retryPublication() }
                            .disabled(!model.hasPending || model.selectedFolder == nil)
                    }.padding(.top, 8)
                }
                Divider()
                Text("Native pen editor. Originals stay intact; conflicting and deleted heads require review.")
                    .font(.caption).foregroundStyle(.secondary)
            }.buttonStyle(.bordered).padding(18).disabled(model.manualBusy)
        }.background(Color(nsColor: .controlBackgroundColor))
    }

    private var editorToolbar: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Picker("Tool", selection: Binding(get: { model.editorTool }, set: { model.setEditorTool($0) })) {
                    ForEach(EditorTool.allCases, id: \.self) { Text($0.rawValue).tag($0) }
                }.pickerStyle(.segmented).frame(width: 220)
                Picker("Layer", selection: $model.editLayer) {
                    ForEach(model.writableLayers) { Text("Layer \($0.id)").tag($0.id) }
                }.frame(width: 115)
                Picker("Ink", selection: $model.penColor) {
                    Text("Black").tag(UInt32(0xff000000))
                    Text("Blue").tag(UInt32(0xff1756aa))
                    Text("Red").tag(UInt32(0xffba2525))
                }.frame(width: 100)
                Slider(value: $model.penWidth, in: 1...20, step: 1).frame(width: 70)
                Text("\(Int(model.penWidth))").font(.caption.monospacedDigit())
            }
            HStack {
                Button("Undo") { model.undoEdit() }
                    .disabled(model.editorJournal.draft?.canUndo != true).keyboardShortcut("z")
                Button("Redo") { model.undoEdit(redo: true) }
                    .disabled(model.editorJournal.draft?.canRedo != true).keyboardShortcut("z", modifiers: [.command, .shift])
                Button("Save to library") { model.saveDraftToLibrary() }
                    .disabled(!model.draftChanged).keyboardShortcut("s")
                Button("Export .note copy…") { model.exportDraftCopy() }
                    .disabled(model.editorJournal.draft == nil)
                Button("Discard draft") { model.discardDraft() }
                    .disabled(model.editorJournal.draft == nil)
                Spacer(minLength: 0)
            }
            if model.editorTool != .view {
                Text("Normal pens on visible unlocked layers. Erase removes a whole pen stroke. Other native content is retained.")
                    .font(.caption2).foregroundStyle(.secondary)
            }
        }.padding(.horizontal, 12).padding(.bottom, 10)
            .disabled(model.notebook == nil || model.manualBusy || !model.editorStorageReady)
    }

    private func libraryRow(_ head: LibraryHead) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(head.title).font(.headline).textSelection(.enabled)
            if let path = FolderHierarchy.path(parentID: head.parentID, heads: model.folderHeads) {
                Text(path).font(.caption).foregroundStyle(.secondary)
            }
            if let count = head.pageCount {
                Text("\(count) \(count == 1 ? "page" : "pages")").font(.caption).foregroundStyle(.secondary)
            }
            if head.isConflict {
                Label("Conflict — retained version", systemImage: "arrow.triangle.branch")
                    .font(.caption).foregroundStyle(.orange)
            }
            if let issue = head.issue {
                Text("Preview: \(issue)").font(.caption).foregroundStyle(.orange)
            }
            if head.revision.deleted {
                Label("Deletion requested — review required", systemImage: "exclamationmark.triangle")
                    .font(.caption).foregroundStyle(.orange)
                Text("Local copies are preserved.").font(.caption)
                if head.isFolder {
                    Button("Restore folder…") { model.changeFolder(head.notebookID, restore: true) }
                        .disabled(head.isConflict || !model.editorStorageReady)
                } else if head.notebookID.hasPrefix("boox-") {
                    Button("Restore notebook…") { model.changeNotebook(head.notebookID, restore: true) }
                        .disabled(head.isConflict || !model.editorStorageReady)
                }
            } else if !head.isFolder {
                Button("Open notebook") { model.openRevision(head.id) }
                if head.notebookID.hasPrefix("boox-") {
                    Button("Rename or move…") { model.changeNotebook(head.notebookID) }
                        .disabled(head.isConflict || !model.editorStorageReady)
                    Button("Request deletion…") { model.requestNotebookDeletion(head.notebookID) }
                        .disabled(head.isConflict || !model.editorStorageReady)
                }
            } else if head.folder != nil {
                Button("Rename or move…") { model.changeFolder(head.notebookID) }
                    .disabled(head.isConflict || !model.editorStorageReady)
                Button("Request deletion…") { model.requestFolderDeletion(head.notebookID) }
                    .disabled(head.isConflict || !model.editorStorageReady)
            }
            Text("\(head.id.prefix(12)) · \(head.revision.device.prefix(22))")
                .font(.caption2.monospaced()).foregroundStyle(.secondary).textSelection(.enabled)
        }
        .padding(11).frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.primary.opacity(0.04), in: RoundedRectangle(cornerRadius: 8))
    }

    private func queuedNotebookRow(_ job: PendingPublication) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            let revision = try? job.checkedRevision()
            let summary = revision?.payload.flatMap { model.decodedLibrarySummaries[$0] }
            Text(summary?.title ?? revision?.notebook ?? "Notebook change").font(.callout.weight(.medium))
            Text("\(job.revisionID.prefix(12)) · \(job.status)")
                .font(.caption2.monospaced()).textSelection(.enabled)
            Button(revision?.deleted == true ? "Review deletion" : "Open saved notebook") {
                model.openQueuedNotebook(job)
            }.disabled(model.draftChanged)
            if let revision, model.isLatestQueuedFolder(job) {
                Button(revision.deleted ? "Restore notebook…" : "Rename or move…") {
                    model.changeNotebook(revision.notebook, restore: revision.deleted)
                }
                if !revision.deleted {
                    Button("Request deletion…") { model.requestNotebookDeletion(revision.notebook) }
                }
            }
        }.padding(8).frame(maxWidth: .infinity, alignment: .leading)
            .background(Color.orange.opacity(0.06), in: RoundedRectangle(cornerRadius: 8))
    }

    private func queuedFolderRow(_ job: PendingPublication) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            let revision = try? Revision.decode(job.revisionBytes)
            let record = revision.flatMap { try? FolderRecord.decode(job.payloadBytes, notebookID: $0.notebook) }
            Text(record?.title ?? revision?.notebook ?? "Folder change").font(.callout.weight(.medium))
            Text("\(job.revisionID.prefix(12)) · \(job.status)")
                .font(.caption2.monospaced()).textSelection(.enabled)
            Button("Review saved change") { model.reviewQueuedFolder(job) }
            if let revision, model.isLatestQueuedFolder(job) {
                Button(revision.deleted ? "Restore folder…" : "Edit folder…") {
                    model.changeFolder(revision.notebook, restore: revision.deleted)
                }
                if !revision.deleted {
                    Button("Request deletion…") { model.requestFolderDeletion(revision.notebook) }
                }
            }
        }.padding(8).frame(maxWidth: .infinity, alignment: .leading)
            .background(Color.orange.opacity(0.06), in: RoundedRectangle(cornerRadius: 8))
    }

    private var viewerToolbar: some View {
        HStack {
            VStack(alignment: .leading, spacing: 3) {
                Text(model.notebook?.title ?? "Notebook preview").font(.headline).lineLimit(1)
                Text(model.source).font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer()
            Button { model.pageIndex -= 1 } label: { Image(systemName: "chevron.left") }
                .disabled(model.pageIndex <= 0)
            Text("\(model.notebook == nil ? 0 : model.pageIndex + 1) / \(model.notebook?.pages.count ?? 0)")
                .monospacedDigit().frame(minWidth: 50)
            Button { model.pageIndex += 1 } label: { Image(systemName: "chevron.right") }
                .disabled(model.pageIndex + 1 >= (model.notebook?.pages.count ?? 0))
            Divider().frame(height: 20)
            Button("−") { model.zoom = max(0.5, model.zoom / 1.25) }.help("Zoom out")
            Button("\(Int(model.zoom * 100))%") { model.zoom = 1 }.help("Fit page")
            Button("+") { model.zoom = min(8, model.zoom * 1.25) }.help("Zoom in")
        }.padding(12)
    }

    private var inspector: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Label("Notebook information", systemImage: "info.circle").font(.headline)
                if let note = model.notebook {
                    Text(note.title).font(.title3)
                    Text("Notebook ID").font(.caption.weight(.semibold))
                    Text(note.document_id).font(.caption.monospaced()).textSelection(.enabled)
                    Text("SHA-256").font(.caption.weight(.semibold))
                    Text(note.sha256).font(.caption2.monospaced()).textSelection(.enabled)
                    Text("\(note.byte_count) bytes · \(note.sample_count) recorded samples").font(.caption)
                    if let page = model.page {
                        Divider()
                        Text("Page \(model.pageIndex + 1)").font(.headline)
                        Text("\(Int(page.width)) × \(Int(page.height)) · \(page.penCount) rendered pen strokes").font(.caption)
                        Text(page.id).font(.caption2.monospaced()).textSelection(.enabled)
                        Text("Layers").font(.headline)
                        ForEach(page.layers) { layer in
                            Label("Layer \(layer.id) · \(layer.visible ? "visible" : "hidden")\(layer.locked ? " · locked" : "")",
                                  systemImage: layer.visible ? "eye" : "eye.slash").font(.caption)
                        }
                        Toggle("Inspect hidden layers", isOn: $model.showHidden).font(.caption)
                        let links = page.strokes.filter { $0.type == 33 }
                        ForEach(links) { link in
                            if let target = link.target_page, let index = note.pages.firstIndex(where: { $0.id == target }) {
                                Button("Follow internal link → page \(index + 1)") { model.pageIndex = index }
                            } else { Text(link.label).font(.caption).foregroundStyle(.orange) }
                        }
                        Divider()
                        Label("Preview limitations", systemImage: "exclamationmark.triangle").font(.headline).foregroundStyle(.orange)
                        ForEach(note.warnings + page.warnings, id: \.self) { warning in
                            Text(warning).font(.caption).fixedSize(horizontal: false, vertical: true)
                        }
                    }
                } else {
                    Text("Page dimensions, layers, archive checksum and unsupported content appear here.")
                        .font(.callout).foregroundStyle(.secondary)
                }
            }.padding(18).frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

@main
struct BOOXNotesReaderApp: App {
    @StateObject private var model: ReaderModel

    init() {
        let arguments = CommandLine.arguments
        if arguments.dropFirst().first?.hasPrefix("--validate-probe") == true {
            Task { @MainActor in await ProbeCLI.main(Array(arguments.dropFirst())) }
            dispatchMain() // Exit before creating ReaderModel, UI or live LocalStore.
        }
        if arguments.count == 5 && arguments[1] == "--edit" {
            do {
                let result = try NativeNoteEditor.command(input: URL(fileURLWithPath: arguments[2]),
                                                          plan: URL(fileURLWithPath: arguments[3]),
                                                          output: URL(fileURLWithPath: arguments[4]))
                FileHandle.standardOutput.write(result)
                exit(0)
            } catch {
                FileHandle.standardError.write(Data((error.localizedDescription + "\n").utf8))
                exit(1)
            }
        }
        if arguments.count == 2 && arguments[1] == "--connect-url" {
            Task { @MainActor in
                do {
                    let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
                    let imported = support.appendingPathComponent("BOOX Notes Reader/oauth-desktop.json")
                    let configURL: URL
                    if FileManager.default.fileExists(atPath: imported.path) { configURL = imported }
                    else if let bundled = Bundle.main.url(forResource: "oauth-desktop", withExtension: "json") { configURL = bundled }
                    else { throw ReaderError("Desktop OAuth configuration is missing.") }
                    let authorization = GoogleAuthorization()
                    authorization.configure(try OAuthConfig.load(configURL))
                    try await authorization.connect { url in
                        FileHandle.standardOutput.write(Data((url.absoluteString + "\n").utf8))
                    }
                    FileHandle.standardError.write(Data("Google grant stored in this Mac’s Keychain. Reconnect saved grant in the app.\n".utf8))
                    exit(0)
                } catch {
                    FileHandle.standardError.write(Data((error.localizedDescription + "\n").utf8))
                    exit(1)
                }
            }
            dispatchMain()
        }
        if arguments.count >= 3 && ["--inspect", "--render"].contains(arguments[1]) {
            do {
                let note = try NotebookLoader.read(URL(fileURLWithPath: arguments[2]))
                if arguments[1] == "--render" {
                    try require(arguments.count >= 4, "Usage: --render notebook.note output-prefix")
                    for (index, page) in note.pages.enumerated() {
                        try PaperRenderer.png(page, to: URL(fileURLWithPath: arguments[3] + "-page-\(index+1).png"))
                    }
                }
                let result: [String: Any] = [
                    "title": note.title, "sha256": note.sha256, "pages": note.pages.count,
                    "samples": note.sample_count, "pen_strokes": note.pages.map(\.penCount),
                    "layers": note.pages.map { $0.layers.count },
                    "limitations": note.warnings
                ]
                let bytes = try JSONSerialization.data(withJSONObject: result, options: [.prettyPrinted, .sortedKeys])
                FileHandle.standardOutput.write(bytes + Data("\n".utf8))
                exit(0)
            } catch {
                FileHandle.standardError.write(Data((error.localizedDescription + "\n").utf8))
                exit(1)
            }
        }
        _model = StateObject(wrappedValue: ReaderModel())
    }

    var body: some Scene {
        WindowGroup("BOOX Notes Reader") { ReaderView(model: model) }
            .defaultSize(width: 1280, height: 850)
            .commands {
                CommandGroup(replacing: .newItem) {
                    Button("Open notebook…") { model.chooseNotebook() }.keyboardShortcut("o")
                    Button("Open test fixture") { model.openFixture() }
                }
            }
    }
}
