import Foundation
@testable import MinicamKit

/// Records requests and answers each with the next canned response.
final class FakeTransport: HTTPTransport, @unchecked Sendable {
    struct Canned {
        var status: Int
        var body: String
        var headers: [String: String] = [:]
    }

    private let lock = NSLock()
    private var responses: [Canned]
    private(set) var requests: [URLRequest] = []

    init(_ responses: [Canned]) {
        self.responses = responses
    }

    func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        let canned = lock.withLock {
            requests.append(request)
            return responses.removeFirst()
        }
        let response = HTTPURLResponse(
            url: request.url!, statusCode: canned.status, httpVersion: "HTTP/1.1",
            headerFields: canned.headers
        )!
        return (Data(canned.body.utf8), response)
    }
}
