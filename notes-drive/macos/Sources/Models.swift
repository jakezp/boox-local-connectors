import AppKit
import CryptoKit
import Foundation

struct ReaderError: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}

func require(_ condition: Bool, _ message: String) throws {
    if !condition { throw ReaderError(message) }
}

func sha256(_ data: Data) -> String {
    SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
}

func md5(_ data: Data) -> String {
    Insecure.MD5.hash(data: data).map { String(format: "%02x", $0) }.joined()
}

func matches(_ value: String, _ pattern: String) -> Bool {
    value.range(of: pattern, options: .regularExpression) != nil
}

struct NoteLayer: Decodable, Identifiable {
    let id: Int
    let visible: Bool
    let locked: Bool
}

struct NoteStroke: Decodable, Identifiable {
    let id: String
    let type: Int
    let layer: Int
    let width: Double
    let color: UInt32
    let supported: Bool
    let points: [[Double]]
    let point_hash: String?
    let sample_count: Int
    let bounds: [Double]?
    let label: String
    let target_page: String?
}

struct NotePage: Decodable, Identifiable {
    let id: String
    let width: Double
    let height: Double
    let layers: [NoteLayer]
    let strokes: [NoteStroke]
    let warnings: [String]
    var penCount: Int { strokes.filter(\.supported).count }
}

struct Notebook: Decodable {
    let title: String
    let document_id: String
    let sha256: String
    let byte_count: Int
    let sample_count: Int
    let warnings: [String]
    let pages: [NotePage]
}

struct BundledFixture {
    let url: URL
    let bytes: Data
    var hash: String { sha256(bytes) }

    static func load(_ name: String, resources: URL? = Bundle.main.resourceURL) throws -> BundledFixture {
        let profiles = ["Target-after.note": "two-page.note", "B2.note": "two-page-variant.note"]
        guard let profile = profiles[name], let resources else {
            throw ReaderError("Unknown or missing bundled synthetic fixture.")
        }
        let manifestURL = resources.appendingPathComponent("synthetic-resources.json")
        let manifestBytes = try boundedFile(manifestURL, limit: 16384)
        let manifest = try JSONDecoder().decode(FixtureManifest.self, from: manifestBytes)
        try require(manifest.schema == 1 && manifest.synthetic_only &&
                    Set(manifest.resources.keys) == Set(profiles.keys),
                    "Invalid bundled synthetic fixture manifest.")
        guard let entry = manifest.resources[name], entry.profile == profile,
              entry.bytes > 0, entry.bytes <= 4 * 1024 * 1024,
              matches(entry.sha256, "^[a-f0-9]{64}$") else {
            throw ReaderError("Invalid bundled synthetic fixture metadata.")
        }
        let url = resources.appendingPathComponent(name)
        let bytes = try boundedFile(url, limit: entry.bytes)
        try require(bytes.count == entry.bytes && sha256(bytes) == entry.sha256,
                    "Bundled synthetic fixture checksum/size mismatch.")
        return BundledFixture(url: url, bytes: bytes)
    }

    private static func boundedFile(_ url: URL, limit: Int) throws -> Data {
        let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? Int.max
        try require(size <= limit, "Bundled fixture resource exceeds its limit.")
        let file = try FileHandle(forReadingFrom: url)
        defer { try? file.close() }
        let bytes = try file.read(upToCount: limit + 1) ?? Data()
        try require(bytes.count <= limit, "Bundled fixture resource exceeds its limit.")
        return bytes
    }

    private struct FixtureManifest: Decodable {
        let schema: Int
        let synthetic_only: Bool
        let resources: [String: Entry]
        struct Entry: Decodable {
            let profile: String
            let sha256: String
            let bytes: Int
        }
    }
}

enum NotebookLoader {
    static func read(_ url: URL, expectedHash: String? = nil) throws -> Notebook {
        let bytes = try decode(url, expectedHash: expectedHash)
        let result = try JSONDecoder().decode(Notebook.self, from: bytes)
        if let expectedHash {
            try require(result.sha256 == expectedHash, "Notebook changed or its checksum does not match.")
        }
        return result
    }

    static func summary(_ url: URL, expectedHash: String) throws -> NoteSummary {
        let bytes = try decode(url, expectedHash: expectedHash, metadataOnly: true)
        let result = try JSONDecoder().decode(NoteSummary.self, from: bytes)
        try require(result.sha256 == expectedHash, "Notebook summary checksum mismatch.")
        return result
    }

    private static func decode(_ url: URL, expectedHash: String?, metadataOnly: Bool = false) throws -> Data {
        let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? Int.max
        try require(size <= 16 * 1024 * 1024, "Notebook exceeds the 16 MiB preview limit.")
        guard let script = Bundle.main.url(forResource: "note_reader", withExtension: "py") else {
            throw ReaderError("Bundled notebook decoder is missing. Rebuild the app.")
        }
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/python3")
        process.arguments = [script.path, url.path] + (expectedHash.map { [$0] } ?? []) +
            (metadataOnly ? ["--metadata"] : [])
        process.environment = ["PATH": "/usr/bin:/bin", "PYTHONDONTWRITEBYTECODE": "1",
                               "PYTHONIOENCODING": "utf-8", "LC_CTYPE": "UTF-8"]
        let output = Pipe()
        process.standardOutput = output
        process.standardError = FileHandle.nullDevice
        try process.run()
        let timeout = DispatchWorkItem { if process.isRunning { process.terminate() } }
        DispatchQueue.global().asyncAfter(deadline: .now() + 25, execute: timeout)
        defer { timeout.cancel() }
        var bytes = Data()
        while let part = try output.fileHandleForReading.read(upToCount: 65536), !part.isEmpty {
            bytes.append(part)
            if bytes.count > (metadataOnly ? 1024 * 1024 : 32 * 1024 * 1024) {
                process.terminate()
                process.waitUntilExit()
                throw ReaderError("Decoded notebook exceeds the preview limit.")
            }
        }
        process.waitUntilExit()
        if process.terminationStatus != 0 {
            let error = (try? JSONSerialization.jsonObject(with: bytes)) as? [String: String]
            throw ReaderError(error?["error"] ?? "The notebook decoder failed or timed out.")
        }
        return bytes
    }
}

enum PaperRenderer {
    static func draw(_ page: NotePage, in context: CGContext, scale: Double,
                     showHidden: Bool = false) {
        context.saveGState()
        defer { context.restoreGState() }
        context.scaleBy(x: scale, y: scale)
        context.setFillColor(NSColor.white.cgColor)
        context.fill(CGRect(x: 0, y: 0, width: page.width, height: page.height))
        context.clip(to: CGRect(x: 0, y: 0, width: page.width, height: page.height))
        let hidden = Set(page.layers.filter { !$0.visible }.map(\.id))
        let order = Dictionary(uniqueKeysWithValues: page.layers.enumerated().map { ($1.id, $0) })
        let strokes = page.strokes.enumerated().sorted {
            let a = order[$0.element.layer] ?? Int.max
            let b = order[$1.element.layer] ?? Int.max
            return a == b ? $0.offset < $1.offset : a < b
        }
        for (_, stroke) in strokes where showHidden || !hidden.contains(stroke.layer) {
            if stroke.supported, let first = stroke.points.first {
                let color = stroke.color
                context.setStrokeColor(CGColor(
                    red: CGFloat((color >> 16) & 255) / 255,
                    green: CGFloat((color >> 8) & 255) / 255,
                    blue: CGFloat(color & 255) / 255,
                    alpha: CGFloat((color >> 24) & 255) / 255))
                context.setLineWidth(max(stroke.width, 0.5))
                context.setLineCap(.round)
                context.setLineJoin(.round)
                context.beginPath()
                context.move(to: CGPoint(x: first[0], y: first[1]))
                for point in stroke.points.dropFirst() {
                    context.addLine(to: CGPoint(x: point[0], y: point[1]))
                }
                if stroke.points.count == 1 {
                    context.addLine(to: CGPoint(x: first[0] + 0.01, y: first[1]))
                }
                context.strokePath()
            } else if let b = stroke.bounds, b.count == 4 {
                context.saveGState()
                context.setStrokeColor(NSColor.systemOrange.cgColor)
                context.setLineWidth(2 / scale)
                context.setLineDash(phase: 0, lengths: [8 / scale, 5 / scale])
                context.stroke(CGRect(x: b[0], y: b[1], width: max(1, b[2]-b[0]),
                                      height: max(1, b[3]-b[1])))
                context.restoreGState()
            }
        }
    }

    static func png(_ page: NotePage, to url: URL) throws {
        let width = 930
        let height = Int(Double(width) * page.height / page.width)
        try require(height > 0 && height <= 10000, "Page aspect ratio exceeds PNG preview limit.")
        guard let image = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: width,
                                          pixelsHigh: height, bitsPerSample: 8,
                                          samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
                                          colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0),
              let graphics = NSGraphicsContext(bitmapImageRep: image) else {
            throw ReaderError("Cannot allocate page preview.")
        }
        let context = graphics.cgContext
        context.translateBy(x: 0, y: CGFloat(height))
        context.scaleBy(x: 1, y: -1)
        draw(page, in: context, scale: Double(width) / page.width)
        guard let bytes = image.representation(using: .png, properties: [:]) else {
            throw ReaderError("Cannot encode page preview.")
        }
        try bytes.write(to: url, options: .atomic)
    }
}
