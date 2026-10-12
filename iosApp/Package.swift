// swift-tools-version: 6.0

import PackageDescription

// The iOS and iPadOS app, as an xtool project: it is built from Linux, with
// no Xcode project. The app is a thin host: the screens and the session come
// from the Kotlin framework (Shared), the ssh client from GliffSSH.
//
// Shared.xcframework is not in the repository: Kotlin can only link it on a
// Mac. scripts/fetch-ios-framework.sh downloads the one the ios-framework
// workflow built.
let package = Package(
    name: "Gliff",
    platforms: [
        .iOS(.v17),
    ],
    products: [
        // An xtool project contains exactly one library product: the app.
        .library(name: "Gliff", targets: ["Gliff"]),
    ],
    dependencies: [
        .package(path: "GliffSSH"),
    ],
    targets: [
        .target(
            name: "Gliff",
            dependencies: [
                .product(name: "GliffSSH", package: "GliffSSH"),
                "Shared",
            ],
            swiftSettings: [
                // The Kotlin types carry no Sendable annotations.
                .swiftLanguageMode(.v5),
            ],
            linkerSettings: [
                // Shared is a static framework: what it uses is linked here.
                .linkedLibrary("c++"),
                .linkedFramework("AVFoundation"),
                .linkedFramework("AVKit"),
                .linkedFramework("CoreGraphics"),
                .linkedFramework("CoreMedia"),
                .linkedFramework("CoreText"),
                .linkedFramework("CoreVideo"),
                .linkedFramework("Metal"),
                .linkedFramework("QuartzCore"),
                .linkedFramework("UIKit"),
                .linkedFramework("VideoToolbox"),
            ]
        ),
        .binaryTarget(name: "Shared", path: "Frameworks/Shared.xcframework"),
    ]
)
