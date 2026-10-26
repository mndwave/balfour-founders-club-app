import SwiftUI
import UIKit

// Offline cold launch: the OS launch screen (cream, logo 156 pt wide, screen-centred) hands over to this layer with
// no visible change, the logo holds for a beat, then one fade reveals the card. No ring: the offline launch has no
// network to wait on, so the calm handover is the whole animation (Kyle 2026-10-26: same care as the Randalls one).
private let cream = Color(red: 0xFB / 255, green: 0xF6 / 255, blue: 0xED / 255)

private let logoWidth: CGFloat = 156
private let holdSeconds = 0.7
private let fadeSeconds = 0.5

struct CardLaunchSplash: View {
    let onDone: () -> Void
    @State private var visible = true

    var body: some View {
        ZStack {
            cream
            // SplashLogo is the launch storyboard's own imageset (App asset catalogue), so frame one is the same pixels.
            if let logo = UIImage(named: "SplashLogo") {
                Image(uiImage: logo).resizable().aspectRatio(logo.size, contentMode: .fit).frame(width: logoWidth)
            }
        }
        .opacity(visible ? 1 : 0)
        .ignoresSafeArea()
        .contentShape(Rectangle())
        .onTapGesture {}
        .task {
            try? await Task.sleep(nanoseconds: UInt64(holdSeconds * 1_000_000_000))
            withAnimation(.timingCurve(0.4, 0, 0.2, 1, duration: fadeSeconds)) { visible = false }
            try? await Task.sleep(nanoseconds: UInt64(fadeSeconds * 1_000_000_000))
            onDone()
        }
    }
}
