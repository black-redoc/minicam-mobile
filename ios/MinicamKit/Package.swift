// swift-tools-version: 6.0
import PackageDescription

// Foundation-only core of the iOS app: builds and tests on macOS with
// `swift test`, no Xcode required (see docs/adr/0001).
let package = Package(
    name: "MinicamKit",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "MinicamKit", targets: ["MinicamKit"]),
    ],
    targets: [
        .target(name: "MinicamKit"),
        .testTarget(name: "MinicamKitTests", dependencies: ["MinicamKit"]),
    ]
)
