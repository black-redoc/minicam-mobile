import Foundation
import Testing
@testable import MinicamKit

@Suite struct LiveStreamTests {
    let hevc = #"video/mp4; codecs="hvc1.1.6.L90""#
    let hevcAAC = #"video/mp4; codecs="hvc1.1.6.L90,mp4a.40.2""#

    @Test func streamFormat_fallsBackToMjpeg() {
        let token = StreamToken(token: "t", expiresIn: 30, videoMime: hevc, audioMime: hevcAAC)
        let everything: ([String]) -> Bool = { _ in true }
        let noHEVC: ([String]) -> Bool = { !$0.contains("video/hevc") }

        #expect(chooseStream(token, preferFMP4: true, audio: false, canPlay: everything)
            == StreamChoice(format: .fmp4, audio: false, mime: hevc))
        #expect(chooseStream(token, preferFMP4: true, audio: true, canPlay: everything)
            == StreamChoice(format: .fmp4, audio: true, mime: hevcAAC))
        // Codec not decodable, codec unknown yet, or fMP4 failed before: MJPEG, never audio.
        let mjpeg = StreamChoice(format: .mjpeg, audio: false, mime: nil)
        #expect(chooseStream(token, preferFMP4: true, audio: true, canPlay: noHEVC) == mjpeg)
        #expect(chooseStream(StreamToken(token: "t", expiresIn: 30), preferFMP4: true, audio: true, canPlay: everything) == mjpeg)
        #expect(chooseStream(token, preferFMP4: false, audio: true, canPlay: everything) == mjpeg)
        // Audio asked for but not offered (no can_listen): video only.
        #expect(chooseStream(StreamToken(token: "t", expiresIn: 30, videoMime: hevc), preferFMP4: true, audio: true, canPlay: everything)
            == StreamChoice(format: .fmp4, audio: false, mime: hevc))
        #expect(LiveCodecs.sampleMimes(hevcAAC) == ["video/hevc", "audio/mp4a-latm"])
        #expect(LiveCodecs.sampleMimes(#"video/mp4; codecs="avc1.64001f""#) == ["video/avc"])
        #expect(LiveCodecs.sampleMimes(#"video/mp4; codecs="vp09.00.10.08""#) == nil)
        #expect(LiveCodecs.sampleMimes("video/mp4") == nil)
    }

    @Test func liveCatchUp_speedsUpWhenBehind() {
        #expect(LiveCatchUp.speed(bufferedMillis: 1_500, current: 1) == LiveCatchUp.fast)
        // Between the thresholds the speed holds, so it doesn't flap.
        #expect(LiveCatchUp.speed(bufferedMillis: 600, current: LiveCatchUp.fast) == LiveCatchUp.fast)
        #expect(LiveCatchUp.speed(bufferedMillis: 600, current: 1) == 1)
        #expect(LiveCatchUp.speed(bufferedMillis: 200, current: LiveCatchUp.fast) == 1)
    }

    @Test func detections_parseMessage() {
        let message = #"""
        {"type": "detections", "width": 1920, "height": 1080,
         "boxes": [{"label": "person", "confidence": 0.87, "color": "#ff0000", "x1": 10, "y1": 20, "x2": 110, "y2": 220}]}
        """#

        #expect(LiveText.parse(message) == .detections(Detections(width: 1920, height: 1080, boxes: [
            DetectionBox(label: "person", confidence: 0.87, color: "#ff0000", x1: 10, y1: 20, x2: 110, y2: 220),
        ])))
        #expect(LiveText.parse(#"{"type": "init", "mime": "video/mp4; codecs=\"hvc1.1.6.L90\""}"#) == .initSegment(mime: hevc))
        #expect(LiveText.parse(#"{"type": "stats"}"#) == nil)
        #expect(LiveText.parse("not json") == nil)
    }

    @Test func detectionBox_fitsContainedFrame() {
        let box = DetectionBox(label: "car", confidence: 0.5, color: "#00ff00", x1: 960, y1: 0, x2: 1920, y2: 540)

        // 1920x1080 in a 4:3 view: full width, letterboxed top and bottom by 75 px each.
        #expect(box.fit(frameWidth: 1920, frameHeight: 1080, viewWidth: 800, viewHeight: 600)
            == BoxRect(left: 400, top: 75, width: 400, height: 225))
        // Same aspect: plain scaling.
        #expect(box.fit(frameWidth: 1920, frameHeight: 1080, viewWidth: 160, viewHeight: 90)
            == BoxRect(left: 80, top: 0, width: 80, height: 45))
        #expect(box.fit(frameWidth: 0, frameHeight: 0, viewWidth: 160, viewHeight: 90) == BoxRect(left: 0, top: 0, width: 0, height: 0))
    }
}
