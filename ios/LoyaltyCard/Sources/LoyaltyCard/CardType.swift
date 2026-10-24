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

/// A centred CSS line drawn the way WebKit draws it, so its glyphs land on the page's device pixels. SwiftUI's layout is
/// float (the same box tops and starts as WebKit's, traced on the gate), but a Text draws from a pixel-snapped origin
/// and WebKit does not: both snap each glyph to a whole device pixel from the line's start, so a start 0.36 px apart
/// moved about every third glyph of "HOLD UP TO THE SCANNER AT THE TILL" one pixel left of the page (render gate,
/// RENDER-GATE-TEXT-ORIGIN-2026-10-24, healer:4b3d7c53). Here, as in WebKit: the line is as wide as its advances plus
/// letter-spacing after every glyph (the last included), rounded up to a layout unit (1/64 px); it starts at half the
/// free width, rounded down to a layout unit; the baseline is the half-leading one, floored to a device pixel (fitted
/// on the gate: 1746.53 -> 1746, 1968.90 -> 1968, 1580.30 -> 1580); glyphs are not subpixel-positioned.
struct CssCentredLine: View {
    let text: String
    let font: UIFont
    let lineHeight: CGFloat
    let tracking: CGFloat
    let color: Color
    @Environment(\.displayScale) private var scale

    var body: some View {
        GeometryReader { g in
            let top = g.frame(in: .global).minY
            Canvas { ctx, size in draw(ctx, width: size.width, globalTop: top) }
        }
        .frame(height: lineHeight)
        .frame(maxWidth: .infinity)
        .accessibilityElement()
        .accessibilityLabel(text)
    }

    private func draw(_ ctx: GraphicsContext, width: CGFloat, globalTop: CGFloat) {
        var attrs: [NSAttributedString.Key: Any] = [
            NSAttributedString.Key(kCTFontAttributeName as String): font,
            NSAttributedString.Key(kCTForegroundColorAttributeName as String): UIColor(color).cgColor,
        ]
        // A kern of 0 turns the font's pair kerning off in CoreText (the Balfour staff line drifted -1..+2 px), so it is
        // only set for letter-spacing.
        if tracking != 0 { attrs[NSAttributedString.Key(kCTKernAttributeName as String)] = tracking }
        let line = CTLineCreateWithAttributedString(NSAttributedString(string: text, attributes: attrs))
        var ascent: CGFloat = 0, descent: CGFloat = 0
        let w = CGFloat(CTLineGetTypographicBounds(line, &ascent, &descent, nil))
        let unit: CGFloat = 64
        let x = ((width - (w * unit).rounded(.up) / unit) / 2 * unit).rounded(.down) / unit
        let baseline = ((globalTop + (lineHeight - ascent - descent) / 2 + ascent) * scale).rounded(.down) / scale - globalTop
        ctx.withCGContext { cg in
            cg.saveGState()
            cg.textMatrix = CGAffineTransform(scaleX: 1, y: -1)
            cg.setAllowsFontSubpixelPositioning(false)
            cg.setShouldSubpixelPositionFonts(false)
            cg.textPosition = CGPoint(x: x, y: baseline)
            CTLineDraw(line, cg)
            cg.restoreGState()
        }
    }
}
