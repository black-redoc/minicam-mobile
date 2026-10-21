import Foundation
import Security

/// Where the session cookie and the Cloudflare Access token live.
/// The password is never stored (docs/SECURITY.md).
public protocol CredentialStore: Sendable {
    func value(for key: CredentialKey) -> String?
    func set(_ value: String?, for key: CredentialKey)
}

public enum CredentialKey: String, CaseIterable, Sendable {
    case sessionCookie = "minicam_session"
    case accessToken = "cf_access_token"
    case serverURL = "server_url"
}

public extension CredentialStore {
    func clearSession() {
        set(nil, for: .sessionCookie)
    }
}

public final class InMemoryCredentialStore: CredentialStore, @unchecked Sendable {
    private let lock = NSLock()
    private var values: [CredentialKey: String] = [:]

    public init() {}

    public func value(for key: CredentialKey) -> String? {
        lock.withLock { values[key] }
    }

    public func set(_ value: String?, for key: CredentialKey) {
        lock.withLock { values[key] = value }
    }
}

/// Keychain-backed store, readable only while the device is unlocked and
/// never migrated to another device or backup.
public final class KeychainCredentialStore: CredentialStore, @unchecked Sendable {
    private let service: String

    public init(service: String = "dev.minicam.mobile") {
        self.service = service
    }

    public func value(for key: CredentialKey) -> String? {
        var query = baseQuery(key)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data
        else { return nil }
        return String(data: data, encoding: .utf8)
    }

    public func set(_ value: String?, for key: CredentialKey) {
        SecItemDelete(baseQuery(key) as CFDictionary)
        guard let value, let data = value.data(using: .utf8) else { return }
        var attributes = baseQuery(key)
        attributes[kSecValueData as String] = data
        attributes[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        SecItemAdd(attributes as CFDictionary, nil)
    }

    private func baseQuery(_ key: CredentialKey) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key.rawValue,
        ]
    }
}
