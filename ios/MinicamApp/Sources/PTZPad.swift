import MinicamKit
import SwiftUI

/// Compass-style 3x3 PTZ grid (`.ptz-grid` in main.css). Holding a direction
/// moves the camera; releasing sends `stop`. Only one request is in flight:
/// a newer action cancels the pending one, like desktop `ptz.js`.
struct PTZPad: View {
    let api: MinicamAPI
    let camera: String
    let onError: (Error) -> Void

    @State private var inFlight: Task<Void, Never>?
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
            .foregroundStyle(isPressed ? Color.white : Theme.text)
            .background(isPressed ? Theme.accent : (action == .stop ? Color(hex: 0x1F2937) : Theme.surface2))
            .overlay(RoundedRectangle(cornerRadius: Theme.radius).stroke(isPressed ? Theme.accent : Theme.border))
            .clipShape(RoundedRectangle(cornerRadius: Theme.radius))
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

    private func dispatch(_ action: PTZAction) {
        inFlight?.cancel()
        inFlight = Task {
            do {
                try await api.ptz(camera: camera, action: action)
            } catch is CancellationError {
                return
            } catch let error as URLError where error.code == .cancelled {
                return
            } catch {
                onError(error)
            }
        }
    }
}
