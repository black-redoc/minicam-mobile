import AVFoundation
import MinicamKit
import Observation
import SwiftUI

/// Playback state of the recordings player, read by the custom controls (spec 006 AC10). One
/// instance serves the inline and the fullscreen layout, so switching doesn't restart the clip.
@MainActor
@Observable
final class ClipPlayback {
    let player = AVPlayer()
    private(set) var currentTime: Double = 0
    private(set) var duration: Double = 0
    private(set) var bufferedTime: Double = 0
    private(set) var isPlaying = false
    /// While the user drags the scrubber, the bar shows this instead of the player's time.
    var scrubTime: Double?

    @ObservationIgnored private var timeObserver: Any?

    init() {
        // Also fires when playback starts, stops or jumps (seek), not only while playing.
        timeObserver = player.addPeriodicTimeObserver(
            forInterval: CMTime(value: 1, timescale: 4), queue: .main
        ) { [weak self] _ in
            MainActor.assumeIsolated { self?.refresh() }
        }
    }

    /// Time the controls show: the scrubber while dragging, otherwise the player's.
    var shownTime: Double { scrubTime ?? currentTime }

    func load(_ item: AVPlayerItem?) {
        player.replaceCurrentItem(with: item)
        currentTime = 0
        duration = 0
        bufferedTime = 0
        scrubTime = nil
        if item != nil { play() } else { pause() }
    }

    func play() {
        // At the end, "play" starts the clip over, like the browser's <video>.
        if duration > 0, currentTime >= duration - 0.25 { seek(to: 0) }
        player.play()
        isPlaying = true
    }

    func pause() {
        player.pause()
        isPlaying = false
    }

    func togglePlay() {
        isPlaying ? pause() : play()
    }

    /// Spec 008 AC6: back to 0:00. A clip that had ended plays again; a paused one stays paused.
    func restart() {
        let ended = duration > 0 && currentTime >= duration - 0.25
        seek(to: 0)
        if ended { play() }
    }

    func seek(to seconds: Double) {
        let target = min(max(0, seconds), duration)
        currentTime = target
        player.seek(to: CMTime(seconds: target, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero)
    }

    private func refresh() {
        currentTime = player.currentTime().seconds.isFinite ? player.currentTime().seconds : 0
        isPlaying = player.timeControlStatus != .paused
        guard let item = player.currentItem else { return }
        if item.duration.seconds.isFinite { duration = item.duration.seconds }
        // How far the clip is loaded from the current position on.
        let now = player.currentTime()
        bufferedTime = item.loadedTimeRanges.map(\.timeRangeValue).first { $0.containsTime(now) }?.end.seconds
            ?? currentTime
    }
}

/// `AVPlayerLayer` in SwiftUI: just the picture (`object-fit: contain`), no system controls.
struct PlayerLayerView: UIViewRepresentable {
    let player: AVPlayer

    final class LayerView: UIView {
        override class var layerClass: AnyClass { AVPlayerLayer.self }
        var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
    }

    func makeUIView(context: Context) -> LayerView {
        let view = LayerView()
        view.backgroundColor = .black
        view.playerLayer.videoGravity = .resizeAspect
        view.playerLayer.player = player
        return view
    }

    func updateUIView(_ view: LayerView, context: Context) {
        view.playerLayer.player = player
    }
}

/// The video with minicam-styled controls: play/pause, time, fullscreen and a time bar whose
/// played part is the brand sky, clearly apart from the buffered and remaining track (AC10).
/// Tapping the video shows or hides the controls; they hide by themselves while playing.
/// "Back" starts the clip over and "next" asks for the clip below; zoom buttons show with the
/// controls (spec 008).
struct ClipPlayerView: View {
    let playback: ClipPlayback
    let isFullscreen: Bool
    @Binding var zoom: VideoZoom
    let hasNext: Bool
    let onNext: () -> Void
    let onFullscreen: (Bool) -> Void
    @State private var controlsVisible = true
    @State private var hideTask: Task<Void, Never>?

    var body: some View {
        ZStack {
            PlayerLayerView(player: playback.player)
                .zoomPan($zoom)
                .contentShape(Rectangle())
                .onTapGesture { controlsVisible ? hideControls() : showControls() }
            Color.black.opacity(controlsVisible ? 0.3 : 0)
                .allowsHitTesting(false)
            controls
                .opacity(controlsVisible ? 1 : 0)
                .allowsHitTesting(controlsVisible)
        }
        .animation(.easeInOut(duration: 0.2), value: controlsVisible)
        .onChange(of: playback.isPlaying) { _, playing in
            if playing { scheduleHide() } else { showControls(autoHide: false) }
        }
        .onDisappear { hideTask?.cancel() }
    }

    private var controls: some View {
        ZStack {
            HStack(spacing: 28) {
                GlassIconButton(label: "Back to start", systemImage: "backward.end.fill", size: 40) {
                    playback.restart()
                    scheduleHide()
                }
                GlassIconButton(
                    label: playback.isPlaying ? "Pause" : "Play",
                    systemImage: playback.isPlaying ? "pause.fill" : "play.fill",
                    size: 52
                ) {
                    playback.togglePlay()
                    scheduleHide()
                }
                GlassIconButton(label: "Next clip", systemImage: "forward.end.fill", size: 40) {
                    onNext()
                    scheduleHide()
                }
                .disabled(!hasNext)
            }
            ZoomButtons(zoom: $zoom, size: 36)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
                .padding(10)
            VStack(spacing: 4) {
                Spacer()
                HStack {
                    Text("\(formatClipDuration(playback.shownTime)) / \(formatClipDuration(playback.duration))")
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(.white)
                    Spacer()
                    GlassIconButton(
                        label: isFullscreen ? "Exit fullscreen" : "Enter fullscreen",
                        systemImage: isFullscreen
                            ? "arrow.down.right.and.arrow.up.left" : "arrow.up.left.and.arrow.down.right",
                        size: 36
                    ) { onFullscreen(!isFullscreen) }
                }
                ClipTimeBar(playback: playback) { dragging in
                    if dragging { showControls(autoHide: false) } else { scheduleHide() }
                }
            }
            .padding(.horizontal, 12)
            .padding(.bottom, 6)
        }
    }

    private func showControls(autoHide: Bool = true) {
        controlsVisible = true
        if autoHide { scheduleHide() } else { hideTask?.cancel() }
    }

    private func hideControls() {
        hideTask?.cancel()
        controlsVisible = false
    }

    /// Hides the controls after 3 s of playback without touches.
    private func scheduleHide() {
        hideTask?.cancel()
        hideTask = Task {
            try? await Task.sleep(for: .seconds(3))
            if !Task.isCancelled, playback.isPlaying, playback.scrubTime == nil { controlsVisible = false }
        }
    }
}

/// Played (sky) / buffered (white 40%) / remaining (white 15%) track with a scrubber; drag or
/// tap anywhere on it to seek.
private struct ClipTimeBar: View {
    let playback: ClipPlayback
    let onDragging: (Bool) -> Void

    var body: some View {
        GeometryReader { geometry in
            let width = geometry.size.width
            let played = width * clipFraction(playback.shownTime, of: playback.duration)
            let buffered = width * clipFraction(playback.bufferedTime, of: playback.duration)
            let scrubbing = playback.scrubTime != nil
            ZStack(alignment: .leading) {
                Capsule().fill(.white.opacity(0.15))
                Capsule().fill(.white.opacity(0.4)).frame(width: buffered)
                Capsule().fill(Theme.accentHover).frame(width: played)
            }
            .frame(height: 3)
            .overlay(alignment: .leading) {
                let size: CGFloat = scrubbing ? 14 : 10
                Circle().fill(Theme.accentHover).frame(width: size, height: size).offset(x: played - size / 2)
            }
            .frame(maxHeight: .infinity)
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { drag in
                        if playback.scrubTime == nil { onDragging(true) }
                        playback.scrubTime = clipFraction(drag.location.x, of: width) * playback.duration
                    }
                    .onEnded { drag in
                        playback.seek(to: clipFraction(drag.location.x, of: width) * playback.duration)
                        playback.scrubTime = nil
                        onDragging(false)
                    }
            )
        }
        .frame(height: 28)
        .accessibilityElement()
        .accessibilityLabel("Playback position")
        .accessibilityValue("\(formatClipDuration(playback.shownTime)) of \(formatClipDuration(playback.duration))")
        .accessibilityAdjustableAction { direction in
            let step: Double = direction == .increment ? 5 : -5
            playback.seek(to: playback.currentTime + step)
        }
    }
}
