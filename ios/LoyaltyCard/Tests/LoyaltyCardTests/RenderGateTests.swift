import XCTest
import SwiftUI
import UIKit
@testable import LoyaltyCard

/// Renders the native card for scripts/render-gate (full-colour per-pixel diff against the React /my-id page rendered by
/// Playwright WebKit at the same size and density). Writes a PNG only when the gate passes CARD_FIXTURE and CARD_RENDER_OUT
/// (xcodebuild test: TEST_RUNNER_CARD_FIXTURE / TEST_RUNNER_CARD_RENDER_OUT); a plain test run skips it.
final class RenderGateTests: XCTestCase {
    func testRendersTheCard() throws {
        let env = ProcessInfo.processInfo.environment
        guard let fixture = env["CARD_FIXTURE"], let out = env["CARD_RENDER_OUT"] else { throw XCTSkip("render gate only") }
        let raw = try String(contentsOfFile: fixture, encoding: .utf8)
        let card = try XCTUnwrap(CardSnapshot.fromCardPayload(raw, sessionToken: "render-gate-fixture-token", now: 0, defaultPrefix: ""))
        let width = Double(env["RENDER_W"] ?? "402") ?? 402, height = Double(env["RENDER_H"] ?? "874") ?? 874
        let scale = Double(env["RENDER_SCALE"] ?? "3") ?? 3
        try renderGatePNG(BalfourMemberCard(card: card, offline: false, onClose: {}, onAddToAppleWallet: {}),
                          width: width, height: height, scale: scale, to: out)
    }
}

/// Lays the view out in a window with no safe area (as the reference page has none) and snapshots it after it settles.
func renderGatePNG<V: View>(_ view: V, width: Double, height: Double, scale: Double, to path: String) throws {
    let frame = CGRect(x: 0, y: 0, width: width, height: height)
    let window = UIWindow(frame: frame)
    let host = UIHostingController(rootView: view.ignoresSafeArea())
    host.view.frame = frame
    window.rootViewController = host
    window.isHidden = false
    host.view.setNeedsLayout()
    host.view.layoutIfNeeded()
    RunLoop.main.run(until: Date().addingTimeInterval(1.5))
    let format = UIGraphicsImageRendererFormat()
    format.scale = scale
    format.opaque = true
    let image = UIGraphicsImageRenderer(bounds: frame, format: format).image { _ in
        _ = host.view.drawHierarchy(in: frame, afterScreenUpdates: true)
    }
    let data = try XCTUnwrap(image.pngData())
    try data.write(to: URL(fileURLWithPath: path))
}
