import Foundation
import Testing
@testable import MinicamKit

/// docs/specs/014: Cloudflare Tunnel setup (Cloudflare API + minicam's tunnel API).
@Suite struct CloudflareSetupTests {
    let account = CloudflareAccount(id: "acc1", name: "Home")
    let zone = CloudflareZone(id: "zone1", name: "example.com")
    let aud = String(repeating: "a", count: 64)

    func ok(_ result: String) -> FakeTransport.Canned {
        .init(status: 200, body: #"{"success": true, "errors": [], "result": \#(result)}"#)
    }

    func target(_ request: URLRequest) -> String {
        let url = request.url!
        let query = url.query.map { "?\($0)" } ?? ""
        return "\(request.httpMethod ?? "") \(url.path(percentEncoded: true))\(query)"
    }

    func json(_ request: URLRequest) -> NSDictionary {
        try! JSONSerialization.jsonObject(with: request.httpBody!) as! NSDictionary
    }

    func setup(_ transport: FakeTransport) -> CloudflareSetup {
        CloudflareSetup(client: CloudflareClient(token: "cf-api-token", transport: transport))
    }

    func run(_ transport: FakeTransport) async throws -> TunnelSetup {
        try await setup(transport).run(
            account: account, zone: zone, hostname: "cam.example.com", adminEmail: "admin@example.com"
        )
    }

    @Test func cloudflareRequests_build() async throws {
        let transport = FakeTransport([
            ok(#"{"auth_domain": "myteam.cloudflareaccess.com"}"#),
            ok("[]"),
            ok(#"{"id": "tun1", "name": "minicam-cam.example.com"}"#),
            ok(#""tunnel-token""#),
            ok("{}"),
            ok("[]"),
            ok(#"{"id": "rec1"}"#),
            ok("[]"),
            ok(#"{"id": "pol1", "name": "minicam-cam.example.com users"}"#),
            ok("[]"),
            ok(#"{"id": "app1", "domain": "cam.example.com", "aud": "\#(aud)"}"#),
        ])

        let result = try await run(transport)

        #expect(result == TunnelSetup(
            tunnelToken: "tunnel-token", hostname: "cam.example.com",
            teamDomain: "https://myteam.cloudflareaccess.com", aud: aud
        ))
        let requests = transport.requests
        #expect(requests.map(target) == [
            "GET /client/v4/accounts/acc1/access/organizations",
            "GET /client/v4/accounts/acc1/cfd_tunnel?name=minicam-cam.example.com&is_deleted=false",
            "POST /client/v4/accounts/acc1/cfd_tunnel",
            "GET /client/v4/accounts/acc1/cfd_tunnel/tun1/token",
            "PUT /client/v4/accounts/acc1/cfd_tunnel/tun1/configurations",
            "GET /client/v4/zones/zone1/dns_records?type=CNAME&name=cam.example.com",
            "POST /client/v4/zones/zone1/dns_records",
            "GET /client/v4/accounts/acc1/access/policies",
            "POST /client/v4/accounts/acc1/access/policies",
            "GET /client/v4/accounts/acc1/access/apps",
            "POST /client/v4/accounts/acc1/access/apps",
        ])
        #expect(requests.allSatisfy { $0.value(forHTTPHeaderField: "Authorization") == "Bearer cf-api-token" })
        #expect(requests.allSatisfy { $0.url?.host() == "api.cloudflare.com" })
        #expect(json(requests[2]) == ["name": "minicam-cam.example.com", "config_src": "cloudflare"])
        let ingress = (json(requests[4])["config"] as! NSDictionary)["ingress"] as! [NSDictionary]
        #expect(ingress == [
            ["hostname": "cam.example.com", "service": "http://127.0.0.1:8080"],
            ["service": "http_status:404"],
        ])
        let record = json(requests[6])
        #expect(record["content"] as? String == "tun1.cfargotunnel.com")
        #expect(record["proxied"] as? Bool == true)
        let policy = json(requests[8])
        #expect(policy["decision"] as? String == "allow")
        #expect(policy["include"] as? [NSDictionary] == [["email": ["email": "admin@example.com"]]])
        let app = json(requests[10])
        #expect(app["domain"] as? String == "cam.example.com")
        #expect(app["type"] as? String == "self_hosted")
        #expect(app["policies"] as? [NSDictionary] == [["id": "pol1", "precedence": 1]])
    }

    @Test func cloudflareSetup_reusesExisting() async throws {
        let transport = FakeTransport([
            ok(#"{"auth_domain": "myteam.cloudflareaccess.com"}"#),
            ok(#"[{"id": "tun1", "name": "minicam-cam.example.com"}]"#),
            ok(#""tunnel-token""#),
            ok("{}"),
            ok(#"[{"id": "rec1", "content": "tun1.cfargotunnel.com", "proxied": true}]"#),
            ok(#"[{"id": "pol1", "name": "minicam-cam.example.com users"}]"#),
            ok(#"{"id": "pol1"}"#),
            ok(#"[{"id": "app1", "domain": "cam.example.com", "aud": "old"}]"#),
            ok(#"{"id": "app1", "domain": "cam.example.com", "aud": "\#(aud)"}"#),
        ])

        let result = try await run(transport)

        #expect(result.aud == aud)
        let methods = transport.requests.map(target)
        #expect(!methods.contains { $0.hasPrefix("POST") })
        #expect(methods.contains("PUT /client/v4/accounts/acc1/access/policies/pol1"))
        #expect(methods.contains("PUT /client/v4/accounts/acc1/access/apps/app1"))
    }

    @Test func cloudflareSetup_updatesARecordPointingElsewhere() async throws {
        let transport = FakeTransport([
            ok(#"[{"id": "rec1", "content": "old.cfargotunnel.com", "proxied": true}]"#),
            ok(#"{"id": "rec1"}"#),
        ])
        let client = CloudflareClient(token: "t", transport: transport)
        try await client.dnsRecord(zoneID: "zone1", hostname: "cam.example.com", tunnelID: "tun1")
        #expect(target(transport.requests[1]) == "PATCH /client/v4/zones/zone1/dns_records/rec1")
        #expect(json(transport.requests[1])["content"] as? String == "tun1.cfargotunnel.com")
    }

    @Test func cloudflareSetup_requiresZeroTrust() async throws {
        for canned in [
            FakeTransport.Canned(status: 404, body: #"{"success": false, "errors": [{"code": 9999, "message": "no org"}], "result": null}"#),
            ok(#"{"auth_domain": ""}"#),
        ] {
            await #expect(throws: CloudflareError.zeroTrustRequired) {
                try await run(FakeTransport([canned]))
            }
        }
    }

    @Test func cloudflareResponses_parse() async throws {
        let failing = FakeTransport([.init(
            status: 403,
            body: #"{"success": false, "errors": [{"code": 10000, "message": "Authentication error"}, {"code": 1, "message": "Bad token"}]}"#
        )])
        await #expect(throws: CloudflareError.api("Authentication error; Bad token")) {
            try await CloudflareClient(token: "t", transport: failing).accounts()
        }

        let bare = FakeTransport([.init(status: 500, body: #"{"success": false}"#)])
        await #expect(throws: CloudflareError.api("HTTP 500")) {
            try await CloudflareClient(token: "t", transport: bare).accounts()
        }

        let html = FakeTransport([.init(status: 502, body: "<html>")])
        await #expect(throws: CloudflareError.invalidResponse) {
            try await CloudflareClient(token: "t", transport: html).accounts()
        }

        let lists = FakeTransport([
            ok(#"[{"id": "acc1", "name": "Home"}]"#),
            ok(#"[{"id": "zone1", "name": "example.com"}]"#),
        ])
        let client = CloudflareClient(token: "t", transport: lists)
        #expect(try await client.accounts() == [account])
        #expect(try await client.zones(accountID: "acc1") == [zone])
        #expect(target(lists.requests[1]) == "GET /client/v4/zones?account.id=acc1&per_page=50")
    }

    @Test func tunnelHostname_validates() {
        #expect(TunnelHostname.make(label: "Cam", zone: "example.com") == "cam.example.com")
        #expect(TunnelHostname.make(label: " my-cam ", zone: "example.com") == "my-cam.example.com")
        for bad in ["", "-cam", "cam-", "a.b", "cam_1", String(repeating: "a", count: 64)] {
            #expect(TunnelHostname.make(label: bad, zone: "example.com") == nil)
        }
    }

    @Test func tunnelApi_postsAndPolls() async throws {
        let lan = try ServerConfig(parsing: "http://192.168.1.20:8080")
        let transport = FakeTransport([
            .init(status: 202, body: ""),
            .init(status: 200, body: #"{"available": true, "state": "installing", "hostname": "cam.example.com", "message": "Installing cloudflared"}"#),
            .init(status: 200, body: #"{"available": true, "state": "running", "hostname": "cam.example.com", "message": null}"#),
            .init(status: 202, body: ""),
        ])
        let api = MinicamAPI(config: lan, transport: transport, store: InMemoryCredentialStore())
        let setup = TunnelSetup(
            tunnelToken: "tok", hostname: "cam.example.com",
            teamDomain: "https://myteam.cloudflareaccess.com", aud: aud
        )

        try await api.setUpTunnel(setup)
        let status = try await api.waitForTunnel(sleep: { _ in })
        try await api.removeTunnel()

        #expect(status == TunnelStatus(available: true, state: .running, hostname: "cam.example.com"))
        #expect(transport.requests.map(target) == [
            "POST /api/system/tunnel", "GET /api/system/tunnel", "GET /api/system/tunnel",
            "DELETE /api/system/tunnel",
        ])
        #expect(json(transport.requests[0]) == [
            "tunnel_token": "tok", "hostname": "cam.example.com",
            "team_domain": "https://myteam.cloudflareaccess.com", "aud": aud,
        ])
    }

    @Test func tunnelApi_stopsWaitingAtTimeout() async throws {
        let lan = try ServerConfig(parsing: "http://192.168.1.20:8080")
        let installing = FakeTransport.Canned(status: 200, body: #"{"available": true, "state": "installing"}"#)
        let transport = FakeTransport([installing, installing, installing])
        let api = MinicamAPI(config: lan, transport: transport, store: InMemoryCredentialStore())

        let status = try await api.waitForTunnel(interval: .seconds(1), timeout: .seconds(2), sleep: { _ in })

        #expect(status.state == .installing)
        #expect(transport.requests.count == 3)
    }

    @Test func cloudflareSetup_doesNotStoreToken() async throws {
        let store = InMemoryCredentialStore()
        let transport = FakeTransport([.init(status: 200, body: #"{"success": true, "result": []}"#)])
        _ = try await CloudflareClient(token: "secret-cf-token", transport: transport).accounts()
        #expect(CredentialKey.allCases.allSatisfy { store.value(for: $0) == nil })
    }
}
