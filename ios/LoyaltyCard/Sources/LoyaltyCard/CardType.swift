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

    var body: some View {
        Text(text)
            .font(Font(font as CTFont))
            .kerning(tracking)
            .foregroundColor(color)
            .lineLimit(1)
            .fixedSize()
            // SwiftUI kerning stops at the last glyph; CSS letter-spacing also follows it, which moves
            // centred text left by half the spacing.
            .padding(.trailing, tracking)
            .frame(height: lineHeight)
    }
}
