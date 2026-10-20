import UIKit
import WebKit
import Capacitor
import LoyaltyCard

// BALFOUR-OFFLINE-LOAD-TIMEOUT-2026-10-19 (healer:52c5943e, port of RR-OFFLINE-LOAD-TIMEOUT-2026-10-19): errorPath only fires on a load ERROR. With a
// weak venue signal the live site can stall with no error and the member sees a blank page. If the
// live page is still barely loading after this bound, show the saved card.
// LOYALTY-NATIVE-CARD-IOS-2026-10-20 (healer:596fd32f): the saved card is now the native SwiftUI card
// (ios/LoyaltyCard, no Capacitor dependency), shown over the WebView whenever it lands on the error page
// or stalls; www/offline.html stays beneath it for a member with no saved card yet. The web app writes
// no snapshot for Balfour, so the app refreshes it at every start (as Android MainActivity does).
class MainViewController: CAPBridgeViewController {
    private let liveLoadTimeout: TimeInterval = 6
    private var timer: Timer?
    private var urlObservation: NSKeyValueObservation?
    private lazy var card = MemberCardOverlay(host: self, brand: .balfour) { [weak self] in self?.reloadLiveIfOnErrorPage() }

    override func viewDidLoad() {
        super.viewDidLoad()
        MemberCardOverlay.prewarm(brand: .balfour)
        // Capacitor loads errorPath on a failed main-frame load; the card goes up the moment it does.
        urlObservation = webView?.observe(\.url, options: [.new]) { [weak self] _, _ in
            DispatchQueue.main.async { if self?.isOnErrorPage == true { self?.card.show() } }
        }
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: liveLoadTimeout, repeats: false) { [weak self] _ in
            self?.fallBackIfStalled()
        }
    }

    private var isOnErrorPage: Bool {
        guard let url = webView?.url, let errorURL = bridge?.config.errorPathURL else { return false }
        return url.host == errorURL.host && url.scheme == errorURL.scheme
    }

    private func fallBackIfStalled() {
        guard let webView = webView, let errorURL = bridge?.config.errorPathURL else { return }
        // Already on the saved-card page, or the live page has started to arrive: leave it alone.
        guard !isOnErrorPage, webView.isLoading, webView.estimatedProgress < 0.3 else { return }
        if card.show() { return }
        webView.load(URLRequest(url: errorURL))
    }

    private func reloadLiveIfOnErrorPage() {
        guard let webView = webView, let live = bridge?.config.serverURL else { return }
        if isOnErrorPage || webView.isLoading { webView.load(URLRequest(url: live)) }
    }
}
