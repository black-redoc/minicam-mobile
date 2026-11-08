import Foundation
import MinicamKit
import Network
import Observation

/// A minicam that answered on the LAN (`_minicam._tcp`, minicam ADR 0006).
struct DiscoveredServer: Identifiable, Equatable {
    let name: String
    let baseURL: String
    var id: String { name }
}

/// Browses `_minicam._tcp` with NWBrowser while started (no multicast entitlement needed;
/// iOS asks for local network access the first time, with NSLocalNetworkUsageDescription).
/// Each result is resolved to an IPv4 address by opening a connection to it.
@MainActor
@Observable
final class LanDiscovery {
    private(set) var servers: [DiscoveredServer] = []
    /// iOS denied local network access (Settings → Privacy & Security → Local Network).
    private(set) var denied = false

    private var browser: NWBrowser?
    private var resolving: [String: NWConnection] = [:]

    func start() {
        guard browser == nil else { return }
        servers = []
        denied = false
        let browser = NWBrowser(for: .bonjour(type: DiscoveredServers.serviceType, domain: nil), using: .tcp)
        browser.stateUpdateHandler = { [weak self] state in
            Task { @MainActor in
                if case .waiting(let error) = state, case .dns = error { self?.denied = true }
                if case .failed = state { self?.denied = true }
            }
        }
        browser.browseResultsChangedHandler = { [weak self] results, _ in
            Task { @MainActor in self?.update(results) }
        }
        browser.start(queue: .main)
        self.browser = browser
    }

    func stop() {
        browser?.cancel()
        browser = nil
        resolving.values.forEach { $0.cancel() }
        resolving = [:]
    }

    private func update(_ results: Set<NWBrowser.Result>) {
        var names = Set<String>()
        for result in results {
            guard case let .service(name, _, _, _) = result.endpoint else { continue }
            names.insert(name)
            if servers.contains(where: { $0.name == name }) || resolving[name] != nil { continue }
            resolve(name: name, endpoint: result.endpoint)
        }
        servers.removeAll { !names.contains($0.name) }
    }

    private func resolve(name: String, endpoint: NWEndpoint) {
        let parameters = NWParameters.tcp
        if let ip = parameters.defaultProtocolStack.internetProtocol as? NWProtocolIP.Options {
            ip.version = .v4
        }
        let connection = NWConnection(to: endpoint, using: parameters)
        resolving[name] = connection
        connection.stateUpdateHandler = { [weak self] state in
            Task { @MainActor in
                guard let self else { return }
                switch state {
                case .ready:
                    if case let .hostPort(host, port) = connection.currentPath?.remoteEndpoint,
                       case let .ipv4(address) = host,
                       let url = DiscoveredServers.baseURL(host: "\(address)", port: Int(port.rawValue)) {
                        self.servers.removeAll { $0.name == name }
                        self.servers.append(DiscoveredServer(name: name, baseURL: url))
                    }
                    connection.cancel()
                    self.resolving[name] = nil
                case .failed, .cancelled:
                    self.resolving[name] = nil
                default:
                    break
                }
            }
        }
        connection.start(queue: .main)
    }
}
