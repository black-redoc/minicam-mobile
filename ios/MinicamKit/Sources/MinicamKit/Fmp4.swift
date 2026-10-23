import Foundation

/// Live fMP4 bytes the server sent don't match the format (ADR 0006): drop them.
public struct Fmp4Error: Error, Equatable {
    public let reason: String

    public init(reason: String) {
        self.reason = reason
    }
}

/// Minimal ISO BMFF reader for minicam's live fMP4 (an init segment, then `moof` + `mdat`
/// fragments). Every size is checked against the data: bad input throws `Fmp4Error`, never
/// reads out of bounds.
public enum Fmp4 {
    public enum Kind: Equatable, Sendable {
        case video, audio, other
    }

    public struct Track: Equatable, Sendable {
        public let id: Int
        public let kind: Kind
        public let timescale: Int
        /// Sample entry type: `avc1`, `hvc1`, `hev1`, `mp4a`, …
        public let codec: String
        /// Video: the `avcC` / `hvcC` payload. Audio: the `esds` ES descriptor (the AAC magic
        /// cookie). Empty if the track has neither.
        public let config: Data
        public let width: Int
        public let height: Int
        public let channels: Int
        public let sampleRate: Int
    }

    public struct Sample: Equatable, Sendable {
        public let decodeTime: Int64
        public let duration: Int64
        public let compositionOffset: Int64
        public let isSync: Bool
        public let data: Data
    }

    public struct TrackFragment: Equatable, Sendable {
        public let trackID: Int
        public let baseDecodeTime: Int64
        public let samples: [Sample]
    }

    public static func parseInit(_ data: Data) throws -> [Track] {
        let bytes = [UInt8](data)
        guard let moov = try children(bytes, 0, bytes.count).first(where: { $0.type == "moov" }) else {
            throw Fmp4Error(reason: "No moov box")
        }
        return try children(bytes, moov.start, moov.end).filter { $0.type == "trak" }.map { try trak(bytes, $0) }
    }

    public static func parseFragment(_ data: Data) throws -> [TrackFragment] {
        let bytes = [UInt8](data)
        let boxes = try children(bytes, 0, bytes.count)
        guard let moof = boxes.first(where: { $0.type == "moof" }) else { throw Fmp4Error(reason: "No moof box") }
        return try children(bytes, moof.start, moof.end).filter { $0.type == "traf" }.map {
            try traf(bytes, $0, moofStart: moof.start - moof.header, end: bytes.count)
        }
    }

    // MARK: - Boxes

    private struct Box {
        let type: String
        let start: Int
        let end: Int
        let header: Int
    }

    /// The boxes in bytes[start..<end], with each payload's bounds.
    private static func children(_ bytes: [UInt8], _ start: Int, _ end: Int) throws -> [Box] {
        var boxes: [Box] = []
        var offset = start
        while end - offset >= 8 {
            var size = try uint(bytes, offset, 4, end)
            let type = String(decoding: bytes[(offset + 4)..<(offset + 8)], as: UTF8.self)
            var header = 8
            if size == 1 {
                size = try uint(bytes, offset + 8, 8, end)
                header = 16
            } else if size == 0 {
                size = UInt64(end - offset)
            }
            guard size >= UInt64(header), size <= UInt64(end - offset) else { throw Fmp4Error(reason: "Bad \(type) box size") }
            boxes.append(Box(type: type, start: offset + header, end: offset + Int(size), header: header))
            offset += Int(size)
        }
        guard offset == end else { throw Fmp4Error(reason: "Trailing bytes") }
        return boxes
    }

    private static func child(_ bytes: [UInt8], _ parent: Box, _ type: String) throws -> Box? {
        try children(bytes, parent.start, parent.end).first { $0.type == type }
    }

    private static func path(_ bytes: [UInt8], _ parent: Box, _ types: String...) throws -> Box? {
        var box: Box? = parent
        for type in types {
            guard let current = box else { return nil }
            box = try child(bytes, current, type)
        }
        return box
    }

    private static func trak(_ bytes: [UInt8], _ trak: Box) throws -> Track {
        guard let tkhd = try child(bytes, trak, "tkhd") else { throw Fmp4Error(reason: "No tkhd") }
        let trackID = try uint(bytes, tkhd.start + (try byte(bytes, tkhd.start, tkhd.end) == 1 ? 20 : 12), 4, tkhd.end)
        guard let mdhd = try path(bytes, trak, "mdia", "mdhd") else { throw Fmp4Error(reason: "No mdhd") }
        let timescale = try uint(bytes, mdhd.start + (try byte(bytes, mdhd.start, mdhd.end) == 1 ? 20 : 12), 4, mdhd.end)
        guard timescale > 0, timescale <= Int32.max else { throw Fmp4Error(reason: "Bad timescale") }
        guard let hdlr = try path(bytes, trak, "mdia", "hdlr"), hdlr.end - hdlr.start >= 12 else {
            throw Fmp4Error(reason: "No hdlr")
        }
        let kind: Kind = switch String(decoding: bytes[(hdlr.start + 8)..<(hdlr.start + 12)], as: UTF8.self) {
        case "vide": .video
        case "soun": .audio
        default: .other
        }

        var codec = ""
        var config = Data()
        var width = 0, height = 0, channels = 0, sampleRate = 0
        if let stsd = try path(bytes, trak, "mdia", "minf", "stbl", "stsd"), stsd.end - stsd.start >= 8 {
            // Full box header + entry count, then the first sample entry.
            if let entry = try children(bytes, stsd.start + 8, stsd.end).first {
                codec = entry.type
                // SampleEntry: 6 reserved + data reference index; then the visual/audio fields.
                switch kind {
                case .video where entry.end - entry.start >= 78:
                    width = Int(try uint(bytes, entry.start + 24, 2, entry.end))
                    height = Int(try uint(bytes, entry.start + 26, 2, entry.end))
                    let atoms = try children(bytes, entry.start + 78, entry.end)
                    if let atom = atoms.first(where: { $0.type == "avcC" || $0.type == "hvcC" }) {
                        config = Data(bytes[atom.start..<atom.end])
                    }
                case .audio where entry.end - entry.start >= 28:
                    channels = Int(try uint(bytes, entry.start + 16, 2, entry.end))
                    sampleRate = Int(try uint(bytes, entry.start + 24, 4, entry.end) >> 16)
                    let atoms = try children(bytes, entry.start + 28, entry.end)
                    if let esds = atoms.first(where: { $0.type == "esds" }), esds.end - esds.start > 4 {
                        config = Data(bytes[(esds.start + 4)..<esds.end])
                    }
                default:
                    break
                }
            }
        }
        return Track(
            id: Int(trackID), kind: kind, timescale: Int(timescale), codec: codec, config: config,
            width: width, height: height, channels: channels, sampleRate: sampleRate
        )
    }

    private struct Defaults {
        var duration: UInt64 = 0
        var size: UInt64 = 0
        var flags: UInt32 = 0
    }

    private static func traf(_ bytes: [UInt8], _ traf: Box, moofStart: Int, end: Int) throws -> TrackFragment {
        guard let tfhd = try child(bytes, traf, "tfhd") else { throw Fmp4Error(reason: "No tfhd") }
        let tfhdFlags = try uint(bytes, tfhd.start, 4, tfhd.end) & 0xFFFFFF
        var offset = tfhd.start + 4
        let trackID = Int(try uint(bytes, offset, 4, tfhd.end))
        offset += 4
        // default-base-is-moof (minicam) unless an explicit base data offset is given.
        var base = moofStart
        if tfhdFlags & 0x01 != 0 {
            let explicit = try uint(bytes, offset, 8, tfhd.end)
            guard explicit <= UInt64(end) else { throw Fmp4Error(reason: "Bad base data offset") }
            base = Int(explicit)
            offset += 8
        }
        if tfhdFlags & 0x02 != 0 { offset += 4 }
        var defaults = Defaults()
        if tfhdFlags & 0x08 != 0 { defaults.duration = try uint(bytes, offset, 4, tfhd.end); offset += 4 }
        if tfhdFlags & 0x10 != 0 { defaults.size = try uint(bytes, offset, 4, tfhd.end); offset += 4 }
        if tfhdFlags & 0x20 != 0 { defaults.flags = UInt32(try uint(bytes, offset, 4, tfhd.end)) }

        guard let tfdt = try child(bytes, traf, "tfdt") else { throw Fmp4Error(reason: "No tfdt") }
        let tfdtVersion = try byte(bytes, tfdt.start, tfdt.end)
        let time = try uint(bytes, tfdt.start + 4, tfdtVersion == 1 ? 8 : 4, tfdt.end)
        guard time <= Int64.max / 2 else { throw Fmp4Error(reason: "Bad decode time") }
        let baseDecodeTime = Int64(time)

        var samples: [Sample] = []
        var decodeTime = baseDecodeTime
        var dataOffset = base
        for trun in try children(bytes, traf.start, traf.end) where trun.type == "trun" {
            try readTrun(bytes, trun, defaults: defaults, base: base, end: end, dataOffset: &dataOffset) { duration, size, flags, cto, start in
                samples.append(Sample(
                    decodeTime: decodeTime, duration: duration, compositionOffset: cto,
                    isSync: flags & nonSync == 0, data: Data(bytes[start..<(start + size)])
                ))
                decodeTime += duration
            }
        }
        return TrackFragment(trackID: trackID, baseDecodeTime: baseDecodeTime, samples: samples)
    }

    private static let nonSync: UInt32 = 0x10000
    // One fragment holds one video frame plus the audio in between: far below this.
    private static let maxSamples: UInt64 = 10_000

    private static func readTrun(
        _ bytes: [UInt8], _ trun: Box, defaults: Defaults, base: Int, end: Int, dataOffset: inout Int,
        sample: (_ duration: Int64, _ size: Int, _ flags: UInt32, _ cto: Int64, _ start: Int) throws -> Void
    ) throws {
        let header = try uint(bytes, trun.start, 4, trun.end)
        let version = header >> 24
        let flags = header & 0xFFFFFF
        let count = try uint(bytes, trun.start + 4, 4, trun.end)
        guard count <= maxSamples else { throw Fmp4Error(reason: "Too many samples") }
        var offset = trun.start + 8
        if flags & 0x001 != 0 {
            // Signed, relative to the base; a later run without it continues after the previous.
            let relative = Int(Int32(bitPattern: UInt32(try uint(bytes, offset, 4, trun.end))))
            dataOffset = base + relative
            offset += 4
        }
        var firstFlags: UInt32?
        if flags & 0x004 != 0 { firstFlags = UInt32(try uint(bytes, offset, 4, trun.end)); offset += 4 }
        for index in 0..<Int(count) {
            var duration = defaults.duration
            var size = defaults.size
            var sampleFlags = index == 0 ? firstFlags ?? defaults.flags : defaults.flags
            var cto: Int64 = 0
            if flags & 0x100 != 0 { duration = try uint(bytes, offset, 4, trun.end); offset += 4 }
            if flags & 0x200 != 0 { size = try uint(bytes, offset, 4, trun.end); offset += 4 }
            if flags & 0x400 != 0 { sampleFlags = UInt32(try uint(bytes, offset, 4, trun.end)); offset += 4 }
            if flags & 0x800 != 0 {
                let raw = UInt32(try uint(bytes, offset, 4, trun.end))
                cto = version == 1 ? Int64(Int32(bitPattern: raw)) : Int64(raw)
                offset += 4
            }
            guard dataOffset >= 0, dataOffset <= end, size <= UInt64(end - dataOffset) else { throw Fmp4Error(reason: "Sample outside the data") }
            try sample(Int64(duration), Int(size), sampleFlags, cto, dataOffset)
            dataOffset += Int(size)
        }
    }

    private static func byte(_ bytes: [UInt8], _ offset: Int, _ end: Int) throws -> UInt8 {
        guard offset >= 0, offset < end else { throw Fmp4Error(reason: "Short box") }
        return bytes[offset]
    }

    private static func uint(_ bytes: [UInt8], _ offset: Int, _ count: Int, _ end: Int) throws -> UInt64 {
        guard offset >= 0, offset + count <= end else { throw Fmp4Error(reason: "Short box") }
        var value: UInt64 = 0
        for index in offset..<(offset + count) { value = value << 8 | UInt64(bytes[index]) }
        guard value <= UInt64(Int64.max) else { throw Fmp4Error(reason: "Value too large") }
        return value
    }
}
