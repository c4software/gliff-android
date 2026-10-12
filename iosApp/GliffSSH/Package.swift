// swift-tools-version: 6.0

import PackageDescription

// The ssh client of the iOS app, in a package of its own: it knows nothing of
// UIKit or of the shared Kotlin framework, so it builds and is tested on
// Linux (`swift test`), against a real sshd.
let package = Package(
    name: "GliffSSH",
    platforms: [
        .iOS(.v17),
        .macOS(.v14),
    ],
    products: [
        .library(name: "GliffSSH", targets: ["GliffSSH"]),
    ],
    dependencies: [
        .package(url: "https://github.com/apple/swift-nio.git", from: "2.65.0"),
        .package(url: "https://github.com/apple/swift-nio-ssh.git", from: "0.9.0"),
        .package(url: "https://github.com/apple/swift-crypto.git", "3.0.0"..<"5.0.0"),
    ],
    targets: [
        .target(
            name: "GliffSSH",
            dependencies: [
                .product(name: "NIOCore", package: "swift-nio"),
                .product(name: "NIOPosix", package: "swift-nio"),
                .product(name: "NIOSSH", package: "swift-nio-ssh"),
                .product(name: "Crypto", package: "swift-crypto"),
            ]
        ),
        .testTarget(name: "GliffSSHTests", dependencies: ["GliffSSH"]),
    ]
)
