import XCTest
import SwiftUI
import UIKit
@testable import LoyaltyCard

/// Renders the native card for scripts/render-gate (full-colour per-pixel diff against the React /my-id page rendered by
/// Playwright WebKit at the same size and density). Writes a PNG only when the gate passes CARD_FIXTURE and CARD_RENDER_OUT
/// (xcodebuild test: TEST_RUNNER_CARD_FIXTURE / TEST_RUNNER_CARD_RENDER_OUT); a plain test run skips it.
final class RenderGateTests: XCTestCase {
    @MainActor
    func testRendersTheCard() throws {
        let env = ProcessInfo.processInfo.environment
        guard let fixture = env["CARD_FIXTURE"], let out = env["CARD_RENDER_OUT"] else { throw XCTSkip("render gate only") }
        let raw = try String(contentsOfFile: fixture, encoding: .utf8)
        let card = try XCTUnwrap(CardSnapshot.fromCardPayload(raw, sessionToken: "render-gate-fixture-token", now: 0, defaultPrefix: ""))
        let width = Double(env["RENDER_W"] ?? "402") ?? 402, height = Double(env["RENDER_H"] ?? "874") ?? 874
        let scale = Double(env["RENDER_SCALE"] ?? "3") ?? 3
        try renderGatePNG(BalfourMemberCard(card: card, offline: false, onClose: {}, onAddToAppleWallet: {}).environment(\.cardStaticRender, true),
                          width: width, height: height, scale: scale, to: out)
    }
}

/// Renders the view at the page's size and density with SwiftUI's ImageRenderer into an 8-bit sRGB bitmap (the reference
/// PNG's format), with no safe area (the reference page has none). Not UIWindow.drawHierarchy: a package test has no host
/// app or window scene, so drawHierarchy drew nothing and a "passing" test gave the gate an all-black PNG. Not
/// renderer.uiImage either: with the Randalls card's shadows it came back in a format pngData() cannot encode
/// (RENDER-GATE-IOS-BLANK-2026-10-23, healer:4b3d7c53). The cards have no UIKit-backed views except the scroller, which
/// the gate swaps for CardScroll's static layout. A render of a single colour fails here, never in the diff.
@MainActor
func renderGatePNG<V: View>(_ view: V, width: Double, height: Double, scale: Double, to path: String) throws {
    // displayScale must be the device's 3: the QR and text snap to device pixels through it (render()'s own default
    // rasterization scale is 1, which put every edge on a 1x grid).
    let renderer = ImageRenderer(content: view.frame(width: width, height: height).ignoresSafeArea()
        .environment(\.displayScale, scale))
    renderer.proposedSize = ProposedViewSize(width: width, height: height)
    renderer.scale = scale
    let w = Int((width * scale).rounded()), h = Int((height * scale).rounded()), rowBytes = w * 4
    var px = [UInt8](repeating: 0, count: rowBytes * h)
    let ctx = try XCTUnwrap(CGContext(data: &px, width: w, height: h, bitsPerComponent: 8, bytesPerRow: rowBytes,
                                      space: try XCTUnwrap(CGColorSpace(name: CGColorSpace.sRGB)),
                                      bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue))
    ctx.setFillColor(CGColor(srgbRed: 0, green: 0, blue: 0, alpha: 1))
    ctx.fill(CGRect(x: 0, y: 0, width: w, height: h))
    ctx.scaleBy(x: scale, y: scale)
    renderer.render(rasterizationScale: scale) { _, draw in draw(ctx) }
    let cg = try XCTUnwrap(ctx.makeImage())
    // Written before the check so a failing render still comes back to the gate for a look.
    try XCTUnwrap(UIImage(cgImage: cg).pngData()).write(to: URL(fileURLWithPath: path))
    let first = px[0..<4]
    var uniform = true
    for y in stride(from: 0, to: h, by: 7) where uniform {
        for x in stride(from: 0, to: w, by: 7) {
            let o = y * rowBytes + x * 4
            if px[o..<(o + 4)] != first { uniform = false; break }
        }
    }
    XCTAssertFalse(uniform, "the native render is a single colour: nothing was drawn")
}
