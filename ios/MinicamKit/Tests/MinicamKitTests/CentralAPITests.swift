import Foundation
import Testing
@testable import MinicamKit

/// minicam ADR 0009: pair the device with the central dashboard from the app.
@Suite struct CentralAPITests {
    let lan = try! ServerConfig(parsing: "http://192.168.1.20:8080")

    func api(_ transport: FakeTransport) -> MinicamAPI {
        MinicamAPI(config: lan, transport: transport, store: InMemoryCredentialStore())
    }

    func target(_ request: URLRequest) -> String {
        "\(request.httpMethod ?? "") \(request.url!.path(percentEncoded: true))"
    }

    @Test func centralInput_normalizes() {
        #expect(CentralURL.normalize(" admin.example.com/ ") == "https://admin.example.com")
        #expect(CentralURL.normalize("https://Admin.Example.com:8443") == "https://admin.example.com:8443")
        for bad in ["http://admin.example.com", "https://admin.example.com/api", "https://u@a.com", ""] {
            #expect(CentralURL.normalize(bad) == nil)
        }
        #expect(PairingCode.normalize("abcd-efgh") == "ABCDEFGH")
        #expect(PairingCode.normalize(" ABCD EFGH ") == "ABCDEFGH")
        for bad in ["ABCD-EFG", "ABCD-EFGHJ", "ABCD;EFGH", "ABCDÉFGH", ""] {
            #expect(PairingCode.normalize(bad) == nil)
        }
    }

    @Test func centralApi_pairsReadsAndUnpairs() async throws {
        let notConnected = #"{"connected": false, "url": null, "source": null, "last_synced_at": null}"#
        let transport = FakeTransport([
            .init(status: 200, body: notConnected),
            .init(status: 200, body: #"""
                {"connected": true, "url": "https://admin.example.com", "source": "app", "last_synced_at": null,
                 "site": {"name": "Casa", "hostname": "cam.example.com"}}
                """#),
            .init(status: 200, body: notConnected),
        ])
        let api = api(transport)

        #expect(try await api.centralStatus().connected == false)
        let paired = try await api.pairWithCentral(url: "https://admin.example.com", pairingCode: "ABCDEFGH")
        let unpaired = try await api.unpairFromCentral()

        #expect(paired.site == PairedSite(name: "Casa", hostname: "cam.example.com"))
        #expect(paired.source == "app" && !paired.managedInEnv)
        #expect(!unpaired.connected)
        #expect(transport.requests.map(target) == [
            "GET /api/system/central", "POST /api/system/central", "DELETE /api/system/central",
        ])
        let body = try JSONSerialization.jsonObject(with: transport.requests[1].httpBody!) as! NSDictionary
        #expect(body == ["url": "https://admin.example.com", "pairing_code": "ABCDEFGH"])
    }

    @Test func centralApi_mapsErrors() async throws {
        let transport = FakeTransport([
            .init(status: 502, body: #"{"detail": "The dashboard did not accept the code."}"#),
            .init(status: 409, body: #"{"detail": "This device's dashboard is set in its .env file; change it there instead"}"#),
            .init(status: 200, body: #"{"connected": true, "url": "https://env.example.com", "source": "env", "last_synced_at": "2026-11-08T12:00:00.000Z"}"#),
        ])
        let api = api(transport)

        await #expect(throws: MinicamError.rejected("The dashboard did not accept the code.")) {
            try await api.pairWithCentral(url: "https://admin.example.com", pairingCode: "ABCDEFGH")
        }
        await #expect(throws: MinicamError.self) { try await api.unpairFromCentral() }
        #expect(try await api.centralStatus().managedInEnv)
    }
}
