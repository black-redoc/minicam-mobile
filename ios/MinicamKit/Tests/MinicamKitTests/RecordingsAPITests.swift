import Foundation
import Testing
@testable import MinicamKit

/// docs/specs/006: event recordings.
@Suite struct RecordingsAPITests {
    let lan = try! ServerConfig(parsing: "http://192.168.1.20:8080")
    let wan = try! ServerConfig(parsing: "https://cam.example.com")
    let recordingJSON = """
        {"id": 42, "camera_name": "front door", "started_at": "2026-10-22T14:03:11.250000+00:00",
         "duration_seconds": 18.4, "size_bytes": 5242880, "classes": ["person"]}
        """
    let settingsJSON = """
        {"enabled": true, "max_storage_gb": 10, "used_bytes": 734003200, "free_bytes": 21474836480,
         "options": [{"gb": 1, "available": true}, {"gb": 50, "available": false}]}
        """

    func api(_ transport: HTTPTransport, config: ServerConfig? = nil, store: CredentialStore = InMemoryCredentialStore()) -> MinicamAPI {
        MinicamAPI(config: config ?? lan, transport: transport, store: store)
    }

    func target(_ request: URLRequest?) -> String {
        guard let url = request?.url else { return "" }
        let query = url.query(percentEncoded: true).map { "?\($0)" } ?? ""
        return "\(request?.httpMethod ?? "") \(url.path(percentEncoded: true))\(query)"
    }

    func recording(_ id: Int, _ startedAt: String, camera: String = "cam") -> Recording {
        Recording(id: id, cameraName: camera, startedAt: startedAt, durationSeconds: 1, sizeBytes: 1)
    }

    @Test func recordings_parsesPage() async throws {
        let transport = FakeTransport([.init(status: 200, body: #"{"items": [\#(recordingJSON)], "next_before": 42}"#)])

        let page = try await api(transport).recordings()

        #expect(page == RecordingPage(
            items: [Recording(
                id: 42, cameraName: "front door", startedAt: "2026-10-22T14:03:11.250000+00:00",
                durationSeconds: 18.4, sizeBytes: 5_242_880, classes: ["person"]
            )],
            nextBefore: 42
        ))
        #expect(target(transport.requests.first) == "GET /api/recordings?limit=30")
    }

    @Test func recordings_sendsFilters() async throws {
        let transport = FakeTransport([.init(status: 200, body: #"{"items": [], "next_before": null}"#)])

        let page = try await api(transport).recordings(camera: "front door", before: 42)

        #expect(page.nextBefore == nil)
        #expect(target(transport.requests.first) == "GET /api/recordings?limit=30&camera=front%20door&before=42")
    }

    @Test func deleteRecording_sendsDelete() async throws {
        let transport = FakeTransport([.init(status: 204, body: ""), .init(status: 404, body: #"{"detail": "Not found"}"#)])
        let api = api(transport)

        try await api.deleteRecording(id: 42)
        // Already deleted (by another admin or by retention) is not an error.
        try await api.deleteRecording(id: 43)

        #expect(transport.requests.map(target) == ["DELETE /api/recordings/42", "DELETE /api/recordings/43"])
    }

    @Test func recordingSettings_roundTrip() async throws {
        let transport = FakeTransport([
            .init(status: 200, body: settingsJSON),
            .init(status: 200, body: settingsJSON),
            .init(status: 400, body: #"{"detail": "Not enough disk space for 50 GB"}"#),
        ])
        let api = api(transport)

        let settings = try await api.recordingSettings()
        _ = try await api.saveRecordingSettings(enabled: true, maxStorageGB: 10)
        await #expect(throws: MinicamError.rejected("Not enough disk space for 50 GB")) {
            try await api.saveRecordingSettings(enabled: true, maxStorageGB: 50)
        }

        #expect(settings.enabled && settings.maxStorageGB == 10)
        #expect(settings.usedBytes == 734_003_200 && settings.freeBytes == 21_474_836_480)
        #expect(settings.options == [StorageOption(gb: 1, available: true), StorageOption(gb: 50, available: false)])
        #expect(target(transport.requests[1]) == "PUT /api/recordings/settings")
        let body = try JSONSerialization.jsonObject(with: try #require(transport.requests[1].httpBody)) as? NSDictionary
        #expect(body == ["enabled": true, "max_storage_gb": 10])
    }

    @Test func recordingVideoRequest_isAuthorized() async throws {
        let store = InMemoryCredentialStore()
        store.set("abc", for: .sessionCookie)
        store.set("jwt", for: .accessToken)
        let transport = FakeTransport([.init(status: 200, body: "mp4-bytes"), .init(status: 404, body: "")])
        let lanAPI = api(transport, store: store)

        let request = lanAPI.recordingVideoRequest(id: 42)
        let file = try await lanAPI.downloadRecording(recording(42, "2026-10-22T14:03:11+00:00", camera: "front door"))
        defer { try? FileManager.default.removeItem(at: file) }

        #expect(request.url?.absoluteString == "http://192.168.1.20:8080/api/recordings/42/video")
        #expect(request.value(forHTTPHeaderField: "Cookie") == "minicam_session=abc")
        // The Access token only goes to WAN hosts.
        #expect(request.value(forHTTPHeaderField: "cf-access-token") == nil)
        #expect(api(transport, config: wan, store: store).recordingVideoRequest(id: 42)
            .value(forHTTPHeaderField: "cf-access-token") == "jwt")
        #expect(target(transport.requests.first) == "GET /api/recordings/42/video?download=true")
        #expect(try String(contentsOf: file, encoding: .utf8) == "mp4-bytes")
        #expect(file.lastPathComponent == "minicam-front-door-2026-10-22T140311.mp4")
        await #expect(throws: MinicamError.notFound) {
            try await lanAPI.downloadRecording(recording(43, "2026-10-22T14:03:11+00:00"))
        }
    }

    @Test func recordingStart_parsesIsoTimestamps() {
        #expect(parseRecordingStart("2026-10-22T14:03:11.250000+00:00")
            == Date(timeIntervalSince1970: 1_792_677_791.25))
        #expect(parseRecordingStart("2026-10-22T14:03:11-05:00") == Date(timeIntervalSince1970: 1_792_695_791))
        #expect(parseRecordingStart("yesterday") == nil)
    }

    @Test func clipDuration_formats() {
        #expect(formatClipDuration(18.4) == "0:18")
        #expect(formatClipDuration(64.6) == "1:05")
        #expect(formatClipDuration(-1) == "0:00")
    }

    @Test func clipSize_formats() {
        #expect(formatClipSize(10) == "1 KB")
        #expect(formatClipSize(512_000) == "500 KB")
        #expect(formatClipSize(5_242_880) == "5.0 MB")
        #expect(formatClipSize(1_610_612_736) == "1.5 GB")
    }

    @Test func storageBytes_formats() {
        #expect(formatStorageBytes(734_003_200) == "700 MB")
        #expect(formatStorageBytes(21_474_836_480) == "20.0 GB")
    }

    @Test func recordingDays_groupByLocalDay() {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "America/Bogota")!
        let items = [
            recording(4, "2026-10-22T14:00:00+00:00"),
            // 01:00 UTC on the 22nd is still the 21st in Bogotá (UTC-5).
            recording(3, "2026-10-22T01:00:00+00:00"),
            recording(2, "2026-10-21T13:00:00+00:00"),
            recording(1, "2026-10-19T13:00:00+00:00"),
        ]

        let days = recordingDays(
            items, calendar: calendar, now: parseRecordingStart("2026-10-22T20:00:00+00:00")!,
            locale: Locale(identifier: "en_US")
        )

        #expect(days.map(\.label) == ["Today", "Yesterday", "Monday, October 19, 2026"])
        #expect(days.map { $0.recordings.map(\.id) } == [[4], [3, 2], [1]])
    }
}
