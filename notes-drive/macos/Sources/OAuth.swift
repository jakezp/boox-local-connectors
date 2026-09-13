import AppKit
import CryptoKit
import Foundation
import Network
import Security
import LocalAuthentication

let driveScope = "https://www.googleapis.com/auth/drive.file"

struct OAuthConfig {
    let clientID: String
    let clientSecret: String?

    static func load(_ url: URL) throws -> OAuthConfig {
        try decode(read(url))
    }

    static func read(_ url: URL) throws -> Data {
        let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? Int.max
        try require(size <= 32768, "OAuth configuration is too large.")
        let file = try FileHandle(forReadingFrom: url)
        defer { try? file.close() }
        let bytes = try file.read(upToCount: 32769) ?? Data()
        try require(bytes.count <= 32768, "OAuth configuration is too large.")
        return bytes
    }

    static func decode(_ bytes: Data) throws -> OAuthConfig {
        try require(bytes.count <= 32768, "OAuth configuration is too large.")
        let root = try jsonObject(bytes)
        try require(root["web"] == nil, "Use a Desktop OAuth client, not a Web client.")
        try require(root["installed"] == nil || root["installed"] is [String: Any],
                    "Invalid Desktop OAuth configuration.")
        let value = (root["installed"] as? [String: Any]) ?? root
        guard let id = value["client_id"] as? String,
              id.count <= 256, matches(id, "^[A-Za-z0-9_-]+\\.apps\\.googleusercontent\\.com$") else {
            throw ReaderError("Import the Desktop OAuth client JSON from Google Cloud.")
        }
        for key in ["project_id", "client_secret"] where value[key] != nil {
            guard let text = value[key] as? String, !text.isEmpty else {
                throw ReaderError("OAuth configuration has an invalid \(key) field.")
            }
        }
        return OAuthConfig(clientID: id, clientSecret: value["client_secret"] as? String)
    }
}

struct StoredGrant: Codable {
    let refreshToken: String
    let scope: String
}

enum TokenKeychain {
    static let service = "local.boox.notesreader.google-oauth"

    static func query(_ client: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: service, kSecAttrAccount as String: client]
    }

    static func read(_ client: String, allowInteraction: Bool = true) throws -> StoredGrant? {
        var request = query(client)
        request[kSecReturnData as String] = true
        request[kSecMatchLimit as String] = kSecMatchLimitOne
        if !allowInteraction {
            let context = LAContext()
            context.interactionNotAllowed = true
            request[kSecUseAuthenticationContext as String] = context
        }
        var result: CFTypeRef?
        let status = SecItemCopyMatching(request as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        try require(status == errSecSuccess, "Keychain access needs a manual Reconnect saved grant (\(status)); automatic sign-in never opens consent.")
        guard let data = result as? Data else { throw ReaderError("Invalid Keychain record.") }
        return try JSONDecoder().decode(StoredGrant.self, from: data)
    }

    static func write(_ grant: StoredGrant, client: String, allowInteraction: Bool = true) throws {
        let data = try JSONEncoder().encode(grant)
        var request = query(client)
        if !allowInteraction {
            let context = LAContext()
            context.interactionNotAllowed = true
            request[kSecUseAuthenticationContext as String] = context
        }
        let status = SecItemUpdate(request as CFDictionary,
                                   [kSecValueData as String: data] as CFDictionary)
        if status == errSecItemNotFound {
            var item = request
            item[kSecValueData as String] = data
            item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            let added = SecItemAdd(item as CFDictionary, nil)
            try require(added == errSecSuccess, "Keychain save failed (\(added)).")
        } else { try require(status == errSecSuccess, "Keychain update failed (\(status)).") }
    }

    static func remove(_ client: String) throws {
        let status = SecItemDelete(query(client) as CFDictionary)
        try require(status == errSecSuccess || status == errSecItemNotFound,
                    "Keychain removal failed (\(status)).")
    }
}

func randomURLString() throws -> String {
    var bytes = [UInt8](repeating: 0, count: 32)
    try require(SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess,
                "Secure random generator failed.")
    return base64URL(Data(bytes))
}

func base64URL(_ data: Data) -> String {
    data.base64EncodedString().replacingOccurrences(of: "+", with: "-")
        .replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
}

final class LoopbackCallback: @unchecked Sendable {
    private let queue = DispatchQueue(label: "local.boox.notesreader.oauth-loopback")
    private let listener: NWListener
    private let state: String
    private var ready: CheckedContinuation<UInt16, Error>?
    private var result: CheckedContinuation<String, Error>?
    private var connections = 0
    private var finished = false
    private var timeout: DispatchWorkItem?
    private var activeConnections: [NWConnection] = []

    init(state: String) throws {
        self.state = state
        let parameters = NWParameters.tcp
        parameters.requiredLocalEndpoint = .hostPort(host: "127.0.0.1", port: .any)
        listener = try NWListener(using: parameters)
    }

    func start() async throws -> UInt16 {
        try await withCheckedThrowingContinuation { continuation in
            queue.async {
                self.ready = continuation
                self.listener.stateUpdateHandler = { state in
                    switch state {
                    case .ready:
                        if let port = self.listener.port {
                            self.ready?.resume(returning: port.rawValue)
                            self.ready = nil
                        }
                    case .failed(let error): self.finish(.failure(error))
                    default: break
                    }
                }
                self.listener.newConnectionHandler = { self.accept($0) }
                self.listener.start(queue: self.queue)
            }
        }
    }

    func receiveCode(openBrowser: @escaping () -> Void) async throws -> String {
        try await withCheckedThrowingContinuation { continuation in
            queue.async {
                guard !self.finished else {
                    continuation.resume(throwing: ReaderError("OAuth listener closed. Connect again to retry."))
                    return
                }
                self.result = continuation
                let timeout = DispatchWorkItem {
                    self.finish(.failure(ReaderError("Google sign-in timed out. Connect again to retry.")))
                }
                self.timeout = timeout
                self.queue.asyncAfter(deadline: .now() + 180, execute: timeout)
                DispatchQueue.main.async(execute: openBrowser)
            }
        }
    }

    private func finish(_ outcome: Result<String, Error>) {
        guard !finished else { return }
        finished = true
        timeout?.cancel()
        if let ready {
            self.ready = nil
            if case .failure(let error) = outcome { ready.resume(throwing: error) }
            else { ready.resume(throwing: ReaderError("OAuth listener closed.")) }
        }
        result?.resume(with: outcome)
        result = nil
        listener.cancel()
        for connection in activeConnections { connection.cancel() }
        activeConnections.removeAll()
    }

    private func accept(_ connection: NWConnection) {
        connections += 1
        guard connections <= 16, !finished else { connection.cancel(); return }
        activeConnections.append(connection)
        connection.start(queue: queue)
        read(connection, accumulated: Data())
    }

    private func read(_ connection: NWConnection, accumulated: Data) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 4096) { bytes, _, complete, error in
            var request = accumulated
            request.append(bytes ?? Data())
            guard request.count <= 16384, error == nil else { connection.cancel(); return }
            if request.range(of: Data("\r\n\r\n".utf8)) != nil {
                self.handle(connection, request: request)
            } else if complete { connection.cancel() }
            else { self.read(connection, accumulated: request) }
        }
    }

    private func handle(_ connection: NWConnection, request: Data) {
        let first = String(data: request, encoding: .utf8)?.components(separatedBy: "\r\n").first ?? ""
        let parts = first.split(separator: " ")
        guard parts.count == 3, parts[0] == "GET", parts[1].hasPrefix("/oauth/callback?"),
              let url = URLComponents(string: "http://127.0.0.1" + String(parts[1])),
              url.path == "/oauth/callback" else {
            reply(connection, status: "400 Bad Request", text: "Invalid callback.", outcome: nil)
            return
        }
        var values: [String: String] = [:]
        for item in url.queryItems ?? [] {
            if values[item.name] != nil {
                reply(connection, status: "400 Bad Request", text: "Duplicate callback field.", outcome: nil)
                return
            }
            values[item.name] = item.value ?? ""
        }
        guard values["state"] == state else {
            reply(connection, status: "400 Bad Request", text: "Invalid sign-in state.", outcome: nil)
            return
        }
        if values["error"] != nil {
            reply(connection, status: "200 OK", text: "Sign-in was not approved. Return to BOOX Notes Reader.",
                  outcome: .failure(ReaderError("Google sign-in was cancelled or denied.")))
        } else if let code = values["code"], !code.isEmpty, code.count <= 4096 {
            reply(connection, status: "200 OK", text: "Sign-in received. You can close this tab and return to BOOX Notes Reader.",
                  outcome: .success(code))
        } else {
            reply(connection, status: "400 Bad Request", text: "Missing authorization code.", outcome: nil)
        }
    }

    private func reply(_ connection: NWConnection, status: String, text: String,
                       outcome: Result<String, Error>?) {
        let body = Data(text.utf8)
        let headers = "HTTP/1.1 \(status)\r\nContent-Type: text/plain; charset=utf-8\r\nCache-Control: no-store\r\nContent-Security-Policy: default-src 'none'\r\nContent-Length: \(body.count)\r\nConnection: close\r\n\r\n"
        connection.send(content: Data(headers.utf8) + body, completion: .contentProcessed { _ in
            connection.cancel()
            if let outcome { self.finish(outcome) }
        })
    }
}

@MainActor
final class GoogleAuthorization {
    private(set) var config: OAuthConfig?
    private var access: String?
    private var expires = Date.distantPast

    func configure(_ config: OAuthConfig) {
        self.config = config
        access = nil
        expires = .distantPast
    }

    func connect(authorizationURL: @escaping (URL) -> Void = { NSWorkspace.shared.open($0) }) async throws {
        guard let config else { throw ReaderError("Import a Desktop OAuth configuration first.") }
        let verifier = try randomURLString()
        let state = try randomURLString()
        let callback = try LoopbackCallback(state: state)
        let port = try await callback.start()
        let redirect = "http://127.0.0.1:\(port)/oauth/callback"
        var url = URLComponents(string: "https://accounts.google.com/o/oauth2/v2/auth")!
        url.queryItems = [
            URLQueryItem(name: "client_id", value: config.clientID),
            URLQueryItem(name: "redirect_uri", value: redirect),
            URLQueryItem(name: "response_type", value: "code"),
            URLQueryItem(name: "scope", value: driveScope),
            URLQueryItem(name: "access_type", value: "offline"),
            URLQueryItem(name: "prompt", value: "consent select_account"),
            URLQueryItem(name: "state", value: state),
            URLQueryItem(name: "code_challenge_method", value: "S256"),
            URLQueryItem(name: "code_challenge", value: base64URL(Data(SHA256.hash(data: Data(verifier.utf8)))))
        ]
        let authURL = url.url!
        let code = try await callback.receiveCode { authorizationURL(authURL) }
        let result = try await exchange(["grant_type": "authorization_code", "code": code,
                                        "redirect_uri": redirect, "code_verifier": verifier])
        guard let refresh = result["refresh_token"] as? String, !refresh.isEmpty else {
            throw ReaderError("Google did not return a refresh token. Reconnect and approve offline access.")
        }
        try validateScope(result, requirePresent: true)
        try TokenKeychain.write(StoredGrant(refreshToken: refresh, scope: driveScope), client: config.clientID)
        try saveAccess(result)
    }

    func hasSavedGrant(allowInteraction: Bool = false) throws -> Bool {
        guard let config else { return false }
        return try TokenKeychain.read(config.clientID, allowInteraction: allowInteraction) != nil
    }

    func token(allowInteraction: Bool = true) async throws -> String {
        if let access, expires > Date() { return access }
        guard let config, let stored = try TokenKeychain.read(config.clientID, allowInteraction: allowInteraction) else {
            throw ReaderError("Connect Google Drive first.")
        }
        try require(stored.scope == driveScope, "Stored grant has unexpected scope. Reconnect Google Drive.")
        let result = try await exchange(["grant_type": "refresh_token", "refresh_token": stored.refreshToken])
        try validateScope(result, requirePresent: false)
        if let refresh = result["refresh_token"] as? String {
            try TokenKeychain.write(StoredGrant(refreshToken: refresh, scope: driveScope),
                                   client: config.clientID, allowInteraction: allowInteraction)
        }
        try saveAccess(result)
        return access!
    }

    func invalidateAccess() { access = nil; expires = .distantPast }

    func forget() throws {
        if let config { try TokenKeychain.remove(config.clientID) }
        invalidateAccess()
    }

    private func exchange(_ fields: [String: String]) async throws -> [String: Any] {
        guard let config else { throw ReaderError("Missing OAuth configuration.") }
        var fields = fields
        fields["client_id"] = config.clientID
        if let secret = config.clientSecret { fields["client_secret"] = secret }
        var request = URLRequest(url: URL(string: "https://oauth2.googleapis.com/token")!)
        request.httpMethod = "POST"
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        request.httpBody = urlEncoded(fields)
        let response = try await BoundedHTTP.send(request, limit: 32768)
        guard response.status == 200 else {
            // Do not log token endpoint bodies or authorization codes.
            invalidateAccess()
            throw ReaderError("Google token exchange failed (HTTP \(response.status)). Reconnect if access was revoked.")
        }
        return try jsonObject(response.data)
    }

    private func validateScope(_ result: [String: Any], requirePresent: Bool) throws {
        if let scope = result["scope"] as? String {
            try require(Set(scope.split(separator: " ").map(String.init)) == [driveScope],
                        "Google returned an unexpected scope; this client requires only drive.file.")
        } else { try require(!requirePresent, "Google did not confirm the requested Drive scope.") }
    }

    private func saveAccess(_ result: [String: Any]) throws {
        guard let token = result["access_token"] as? String, !token.isEmpty,
              let type = result["token_type"] as? String, type.lowercased() == "bearer",
              let duration = result["expires_in"] as? NSNumber, duration.doubleValue > 0 else {
            throw ReaderError("Google returned an invalid access token response.")
        }
        access = token
        expires = Date().addingTimeInterval(max(0, duration.doubleValue - 60))
    }
}
