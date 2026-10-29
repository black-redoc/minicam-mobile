import Foundation

// Cloudflare side of "Remote access" (docs/adr/0009): with an API token the
// admin pastes (kept only in memory, never stored, logged or sent to minicam)
// create the tunnel, its route, the DNS record and the Access application,
// and return what minicam needs (`TunnelSetup`). Every step reuses what an
// earlier, interrupted run already created.

public enum CloudflareError: Error, Equatable {
    /// Cloudflare refused the request; its own messages, joined.
    case api(String)
    /// The account has no Zero Trust organization (needed for Access).
    case zeroTrustRequired
    case invalidResponse
}

public struct CloudflareAccount: Decodable, Equatable, Sendable, Identifiable {
    public let id: String
    public let name: String
}

public struct CloudflareZone: Decodable, Equatable, Sendable, Identifiable {
    public let id: String
    public let name: String
}

/// Thin client for the parts of https://api.cloudflare.com/client/v4 the setup uses.
public struct CloudflareClient: Sendable {
    public static let baseURL = URL(string: "https://api.cloudflare.com/client/v4")!

    private let token: String
    private let transport: HTTPTransport

    public init(token: String, transport: HTTPTransport) {
        self.token = token
        self.transport = transport
    }

    private struct Envelope<T: Decodable>: Decodable {
        struct Message: Decodable { let message: String }
        let success: Bool
        let errors: [Message]?
        let result: T?
    }

    private struct Empty: Decodable {}

    func request<T: Decodable>(
        _ method: String, _ path: String, query: [URLQueryItem] = [], body: [String: Any]? = nil
    ) async throws -> T {
        var components = URLComponents(
            url: Self.baseURL.appendingPathComponent(path), resolvingAgainstBaseURL: false
        )!
        if !query.isEmpty { components.queryItems = query }
        var request = URLRequest(url: components.url!)
        request.httpMethod = method
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let body {
            request.httpBody = try JSONSerialization.data(withJSONObject: body, options: [.sortedKeys])
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        let (data, response) = try await transport.send(request)
        guard let envelope = try? JSONDecoder().decode(Envelope<T>.self, from: data) else {
            throw CloudflareError.invalidResponse
        }
        guard envelope.success, (200..<300).contains(response.statusCode) else {
            let messages = (envelope.errors ?? []).map(\.message).joined(separator: "; ")
            throw CloudflareError.api(messages.isEmpty ? "HTTP \(response.statusCode)" : messages)
        }
        guard let result = envelope.result else { throw CloudflareError.invalidResponse }
        return result
    }

    public func accounts() async throws -> [CloudflareAccount] {
        try await request("GET", "accounts", query: [.init(name: "per_page", value: "50")])
    }

    public func zones(accountID: String) async throws -> [CloudflareZone] {
        try await request("GET", "zones", query: [
            .init(name: "account.id", value: accountID), .init(name: "per_page", value: "50"),
        ])
    }

    /// `https://<team>.cloudflareaccess.com`, or `zeroTrustRequired`.
    public func teamDomain(accountID: String) async throws -> String {
        struct Organization: Decodable { let auth_domain: String? }
        let organization: Organization
        do {
            organization = try await request("GET", "accounts/\(accountID)/access/organizations")
        } catch CloudflareError.api {
            throw CloudflareError.zeroTrustRequired
        }
        guard let domain = organization.auth_domain, !domain.isEmpty else {
            throw CloudflareError.zeroTrustRequired
        }
        return "https://\(domain)"
    }

    struct Identified: Decodable { let id: String; let name: String? }

    /// The tunnel's id, reusing a live tunnel with the same name.
    func tunnel(accountID: String, name: String) async throws -> String {
        let existing: [Identified] = try await request(
            "GET", "accounts/\(accountID)/cfd_tunnel",
            query: [.init(name: "name", value: name), .init(name: "is_deleted", value: "false")]
        )
        if let tunnel = existing.first(where: { $0.name == name }) { return tunnel.id }
        let created: Identified = try await request(
            "POST", "accounts/\(accountID)/cfd_tunnel",
            body: ["name": name, "config_src": "cloudflare"]
        )
        return created.id
    }

    func tunnelToken(accountID: String, tunnelID: String) async throws -> String {
        try await request("GET", "accounts/\(accountID)/cfd_tunnel/\(tunnelID)/token")
    }

    /// `hostname` → minicam on the device; anything else → 404.
    func route(accountID: String, tunnelID: String, hostname: String) async throws {
        let ingress: [[String: Any]] = [
            ["hostname": hostname, "service": "http://127.0.0.1:8080"],
            ["service": "http_status:404"],
        ]
        let _: Empty = try await request(
            "PUT", "accounts/\(accountID)/cfd_tunnel/\(tunnelID)/configurations",
            body: ["config": ["ingress": ingress]]
        )
    }

    func dnsRecord(zoneID: String, hostname: String, tunnelID: String) async throws {
        struct Record: Decodable { let id: String; let content: String; let proxied: Bool? }
        let target = "\(tunnelID).cfargotunnel.com"
        let existing: [Record] = try await request(
            "GET", "zones/\(zoneID)/dns_records",
            query: [.init(name: "type", value: "CNAME"), .init(name: "name", value: hostname)]
        )
        let body: [String: Any] = [
            "type": "CNAME", "name": hostname, "content": target, "proxied": true,
            "comment": "minicam tunnel",
        ]
        if let record = existing.first {
            if record.content == target && record.proxied == true { return }
            let _: Identified = try await request("PATCH", "zones/\(zoneID)/dns_records/\(record.id)", body: body)
        } else {
            let _: Identified = try await request("POST", "zones/\(zoneID)/dns_records", body: body)
        }
    }

    /// A reusable Access policy allowing `email`, reused by name.
    func accessPolicy(accountID: String, name: String, email: String) async throws -> String {
        let existing: [Identified] = try await request("GET", "accounts/\(accountID)/access/policies")
        let body: [String: Any] = [
            "name": name, "decision": "allow", "include": [["email": ["email": email]]],
        ]
        if let policy = existing.first(where: { $0.name == name }) {
            let _: Identified = try await request(
                "PUT", "accounts/\(accountID)/access/policies/\(policy.id)", body: body
            )
            return policy.id
        }
        let created: Identified = try await request("POST", "accounts/\(accountID)/access/policies", body: body)
        return created.id
    }

    /// The self-hosted Access application for `hostname`; returns its AUD tag.
    func accessApplication(accountID: String, hostname: String, policyID: String) async throws -> String {
        struct App: Decodable { let id: String; let domain: String?; let aud: String? }
        let existing: [App] = try await request("GET", "accounts/\(accountID)/access/apps")
        let body: [String: Any] = [
            "name": "minicam \(hostname)", "domain": hostname, "type": "self_hosted",
            "session_duration": "24h", "policies": [["id": policyID, "precedence": 1]],
        ]
        let app: App
        if let current = existing.first(where: { $0.domain == hostname }) {
            app = try await request("PUT", "accounts/\(accountID)/access/apps/\(current.id)", body: body)
        } else {
            app = try await request("POST", "accounts/\(accountID)/access/apps", body: body)
        }
        guard let aud = app.aud, !aud.isEmpty else { throw CloudflareError.invalidResponse }
        return aud
    }
}

public enum CloudflareStep: Int, CaseIterable, Sendable {
    case zeroTrust, tunnel, route, dns, access
}

public enum TunnelHostname {
    /// `<label>.<zone>`, or nil when `label` is not one valid DNS label.
    public static func make(label: String, zone: String) -> String? {
        let label = label.trimmingCharacters(in: .whitespaces).lowercased()
        let valid = label.count <= 63
            && label.range(of: "^[a-z0-9]([a-z0-9-]*[a-z0-9])?$", options: .regularExpression) != nil
        return valid ? "\(label).\(zone)" : nil
    }
}

public struct CloudflareSetup: Sendable {
    let client: CloudflareClient

    public init(client: CloudflareClient) {
        self.client = client
    }

    /// Runs every step for `hostname` in `zone`, reporting each one before it starts.
    public func run(
        account: CloudflareAccount,
        zone: CloudflareZone,
        hostname: String,
        adminEmail: String,
        progress: @Sendable (CloudflareStep) async -> Void = { _ in }
    ) async throws -> TunnelSetup {
        await progress(.zeroTrust)
        let teamDomain = try await client.teamDomain(accountID: account.id)
        await progress(.tunnel)
        let tunnelID = try await client.tunnel(accountID: account.id, name: "minicam-\(hostname)")
        let token = try await client.tunnelToken(accountID: account.id, tunnelID: tunnelID)
        await progress(.route)
        try await client.route(accountID: account.id, tunnelID: tunnelID, hostname: hostname)
        await progress(.dns)
        try await client.dnsRecord(zoneID: zone.id, hostname: hostname, tunnelID: tunnelID)
        await progress(.access)
        let policyID = try await client.accessPolicy(
            accountID: account.id, name: "minicam-\(hostname) users", email: adminEmail
        )
        let aud = try await client.accessApplication(accountID: account.id, hostname: hostname, policyID: policyID)
        return TunnelSetup(tunnelToken: token, hostname: hostname, teamDomain: teamDomain, aud: aud)
    }
}
