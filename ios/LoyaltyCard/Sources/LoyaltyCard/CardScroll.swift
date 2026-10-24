import SwiftUI

/// True only in the render gate's test (RenderGateTests): the card is drawn with SwiftUI's ImageRenderer there, which
/// cannot draw a ScrollView (UIKit-backed), so the scroller's content came out missing (RENDER-GATE-IOS-BLANK-2026-10-23,
/// healer:4b3d7c53). Never set in the app.
struct CardStaticRenderKey: EnvironmentKey { static let defaultValue = false }

extension EnvironmentValues {
    var cardStaticRender: Bool {
        get { self[CardStaticRenderKey.self] }
        set { self[CardStaticRenderKey.self] = newValue }
    }
}

/// The card's vertical scroller (the page's overflow-y-auto). For the render gate the same content is laid out the way
/// ScrollView lays it out (width proposed, height unbounded, pinned to the top) in a plain container, so the two are the
/// same picture whenever the content fits the screen, which it does at the gate's size.
struct CardScroll<Content: View>: View {
    @Environment(\.cardStaticRender) private var staticRender
    @ViewBuilder let content: Content

    var body: some View {
        if staticRender {
            content
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                .clipped()
        } else {
            ScrollView(.vertical, showsIndicators: false) { content }
        }
    }
}
