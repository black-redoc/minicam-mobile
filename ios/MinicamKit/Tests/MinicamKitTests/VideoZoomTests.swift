import Testing
@testable import MinicamKit

/// docs/specs/008: digital zoom.
@Suite struct VideoZoomTests {
    @Test func videoZoom_stepsBetweenLimits() {
        var zoom = VideoZoom()
        #expect(!zoom.canZoomOut)
        var scales = [zoom.scale]
        while zoom.canZoomIn {
            zoom = zoom.zoomedIn()
            scales.append(zoom.scale)
        }
        #expect(scales == [1, 1.5, 2, 3, 4])
        #expect(zoom.zoomedIn().scale == 4)
        #expect(zoom.canZoomOut)
        #expect(zoom.zoomedOut().scale == 3)
        #expect(VideoZoom().zoomedOut().scale == 1)
    }

    @Test func videoZoom_panIsClamped() {
        // Not zoomed: nothing to pan.
        #expect(VideoZoom().panned(dx: 0.3, dy: -0.3) == VideoZoom())

        // 3×: the picture may move a whole view width either way, no further.
        let zoomed = VideoZoom(scale: 3).panned(dx: 5, dy: -0.4)
        #expect(zoomed.panX == 1)
        #expect(zoomed.panY == -0.4)

        // Zooming out to 2× pulls the pan back inside ±0.5.
        let out = zoomed.zoomedOut()
        #expect(out.scale == 2)
        #expect(out.panX == 0.5)
        #expect(out.panY == -0.4)
        #expect(out.zoomedOut().zoomedOut().panX == 0)
    }
}
