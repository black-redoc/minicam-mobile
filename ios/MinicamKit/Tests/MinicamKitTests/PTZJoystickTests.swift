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

@Test func transitionSendsNewDirectionOrStop() {
    #expect(PTZJoystick.transition(from: nil, to: .left) == .left)
    #expect(PTZJoystick.transition(from: .left, to: .up) == .up)
    #expect(PTZJoystick.transition(from: .up, to: nil) == .stop)
    #expect(PTZJoystick.transition(from: .up, to: .up) == nil)
    #expect(PTZJoystick.transition(from: nil, to: nil) == nil)
}
