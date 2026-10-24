// CARD-PAGE-WEBVIEW-2026-10-24 (healer:4b3d7c53): fills the bundled copy of the React card (build-card-page.mjs) with the
// member on this device, exactly as the page's React code would render it, and hands its controls to the app.
// Data: window.__CARD_FIXTURE (the build's proof) or the app's LoyaltyCardPage.card() JSON
// {scan, name, tier, offline, updatedAt}; the app calls window.renderCard(json) again when the card refreshes.
// ES5: runs on every WebView the app supports.
(function () {
  var C = window.CARD_PAGE_CONFIG || {}
  var app = window.LoyaltyCardPage
  function slot(n) { return document.querySelector('[data-card="' + n + '"]') }
  // The page's own card size rule (Randalls src/app/qr/page.tsx fitCard); null = the page's QR is a fixed size.
  function fit() { return C.fit === 'rr' ? Math.min(325, window.innerWidth - 48, window.innerHeight * 0.4) : null }
  function hhmm(ms) { return new Date(ms).toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' }) }
  var current = null
  var copiedTimer = 0

  function render(card) {
    if (typeof card === 'string') card = JSON.parse(card)
    if (!card) return
    current = card
    var size = fit()
    var box = slot('box')
    if (size != null && box) box.style.width = size + 'px'
    var qr = slot('qr')
    var qrSize = size != null ? size - C.qrInset : Number(qr.getAttribute('width'))
    var t = document.createElement('div')
    t.innerHTML = window.cardQrSvg(card.scan, qrSize, C.qrFg)
    var svg = t.firstChild
    svg.setAttribute('data-card', 'qr')
    qr.parentNode.replaceChild(svg, qr)
    var number = slot('number')
    if (number && !copiedTimer) number.textContent = card.scan
    var name = slot('name')
    if (name) { name.textContent = card.name || ''; name.style.display = card.name ? '' : 'none'; if (card.name) name.removeAttribute('style') }
    var tier = slot('tier')
    if (tier) tier.textContent = (card.tier || '').toUpperCase() || 'MEMBER'
    var hold = slot('hold')
    var line = slot('offline')
    // Only where the page has the line (config offlineClass): Balfour's /my-id has none.
    if (card.offline && hold && C.offlineClass) {
      if (!line) {
        line = document.createElement('p')
        line.className = C.offlineClass
        line.setAttribute('role', 'status')
        line.setAttribute('data-card', 'offline')
        hold.parentNode.insertBefore(line, hold.nextSibling)
      }
      line.textContent = 'NO CONNECTION · SAVED CARD' + (card.updatedAt ? ' · UPDATED ' + hhmm(card.updatedAt) : '')
    } else if (line) {
      line.parentNode.removeChild(line)
    }
  }
  window.renderCard = render

  function on(n, fn) {
    var el = slot(n)
    if (el) el.addEventListener('click', function (e) { e.preventDefault(); fn(el) })
  }
  on('close', function () { if (app) app.close() })
  on('wallet', function () { if (app) app.wallet() })
  // CopyText: copies the scan value, shows the confirmation for 1.4 s.
  on('number', function (el) {
    if (!current || !app) return
    app.copy(current.scan)
    clearTimeout(copiedTimer)
    el.textContent = C.copied
    el.title = C.copiedTitle
    copiedTimer = setTimeout(function () { copiedTimer = 0; el.textContent = current.scan; el.title = 'Click to copy' }, 1400)
  })
  window.addEventListener('resize', function () { if (current) render(current) })

  render(window.__CARD_FIXTURE || (app && app.card()))
})()
