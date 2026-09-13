import Foundation

struct HTTPResult {
    let status: Int
    let data: Data
}

final class BoundedHTTP: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    private let limit: Int
    private var buffer = Data()
    private var response: HTTPURLResponse?
    private var session: URLSession?
    private var failure: Error?
    private var continuation: CheckedContinuation<HTTPResult, Error>?
    private let cancellationLock = NSLock()
    private var cancelled = false
    private var task: URLSessionDataTask?

    private init(limit: Int) { self.limit = limit }

    static func send(_ request: URLRequest, limit: Int) async throws -> HTTPResult {
        let client = BoundedHTTP(limit: limit)
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                client.continuation = continuation
                let config = URLSessionConfiguration.ephemeral
                config.urlCache = nil
                config.httpCookieStorage = nil
                config.timeoutIntervalForRequest = 30
                config.timeoutIntervalForResource = 90
                let queue = OperationQueue()
                queue.maxConcurrentOperationCount = 1
                let session = URLSession(configuration: config, delegate: client, delegateQueue: queue)
                client.session = session
                client.start(session.dataTask(with: request))
            }
        } onCancel: { client.cancel() }
    }

    private func start(_ task: URLSessionDataTask) {
        cancellationLock.lock()
        self.task = task
        let wasCancelled = cancelled
        cancellationLock.unlock()
        task.resume()
        if wasCancelled { task.cancel() }
    }

    private func cancel() {
        cancellationLock.lock()
        cancelled = true
        let task = task
        cancellationLock.unlock()
        task?.cancel()
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask,
                    didReceive response: URLResponse,
                    completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        guard let http = response as? HTTPURLResponse else {
            failure = ReaderError("Invalid HTTP response.")
            completionHandler(.cancel)
            return
        }
        self.response = http
        if response.expectedContentLength > limit {
            failure = ReaderError("Server response exceeds the bounded download limit.")
            completionHandler(.cancel)
        } else { completionHandler(.allow) }
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        if buffer.count + data.count > limit {
            failure = ReaderError("Server response exceeds the bounded download limit.")
            dataTask.cancel()
        } else { buffer.append(data) }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask,
                    willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest,
                    completionHandler: @escaping (URLRequest?) -> Void) {
        failure = ReaderError("Unexpected HTTP redirect refused.")
        completionHandler(nil)
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        if let error = failure ?? error { continuation?.resume(throwing: error) }
        else if let response { continuation?.resume(returning: HTTPResult(status: response.statusCode, data: buffer)) }
        else { continuation?.resume(throwing: ReaderError("Missing HTTP response.")) }
        continuation = nil
        cancellationLock.lock()
        self.task = nil
        cancellationLock.unlock()
        session.finishTasksAndInvalidate()
        self.session = nil
    }
}

func jsonObject(_ data: Data) throws -> [String: Any] {
    guard let result = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
        throw ReaderError("Expected a JSON object.")
    }
    return result
}

func urlEncoded(_ parameters: [String: String]) -> Data {
    var components = URLComponents()
    components.queryItems = parameters.sorted { $0.key < $1.key }.map { URLQueryItem(name: $0.key, value: $0.value) }
    // '+' must be escaped for application/x-www-form-urlencoded.
    return Data((components.percentEncodedQuery ?? "").replacingOccurrences(of: "+", with: "%2B").utf8)
}
