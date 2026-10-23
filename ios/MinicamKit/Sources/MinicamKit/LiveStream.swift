import Foundation

/// What the stream WebSocket carries (ADR 0006).
public enum StreamFormat: Sendable {
    case mjpeg, fmp4
}

/// The format (and whether audio is on) one stream session uses.
public struct StreamChoice: Equatable, Sendable {
    public let format: StreamFormat
    public let audio: Bool
    public let mime: String?

    public init(format: StreamFormat, audio: Bool, mime: String?) {
        self.format = format
        self.audio = audio
        self.mime = mime
    }
}

/// fMP4 when the server knows the camera's codec and the device can decode it (`canPlay` takes
/// the sample MIME types of `LiveCodecs.sampleMimes`); audio only if also asked for and offered.
/// MJPEG otherwise, and always when `preferFMP4` is off (after an fMP4 playback failure).
public func chooseStream(
    _ token: StreamToken, preferFMP4: Bool, audio: Bool, canPlay: ([String]) -> Bool
) -> StreamChoice {
    guard preferFMP4, let video = token.videoMime else {
        return StreamChoice(format: .mjpeg, audio: false, mime: nil)
    }
    if audio, let withAudio = token.audioMime, let mimes = LiveCodecs.sampleMimes(withAudio), canPlay(mimes) {
        return StreamChoice(format: .fmp4, audio: true, mime: withAudio)
    }
    if let mimes = LiveCodecs.sampleMimes(video), canPlay(mimes) {
        return StreamChoice(format: .fmp4, audio: false, mime: video)
    }
    return StreamChoice(format: .mjpeg, audio: false, mime: nil)
}

public enum LiveCodecs {
    /// The decoder MIME types for `video/mp4; codecs="hvc1.1.6.L90,mp4a.40.2"`, or `nil` if the
    /// MIME has no codecs or one the app can't play.
    public static func sampleMimes(_ mime: String) -> [String]? {
        guard let match = mime.firstMatch(of: /codecs\s*=\s*"?([^";]+)"?/) else { return nil }
        var mimes: [String] = []
        for codec in match.1.split(separator: ",") {
            switch codec.trimmingCharacters(in: .whitespaces).split(separator: ".").first ?? "" {
            case "avc1", "avc3": mimes.append("video/avc")
            case "hvc1", "hev1": mimes.append("video/hevc")
            case "mp4a": mimes.append("audio/mp4a-latm")
            default: return nil
            }
        }
        return mimes
    }
}

/// Keeps live playback near the newest frame: a new viewer starts at the latest keyframe (up to a
/// GOP behind) and any stall adds delay, so play faster while too much is buffered.
public enum LiveCatchUp {
    public static let fast: Float = 1.5
    public static let behindMillis = 1_000
    public static let caughtUpMillis = 300

    public static func speed(bufferedMillis: Int, current: Float) -> Float {
        if bufferedMillis > behindMillis { return fast }
        if bufferedMillis < caughtUpMillis { return 1 }
        return current
    }
}

/// One box of a `detections` message, in frame pixels.
public struct DetectionBox: Codable, Equatable, Sendable {
    public let label: String
    public let confidence: Double
    public let color: String
    public let x1: Double
    public let y1: Double
    public let x2: Double
    public let y2: Double

    public init(label: String, confidence: Double, color: String, x1: Double, y1: Double, x2: Double, y2: Double) {
        self.label = label
        self.confidence = confidence
        self.color = color
        self.x1 = x1
        self.y1 = y1
        self.x2 = x2
        self.y2 = y2
    }

    /// Where the box lands in a `viewWidth` x `viewHeight` view showing the `frameWidth` x
    /// `frameHeight` frame fitted inside it (`object-fit: contain`).
    public func fit(frameWidth: Int, frameHeight: Int, viewWidth: Double, viewHeight: Double) -> BoxRect {
        guard frameWidth > 0, frameHeight > 0 else { return BoxRect(left: 0, top: 0, width: 0, height: 0) }
        let scale = min(viewWidth / Double(frameWidth), viewHeight / Double(frameHeight))
        let left = (viewWidth - Double(frameWidth) * scale) / 2
        let top = (viewHeight - Double(frameHeight) * scale) / 2
        return BoxRect(left: left + x1 * scale, top: top + y1 * scale, width: (x2 - x1) * scale, height: (y2 - y1) * scale)
    }
}

public struct BoxRect: Equatable, Sendable {
    public let left: Double
    public let top: Double
    public let width: Double
    public let height: Double

    public init(left: Double, top: Double, width: Double, height: Double) {
        self.left = left
        self.top = top
        self.width = width
        self.height = height
    }
}

public struct Detections: Codable, Equatable, Sendable {
    public let width: Int
    public let height: Int
    public let boxes: [DetectionBox]

    public init(width: Int, height: Int, boxes: [DetectionBox]) {
        self.width = width
        self.height = height
        self.boxes = boxes
    }
}

/// A text message of an fMP4 stream.
public enum LiveText: Equatable, Sendable {
    /// Announces the binary init segment that follows.
    case initSegment(mime: String)
    case detections(Detections)

    private struct Envelope: Decodable {
        let type: String
        let mime: String?
    }

    /// `nil` for unknown types or malformed JSON: newer servers may send more.
    public static func parse(_ text: String) -> LiveText? {
        let data = Data(text.utf8)
        guard let envelope = try? JSONDecoder().decode(Envelope.self, from: data) else { return nil }
        switch envelope.type {
        case "init": return envelope.mime.map { .initSegment(mime: $0) }
        case "detections": return (try? JSONDecoder().decode(Detections.self, from: data)).map { .detections($0) }
        default: return nil
        }
    }
}
