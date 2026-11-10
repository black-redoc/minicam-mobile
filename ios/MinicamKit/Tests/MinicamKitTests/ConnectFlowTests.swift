import Foundation
import Testing
@testable import MinicamKit

/// docs/adr/0011: connect to the dashboard with a code or its QR deep link.
@Suite struct ConnectFlowTests {
    let lan = try! ServerConfig(parsing: "http://192.168.1.20:8080")
    let paired = #""connected": true, "url": "https://admin.example.com", "source": "app", "last_synced_at": null, "site": {"name": "Casa", "hostname": "cam.example.com"}"#

    func api(_ transport: FakeTransport) -> MinicamAPI {
        MinicamAPI(config: lan, transport: transport, store: InMemoryCredentialStore())
    }

    func target(_ request: URLRequest) -> String {
        "\(request.httpMethod ?? "") \(request.url!.path(percentEncoded: true))"
    }

    func status(_ json: String) throws -> CentralStatus {
        try JSONDecoder().decode(CentralStatus.self, from: Data(json.utf8))
    }

    @Test func pairLink_parses() {
        #expect(PairLink.parse(URL(string: "minicam://pair?url=https%3A%2F%2Fadmin.example.com&code=ABCDEFGH")!)
            == PairLink(url: "https://admin.example.com", code: "ABCDEFGH"))
        let other = PairLink.parse(URL(string: "minicam://pair?code=abcd-efgh&url=https://Admin.example.com:8443")!)
        #expect(other == PairLink(url: "https://admin.example.com:8443", code: "ABCDEFGH"))
        #expect(other?.host == "admin.example.com")
        #expect(!String(describing: other!).contains("ABCDEFGH"))
    }

    @Test func pairLink_rejectsInvalid() {
        for bad in [
            "https://pair?url=https://admin.example.com&code=ABCDEFGH",
            "minicam://access-callback?url=https://admin.example.com&code=ABCDEFGH",
            "minicam://pair/extra?url=https://admin.example.com&code=ABCDEFGH",
            "minicam://pair?url=http://admin.example.com&code=ABCDEFGH",
            "minicam://pair?url=admin.example.com&code=ABCDEFGH",
            "minicam://pair?url=https://admin.example.com/api&code=ABCDEFGH",
            "minicam://pair?url=https://admin.example.com&code=ABCD",
            "minicam://pair?url=https://admin.example.com",
            "minicam://pair?url=https://admin.example.com&code=ABCDEFGH&next=x",
            "minicam://pair?url=https://a.example.com&url=https://b.example.com&code=ABCDEFGH",
            "minicam://pair?url=https://admin.example.com&code=ABCDEFGH#frag",
        ] {
            #expect(PairLink.parse(URL(string: bad)!) == nil, "\(bad)")
        }
    }

    @Test func pendingPairLink_expires() {
        let start = Date(timeIntervalSince1970: 0)
        let link = PairLink(url: "https://admin.example.com", code: "ABCDEFGH")
        var pending = PendingPairLink()

        pending.store(link, now: start)
        let fresh = start.addingTimeInterval(PendingPairLink.ttl - 1)
        #expect(pending.peek(now: fresh) == link)
        #expect(pending.take(now: fresh) == link)
        #expect(pending.take(now: fresh) == nil)

        pending.store(link, now: start)
        #expect(pending.peek(now: start.addingTimeInterval(PendingPairLink.ttl)) == nil)
        #expect(pending.take(now: start.addingTimeInterval(PendingPairLink.ttl)) == nil)
    }

    @Test func centralApi_parsesTunnelResult() async throws {
        let transport = FakeTransport([
            .init(status: 200, body: #"{\#(paired), "tunnel": {"state": "installing", "hostname": "cam.example.com", "message": null, "replaced": true}}"#),
            .init(status: 200, body: #"{\#(paired), "tunnel": null}"#),
            .init(status: 200, body: #"{\#(paired)}"#),
        ])
        let api = api(transport)

        #expect(try await api.pairWithCentral(url: "https://admin.example.com", pairingCode: "ABCDEFGH").tunnel
            == CentralTunnel(state: "installing", hostname: "cam.example.com", replaced: true))
        #expect(try await api.pairWithCentral(url: "https://admin.example.com", pairingCode: "ABCDEFGH").tunnel == nil)
        #expect(try await api.pairWithCentral(url: "https://admin.example.com", pairingCode: "ABCDEFGH").tunnel == nil)

        let base = ConnectProgress()
        #expect(base.afterPairing(try status(#"{"connected": true}"#)).remoteAccess == .skipped)
        #expect(base.afterPairing(try status(#"{"connected": true, "tunnel": {"state": "skipped", "message": "env"}}"#))
            .remoteAccess == .skipped)
        let failed = base.afterPairing(try status(#"{"connected": true, "tunnel": {"state": "error", "message": "no helper"}}"#))
        #expect(failed.remoteAccess == .failed && failed.remoteAccessText == "no helper")
        #expect(failed.dashboard == .done)
    }

    @Test func connectFlow_pollsTunnel() async throws {
        let transport = FakeTransport([
            .init(status: 200, body: #"{\#(paired), "tunnel": {"state": "installing", "hostname": "cam.example.com"}}"#),
            .init(status: 200, body: #"{"available": true, "state": "installing", "hostname": "cam.example.com", "source": "central"}"#),
            .init(status: 200, body: #"{"available": true, "state": "running", "hostname": "cam.example.com", "source": "central"}"#),
        ])
        let seen = Recorder()

        let result = try await api(transport).connectToDashboard(
            url: "https://admin.example.com",
            code: "ABCDEFGH",
            onProgress: { await seen.add($0.remoteAccess) },
            wait: { try await $0.waitForTunnel(sleep: { _ in }) }
        )

        #expect(result.dashboard == .done && result.remoteAccess == .done)
        #expect(await seen.states == [.waiting, .working, .done])
        #expect(transport.requests.map(target) == [
            "POST /api/system/central", "GET /api/system/tunnel", "GET /api/system/tunnel",
        ])
    }

    @Test func connectFlow_reportsWanUrl() async throws {
        let running = try await api(FakeTransport([
            .init(status: 200, body: #"{\#(paired), "tunnel": {"state": "installing", "hostname": "cam.example.com"}}"#),
            .init(status: 200, body: #"{"available": true, "state": "running", "hostname": "cam.example.com"}"#),
        ])).connectToDashboard(url: "https://admin.example.com", code: "ABCDEFGH")
        #expect(running.wanURL == "https://cam.example.com")

        let failed = try await api(FakeTransport([
            .init(status: 200, body: #"{\#(paired), "tunnel": {"state": "installing", "hostname": "cam.example.com"}}"#),
            .init(status: 200, body: #"{"available": true, "state": "error", "message": "apt failed"}"#),
        ])).connectToDashboard(url: "https://admin.example.com", code: "ABCDEFGH")
        #expect(failed.wanURL == nil && failed.remoteAccess == .failed && failed.remoteAccessText == "apt failed")

        let noTunnel = try await api(FakeTransport([.init(status: 200, body: #"{\#(paired)}"#)]))
            .connectToDashboard(url: "https://admin.example.com", code: "ABCDEFGH")
        #expect(noTunnel.wanURL == nil && noTunnel.remoteAccess == .skipped)
    }

}

private actor Recorder {
    var states: [StepState] = []
    func add(_ state: StepState) { states.append(state) }
}

/// docs/adr/0012: values read by the in-app QR scanner.
@Suite struct QRScanTests {
    @Test func qrScan_acceptsOnlyPairLinks() {
        let link = "minicam://pair?url=https%3A%2F%2Fadmin.example.com&code=ABCDEFGH"
        #expect(PairLink.fromScan("  \(link)\n") == PairLink(url: "https://admin.example.com", code: "ABCDEFGH"))
        for bad in [nil, "", "https://admin.example.com", "ABCD-EFGH", "minicam://access-callback?x=1",
                    link + String(repeating: "x", count: 600)] as [String?] {
            #expect(PairLink.fromScan(bad) == nil)
        }
        #expect(PairLink.notAPairQR == "That QR is not a minicam dashboard code.")
    }
}
