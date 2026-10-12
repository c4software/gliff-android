import Crypto
import Foundation
import NIOCore
import NIOPosix
import NIOSSH

/// What a connection tells its owner. Called on the connection's own thread:
/// answer at once, and do the work elsewhere.
public protocol SshEvents: AnyObject, Sendable {
    /// The server's host key, as its ssh public key blob. False ends the
    /// connection before anything is sent to the server.
    func hostKey(_ blob: Data) -> Bool

    /// The command is running. `keymap` is the remote's, or empty when it
    /// could not be read.
    func opened(keymap: String)

    /// Bytes of the command's standard output, in order. False asks for a
    /// pause: nothing more comes until `SshConnection.resumeReading()`.
    func stdout(_ data: Data) -> Bool

    /// One line the command wrote to its standard error.
    func stderr(_ line: String)

    /// The end, once and last: `error` says why, or is nil when the
    /// connection was closed from here or the command ended by itself.
    func closed(error: String?)
}

/// What to connect to, and what to run there.
public struct SshRequest: Sendable {
    public var host: String
    public var port: Int
    public var user: String
    /// The remote command whose standard streams the connection carries.
    public var command: String
    /// A command run first, on a channel of its own, whose output is the
    /// remote's keymap. Past the timeout or the size there is no keymap, and
    /// the connection goes on without it.
    public var keymapCommand: String
    public var keymapTimeoutMs: Int64
    public var keymapMaxBytes: Int
    public var connectTimeoutMs: Int64 = 15_000

    public init(
        host: String, port: Int, user: String, command: String,
        keymapCommand: String, keymapTimeoutMs: Int64 = 6_000, keymapMaxBytes: Int = 512 * 1024
    ) {
        self.host = host
        self.port = port
        self.user = user
        self.command = command
        self.keymapCommand = keymapCommand
        self.keymapTimeoutMs = keymapTimeoutMs
        self.keymapMaxBytes = keymapMaxBytes
    }
}

public enum SshClient {
    /// One thread carries every connection: there are few, and each is
    /// mostly one video stream.
    private static let group = MultiThreadedEventLoopGroup(numberOfThreads: 1)

    /// Start connecting. Never blocks: everything comes back through `events`.
    public static func open(_ request: SshRequest, key: Curve25519.Signing.PrivateKey, events: SshEvents) -> SshConnection {
        let connection = SshConnection(request: request, key: key, events: events, loop: group.next())
        connection.start()
        return connection
    }
}

/// One ssh connection, from the socket to the remote command. Every field is
/// touched on the event loop only; the public methods hop onto it.
public final class SshConnection: @unchecked Sendable {
    private let request: SshRequest
    private let key: Curve25519.Signing.PrivateKey
    private let events: SshEvents
    private let loop: EventLoop

    private var channel: Channel?
    private var session: Channel?
    private var sessionHandler: SessionHandler?
    private var finished = false
    private var closedHere = false
    /// Why the connection is about to end, when a delegate knows better than the error that follows.
    fileprivate var reason: String?

    init(request: SshRequest, key: Curve25519.Signing.PrivateKey, events: SshEvents, loop: EventLoop) {
        self.request = request
        self.key = key
        self.events = events
        self.loop = loop
    }

    /// Send to the command's standard input. Never blocks.
    public func write(_ data: Data) {
        loop.execute {
            guard let session = self.session, !self.finished else { return }
            var buffer = session.allocator.buffer(capacity: data.count)
            buffer.writeBytes(data)
            session.writeAndFlush(SSHChannelData(type: .channel, data: .byteBuffer(buffer)), promise: nil)
        }
    }

    /// Go on after a `stdout` that answered false.
    public func resumeReading() {
        loop.execute { self.sessionHandler?.resume() }
    }

    /// End the connection; safe to call more than once.
    public func close() {
        loop.execute {
            self.closedHere = true
            self.finish(error: nil)
        }
    }

    func start() {
        let bootstrap = ClientBootstrap(group: loop)
            .connectTimeout(.milliseconds(request.connectTimeoutMs))
            // Input events are tiny and must not wait for a full segment.
            .channelOption(ChannelOptions.tcpOption(.tcp_nodelay), value: 1)
            // A link that died without a word is noticed, rather than waited on forever.
            .channelOption(ChannelOptions.socketOption(.so_keepalive), value: 1)
            .channelInitializer { channel in
                channel.eventLoop.makeCompletedFuture {
                    let ssh = NIOSSHHandler(
                        role: .client(.init(
                            userAuthDelegate: KeyAuthentication(connection: self, user: self.request.user, key: self.key),
                            serverAuthDelegate: HostKeyCheck(connection: self, events: self.events)
                        )),
                        allocator: channel.allocator,
                        inboundChildChannelInitializer: nil
                    )
                    try channel.pipeline.syncOperations.addHandler(ssh)
                    try channel.pipeline.syncOperations.addHandler(ConnectionEnd(connection: self))
                }
            }
        bootstrap.connect(host: request.host, port: request.port).whenComplete { result in
            switch result {
            case .failure(let error):
                self.finish(error: "cannot reach \(self.request.host): \(describe(error))")
            case .success(let channel):
                if self.finished {
                    channel.close(promise: nil)
                    return
                }
                self.channel = channel
                Self.keepAlive(channel)
                self.readKeymap(on: channel)
            }
        }
    }

    /// Probes after 15 s of silence, every 15 s, three times. The options are
    /// named differently on Darwin; a system without them keeps its defaults.
    private static func keepAlive(_ channel: Channel) {
        guard let options = channel as? SocketOptionProvider else { return }
        #if canImport(Darwin)
        _ = options.unsafeSetSocketOption(level: .tcp, name: .init(rawValue: TCP_KEEPALIVE), value: CInt(15))
        #else
        _ = options.unsafeSetSocketOption(level: .tcp, name: .init(rawValue: CInt(TCP_KEEPIDLE)), value: CInt(15))
        #endif
        _ = options.unsafeSetSocketOption(level: .tcp, name: .init(rawValue: CInt(TCP_KEEPINTVL)), value: CInt(15))
        _ = options.unsafeSetSocketOption(level: .tcp, name: .init(rawValue: CInt(TCP_KEEPCNT)), value: CInt(3))
    }

    /// The first channel: the remote's keymap, or nothing. Whatever it gives, the session follows.
    private func readKeymap(on channel: Channel) {
        let keymap = loop.makePromise(of: String.self)
        let opened = loop.makePromise(of: Channel.self)
        let handler = KeymapHandler(command: request.keymapCommand, maxBytes: request.keymapMaxBytes, done: keymap)
        do {
            try channel.pipeline.syncOperations.handler(type: NIOSSHHandler.self).createChannel(opened, channelType: .session) { child, _ in
                child.eventLoop.makeCompletedFuture {
                    try child.pipeline.syncOperations.addHandler(handler)
                }
            }
        } catch {
            finish(error: describe(error))
            return
        }
        // Opening it is where the host key and the login are settled: a failure here is the connection's.
        opened.futureResult.whenFailure { error in
            keymap.fail(error)
        }
        opened.futureResult.whenSuccess { child in
            self.loop.scheduleTask(in: .milliseconds(self.request.keymapTimeoutMs)) {
                keymap.succeed("")
                child.close(promise: nil)
            }
        }
        keymap.futureResult.whenComplete { result in
            switch result {
            case .failure(let error):
                self.finish(error: describe(error))
            case .success(let text):
                self.openSession(on: channel, keymap: text)
            }
        }
    }

    private func openSession(on channel: Channel, keymap: String) {
        if finished { return }
        let handler = SessionHandler(connection: self, command: request.command, keymap: keymap, events: events)
        let opened = loop.makePromise(of: Channel.self)
        do {
            try channel.pipeline.syncOperations.handler(type: NIOSSHHandler.self).createChannel(opened, channelType: .session) { child, _ in
                // Read on demand: a reader that falls behind holds the server back.
                child.setOption(ChannelOptions.autoRead, value: false).flatMapThrowing {
                    try child.pipeline.syncOperations.addHandler(handler)
                }
            }
        } catch {
            finish(error: describe(error))
            return
        }
        opened.futureResult.whenComplete { result in
            switch result {
            case .failure(let error):
                self.finish(error: describe(error))
            case .success(let child):
                if self.finished {
                    child.close(promise: nil)
                } else {
                    self.session = child
                    self.sessionHandler = handler
                }
            }
        }
    }

    /// The one way out. On the event loop.
    fileprivate func finish(error: String?) {
        if finished { return }
        finished = true
        session = nil
        sessionHandler = nil
        channel?.close(promise: nil)
        channel = nil
        events.closed(error: closedHere ? nil : (reason ?? error))
    }
}

private func describe(_ error: Error) -> String {
    if let error = error as? ChannelError, case .connectTimeout = error { return "no answer" }
    if let error = error as? NIOConnectionError {
        return error.connectionErrors.first.map { describe($0.error) } ?? "no address for this name"
    }
    if let error = error as? IOError { return error.description }
    return String(describing: error)
}

/// Asks the owner whether the server's key is the expected one.
private final class HostKeyCheck: NIOSSHClientServerAuthenticationDelegate {
    private unowned let connection: SshConnection
    private let events: SshEvents

    init(connection: SshConnection, events: SshEvents) {
        self.connection = connection
        self.events = events
    }

    struct Refused: Error {}

    func validateHostKey(hostKey: NIOSSHPublicKey, validationCompletePromise: EventLoopPromise<Void>) {
        // "algorithm base64": the base64 is the key blob, the very bytes a fingerprint is taken of.
        let fields = String(openSSHPublicKey: hostKey).split(separator: " ")
        guard fields.count >= 2, let blob = Data(base64Encoded: String(fields[1])), events.hostKey(blob) else {
            connection.reason = "host key not accepted"
            validationCompletePromise.fail(Refused())
            return
        }
        validationCompletePromise.succeed(())
    }
}

/// Offers the device's key, once.
private final class KeyAuthentication: NIOSSHClientUserAuthenticationDelegate {
    private unowned let connection: SshConnection
    private let user: String
    private let key: Curve25519.Signing.PrivateKey
    private var offered = false

    init(connection: SshConnection, user: String, key: Curve25519.Signing.PrivateKey) {
        self.connection = connection
        self.user = user
        self.key = key
    }

    struct Refused: Error {}

    func nextAuthenticationType(
        availableMethods: NIOSSHAvailableUserAuthenticationMethods,
        nextChallengePromise: EventLoopPromise<NIOSSHUserAuthenticationOffer?>
    ) {
        guard !offered, availableMethods.contains(.publicKey) else {
            connection.reason = "the server refused this device's key for \(user)"
            nextChallengePromise.fail(Refused())
            return
        }
        offered = true
        nextChallengePromise.succeed(.init(
            username: user, serviceName: "",
            offer: .privateKey(.init(privateKey: NIOSSHPrivateKey(ed25519Key: key)))
        ))
    }
}

/// Last in the connection's pipeline: turns its end, clean or not, into the one `closed`.
private final class ConnectionEnd: ChannelInboundHandler {
    typealias InboundIn = Any

    private let connection: SshConnection

    init(connection: SshConnection) {
        self.connection = connection
    }

    func errorCaught(context: ChannelHandlerContext, error: Error) {
        connection.finish(error: describe(error))
    }

    func channelInactive(context: ChannelHandlerContext) {
        connection.finish(error: "connection lost")
        context.fireChannelInactive()
    }
}

/// Runs one command and gathers what it prints, up to a size.
private final class KeymapHandler: ChannelInboundHandler, @unchecked Sendable {
    typealias InboundIn = SSHChannelData

    private let command: String
    private let maxBytes: Int
    private let done: EventLoopPromise<String>
    private var output = [UInt8]()
    private var tooLong = false

    init(command: String, maxBytes: Int, done: EventLoopPromise<String>) {
        self.command = command
        self.maxBytes = maxBytes
        self.done = done
    }

    func handlerAdded(context: ChannelHandlerContext) {
        context.channel.setOption(ChannelOptions.allowRemoteHalfClosure, value: true).whenFailure { _ in }
    }

    func channelActive(context: ChannelHandlerContext) {
        context.triggerUserOutboundEvent(SSHChannelRequestEvent.ExecRequest(command: command, wantReply: false), promise: nil)
    }

    func channelRead(context: ChannelHandlerContext, data: NIOAny) {
        let message = unwrapInboundIn(data)
        guard case .channel = message.type, case .byteBuffer(let bytes) = message.data, !tooLong else { return }
        if output.count + bytes.readableBytes >= maxBytes {
            tooLong = true
            output = []
            context.close(promise: nil)
        } else {
            output.append(contentsOf: bytes.readableBytesView)
        }
    }

    func userInboundEventTriggered(context: ChannelHandlerContext, event: Any) {
        // Its output is whole once it has said it writes no more.
        if case ChannelEvent.inputClosed = event { context.close(promise: nil) }
        context.fireUserInboundEventTriggered(event)
    }

    func channelInactive(context: ChannelHandlerContext) {
        // A second answer, after the timeout gave its own, changes nothing.
        done.succeed(tooLong ? "" : String(decoding: output, as: UTF8.self))
        context.fireChannelInactive()
    }
}

/// The channel the session runs over: the command's stdout to the owner, its stderr as lines.
private final class SessionHandler: ChannelInboundHandler, @unchecked Sendable {
    typealias InboundIn = SSHChannelData

    private let connection: SshConnection
    private let command: String
    private let keymap: String
    private let events: SshEvents
    private var context: ChannelHandlerContext?
    private var paused = false
    private var running = false
    private var errorLine = [UInt8]()

    init(connection: SshConnection, command: String, keymap: String, events: SshEvents) {
        self.connection = connection
        self.command = command
        self.keymap = keymap
        self.events = events
    }

    func handlerAdded(context: ChannelHandlerContext) {
        self.context = context
        context.channel.setOption(ChannelOptions.allowRemoteHalfClosure, value: true).whenFailure { _ in }
    }

    func handlerRemoved(context: ChannelHandlerContext) {
        self.context = nil
    }

    func channelActive(context: ChannelHandlerContext) {
        context.triggerUserOutboundEvent(SSHChannelRequestEvent.ExecRequest(command: command, wantReply: true), promise: nil)
        context.read()
    }

    func userInboundEventTriggered(context: ChannelHandlerContext, event: Any) {
        switch event {
        case is ChannelSuccessEvent:
            if !running {
                running = true
                events.opened(keymap: keymap)
            }
        case is ChannelFailureEvent:
            connection.finish(error: "the server refused to run the command")
        case ChannelEvent.inputClosed:
            context.close(promise: nil)
        default:
            break
        }
        context.fireUserInboundEventTriggered(event)
    }

    func channelRead(context: ChannelHandlerContext, data: NIOAny) {
        let message = unwrapInboundIn(data)
        guard case .byteBuffer(let bytes) = message.data else { return }
        switch message.type {
        case .channel:
            if !events.stdout(Data(bytes.readableBytesView)) { paused = true }
        case .stdErr:
            for byte in bytes.readableBytesView {
                if byte == UInt8(ascii: "\n") { flushErrorLine() } else { errorLine.append(byte) }
            }
        default:
            break
        }
    }

    func channelReadComplete(context: ChannelHandlerContext) {
        if !paused { context.read() }
        context.fireChannelReadComplete()
    }

    func resume() {
        guard paused, let context else { return }
        paused = false
        context.read()
    }

    func channelInactive(context: ChannelHandlerContext) {
        flushErrorLine()
        // The command ended, or never started: either way the session is over.
        connection.finish(error: nil)
        context.fireChannelInactive()
    }

    private func flushErrorLine() {
        if errorLine.isEmpty { return }
        events.stderr(String(decoding: errorLine, as: UTF8.self))
        errorLine = []
    }
}
