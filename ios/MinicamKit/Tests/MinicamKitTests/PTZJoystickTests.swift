import Testing
@testable import MinicamKit

@Test func insideDeadZoneIsNoDirection() {
    #expect(PTZJoystick.direction(dx: 3, dy: -4, deadZone: 10) == nil)
}

@Test func dominantAxisWins() {
    #expect(PTZJoystick.direction(dx: 30, dy: 10, deadZone: 10) == .right)
    #expect(PTZJoystick.direction(dx: -30, dy: -29, deadZone: 10) == .left)
    #expect(PTZJoystick.direction(dx: 5, dy: -30, deadZone: 10) == .up)
    #expect(PTZJoystick.direction(dx: -5, dy: 30, deadZone: 10) == .down)
}

@Test func activeDirectionHoldsUntilWellInsideDeadZone() {
    // 8 is under the dead zone but above 60 % of it: still moving.
    #expect(PTZJoystick.direction(dx: 0, dy: -8, deadZone: 10, current: .up) == .up)
    #expect(PTZJoystick.direction(dx: 0, dy: -5, deadZone: 10, current: .up) == nil)
    // From rest, the same deflection does not start a move.
    #expect(PTZJoystick.direction(dx: 0, dy: -8, deadZone: 10) == nil)
}

@Test func otherAxisNeedsAClearWin() {
    // Slightly past the diagonal: keep going right.
    #expect(PTZJoystick.direction(dx: 20, dy: 24, deadZone: 10, current: .right) == .right)
    // Clearly down now.
    #expect(PTZJoystick.direction(dx: 20, dy: 30, deadZone: 10, current: .right) == .down)
    // Same axis, other side: follow at once.
    #expect(PTZJoystick.direction(dx: -20, dy: 5, deadZone: 10, current: .right) == .left)
}

@Test func transitionSendsNewDirectionOrStop() {
    #expect(PTZJoystick.transition(from: nil, to: .left) == .left)
    #expect(PTZJoystick.transition(from: .left, to: .up) == .up)
    #expect(PTZJoystick.transition(from: .up, to: nil) == .stop)
    #expect(PTZJoystick.transition(from: .up, to: .up) == nil)
    #expect(PTZJoystick.transition(from: nil, to: nil) == nil)
}
