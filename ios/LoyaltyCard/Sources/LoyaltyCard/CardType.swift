import CoreText
import SwiftUI
import UIKit

/// The card's fonts: the exact files the web page loads (next/font woff2, here as TTF), made straight from
/// this package's bundle. Not registered by name: iOS ships its own Optima, which a name lookup would pick
/// instead of the site's file. Sizes are CSS px, which is points in WKWebView, and do not follow Dynamic
/// Type (the WebView does not either).
enum CardType {
    private static var descriptors: [String: CTFontDescriptor] = [:]

    private static func font(_ file: String, _ size: CGFloat, fallback: UIFont) -> UIFont {
        if descriptors[file] == nil, let url = Bundle.module.url(forResource: file, withExtension: "ttf"),
           let d = (CTFontManagerCreateFontDescriptorsFromURL(url as CFURL) as? [CTFontDescriptor])?.first {
            descriptors[file] = d
        }
        guard let d = descriptors[file] else { return fallback }
        return CTFontCreateWithFontDescriptor(d, size, nil) as UIFont
    }

    /// font-display bold: Optima Bold, for the FOUNDER label.
    static func optimaBold(_ size: CGFloat) -> UIFont { font("Optima-Bold", size, fallback: .systemFont(ofSize: size, weight: .bold)) }

    /// font-sans: Gotham Book, the page body font.
    static func gothamBook(_ size: CGFloat) -> UIFont { font("Gotham-Book", size, fallback: .systemFont(ofSize: size)) }

    /// Tailwind font-mono resolves to ui-monospace in WebKit: SF Mono.
    static func mono(_ size: CGFloat) -> UIFont { .monospacedSystemFont(ofSize: size, weight: .regular) }
}

/// One line of CSS text: the font at `size`, a line box `lineHeight` tall with the glyphs centred in it
/// (CSS half-leading), and letter-spacing added after every character, the last included, as CSS does.
struct CssText: View {
    let text: String
    let font: UIFont
    let lineHeight: CGFloat
    var tracking: CGFloat = 0
    let color: Color
    /// A text-align:center line: fills the width it is given and is placed as WebKit places it (CssCentredLine).
    var centred = false

    var body: some View {
        if centred {
            CssCentredLine(text: text, font: font, lineHeight: lineHeight, tracking: tracking, color: color)
        } else {
            inline
        }
    }

    private var inline: some View {
        Text(spaced)
            .font(Font(font as CTFont))
            .foregroundColor(color)
            .lineLimit(1)
            .fixedSize()
            // CSS letter-spacing also follows the last glyph, which moves centred text left by half the
            // spacing. The kern is set on every glyph but the last (SwiftUI/CoreText disagree across OS
            // versions on whether a trailing kern is applied), so this padding is the only trailing space.
            .padding(.trailing, tracking)
            .frame(height: lineHeight)
    }

    private var spaced: AttributedString {
        var a = AttributedString(text)
        a.kern = tracking
        if let last = a.characters.indices.last { a[last..<a.endIndex].kern = 0 }
        return a
    }
}

/// A centred CSS line drawn the way WebKit draws it, so its glyphs land on the page's device pixels (render gate,
/// RENDER-GATE-TEXT-ORIGIN-2026-10-24, healer:4b3d7c53). SwiftUI's layout is float and gives the same box tops and
/// starts as WebKit's (traced on the gate), but a Text draws from a pixel-snapped origin, and so does a Canvas: its layer
/// sits on the nearest device pixel (0.5 px -> 1), so a line in a box at x 166.5 drew half a pixel right. WebKit snaps
/// each glyph to a whole device pixel from the float start, so any fraction moves glyphs a pixel. Here, as in WebKit:
/// - the line is as wide as its advances plus letter-spacing after every glyph (the last included), rounded up to a
///   layout unit (1/64 px), and starts at half the free width, rounded down to a layout unit;
/// - the baseline is the half-leading one (WebKitLineMetrics), rounded to a device pixel: the page's layout baselines
///   (reference.mjs --eval with a zero-size baseline marker) 1746.52, 1968.89, 1581.09, 1807.55, 1991.34, 721.03 paint
///   at 1747, 1969, 1581, 1808, 1991, 721;
/// - the Canvas layer's snap is undone, so both land at the page's absolute positions; no subpixel glyph positioning.
struct CssCentredLine: View {
    let text: String
    let font: UIFont
    let lineHeight: CGFloat
    let tracking: CGFloat
    let color: Color
    @Environment(\.displayScale) private var scale

    var body: some View {
        GeometryReader { g in
            let origin = g.frame(in: .global).origin
            Canvas { ctx, size in draw(ctx, width: size.width, globalOrigin: origin) }
        }
        .frame(height: lineHeight)
        .frame(maxWidth: .infinity)
        .accessibilityElement()
        .accessibilityLabel(text)
    }

    private func draw(_ ctx: GraphicsContext, width: CGFloat, globalOrigin: CGPoint) {
        var attrs: [NSAttributedString.Key: Any] = [
            NSAttributedString.Key(kCTFontAttributeName as String): font,
            NSAttributedString.Key(kCTForegroundColorAttributeName as String): UIColor(color).cgColor,
        ]
        // A kern of 0 turns the font's pair kerning off in CoreText (the Balfour staff line drifted -1..+2 px), so it is
        // only set for letter-spacing.
        if tracking != 0 { attrs[NSAttributedString.Key(kCTKernAttributeName as String)] = tracking }
        let line = CTLineCreateWithAttributedString(NSAttributedString(string: text, attributes: attrs))
        let w = CGFloat(CTLineGetTypographicBounds(line, nil, nil, nil))
        let (ascent, descent) = WebKitLineMetrics.of(font)
        let unit: CGFloat = 64
        let x = ((width - (w * unit).rounded(.up) / unit) / 2 * unit).rounded(.down) / unit
        // Where SwiftUI put this Canvas's layer: its float origin snapped to the nearest device pixel.
        let snap = { (v: CGFloat) in (v * scale).rounded() / scale }
        let layer = CGPoint(x: snap(globalOrigin.x), y: snap(globalOrigin.y))
        let baseline = snap(globalOrigin.y + (lineHeight - ascent - descent) / 2 + ascent)
        ctx.withCGContext { cg in
            cg.saveGState()
            cg.textMatrix = CGAffineTransform(scaleX: 1, y: -1)
            cg.setAllowsFontSubpixelPositioning(false)
            cg.setShouldSubpixelPositionFonts(false)
            cg.textPosition = CGPoint(x: globalOrigin.x + x - layer.x, y: baseline - layer.y)
            CTLineDraw(line, cg)
            cg.restoreGState()
        }
    }
}

/// The ascent and descent WebKit lays a line out with. For the card's own fonts these are CoreText's; for the system
/// monospaced font (Tailwind font-mono = ui-monospace = SF Mono) WebKit uses its own: measured on the page at 12px,
/// ascent 11.598 and line content height 14.133 (CoreText says 11.426 / 14.320), which put the member number's baseline
/// 0.75 device px lower than CoreText's metrics do (Balfour's number painted 2 px high).
enum WebKitLineMetrics {
    static func of(_ font: UIFont) -> (ascent: CGFloat, descent: CGFloat) {
        // The system font's families are hidden (dot-prefixed) and renamed across OS versions, so match by trait.
        let ct = font as CTFont
        if CTFontGetSymbolicTraits(ct).contains(.traitMonoSpace) && (CTFontCopyFamilyName(ct) as String).hasPrefix(".") {
            return (font.pointSize * 11.598 / 12, font.pointSize * (14.133 - 11.598) / 12)
        }
        return (CTFontGetAscent(ct), CTFontGetDescent(ct))
    }
}
