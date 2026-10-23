import Foundation

/// Hand-built fMP4 boxes shaped like minicam's live stream (FFmpeg `empty_moov` + `default_base_moof`).
enum Fmp4Fixtures {
    static func u16(_ value: Int) -> [UInt8] { (0..<2).map { UInt8(truncatingIfNeeded: value >> (8 * (1 - $0))) } }
    static func u32(_ value: Int) -> [UInt8] { (0..<4).map { UInt8(truncatingIfNeeded: value >> (8 * (3 - $0))) } }
    static func u64(_ value: Int) -> [UInt8] { (0..<8).map { UInt8(truncatingIfNeeded: value >> (8 * (7 - $0))) } }

    static func box(_ type: String, _ payload: [UInt8]...) -> [UInt8] {
        let body = payload.flatMap { $0 }
        return u32(8 + body.count) + Array(type.utf8) + body
    }

    static func fullBox(_ type: String, _ version: Int, _ flags: Int, _ payload: [UInt8]...) -> [UInt8] {
        box(type, u32(version << 24 | flags), payload.flatMap { $0 })
    }

    static func trak(_ id: Int, _ handler: String, _ timescale: Int, entry: [UInt8]) -> [UInt8] {
        box(
            "trak",
            fullBox("tkhd", 0, 3, u32(0), u32(0), u32(id), [UInt8](repeating: 0, count: 68)),
            box(
                "mdia",
                fullBox("mdhd", 0, 0, u32(0), u32(0), u32(timescale), u32(0), [0, 0, 0, 0]),
                fullBox("hdlr", 0, 0, u32(0), Array(handler.utf8), [UInt8](repeating: 0, count: 12), Array("minicam\0".utf8)),
                box("minf", box("stbl", fullBox("stsd", 0, 0, u32(1), entry)))
            )
        )
    }

    /// 640x360 HEVC track 1 at 90 kHz and stereo 16 kHz AAC track 2.
    static func initSegment() -> Data {
        let hvc1 = box(
            "hvc1", [UInt8](repeating: 0, count: 24), u16(640), u16(360), [UInt8](repeating: 0, count: 50),
            box("hvcC", [1, 2, 3])
        )
        let mp4a = box(
            "mp4a", [UInt8](repeating: 0, count: 16), u16(2), u16(16), u32(0), u32(16_000 << 16),
            fullBox("esds", 0, 0, [3, 4, 5])
        )
        return Data(
            box("ftyp", Array("isom".utf8), u32(512))
                + box("moov", fullBox("mvhd", 0, 0, [UInt8](repeating: 0, count: 96)),
                      trak(1, "vide", 90_000, entry: hvc1), trak(2, "soun", 16_000, entry: mp4a), box("mvex"))
        )
    }

    struct Traf {
        var trackID: Int
        var decodeTime: Int
        var sizes: [Int]
        var sync = true
    }

    /// moof + mdat; the samples' bytes count up from 0 so tests can check where each one is.
    static func fragment(_ trafs: Traf...) -> Data {
        func moof(_ offsets: [Int]) -> [UInt8] {
            box("moof", fullBox("mfhd", 0, 0, u32(1)), trafs.enumerated().flatMap { index, traf in
                box(
                    "traf",
                    // default-base-is-moof | default duration | default size | default flags
                    fullBox("tfhd", 0, 0x020038, u32(traf.trackID), u32(3_000), u32(0), u32(0x10000)),
                    fullBox("tfdt", 1, 0, u64(traf.decodeTime)),
                    // data-offset | first-sample-flags | per-sample size
                    fullBox("trun", 0, 0x000205, u32(traf.sizes.count), u32(offsets[index]),
                            u32(traf.sync ? 0x2000000 : 0x10000), traf.sizes.flatMap { u32($0) })
                )
            })
        }
        var offset = moof(trafs.map { _ in 0 }).count + 8
        let offsets = trafs.map { traf in defer { offset += traf.sizes.reduce(0, +) }; return offset }
        let total = trafs.reduce(0) { $0 + $1.sizes.reduce(0, +) }
        return Data(moof(offsets) + box("mdat", (0..<total).map { UInt8(truncatingIfNeeded: $0) }))
    }
}
