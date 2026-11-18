import Foundation
import Testing
@testable import MinicamKit

/// docs/specs/020 AC3/AC4: the device's version, its update and following one.
@Suite struct UpdateAPITests {
    let statusJSON = """
        {"available": true, "current": "v0.6.0", "latest": "v0.7.0", "update_available": true,
         "state": "idle", "version": null, "message": null, "checked_at": "2026-11-18T12:00:00+00:00"}
        """

    func api(_ transport: HTTPTransport) -> MinicamAPI {
        MinicamAPI(config: try! ServerConfig(parsing: "http://192.168.1.20:8080"), transport: transport, store: InMemoryCredentialStore())
    }

    func target(_ request: URLRequest?) -> String {
        guard let url = request?.url else { return "" }
        let query = url.query(percentEncoded: true).map { "?\($0)" } ?? ""
        return "\(request?.httpMethod ?? "") \(url.path(percentEncoded: true))\(query)"
    }

    @Test func systemUpdate_parsesTheStatus() async throws {
        let transport = FakeTransport([.init(status: 200, body: statusJSON)])

        let status = try await api(transport).systemUpdate()

        #expect(target(transport.requests.first) == "GET /api/system/update")
        #expect(status?.current == "v0.6.0")
        #expect(status?.latest == "v0.7.0")
        #expect(status?.updateAvailable == true)
        #expect(status?.busy == false)
    }

    @Test func systemUpdate_refreshAsksTheDeviceToCheckNow() async throws {
        let transport = FakeTransport([.init(status: 200, body: statusJSON)])

        _ = try await api(transport).systemUpdate(refresh: true)

        #expect(target(transport.requests.first) == "GET /api/system/update?refresh=true")
    }

    @Test func systemUpdate_olderMinicamHasNoUpdates() async throws {
        let transport = FakeTransport([.init(status: 404, body: #"{"detail": "Not Found"}"#)])

        #expect(try await api(transport).systemUpdate() == nil)
    }

    @Test func startUpdate_postsTheVersion() async throws {
        let body = statusJSON.replacingOccurrences(of: "\"idle\"", with: "\"requested\"")
        let transport = FakeTransport([.init(status: 202, body: body)])

        let status = try await api(transport).startUpdate(version: "v0.7.0")

        let request = try #require(transport.requests.first)
        #expect(target(request) == "POST /api/system/update")
        #expect(String(decoding: request.httpBody ?? Data(), as: UTF8.self) == #"{"version":"v0.7.0"}"#)
        #expect(status.busy)
    }

    func status(_ current: String, _ state: String, _ message: String? = nil) -> SystemUpdate {
        SystemUpdate(available: true, current: current, state: state, message: message)
    }

    let start = Date(timeIntervalSince1970: 0)
    func at(_ seconds: TimeInterval) -> Date { start.addingTimeInterval(seconds) }

    @Test func tracker_runsUntilTheDeviceAnswersWithTheNewVersion() {
        let tracker = UpdateTracker(target: "v0.7.0", startedAt: start)

        #expect(tracker.progress(after: status("v0.6.0", "downloading", "Downloading"), at: at(5))
            == .running(target: "v0.7.0", state: "downloading", message: "Downloading"))
        #expect(tracker.progressWhenUnreachable(at: at(60)) == .running(target: "v0.7.0", state: "restarting", message: nil))
        #expect(tracker.progress(after: status("v0.7.0", "done"), at: at(90)) == .done(version: "v0.7.0"))
    }

    @Test func tracker_anErrorEndsIt() {
        let tracker = UpdateTracker(target: "v0.7.0", startedAt: start)

        #expect(tracker.progress(after: status("v0.6.0", "error", "restored"), at: at(200)) == .failed(message: "restored"))
    }

    @Test func tracker_idleOnTheOldVersionAfterTheGraceIsAFailure() {
        let tracker = UpdateTracker(target: "v0.7.0", startedAt: start)

        #expect(tracker.progress(after: status("v0.6.0", "idle"), at: at(UpdateTracker.grace))
            == .running(target: "v0.7.0", state: "idle", message: nil))
        #expect(tracker.progress(after: status("v0.6.0", "idle"), at: at(UpdateTracker.grace + 1)) == .failed(message: nil))
    }

    @Test func tracker_givesUpAfterTheTimeout() {
        let tracker = UpdateTracker(target: "v0.7.0", startedAt: start)

        #expect(tracker.progressWhenUnreachable(at: at(UpdateTracker.timeout + 1)) == .failed(message: nil))
    }
}
