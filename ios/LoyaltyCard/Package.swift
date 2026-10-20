// swift-tools-version: 5.9
import PackageDescription

// LOYALTY-NATIVE-CARD-IOS-2026-10-20 (healer:596fd32f): the native member card for iOS, a SwiftUI copy of
// the Android package gs.boldthin.loyalty.card (healer:716e5320); same core as randalls-rewards-app ios/LoyaltyCard. No Capacitor dependency, so it moves
// unchanged into the native shell that replaces Capacitor (healer:062765cc).
let package = Package(
    name: "LoyaltyCard",
    platforms: [.iOS(.v15)],
    products: [
        .library(name: "LoyaltyCard", targets: ["LoyaltyCard"])
    ],
    targets: [
        .target(name: "LoyaltyCard", resources: [.process("Resources")]),
        .testTarget(name: "LoyaltyCardTests", dependencies: ["LoyaltyCard"], resources: [.copy("qr-vectors.json")]),
    ]
)
