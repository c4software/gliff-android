import Crypto
import Foundation
import XCTest

@testable import GliffSSH

/// The client against a real OpenSSH server started for the test on a local
/// port, as the Kotlin `SshTest` does for the JVM client. Skipped where
/// `sshd` is not installed.
final class SshClientTests: XCTestCase {
    private var dir: URL!
    private var sshd: Process?
    private var port = 0
    private let key = Curve25519.Signing.PrivateKey()

    /// Gathers what a connection reports, for a test to wait on.
    private final class Recorder: SshEvents, @unchecked Sendable {
        private let lock = NSCondition()
        private var hostKeys = [Data]()
        private var keymap: String?
        private var output = Data()
        private var lines = [String]()
        private var end: String??
        var accept: @Sendable (Data) -> Bool = { _ in true }
        /// Answer false to stdout once this much is held, until `take`.
        var highWater = Int.max

        func hostKey(_ blob: Data) -> Bool {
            lock.lock()
            hostKeys.append(blob)
            lock.unlock()
            return accept(blob)
        }

        func opened(keymap: String) { set { self.keymap = keymap } }

        func stdout(_ data: Data) -> Bool {
            lock.lock()
            defer { lock.unlock() }
            output.append(data)
            lock.broadcast()
            return output.count < highWater
        }

        func stderr(_ line: String) { set { self.lines.append(line) } }

        func closed(error: String?) { set { self.end = .some(error) } }

        private func set(_ change: () -> Void) {
            lock.lock()
            change()
            lock.broadcast()
            lock.unlock()
        }

        private func wait<T>(_ what: String, _ read: () -> T?) throws -> T {
            let deadline = Date().addingTimeInterval(10)
            lock.lock()
            defer { lock.unlock() }
            while true {
                if let value = read() { return value }
                if !lock.wait(until: deadline) { throw TimedOut(description: "timed out waiting for \(what)") }
            }
        }

        func waitOpened() throws -> String {
            let result: Result<String, TimedOut> = try wait("the command to run") {
                if let keymap { return .success(keymap) }
                if let end { return .failure(TimedOut(description: "closed before the command ran: \(end ?? "no error")")) }
                return nil
            }
            return try result.get()
        }

        func waitClosed() throws -> String? { try wait("the end") { end } }

        func waitLine() throws -> String { try wait("a line of stderr") { lines.first } }

        /// Take `count` bytes of output, as a reader would.
        func take(_ count: Int) throws -> Data {
            let bytes: Data = try wait("\(count) bytes") { output.count >= count ? Data(output.prefix(count)) : nil }
            lock.lock()
            output.removeFirst(count)
            lock.unlock()
            return bytes
        }

        var held: Int {
            lock.lock()
            defer { lock.unlock() }
            return output.count
        }

        var seenHostKeys: [Data] {
            lock.lock()
            defer { lock.unlock() }
            return hostKeys
        }
    }

    private struct TimedOut: Error, CustomStringConvertible {
        let description: String
    }

    @discardableResult
    private func run(_ command: String...) throws -> String {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/env")
        process.arguments = command
        let pipe = Pipe()
        process.standardOutput = pipe
        process.standardError = pipe
        try process.run()
        let out = String(decoding: pipe.fileHandleForReading.readDataToEndOfFile(), as: UTF8.self)
        process.waitUntilExit()
        XCTAssertEqual(process.terminationStatus, 0, "\(command): \(out)")
        return out
    }

    override func setUpWithError() throws {
        guard let binary = ["/usr/bin/sshd", "/usr/sbin/sshd"].first(where: FileManager.default.isExecutableFile) else {
            throw XCTSkip("no sshd on this machine")
        }
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("gliff-ssh-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let path = dir.path
        try run("ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", "\(path)/hostkey")
        try (authorizedKey(key.publicKey.rawRepresentation) + "\n").write(toFile: "\(path)/authorized_keys", atomically: true, encoding: .utf8)
        // Stands in for gliff-server: echoes the session back.
        try "#!/bin/sh\necho \"args: $*\" >&2\nexec cat\n".write(toFile: "\(path)/echo-server", atomically: true, encoding: .utf8)
        try FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: "\(path)/echo-server")
        // Its own configuration, empty: the machine's may not even be readable by this user.
        try "".write(toFile: "\(path)/sshd_config", atomically: true, encoding: .utf8)
        port = try freePort()
        let server = Process()
        server.executableURL = URL(fileURLWithPath: binary)
        server.arguments = [
            "-D", "-e", "-f", "\(path)/sshd_config", "-p", "\(port)", "-o", "ListenAddress=127.0.0.1",
            "-h", "\(path)/hostkey", "-o", "AuthorizedKeysFile=\(path)/authorized_keys", "-o", "PidFile=none",
            "-o", "UsePAM=no", "-o", "StrictModes=no", "-o", "PasswordAuthentication=no",
        ]
        _ = FileManager.default.createFile(atPath: "\(path)/sshd.log", contents: nil)
        server.standardError = FileHandle(forWritingAtPath: "\(path)/sshd.log")
        try server.run()
        sshd = server
        for _ in 0..<50 {
            if Self.withSocket(port: port, { connect($0, $1, $2) }) != nil { return }
            Thread.sleep(forTimeInterval: 0.1)
        }
        XCTFail("sshd did not start: " + ((try? String(contentsOfFile: "\(path)/sshd.log", encoding: .utf8)) ?? ""))
    }

    override func tearDown() {
        sshd?.terminate()
        sshd?.waitUntilExit()
        if let dir { try? FileManager.default.removeItem(at: dir) }
    }

    /// A TCP socket on the loopback, handed with an address of `port` to `use`
    /// (bind or connect); the port it ends up on, or nil when `use` failed.
    private static func withSocket(port: Int, _ use: (Int32, UnsafePointer<sockaddr>, socklen_t) -> Int32) -> Int? {
        let fd = socket(AF_INET, Int32(SOCK_STREAM.rawValue), 0)
        defer { close(fd) }
        var address = sockaddr_in()
        address.sin_family = sa_family_t(AF_INET)
        address.sin_port = in_port_t(port).bigEndian
        address.sin_addr.s_addr = UInt32(0x7f00_0001).bigEndian
        var length = socklen_t(MemoryLayout<sockaddr_in>.size)
        let done = withUnsafeMutablePointer(to: &address) { pointer in
            pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) { generic in
                use(fd, generic, length) == 0 && getsockname(fd, generic, &length) == 0
            }
        }
        return done ? Int(in_port_t(bigEndian: address.sin_port)) : nil
    }

    /// A port nothing listens on.
    private func freePort() throws -> Int {
        try XCTUnwrap(Self.withSocket(port: 0) { bind($0, $1, $2) })
    }

    private func authorizedKey(_ publicKey: Data) -> String {
        var blob = Data([0, 0, 0, 11]) + Data("ssh-ed25519".utf8)
        blob += Data([0, 0, 0, 32]) + publicKey
        return "ssh-ed25519 \(blob.base64EncodedString()) gliff-test"
    }

    private func request(command: String? = nil, keymap: String = "printf 'xkb_keymap { }'") -> SshRequest {
        SshRequest(
            host: "127.0.0.1", port: port, user: NSUserName(),
            command: command ?? "\(dir.path)/echo-server --stdio --headless",
            keymapCommand: keymap
        )
    }

    func testAKeyLogsInAndTheSessionCarriesBytesBothWays() throws {
        let events = Recorder()
        let connection = SshClient.open(request(), key: key, events: events)
        defer { connection.close() }
        XCTAssertEqual(try events.waitOpened(), "xkb_keymap { }")
        XCTAssertEqual(try events.waitLine(), "args: --stdio --headless")

        // The key shown to the owner is the one OpenSSH has: same blob, so same fingerprint.
        let published = try String(contentsOfFile: "\(dir.path)/hostkey.pub", encoding: .utf8).split(separator: " ")[1]
        XCTAssertEqual(events.seenHostKeys.map { $0.base64EncodedString() }, [String(published)])

        let sent = Data((0..<200_000).map { UInt8(truncatingIfNeeded: $0 * 31) })
        connection.write(sent.prefix(70_000))
        connection.write(sent.dropFirst(70_000))
        XCTAssertEqual(try events.take(sent.count), sent)
    }

    func testARefusedHostKeyStopsBeforeAuthentication() throws {
        let events = Recorder()
        events.accept = { _ in false }
        _ = SshClient.open(request(), key: key, events: events)
        XCTAssertEqual(try events.waitClosed(), "host key not accepted")
        XCTAssertEqual(events.seenHostKeys.count, 1)
        // Nothing was offered: the server never saw this device's key.
        let log = try String(contentsOfFile: "\(dir.path)/sshd.log", encoding: .utf8)
        XCTAssertFalse(log.contains("Accepted publickey"), log)
    }

    func testAnUnknownClientKeyIsSaidSo() throws {
        let events = Recorder()
        _ = SshClient.open(request(), key: Curve25519.Signing.PrivateKey(), events: events)
        XCTAssertEqual(try events.waitClosed(), "the server refused this device's key for \(NSUserName())")
    }

    func testAnUnreachableHostIsAnError() throws {
        let events = Recorder()
        var nowhere = request()
        nowhere.port = try freePort()
        _ = SshClient.open(nowhere, key: key, events: events)
        let error = try XCTUnwrap(try events.waitClosed())
        XCTAssertTrue(error.hasPrefix("cannot reach 127.0.0.1"), error)
    }

    func testASessionGoesOnWithoutAKeymap() throws {
        for keymap in ["exit 3", "head -c 600000 /dev/zero", "sleep 30"] {
            let events = Recorder()
            var slow = request(keymap: keymap)
            slow.keymapTimeoutMs = 700
            let connection = SshClient.open(slow, key: key, events: events)
            XCTAssertEqual(try events.waitOpened(), "", keymap)
            connection.write(Data("still there".utf8))
            XCTAssertEqual(try events.take(11), Data("still there".utf8), keymap)
            connection.close()
            XCTAssertNil(try events.waitClosed(), keymap)
        }
    }

    func testAReaderThatFallsBehindHoldsTheServerBack() throws {
        let events = Recorder()
        events.highWater = 1
        // An endless stream: without a pause it would all be held here.
        let connection = SshClient.open(request(command: "cat /dev/zero"), key: key, events: events)
        defer { connection.close() }
        _ = try events.waitOpened()
        _ = try events.take(1)
        Thread.sleep(forTimeInterval: 0.5)
        let paused = events.held
        Thread.sleep(forTimeInterval: 0.5)
        XCTAssertEqual(events.held, paused, "the stream went on while paused")
        XCTAssertLessThan(paused, 4 * 1024 * 1024)

        _ = try events.take(paused)
        connection.resumeReading()
        _ = try events.take(1)
    }

    func testTheEndOfTheCommandEndsTheConnection() throws {
        let events = Recorder()
        _ = SshClient.open(request(command: "echo gone >&2; exit 4"), key: key, events: events)
        XCTAssertNil(try events.waitClosed())
        XCTAssertEqual(try events.waitLine(), "gone")
    }

    func testClosingIsSilentAndCanBeRepeated() throws {
        let events = Recorder()
        let connection = SshClient.open(request(), key: key, events: events)
        _ = try events.waitOpened()
        connection.close()
        connection.close()
        XCTAssertNil(try events.waitClosed())
        connection.write(Data("late".utf8))
    }

    /// Against a real server, only when one is named: `GLIFF_REAL=user@host swift test`.
    /// It runs a harmless command there, not gliff-server. Tailscale SSH lets
    /// any key in; another server has to know this run's key, which it cannot.
    func testARealServerWhenOneIsNamed() throws {
        guard let spec = ProcessInfo.processInfo.environment["GLIFF_REAL"], let at = spec.firstIndex(of: "@") else {
            throw XCTSkip("GLIFF_REAL is not set")
        }
        let events = Recorder()
        let real = SshRequest(
            host: String(spec[spec.index(after: at)...]), port: 22, user: String(spec[..<at]),
            command: "echo gliff-ok", keymapCommand: "echo keymap"
        )
        let connection = SshClient.open(real, key: key, events: events)
        defer { connection.close() }
        XCTAssertEqual(try events.waitOpened(), "keymap\n")
        XCTAssertEqual(try events.take(9), Data("gliff-ok\n".utf8))
        XCTAssertNil(try events.waitClosed())
    }
}
