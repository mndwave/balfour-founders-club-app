import Network
import SwiftUI
import UIKit

/// The card state the overlay drives; the SwiftUI view redraws from it.
final class CardModel: ObservableObject {
    @Published var card: CardSnapshot
    @Published var offline = false
    @Published var splash: Bool

    init(card: CardSnapshot, splash: Bool) {
        self.card = card
        self.splash = splash
    }
}

private struct OverlayRoot: View {
    @ObservedObject var model: CardModel
    let onClose: () -> Void
    let onWallet: () -> Void

    var body: some View {
        ZStack {
            BalfourMemberCard(card: model.card, offline: model.offline, onClose: onClose, onAddToAppleWallet: onWallet)
            // Cold launch only: the logo holds on cream, then one fade into the card.
            if model.splash { CardLaunchSplash { model.splash = false } }
        }
    }
}

/// Puts the native member card over a view controller, from the device snapshot, with no network wait,
/// then freshens it from GET /loyalty/portal/card. Keeps the screen awake and at full brightness while
/// shown, as the web /qr page does through the keep-awake and screen-brightness plugins.
public final class MemberCardOverlay {
    private weak var host: UIViewController?
    private let brand: CardBrand
    private let store: CardStore
    private let client: CardClient
    /// Called after the member closes the card (the host decides what to load behind it).
    private let onClosed: () -> Void
    private var hosting: UIHostingController<OverlayRoot>?
    private var model: CardModel?
    private var monitor: NWPathMonitor?
    private var lastPathSatisfied: Bool?
    private var savedBrightness: CGFloat?

    /// The splash plays once per process; a card shown again later goes straight to the card.
    private static var splashPlayed = false

    public init(host: UIViewController, brand: CardBrand, onClosed: @escaping () -> Void) {
        self.host = host
        self.brand = brand
        store = CardStore(brand: brand)
        client = CardClient(brand: brand)
        self.onClosed = onClosed
    }

    public var isShowing: Bool { hosting != nil }

    /// Shows the saved card. False when this member has none yet: the caller keeps its own offline page.
    @discardableResult
    public func show() -> Bool {
        if hosting != nil { return true }
        guard let host = host, let saved = store.load() else { return false }
        let model = CardModel(card: saved, splash: !MemberCardOverlay.splashPlayed)
        MemberCardOverlay.splashPlayed = true
        self.model = model
        let root = OverlayRoot(model: model, onClose: { [weak self] in self?.close() }, onWallet: { [weak self] in self?.addToAppleWallet() })
        let vc = UIHostingController(rootView: root)
        vc.view.backgroundColor = .clear
        host.addChild(vc)
        vc.view.frame = host.view.bounds
        vc.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        host.view.addSubview(vc.view)
        vc.didMove(toParent: host)
        hosting = vc
        UIApplication.shared.isIdleTimerDisabled = true
        savedBrightness = UIScreen.main.brightness
        UIScreen.main.brightness = 1
        UIImpactFeedbackGenerator(style: .light).impactOccurred()
        refresh(retry: true)
        watchForNetwork()
        return true
    }

    public func close() {
        guard let vc = hosting else { return }
        vc.willMove(toParent: nil)
        vc.view.removeFromSuperview()
        vc.removeFromParent()
        hosting = nil
        model = nil
        monitor?.cancel()
        monitor = nil
        lastPathSatisfied = nil
        UIApplication.shared.isIdleTimerDisabled = false
        if let b = savedBrightness { UIScreen.main.brightness = b }
        savedBrightness = nil
        onClosed()
    }

    // Same as the web page: one retry after 1.5 s, then say the card is the saved one.
    private func refresh(retry: Bool) {
        guard let token = store.sessionToken() else { return }
        client.fetch(sessionToken: token) { [weak self] result in
            DispatchQueue.main.async {
                guard let self = self, let model = self.model else { return }
                switch result {
                case .ok(let card):
                    self.store.save(card)
                    model.card = card
                    model.offline = false
                // A signed-out session keeps showing the saved card; the web app signs the member out.
                case .signedOut, .failed:
                    break
                case .unreachable:
                    if retry {
                        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { [weak self] in
                            if self?.model != nil { self?.refresh(retry: false) }
                        }
                    } else {
                        model.offline = true
                    }
                }
            }
        }
    }

    // No path at all says "no connection" at once; on reconnect the card refreshes itself in place and the
    // saved-card line simply goes.
    private func watchForNetwork() {
        let m = NWPathMonitor()
        m.pathUpdateHandler = { [weak self] path in
            DispatchQueue.main.async {
                guard let self = self, let model = self.model else { return }
                let ok = path.status == .satisfied
                defer { self.lastPathSatisfied = ok }
                if !ok { model.offline = true; return }
                if self.lastPathSatisfied == false { self.refresh(retry: true) }
            }
        }
        m.start(queue: DispatchQueue(label: "gs.boldthin.loyalty.card.path"))
        monitor = m
    }

    /// Fetches the card and keeps the device snapshot current (background, no UI); a no-op when signed out.
    /// Balfour's web app writes no snapshot, so this is what keeps the saved card fresh between card views.
    public static func prewarm(brand: CardBrand) {
        let store = CardStore(brand: brand)
        guard let token = store.sessionToken() else { return }
        CardClient(brand: brand).fetch(sessionToken: token) { result in
            if case .ok(let card) = result { DispatchQueue.main.async { store.save(card) } }
        }
    }

    private func addToAppleWallet() {
        guard let token = store.sessionToken(), let url = client.appleWalletURL(sessionToken: token) else { return }
        UINotificationFeedbackGenerator().notificationOccurred(.success)
        UIApplication.shared.open(url)
    }
}
