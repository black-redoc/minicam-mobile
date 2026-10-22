import MinicamKit
import SwiftUI

/// Compass-style 3x3 PTZ grid (`.ptz-grid` in main.css). Holding a direction
/// moves the camera; releasing sends `stop`. `PTZDispatcher` keeps at most one
/// request in flight. `glass` draws translucent buttons for the fullscreen overlay (spec 004).
struct PTZPad: View {
    let api: MinicamAPI
    let camera: String
    var glass = false
    let onError: (Error) -> Void

    @State private var dispatcher: PTZDispatcher?
    @State private var pressed: PTZAction?

    var body: some View {
        Grid(horizontalSpacing: 6, verticalSpacing: 6) {
            GridRow {
                Color.clear.frame(width: Theme.ptzSize, height: Theme.ptzSize)
                button(.up, systemImage: "chevron.up")
                Color.clear.frame(width: Theme.ptzSize, height: Theme.ptzSize)
            }
            GridRow {
                button(.left, systemImage: "chevron.left")
                button(.stop, systemImage: "stop.fill")
                button(.right, systemImage: "chevron.right")
            }
            GridRow {
                Color.clear.frame(width: Theme.ptzSize, height: Theme.ptzSize)
                button(.down, systemImage: "chevron.down")
                Color.clear.frame(width: Theme.ptzSize, height: Theme.ptzSize)
            }
        }
    }

    private func button(_ action: PTZAction, systemImage: String) -> some View {
        let isPressed = pressed == action
        return Image(systemName: systemImage)
            .font(action == .stop ? .footnote : .title3)
            .frame(width: Theme.ptzSize, height: Theme.ptzSize)
            .foregroundStyle(isPressed || glass ? Color.white : Theme.text)
            // Inside `background` so the press styling never changes the view's identity and
            // cancels the drag gesture mid-press.
            .background { surface(action, pressed: isPressed) }
            .scaleEffect(isPressed ? 0.93 : 1)
            .animation(.easeOut(duration: 0.08), value: isPressed)
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { _ in
                        guard pressed != action else { return }
                        pressed = action
                        dispatch(action)
                    }
                    .onEnded { _ in
                        pressed = nil
                        if action != .stop { dispatch(.stop) }
                    }
            )
            .accessibilityLabel("PTZ \(action.rawValue)")
            .accessibilityAddTraits(.isButton)
    }

    @ViewBuilder
    private func surface(_ action: PTZAction, pressed: Bool) -> some View {
        let shape = RoundedRectangle(cornerRadius: Theme.radius)
        if pressed {
            shape.fill(Theme.accent.opacity(glass ? 0.75 : 1))
                .overlay(shape.stroke(glass ? Theme.accentHover : Theme.accent))
        } else if glass {
            Color.clear.glassSurface(in: shape)
        } else {
            shape.fill(action == .stop ? Color(hex: 0x1F2937) : Theme.surface2)
                .overlay(shape.stroke(Theme.border))
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
