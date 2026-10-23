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
