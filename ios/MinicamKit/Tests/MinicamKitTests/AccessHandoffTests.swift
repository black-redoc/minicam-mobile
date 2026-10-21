import Foundation
import Testing
@testable import MinicamKit

@Suite struct AccessHandoffTests {
    let wan = try! ServerConfig(parsing: "https://cam.example.com")

    @Test func accessHandoff_buildsUrlWithState() {
        let handoff = AccessHandoff()

        #expect(handoff.state.count == 43)
        #expect(handoff.state.allSatisfy { $0.isLetter || $0.isNumber || $0 == "-" || $0 == "_" })
        #expect(AccessHandoff().state != handoff.state)
        #expect(handoff.handoffURL(for: wan).absoluteString
            == "https://cam.example.com/api/mobile/access-handoff?state=\(handoff.state)")
    }

    @Test func accessHandoff_extractsTokenFromFragment() throws {
        let handoff = AccessHandoff(state: "s1")
        let url = URL(string: "minicam://access-callback?state=s1#token=eyJ.abc.def")!

        #expect(try handoff.token(fromCallback: url) == "eyJ.abc.def")
    }

    @Test func accessHandoff_rejectsStateMismatch() {
        let handoff = AccessHandoff(state: "s1")

        #expect(throws: AccessHandoffError.stateMismatch) {
            try handoff.token(fromCallback: URL(string: "minicam://access-callback?state=evil#token=t")!)
        }
        #expect(throws: AccessHandoffError.notACallback) {
            try handoff.token(fromCallback: URL(string: "https://evil.example.com/?state=s1#token=t")!)
        }
        #expect(throws: AccessHandoffError.missingToken) {
            try handoff.token(fromCallback: URL(string: "minicam://access-callback?state=s1")!)
        }
    }

    @Test func reconnectPolicy_waitsThreeSeconds() {
        #expect(ReconnectPolicy().delay == .seconds(3))
    }
}
