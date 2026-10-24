import SwiftUI
import UIKit

// Colours from balfour-founders-club src/app/globals.css.
private let forest = Color(red: 0x13 / 255, green: 0x34 / 255, blue: 0x2B / 255)
private let cream = Color(red: 0xFB / 255, green: 0xF6 / 255, blue: 0xED / 255)
private let ink = Color(red: 0x21 / 255, green: 0x23 / 255, blue: 0x22 / 255)

// Fallbacks of the CMS-bound copy keys the web card shows (member_card.founder_label / member_card.staff_prompt).
private let founderLabel = "FOUNDER"
private let staffPrompt = "Show this to a member of staff"

func cardImage(_ name: String) -> UIImage? {
    for ext in ["png", "webp"] {
        if let url = Bundle.module.url(forResource: name, withExtension: ext), let img = UIImage(contentsOfFile: url.path) { return img }
    }
    return nil
}

/// Native copy of balfour-founders-club src/components/MyIdOverlay.tsx + MyIdCardContent.tsx, measure for
/// measure (CSS px == pt in WKWebView), as the iOS app shows it. Shown settled, as the web overlay paints
/// once its spring has run.
public struct BalfourMemberCard: View {
    let card: CardSnapshot
    let offline: Bool
    let onClose: () -> Void
    let onAddToAppleWallet: () -> Void

    public init(card: CardSnapshot, offline: Bool, onClose: @escaping () -> Void, onAddToAppleWallet: @escaping () -> Void) {
        self.card = card
        self.offline = offline
        self.onClose = onClose
        self.onAddToAppleWallet = onAddToAppleWallet
    }

    public var body: some View {
        // The outer reader keeps the safe area so it can report env(safe-area-inset-top); the inner one
        // ignores it: the overlay is fixed inset-0 over an edge-to-edge WebView (contentInset never).
        GeometryReader { outer in
            page(safeTop: outer.safeAreaInsets.top)
        }
    }

    private func page(safeTop: CGFloat) -> some View {
        GeometryReader { geo in
            let w = geo.size.width
            ZStack(alignment: .top) {
                forest
                // next/image fill + object-cover over the whole overlay.
                if let pattern = cardImage("bf-pattern") {
                    Image(uiImage: pattern).resizable().scaledToFill().frame(width: w, height: geo.size.height).clipped()
                }
                VStack(spacing: 0) {
                    header(safeTop: safeTop)
                    // flex-1 items-center justify-center overflow-y-auto; content is max-w-sm px-6 py-24 gap-6.
                    GeometryReader { area in
                        CardScroll {
                            content(width: min(384, w))
                                .frame(width: w)
                                .frame(minHeight: area.size.height)
                        }
                    }
                }
            }
            .frame(width: w, height: geo.size.height)
        }
        .ignoresSafeArea()
    }

    /// Header: shrink-0, px-6 pt-6 pb-4 (native myid-header-safe-top: safe-area-inset-top + 1.5rem).
    private func header(safeTop: CGFloat) -> some View {
        HStack(alignment: .center, spacing: 0) {
            Color.clear.frame(width: 44, height: 44)
            Spacer(minLength: 0)
            if let logo = cardImage("bf-logo") {
                // h-12 w-auto brightness-0 invert: the logo drawn white. bf-logo.png is that img as iOS WebKit paints it on
                // the page (226x144 device px, the box it snaps to), so it is drawn 1:1, never resampled here
                // (RENDER-GATE-WEBKIT-RASTERS-2026-10-23, healer:4b3d7c53).
                Image(uiImage: logo).renderingMode(.template).resizable().foregroundColor(.white)
                    .frame(width: 48 * logo.size.width / logo.size.height, height: 48)
                    .accessibilityLabel("Balfour Winery")
            }
            Spacer(minLength: 0)
            CloseButton(action: onClose)
        }
        .padding(.horizontal, 24)
        .padding(.top, safeTop + 24)
        .padding(.bottom, 16)
    }

    private func content(width: CGFloat) -> some View {
        VStack(spacing: 24) {
            CssText(text: founderLabel, font: CardType.optimaBold(14), lineHeight: 20, tracking: 3.92, color: cream)
            QrCardBox(width: width - 48, scanValue: card.scanValue, memberNumber: card.memberNumber)
            CssText(text: staffPrompt, font: CardType.gothamBook(16), lineHeight: 26, color: cream)
            if offline {
                CssText(text: "NO CONNECTION · SAVED CARD", font: CardType.gothamBook(12), lineHeight: 16, tracking: 1.2, color: cream.opacity(0.7))
            }
            // WalletButtons on iOS: the Apple Wallet badge only, h-10, in an overflow-hidden rounded-lg button.
            if let badge = cardImage("bf-apple-wallet") {
                // bf-apple-wallet.png is the page's add-to-apple-wallet.svg as iOS WebKit paints it at h-10 (388x120 device px),
                // drawn 1:1 (RENDER-GATE-WEBKIT-RASTERS-2026-10-23, healer:4b3d7c53).
                Image(uiImage: badge).resizable()
                    .frame(width: 40 * badge.size.width / badge.size.height, height: 40)
                    .clipShape(RoundedRectangle(cornerRadius: 8, style: .circular))
                    .contentShape(Rectangle())
                    .onTapGesture(perform: onAddToAppleWallet)
                    .accessibilityLabel("Add to Apple Wallet")
                    .accessibilityAddTraits(.isButton)
            }
        }
        .padding(.vertical, 96)
        .frame(width: width)
    }
}

/// MemberQrCode: `flex flex-col items-center bg-white p-4 w-full rounded-lg`, QR 288, member number mt-4 below
/// it (always the stable member number, even when the QR carries the rotating token).
private struct QrCardBox: View {
    let width: CGFloat
    let scanValue: String
    let memberNumber: String

    var body: some View {
        VStack(spacing: 0) {
            QrModules(qr: QrCode.encode(scanValue), ink: ink)
                .frame(width: 288, height: 288)
                .accessibilityLabel("Your member QR code")
            CssText(text: memberNumber, font: CardType.mono(12), lineHeight: 16, tracking: 2, color: ink.opacity(0.7))
                .contentShape(Rectangle())
                .onTapGesture { UIPasteboard.general.string = memberNumber }
                .padding(.top, 16)
        }
        .padding(16)
        .frame(width: width)
        .background(RoundedRectangle(cornerRadius: 8, style: .circular).fill(Color.white))
    }
}

/// h-11 w-11 button, 16x16 svg of a 14x14 X, cream 1.5 round strokes.
private struct CloseButton: View {
    let action: () -> Void

    var body: some View {
        Path { p in
            let u: CGFloat = 16 / 14
            p.move(to: CGPoint(x: 1 * u, y: 1 * u)); p.addLine(to: CGPoint(x: 13 * u, y: 13 * u))
            p.move(to: CGPoint(x: 13 * u, y: 1 * u)); p.addLine(to: CGPoint(x: 1 * u, y: 13 * u))
        }
        .stroke(cream, style: StrokeStyle(lineWidth: 1.5 * 16 / 14, lineCap: .round))
        .frame(width: 16, height: 16)
        .frame(width: 44, height: 44)
        .contentShape(Rectangle())
        .onTapGesture(perform: action)
        .accessibilityLabel("Close")
        .accessibilityAddTraits(.isButton)
    }
}

/// Modules snapped to device pixels, as WebKit paints the SVG's shape-rendering="crispEdges".
struct QrModules: View {
    let qr: QrCode
    let ink: Color
    @Environment(\.displayScale) private var scale

    var body: some View {
        Canvas { ctx, size in
            let n = qr.size
            let m = size.width / CGFloat(n)
            // WebKit's crispEdges fills every device pixel a dark module touches: a run starts at the floor of its edge and
            // ends at the ceiling (fitted on every module edge of the gate's WebKit reference: 3014/3014 x, 2980/2980 y;
            // round-to-nearest matched only ~64%) (RENDER-GATE-QR-SNAP-2026-10-23, healer:4b3d7c53).
            let start = { (v: CGFloat) in (v * scale + 1e-6).rounded(.down) / scale }
            let end = { (v: CGFloat) in (v * scale - 1e-6).rounded(.up) / scale }
            ctx.fill(Path(CGRect(origin: .zero, size: size)), with: .color(.white))
            var path = Path()
            for y in 0..<n {
                let top = start(CGFloat(y) * m), bottom = end(CGFloat(y + 1) * m)
                var x = 0
                while x < n {
                    if !qr.isDark(x, y) { x += 1; continue }
                    var run = x
                    while run < n && qr.isDark(run, y) { run += 1 }
                    let left = start(CGFloat(x) * m), right = end(CGFloat(run) * m)
                    path.addRect(CGRect(x: left, y: top, width: right - left, height: bottom - top))
                    x = run
                }
            }
            ctx.fill(path, with: .color(ink))
        }
    }
}
