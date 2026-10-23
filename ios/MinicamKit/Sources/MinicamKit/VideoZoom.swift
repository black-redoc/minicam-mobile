import Foundation

/// Digital zoom of a video view (spec 008): the picture is scaled around its centre and panned.
/// The pan is a fraction of the view's size, so it survives switching between layouts, and is
/// kept within `±(scale - 1) / 2` so the picture never shows past its edges.
public struct VideoZoom: Equatable, Sendable {
    public static let steps: [Double] = [1, 1.5, 2, 3, 4]

    public private(set) var scale: Double
    public private(set) var panX: Double
    public private(set) var panY: Double

    public init(scale: Double = 1, panX: Double = 0, panY: Double = 0) {
        self.scale = scale
        self.panX = panX
        self.panY = panY
        clamp()
    }

    public var canZoomIn: Bool { scale < Self.steps.last! }
    public var canZoomOut: Bool { scale > Self.steps.first! }
    public var isZoomed: Bool { scale > Self.steps.first! }

    public func zoomedIn() -> VideoZoom {
        VideoZoom(scale: Self.steps.first { $0 > scale } ?? Self.steps.last!, panX: panX, panY: panY)
    }

    public func zoomedOut() -> VideoZoom {
        VideoZoom(scale: Self.steps.last { $0 < scale } ?? Self.steps.first!, panX: panX, panY: panY)
    }

    /// Moves the picture by a fraction of the view's width (`dx`) and height (`dy`).
    public func panned(dx: Double, dy: Double) -> VideoZoom {
        VideoZoom(scale: scale, panX: panX + dx, panY: panY + dy)
    }

    private mutating func clamp() {
        let limit = (scale - 1) / 2
        panX = min(max(panX, -limit), limit)
        panY = min(max(panY, -limit), limit)
    }
}
