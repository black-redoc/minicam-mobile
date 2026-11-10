import Foundation

/// Maps a joystick deflection to a PTZ direction. `dx` grows to the right and `dy` grows
/// downwards, like screen coordinates.
///
/// Built against false positives: nothing inside the dead zone; once a direction is `current`
/// it holds until the stick is back under `releaseRatio` of the dead zone (hysteresis), and it
/// only turns to the other axis when that axis clearly wins (`switchRatio`), so a shaky thumb
/// near a diagonal does not flip between directions.
public enum PTZJoystick {
    public static let releaseRatio = 0.6
    public static let switchRatio = 1.35

    public static func direction(
        dx: Double, dy: Double, deadZone: Double, current: PTZAction? = nil
    ) -> PTZAction? {
        let threshold = current == nil ? deadZone : deadZone * releaseRatio
        if (dx * dx + dy * dy).squareRoot() < threshold { return nil }
        let dominant: PTZAction = abs(dx) > abs(dy) ? (dx > 0 ? .right : .left) : (dy > 0 ? .down : .up)
        guard let current, current != .stop, dominant != current else { return dominant }
        // Same axis, other side: follow it. Other axis: only when it clearly wins.
        let horizontal = current == .left || current == .right
        let along = horizontal ? dx : dy
        let across = horizontal ? dy : dx
        let sameSide = current == .right || current == .down ? along > 0 : along < 0
        return sameSide && abs(across) < abs(along) * switchRatio ? current : dominant
    }

    /// Command to send when the stick moves from `from` to `to`: the new direction, or `stop`
    /// when it comes back to the dead zone. Nothing while the direction stays the same.
    public static func transition(from: PTZAction?, to: PTZAction?) -> PTZAction? {
        if from == to { return nil }
        return to ?? .stop
    }
}
