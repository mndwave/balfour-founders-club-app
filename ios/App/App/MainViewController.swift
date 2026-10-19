import UIKit
import WebKit
import Capacitor

// BALFOUR-OFFLINE-LOAD-TIMEOUT-2026-10-19 (healer:52c5943e, port of RR-OFFLINE-LOAD-TIMEOUT-2026-10-19): errorPath only fires on a load ERROR. With a
// weak venue signal the live site can stall with no error and the member sees a blank page. If the
// live page is still barely loading after this bound, show the saved card (www/offline.html).
class MainViewController: CAPBridgeViewController {
    private let liveLoadTimeout: TimeInterval = 6
    private var timer: Timer?

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: liveLoadTimeout, repeats: false) { [weak self] _ in
            self?.fallBackIfStalled()
        }
    }

    private func fallBackIfStalled() {
        guard let webView = webView, let errorURL = bridge?.config.errorPathURL else { return }
        // Already on the saved-card page, or the live page has started to arrive: leave it alone.
        let onLocal = webView.url?.host == errorURL.host
        guard !onLocal, webView.isLoading, webView.estimatedProgress < 0.3 else { return }
        webView.load(URLRequest(url: errorURL))
    }
}
