import Foundation
import Testing
@testable import MinicamKit

@Suite struct Fmp4Tests {
    typealias Traf = Fmp4Fixtures.Traf

    @Test func fmp4_parsesInitSegment() throws {
        let tracks = try Fmp4.parseInit(Fmp4Fixtures.initSegment())

        #expect(tracks.map(\.id) == [1, 2])
        #expect(tracks.map(\.kind) == [.video, .audio])
        #expect(tracks.map(\.timescale) == [90_000, 16_000])
        #expect(tracks.map(\.codec) == ["hvc1", "mp4a"])
        #expect(tracks[0].config == Data([1, 2, 3]))
        #expect(tracks[0].width == 640 && tracks[0].height == 360)
        // The esds payload without its full-box header is the AAC magic cookie.
        #expect(tracks[1].config == Data([3, 4, 5]))
        #expect(tracks[1].channels == 2 && tracks[1].sampleRate == 16_000)
    }

    @Test func fmp4_parsesFragment() throws {
        let fragment = Fmp4Fixtures.fragment(
            Traf(trackID: 1, decodeTime: 900_000, sizes: [3]),
            Traf(trackID: 2, decodeTime: 160_000, sizes: [2, 1])
        )

        let trafs = try Fmp4.parseFragment(fragment)

        #expect(trafs.map(\.trackID) == [1, 2])
        #expect(trafs.map(\.baseDecodeTime) == [900_000, 160_000])
        // Samples follow each other in mdat; the bytes count up from 0.
        #expect(trafs[0].samples.map(\.data) == [Data([0, 1, 2])])
        #expect(trafs[1].samples.map(\.data) == [Data([3, 4]), Data([5])])
        #expect(trafs[1].samples.map(\.decodeTime) == [160_000, 163_000])
        // Only the first sample takes first-sample-flags; the rest use tfhd's non-sync default.
        #expect(trafs[1].samples.map(\.isSync) == [true, false])
        let nonKey = try Fmp4.parseFragment(Fmp4Fixtures.fragment(Traf(trackID: 1, decodeTime: 0, sizes: [1], sync: false)))
        #expect(nonKey.first?.samples.first?.isSync == false)
    }

    @Test func fmp4_rejectsMalformedBoxes() {
        let fragment = Fmp4Fixtures.fragment(Traf(trackID: 1, decodeTime: 0, sizes: [10]))
        // Truncated, a box claiming more bytes than there are, and samples past the data.
        #expect(throws: Fmp4Error.self) { try Fmp4.parseFragment(fragment.dropLast(3)) }
        var oversized = fragment
        oversized.replaceSubrange(0..<4, with: Fmp4Fixtures.u32(0x7FFF_FFFF))
        #expect(throws: Fmp4Error.self) { try Fmp4.parseFragment(oversized) }
        #expect(throws: Fmp4Error.self) { try Fmp4.parseInit(Data([0, 0, 0])) }
        #expect(throws: Fmp4Error.self) { try Fmp4.parseInit(Data(Fmp4Fixtures.box("ftyp"))) }
        let pastData = Data(Fmp4Fixtures.box(
            "moof",
            Fmp4Fixtures.box(
                "traf",
                Fmp4Fixtures.fullBox("tfhd", 0, 0x020000, Fmp4Fixtures.u32(1)),
                Fmp4Fixtures.fullBox("tfdt", 0, 0, Fmp4Fixtures.u32(0)),
                Fmp4Fixtures.fullBox("trun", 0, 0x201, Fmp4Fixtures.u32(1), Fmp4Fixtures.u32(0), Fmp4Fixtures.u32(1_000))
            )
        ))
        #expect(throws: Fmp4Error.self) { try Fmp4.parseFragment(pastData) }
    }
}
