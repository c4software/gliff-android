import CryptoKit
import Foundation
import Security

/// The device's Ed25519 key, made on first use and kept in the keychain for
/// this device only: a backup restored on another device must not carry it,
/// the same rule as on Android.
enum KeyStore {
    typealias Key = Curve25519.Signing.PrivateKey

    private static let lock = NSLock()
    private static var cached: Curve25519.Signing.PrivateKey?

    private static let item: [String: Any] = [
        kSecClass as String: kSecClassGenericPassword,
        kSecAttrService as String: "io.github.c4software.gliff",
        kSecAttrAccount as String: "id_ed25519",
    ]

    struct Failure: Error {
        let status: OSStatus
    }

    static func key() throws -> Curve25519.Signing.PrivateKey {
        lock.lock()
        defer { lock.unlock() }
        if let cached { return cached }
        let key = try stored() ?? create()
        cached = key
        return key
    }

    private static func stored() throws -> Curve25519.Signing.PrivateKey? {
        var query = item
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var found: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &found)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = found as? Data else { throw Failure(status: status) }
        return try Curve25519.Signing.PrivateKey(rawRepresentation: data)
    }

    private static func create() throws -> Curve25519.Signing.PrivateKey {
        let key = Curve25519.Signing.PrivateKey()
        var attributes = item
        attributes[kSecValueData as String] = key.rawRepresentation
        // Readable once the device has been unlocked, so a session can reconnect in the background.
        attributes[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let status = SecItemAdd(attributes as CFDictionary, nil)
        guard status == errSecSuccess else { throw Failure(status: status) }
        return key
    }
}
