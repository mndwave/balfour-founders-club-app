// The card page's QR, drawn by the site's own component (qrcode.react, react-dom/server from the web app's node_modules),
// with the page's props (config.json runtime.qrFg is the page's fgColor), so the bundled card's QR is the page's markup by construction. build-card-page.mjs
// bundles this and checks its output equals the live page's <svg> for the fixture member.
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server.browser'
import { QRCodeSVG } from 'qrcode.react'

window.cardQrSvg = (value, size, fg) => renderToStaticMarkup(createElement(QRCodeSVG, {
  value, size, bgColor: '#ffffff', fgColor: fg, level: 'M', marginSize: 0,
}))
