import MinicamKit
import SwiftUI

/// Game-controller thumbstick for PTZ: a round base with the four arrows and a knob that follows
/// the finger. Pressing the knob sends `stop` at once; dragging it past the dead zone (measured
/// from where the thumb landed, so a press slightly off-centre is not a move) moves the camera on
/// the dominant axis. Pressing an arrow moves straight away. Letting go springs the knob back to
/// the centre and sends `stop` if it was moving. While a direction is held the dispatcher keeps
/// re-sending it, so the camera turns for as long as the thumb stays there.
/// `PTZDispatcher` keeps at most one request in flight. `glass` draws a translucent base for the
/// fullscreen overlay (spec 004).
struct PTZPad: View {
    let api: MinicamAPI
    let camera: String
    var glass = false
    let onError: (Error) -> Void

    private static let padSize = Theme.ptzSize * 3 + 12
    private static let knobSize: CGFloat = 64
    private static let travel: CGFloat = 30
    private static let deadZone: CGFloat = 22

    @State private var dispatcher: PTZDispatcher?
    @State private var knob: CGSize = .zero
    @State private var active: PTZAction?
    /// Where the glow wedge points; kept after release so it fades out in place.
    @State private var lastDirection: PTZAction = .up
    @State private var held = false
    /// Where the deflection is measured from during this press: the landing point on the knob,
    /// the centre on the arrow ring.
    @State private var origin: CGPoint?
    /// True while the drag lasts; it also resets when the system cancels the gesture (a scroll
    /// view taking the touch), where `onEnded` never runs, so the camera still gets `stop`.
    @GestureState private var dragging = false

    var body: some View {
        ZStack {
            base
            arrow(.up, systemImage: "arrowtriangle.up.fill").frame(maxHeight: .infinity, alignment: .top).padding(.top, 10)
            arrow(.down, systemImage: "arrowtriangle.down.fill").frame(maxHeight: .infinity, alignment: .bottom).padding(.bottom, 10)
            arrow(.left, systemImage: "arrowtriangle.left.fill").frame(maxWidth: .infinity, alignment: .leading).padding(.leading, 12)
            arrow(.right, systemImage: "arrowtriangle.right.fill").frame(maxWidth: .infinity, alignment: .trailing).padding(.trailing, 12)
            stick
        }
        .frame(width: Self.padSize, height: Self.padSize)
        .contentShape(Circle())
        .gesture(
            DragGesture(minimumDistance: 0)
                .updating($dragging) { _, state, _ in state = true }
                .onChanged { track($0.location) }
                .onEnded { _ in release() }
        )
        .onChange(of: dragging) { _, now in
            if !now, origin != nil { release() }
        }
    }

    // MARK: Drawing

    /// Round well with a recessed ring and a glow wedge towards the active direction.
    private var base: some View {
        let edge = glass ? Color.white.opacity(0.25) : Theme.border
        return ZStack {
            Circle().fill(glass ? Theme.bg.opacity(0.35) : Theme.surface2)
            Circle().stroke(edge)
            Circle()
                .fill(Color.black.opacity(glass ? 0.18 : 0.22))
                .overlay(Circle().stroke(edge))
                .frame(width: Self.padSize * 0.62, height: Self.padSize * 0.62)
            Circle()
                .trim(from: 0, to: 70.0 / 360)
                .stroke(Theme.accent.opacity(0.55), style: StrokeStyle(lineWidth: 4, lineCap: .round))
                .rotationEffect(.degrees(Self.angle(of: lastDirection) - 35))
                .padding(3)
                .opacity(active == nil ? 0 : 1)
                .animation(.easeOut(duration: 0.2), value: active)
        }
    }

    private func arrow(_ action: PTZAction, systemImage: String) -> some View {
        let on = active == action
        return Image(systemName: systemImage)
            .font(.system(size: 13))
            .foregroundStyle(on ? Theme.accentHover : Theme.muted)
            .scaleEffect(on ? 1.3 : 1)
            .animation(.spring(response: 0.25, dampingFraction: 0.5), value: on)
            .accessibilityLabel("PTZ \(action.rawValue)")
            .accessibilityAddTraits(.isButton)
            .accessibilityAction { nudge(action) }
    }

    /// The thumbstick cap: domed, with grip rings, leaning towards where it is pushed.
    private var stick: some View {
        let on = active != nil
        let alpha = glass ? 0.92 : 1
        let top = on ? Theme.accentHover : Color(hex: 0x3A4A63)
        let bottom = on ? Theme.accent : Color(hex: 0x1F2937)
        let grip = Color.white.opacity(on ? 0.35 : 0.14)
        return ZStack {
            Circle().fill(LinearGradient(colors: [top.opacity(alpha), bottom.opacity(alpha)], startPoint: .top, endPoint: .bottom))
            Circle().stroke(grip, lineWidth: 1.5).frame(width: Self.knobSize * 0.62, height: Self.knobSize * 0.62)
            Circle().stroke(grip, lineWidth: 1.5).frame(width: Self.knobSize * 0.38, height: Self.knobSize * 0.38)
            // Specular highlight near the top of the dome.
            Circle()
                .fill(RadialGradient(colors: [.white.opacity(0.28), .clear], center: UnitPoint(x: 0.5, y: 0.3), startRadius: 0, endRadius: Self.knobSize * 0.35))
            Circle().stroke(on ? Theme.accent : Theme.border, lineWidth: 2)
        }
        .frame(width: Self.knobSize, height: Self.knobSize)
        .shadow(color: .black.opacity(0.45), radius: held ? 2 : 5, y: held ? 1 : 3)
        .scaleEffect(held ? 0.94 : 1)
        // Lean the cap towards the push, like a real stick pivoting in its socket.
        .rotation3DEffect(.degrees(Double(-knob.height / Self.travel) * 14), axis: (x: 1, y: 0, z: 0), perspective: 0.6)
        .rotation3DEffect(.degrees(Double(knob.width / Self.travel) * 14), axis: (x: 0, y: 1, z: 0), perspective: 0.6)
        .offset(knob)
        .animation(.easeOut(duration: 0.12), value: on)
        .accessibilityHidden(true)
    }

    // MARK: Input

    private func track(_ location: CGPoint) {
        let centre = CGPoint(x: Self.padSize / 2, y: Self.padSize / 2)
        if origin == nil {
            held = true
            let onKnob = hypot(location.x - centre.x, location.y - centre.y) <= Self.knobSize / 2
            origin = onKnob ? location : centre
            if onKnob { dispatch(.stop) }
        }
        let start = origin ?? centre
        let dx = location.x - start.x
        let dy = location.y - start.y
        let distance = (dx * dx + dy * dy).squareRoot()
        let scale = distance > Self.travel ? Self.travel / distance : 1
        withAnimation(.interactiveSpring(response: 0.15, dampingFraction: 0.8)) {
            knob = CGSize(width: dx * scale, height: dy * scale)
        }
        let next = PTZJoystick.direction(dx: dx, dy: dy, deadZone: Self.deadZone, current: active)
        if let command = PTZJoystick.transition(from: active, to: next) { dispatch(command) }
        active = next
        if let next { lastDirection = next }
    }

    private func release() {
        if active != nil { dispatch(.stop) }
        active = nil
        held = false
        origin = nil
        withAnimation(.spring(response: 0.4, dampingFraction: 0.45)) { knob = .zero }
    }

    /// VoiceOver: a short move in one direction, then stop.
    private func nudge(_ action: PTZAction) {
        dispatch(action)
        Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(400))
            dispatch(.stop)
        }
    }

    private static func angle(of action: PTZAction) -> Double {
        switch action {
        case .right: 0
        case .down: 90
        case .left: 180
        default: 270
        }
    }

    private func dispatch(_ action: PTZAction) {
        let dispatcher = self.dispatcher ?? PTZDispatcher(
            send: { [api, camera] in try await api.ptz(camera: camera, action: $0) },
            onError: onError
        )
        self.dispatcher = dispatcher
        dispatcher.dispatch(action)
    }
}
