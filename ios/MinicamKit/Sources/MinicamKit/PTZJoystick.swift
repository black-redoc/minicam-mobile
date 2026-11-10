import Foundation

/// Maps a joystick deflection to a PTZ direction: `nil` inside the dead zone, otherwise the
/// dominant axis. `dx` grows to the right and `dy` grows downwards, like screen coordinates.
public enum PTZJoystick {
    public static func direction(dx: Double, dy: Double, deadZone: Double) -> PTZAction? {
        if (dx * dx + dy * dy).squareRoot() < deadZone { return nil }
        if abs(dx) > abs(dy) { return dx > 0 ? .right : .left }
        return dy > 0 ? .down : .up
    }

    /// Command to send when the stick moves from `from` to `to`: the new direction, or `stop`
    /// when it comes back to the dead zone. Nothing while the direction stays the same.
    public static func transition(from: PTZAction?, to: PTZAction?) -> PTZAction? {
        if from == to { return nil }
        return to ?? .stop
    }
}
