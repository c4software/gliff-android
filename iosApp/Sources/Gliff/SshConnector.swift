import Foundation
import GliffSSH
import Shared

/// The ssh client as the shared code sees it: GliffSSH behind the Kotlin
/// `SshBridge`, with the device's key from the keychain.
final class SshConnector: NSObject, SshBridge {
    func publicKey() -> Data {
        (try? KeyStore.key())?.publicKey.rawRepresentation ?? Data()
    }

    func open(
        host: String, port: Int32, user: String, command: String,
        keymapCommand: String, keymapTimeoutMs: Int64, keymapMaxBytes: Int32,
        listener: SshListener
    ) -> SshChannel {
        let key: KeyStore.Key
        do {
            key = try KeyStore.key()
        } catch {
            // Told from elsewhere: the caller is not listening before this returns.
            DispatchQueue.global().async { listener.closed(error: "The keychain did not give this device's ssh key.") }
            return NoChannel()
        }
        let request = SshRequest(
            host: host, port: Int(port), user: user, command: command,
            keymapCommand: keymapCommand,
            keymapTimeoutMs: keymapTimeoutMs,
            keymapMaxBytes: Int(keymapMaxBytes)
        )
        return Channel(SshClient.open(request, key: key, events: Events(listener)))
    }
}

/// Passes on what the connection reports. The Kotlin listener is thread-safe.
private final class Events: SshEvents, @unchecked Sendable {
    private let listener: SshListener

    init(_ listener: SshListener) {
        self.listener = listener
    }

    func hostKey(_ blob: Data) -> Bool { listener.hostKey(blob: blob) }
    func opened(keymap: String) { listener.opened(keymap: keymap) }
    func stdout(_ data: Data) -> Bool { listener.stdout(data: data) }
    func stderr(_ line: String) { listener.stderr(line: line) }
    func closed(error: String?) { listener.closed(error: error) }
}

private final class Channel: NSObject, SshChannel {
    private let connection: SshConnection

    init(_ connection: SshConnection) {
        self.connection = connection
    }

    func write(data: Data) { connection.write(data) }
    func resumeReading() { connection.resumeReading() }
    func close() { connection.close() }
}

/// A connection that never was.
private final class NoChannel: NSObject, SshChannel {
    func write(data: Data) {}
    func resumeReading() {}
    func close() {}
}
