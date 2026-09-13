import Foundation

struct AutomaticRefreshSettings: Codable, Equatable {
    var enabled = true
}

/// Main-actor caller owns this state; explicit times make the actual schedule testable.
struct AutomaticRefreshSchedule {
    enum Operation { case automatic, manual }
    enum ManualRequest { case start, waitForAutomatic, alreadyBusy }
    private(set) var operation: Operation?
    private(set) var manualWaiting = false
    private(set) var consecutiveFailures = 0
    private(set) var nextAttempt = Date.distantPast
    let interval: TimeInterval = 12
    let maximumBackoff: TimeInterval = 300

    mutating func beginAutomatic(now: Date, enabled: Bool, eligible: Bool) -> Bool {
        guard enabled, eligible, operation == nil, now >= nextAttempt else { return false }
        operation = .automatic
        return true
    }

    mutating func requestManual() -> ManualRequest {
        if manualWaiting || operation == .manual { return .alreadyBusy }
        if operation == .automatic {
            manualWaiting = true
            return .waitForAutomatic
        }
        operation = .manual
        return .start
    }

    /// nil means cancelled for manual work or a disabled preference, not a failed poll.
    mutating func finishAutomatic(succeeded: Bool?, now: Date) -> Bool {
        if let succeeded {
            consecutiveFailures = succeeded ? 0 : min(consecutiveFailures + 1, 8)
            let delay = min(maximumBackoff, interval * pow(2, Double(consecutiveFailures)))
            nextAttempt = now.addingTimeInterval(delay)
        }
        if manualWaiting {
            manualWaiting = false
            operation = .manual
            return true
        }
        operation = nil
        return false
    }

    mutating func finishManual(now: Date) {
        operation = nil
        nextAttempt = now.addingTimeInterval(interval)
    }

    mutating func requestSoon(now: Date) {
        consecutiveFailures = 0
        nextAttempt = now
    }
}

struct OpenLibraryRevision: Equatable {
    let scope: String
    let notebookID: String
    let revisionID: String
}

struct ReadingPosition: Equatable {
    let pageID: String?
    let pageIndex: Int
    let zoom: Double

    func following(pageIDs: [String]) -> ReadingPosition {
        let index = pageID.flatMap { pageIDs.firstIndex(of: $0) } ??
            min(max(0, pageIndex), max(0, pageIDs.count - 1))
        return ReadingPosition(pageID: pageIDs.isEmpty ? nil : pageIDs[index],
                               pageIndex: index, zoom: zoom)
    }
}

enum LibraryFollowDecision: Equatable {
    case unchanged
    case advance(String)
    case reviewDeletion(String)
    case conflict
    case preserveCurrent
}

enum AutomaticLibraryFollow {
    static func decision(open: OpenLibraryRevision, scope: String,
                         catalog: RevisionCatalog) -> LibraryFollowDecision {
        guard open.scope == scope,
              catalog.revisions[open.revisionID]?.notebook == open.notebookID else {
            return .preserveCurrent
        }
        let heads = catalog.heads[open.notebookID] ?? []
        if heads.count > 1 { return .conflict }
        guard let head = heads.first, let revision = catalog.revisions[head] else { return .preserveCurrent }
        if head == open.revisionID { return .unchanged }
        guard catalog.isAncestor(open.revisionID, of: head) else { return .preserveCurrent }
        return revision.deleted ? .reviewDeletion(head) : .advance(head)
    }
}

struct NoteSummary: Codable {
    let title: String
    let document_id: String
    let sha256: String
    let byte_count: Int
    let page_count: Int
    let page_ids: [String]
    var parent_id: String? = nil
}

struct LibraryHead: Identifiable {
    let id: String
    let notebookID: String
    let revision: Revision
    let title: String
    let pageCount: Int?
    let issue: String?
    let isConflict: Bool
    var parentID: String? = nil
    var folder: FolderRecord? = nil
    var isFolder: Bool { notebookID.hasPrefix("folder-") }
    var isValidation: Bool { !isFolder && (notebookID == validationNotebook || title.hasPrefix("GDrive-Sync-Probe")) }
}

enum LibrarySummaryResult {
    case decoded(NoteSummary)
    case folder(FolderRecord)
    case unsupported(String)
}

enum LibraryIndexBuilder {
    static func summaryPayload(for revision: Revision, catalog: RevisionCatalog) -> String? {
        if let payload = revision.payload { return payload }
        var pending = revision.parents
        var seen = Set<String>()
        while !pending.isEmpty {
            let id = pending.removeFirst()
            guard seen.insert(id).inserted, let ancestor = catalog.revisions[id] else { continue }
            if let payload = ancestor.payload { return payload }
            pending.append(contentsOf: ancestor.parents)
        }
        return nil
    }

    static func heads(catalog: RevisionCatalog, summaries: [String: LibrarySummaryResult],
                      previewIssues: [String: String] = [:]) -> [LibraryHead] {
        var result: [LibraryHead] = []
        for (notebookID, heads) in catalog.heads {
            for id in heads {
                guard let revision = catalog.revisions[id] else { continue }
                let hash = summaryPayload(for: revision, catalog: catalog)
                var title = notebookID
                var pages: Int?
                var parentID: String?
                var folder: FolderRecord?
                var issue = hash.flatMap { previewIssues[$0] }
                if let hash, let summary = summaries[hash] {
                    switch summary {
                    case .decoded(let note):
                        title = note.title
                        pages = note.page_count
                        parentID = note.parent_id
                    case .folder(let record):
                        if notebookID == "folder-" + record.id {
                            title = record.title
                            parentID = record.parent
                            folder = record
                        } else { issue = "Folder payload identity does not match its revision." }
                    case .unsupported(let message): issue = message
                    }
                }
                result.append(LibraryHead(id: id, notebookID: notebookID, revision: revision,
                                          title: title, pageCount: pages, issue: issue,
                                          isConflict: heads.count > 1, parentID: parentID, folder: folder))
            }
        }
        return result.sorted {
            if $0.title == $1.title { return $0.id < $1.id }
            return $0.title.localizedStandardCompare($1.title) == .orderedAscending
        }
    }
}
