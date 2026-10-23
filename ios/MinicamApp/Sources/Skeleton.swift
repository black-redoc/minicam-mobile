import SwiftUI

/// Placeholder shape for content that is still loading (spec 007): `surface2` with a `border`
/// highlight sweeping left to right every 1.2 s. Every block reads the same clock, so they
/// sweep together; with Reduce Motion on they stay still.
struct SkeletonBlock: View {
    var width: CGFloat?
    /// nil fills the available height, e.g. the live view before its first frame.
    var height: CGFloat? = 14
    var cornerRadius: CGFloat = Theme.radiusSm

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        RoundedRectangle(cornerRadius: cornerRadius)
            .fill(Theme.surface2)
            .overlay {
                TimelineView(.animation(paused: reduceMotion)) { context in
                    GeometryReader { geo in
                        let band = max(min(geo.size.width, 240), geo.size.height)
                        let cycle = 1.2
                        let progress = context.date.timeIntervalSinceReferenceDate
                            .truncatingRemainder(dividingBy: cycle) / cycle
                        LinearGradient(
                            colors: [Theme.surface2, Theme.border, Theme.surface2],
                            startPoint: .leading,
                            endPoint: .trailing
                        )
                        .frame(width: band)
                        .offset(x: -band + progress * (geo.size.width + band))
                    }
                }
                .opacity(reduceMotion ? 0 : 1)
            }
            .clipShape(RoundedRectangle(cornerRadius: cornerRadius))
            .frame(width: width, height: height)
            .frame(
                maxWidth: width == nil ? .infinity : nil,
                maxHeight: height == nil ? .infinity : nil,
                alignment: .leading
            )
    }
}

extension View {
    /// Groups placeholders so VoiceOver announces them once as "Loading".
    func skeletonGroup() -> some View {
        accessibilityElement(children: .ignore).accessibilityLabel("Loading")
    }
}
