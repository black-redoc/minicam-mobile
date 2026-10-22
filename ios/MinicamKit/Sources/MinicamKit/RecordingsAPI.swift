import Foundation

// Event recordings (docs/specs/006, ADR 0005, minicam `app/docs/recordings.md`). The server
// returns only clips of cameras the user can view; delete and settings are admin-only.

/// Page size of the clip list, like the web Recordings page.
public let recordingsPageSize = 30

public struct Recording: Codable, Equatable, Hashable, Sendable, Identifiable {
    public let id: Int
    public let cameraName: String
    public let startedAt: String
    public let durationSeconds: Double
    public let sizeBytes: Int64
    public let classes: [String]

    public init(
        id: Int, cameraName: String, startedAt: String, durationSeconds: Double, sizeBytes: Int64,
        classes: [String] = []
    ) {
        self.id = id
        self.cameraName = cameraName
        self.startedAt = startedAt
        self.durationSeconds = durationSeconds
        self.sizeBytes = sizeBytes
        self.classes = classes
    }

    /// When the clip starts; the epoch if the server sent something unexpected.
    public var started: Date { parseRecordingStart(startedAt) ?? Date(timeIntervalSince1970: 0) }

    enum CodingKeys: String, CodingKey {
        case id, classes
        case cameraName = "camera_name"
        case startedAt = "started_at"
        case durationSeconds = "duration_seconds"
        case sizeBytes = "size_bytes"
    }
}

/// `GET /api/recordings`: newest first; pass `nextBefore` as `before` for the next page.
public struct RecordingPage: Decodable, Equatable, Sendable {
    public let items: [Recording]
    public let nextBefore: Int?

    enum CodingKeys: String, CodingKey {
        case items
        case nextBefore = "next_before"
    }
}

public struct StorageOption: Codable, Equatable, Hashable, Sendable {
    public let gb: Int
    public let available: Bool

    public init(gb: Int, available: Bool) {
        self.gb = gb
        self.available = available
    }
}

/// `GET/PUT /api/recordings/settings` response: settings plus disk figures.
public struct RecordingSettings: Codable, Equatable, Sendable {
    public var enabled: Bool
    public var maxStorageGB: Int
    public let usedBytes: Int64
    public let freeBytes: Int64
    public let options: [StorageOption]

    enum CodingKeys: String, CodingKey {
        case enabled, options
        case maxStorageGB = "max_storage_gb"
        case usedBytes = "used_bytes"
        case freeBytes = "free_bytes"
    }
}

extension MinicamAPI {
    public func recordings(
        camera: String? = nil, before: Int? = nil, limit: Int = recordingsPageSize
    ) async throws -> RecordingPage {
        var query = ["limit=\(limit)"]
        if let camera { query.append("camera=\(ServerConfig.encodePathSegment(camera))") }
        if let before { query.append("before=\(before)") }
        return try await get("/api/recordings?\(query.joined(separator: "&"))")
    }

    /// Deletes a clip and its file (admin). Already gone counts as deleted, like the web.
    public func deleteRecording(id: Int) async throws {
        do {
            _ = try await send("DELETE", "/api/recordings/\(id)")
        } catch MinicamError.notFound {}
    }

    public func recordingSettings() async throws -> RecordingSettings {
        try await get("/api/recordings/settings")
    }

    public func saveRecordingSettings(enabled: Bool, maxStorageGB: Int) async throws -> RecordingSettings {
        struct Body: Encodable {
            let enabled: Bool
            let max_storage_gb: Int
        }
        return try await sendJSON("PUT", "/api/recordings/settings", body: Body(enabled: enabled, max_storage_gb: maxStorageGB))
    }

    /// The clip's MP4 (HTTP Range supported) with the auth headers every request carries,
    /// for the player (ADR 0005).
    public func recordingVideoRequest(id: Int, download: Bool = false) -> URLRequest {
        let path = "/api/recordings/\(id)/video\(download ? "?download=true" : "")"
        return authorized(URLRequest(url: config.url(path: path)))
    }

    /// Downloads the clip into the temporary directory, named like the server's download.
    public func downloadRecording(_ recording: Recording) async throws -> URL {
        let (file, response) = try await transport.download(recordingVideoRequest(id: recording.id, download: true))
        do {
            try check(response, data: response.statusCode < 300 ? Data() : (try? Data(contentsOf: file)) ?? Data())
        } catch {
            try? FileManager.default.removeItem(at: file)
            throw error
        }
        let destination = FileManager.default.temporaryDirectory.appendingPathComponent(recordingFileName(recording))
        try? FileManager.default.removeItem(at: destination)
        try FileManager.default.moveItem(at: file, to: destination)
        return destination
    }
}

// MARK: - Formatting (mirrors minicam `app/static/js/recordings.js`)

/// `2026-10-22T14:03:11.250000+00:00` (or without the fraction) as a date.
public func parseRecordingStart(_ text: String) -> Date? {
    // Python sends microseconds; ISO8601DateFormatter only reads milliseconds reliably.
    var base = Substring(text)
    var fraction = 0.0
    if let dot = text.firstIndex(of: ".") {
        let digits = text[text.index(after: dot)...].prefix { $0.isNumber }
        fraction = Double("0.\(digits)") ?? 0
        base = text[..<dot] + text[digits.endIndex...]
    }
    let formatter = ISO8601DateFormatter()
    formatter.formatOptions = [.withInternetDateTime]
    return formatter.date(from: String(base))?.addingTimeInterval(fraction)
}

/// `m:ss`, rounded to the second.
public func formatClipDuration(_ seconds: Double) -> String {
    let total = Int(max(0, seconds).rounded())
    return String(format: "%d:%02d", total / 60, total % 60)
}

private let mib = 1024.0 * 1024
private let gib = mib * 1024

public func formatClipSize(_ bytes: Int64) -> String {
    let value = Double(bytes)
    if value >= gib { return String(format: "%.1f GB", value / gib) }
    if value >= mib { return String(format: "%.1f MB", value / mib) }
    return "\(max(1, Int((value / 1024).rounded()))) KB"
}

/// Disk usage line on the Admin card: GB with one decimal, MB below 1 GB.
public func formatStorageBytes(_ bytes: Int64) -> String {
    let value = Double(bytes)
    return value >= gib ? String(format: "%.1f GB", value / gib) : "\(Int((value / mib).rounded())) MB"
}

/// The server's `Content-Disposition` name, used for the downloaded file.
public func recordingFileName(_ recording: Recording) -> String {
    func safe(_ text: String, separator: String) -> String {
        text.replacing(/[^A-Za-z0-9_-]+/, with: separator)
    }
    let camera = safe(recording.cameraName, separator: "-").replacing(/^-+|-+$/, with: "")
    let started = safe(String(recording.startedAt.prefix(19)), separator: "")
    return "minicam-\(camera.isEmpty ? "camera" : camera)-\(started).mp4"
}

/// One "Today" / "Yesterday" / date section of the clip list.
public struct RecordingDay: Equatable, Sendable, Identifiable {
    public let label: String
    public let recordings: [Recording]
    public var id: String { label }
}

/// Groups clips (newest first) by their local day, keeping their order.
public func recordingDays(
    _ recordings: [Recording], calendar: Calendar = .current, now: Date = Date(), locale: Locale = .current
) -> [RecordingDay] {
    let formatter = DateFormatter()
    formatter.calendar = calendar
    formatter.timeZone = calendar.timeZone
    formatter.locale = locale
    formatter.dateStyle = .full
    formatter.timeStyle = .none
    let today = calendar.startOfDay(for: now)
    let yesterday = calendar.date(byAdding: .day, value: -1, to: today)!

    var days: [(day: Date, items: [Recording])] = []
    for recording in recordings {
        let day = calendar.startOfDay(for: recording.started)
        if let index = days.firstIndex(where: { $0.day == day }) {
            days[index].items.append(recording)
        } else {
            days.append((day, [recording]))
        }
    }
    return days.map { day, items in
        let label = switch day {
        case today: "Today"
        case yesterday: "Yesterday"
        default: formatter.string(from: day)
        }
        return RecordingDay(label: label, recordings: items)
    }
}
