#!/usr/bin/env node
// Renders the React reference for a native element: the live site page, at the device's CSS size and density,
// with every API call answered from a recorded HAR (deterministic member data, no network to the API).
//   --record --token T   capture the HAR from a real (synthetic test) member session instead of replaying it
// Usage: reference.mjs --site URL --path /qr --har F --out PNG [--engine chromium|webkit] [--w 412 --h 915 --dpr 3] [--click SELECTOR]
//        [--token T] [--token-key randalls_member_token] [--record] [--wait-ms 2500] [--local-storage JSON]
//        [--eval JS] [--raster SELECTOR --raster-out PNG]
import { createRequire } from 'node:module'
import { homedir } from 'node:os'
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { execSync } from 'node:child_process'

const args = Object.fromEntries(process.argv.slice(2).reduce((acc, a, i, all) => {
  if (a.startsWith('--')) acc.push([a.slice(2), all[i + 1] && !all[i + 1].startsWith('--') ? all[i + 1] : true])
  return acc
}, []))
const need = k => { if (!args[k]) { console.error(`reference.mjs: --${k} is required`); process.exit(2) } return args[k] }

// Playwright comes from the web app's checkout (the app repos carry no browser tooling of their own).
const pwRoot = process.env.RENDER_GATE_PLAYWRIGHT_FROM || `${homedir()}/randalls-rewards`
const { chromium, webkit } = createRequire(`${pwRoot}/package.json`)('playwright')

function androidFontconfig() {
  const dir = `${homedir()}/.cache/render-gate/android-fonts`
  const conf = `${dir}/fonts-v3.conf`
  if (existsSync(conf)) return conf
  const jar = execSync('ls -1 $HOME/.gradle/caches/modules-2/files-2.1/org.robolectric/nativeruntime-dist-compat/*/*/nativeruntime-dist-compat-*.jar | sort -V | tail -1', { shell: '/bin/bash' }).toString().trim()
  if (!jar) { console.error('reference.mjs: no Robolectric nativeruntime-dist-compat jar (run the Android render once)'); process.exit(2) }
  mkdirSync(dir, { recursive: true })
  execSync(`unzip -qo "${jar}" 'fonts/*' -d "${dir}"`)
  writeFileSync(conf, `<?xml version="1.0"?>
<!DOCTYPE fontconfig SYSTEM "fonts.dtd">
<fontconfig>
  <dir>${dir}/fonts</dir>
  <cachedir>${dir}/cache</cachedir>
  <!-- Android names these by build (e.g. "Droid Sans Mono100build113"); its own fonts.xml maps the generic
       families by file, so do the same here. -->
  <match target="scan"><test name="file" compare="contains"><string>/Roboto-</string></test>
    <edit name="family" mode="assign" binding="same"><string>Roboto</string></edit></match>
  <match target="scan"><test name="file" compare="contains"><string>/DroidSansMono</string></test>
    <edit name="family" mode="assign" binding="same"><string>Droid Sans Mono</string></edit></match>
  <match target="scan"><test name="file" compare="contains"><string>/NotoSerif-</string></test>
    <edit name="family" mode="assign" binding="same"><string>Noto Serif</string></edit></match>
  <alias><family>sans-serif</family><prefer><family>Roboto</family></prefer></alias>
  <alias><family>serif</family><prefer><family>Noto Serif</family></prefer></alias>
  <alias><family>monospace</family><prefer><family>Droid Sans Mono</family></prefer></alias>
  <match target="font">
    <edit name="antialias" mode="assign"><bool>true</bool></edit>
    <edit name="hinting" mode="assign"><bool>false</bool></edit>
    <edit name="hintstyle" mode="assign"><const>hintnone</const></edit>
    <edit name="rgba" mode="assign"><const>none</const></edit>
  </match>
</fontconfig>
`)
  return conf
}

const site = need('site'), path = need('path'), har = need('har'), out = need('out')
const w = Number(args.w || 412), h = Number(args.h || 915), dpr = Number(args.dpr || 3)
const engine = args.engine === 'webkit' ? webkit : chromium
const record = args.record === true
const tokenKey = args['token-key'] || 'randalls_member_token'
const token = args.token || 'render-gate-fixture-token'
if (!record && !existsSync(har)) { console.error(`reference.mjs: no HAR at ${har} (record one with --record --token)`); process.exit(2) }

// Android reference: Chromium sees only the Android system fonts (the ones Robolectric and the device draw with), so
// generic families (monospace, sans-serif, serif) resolve as on the phone and not to the box's DejaVu.
const launchEnv = args.engine === 'webkit' ? undefined : { ...process.env, FONTCONFIG_FILE: androidFontconfig() }
const browser = await engine.launch({ env: launchEnv })
// The app WebView's platform decides platform-specific UI (Apple vs Google Wallet), so the reference claims the same device.
const UA = args.engine === 'webkit'
  ? 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148'
  : 'Mozilla/5.0 (Linux; Android 16; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36 wv'
const ctx = await browser.newContext({
  userAgent: UA, viewport: { width: w, height: h }, deviceScaleFactor: dpr, isMobile: args.engine !== 'webkit', hasTouch: true,
  reducedMotion: 'reduce', timezoneId: 'Europe/London', locale: 'en-GB',
})
// Same-origin /api/* and the API host are data; everything else (HTML, JS, CSS, fonts, images) is the live site.
const dataUrl = /^https:\/\/api\.boldthin\.gs\/|^https:\/\/[^/]+\/api\//
await ctx.routeFromHAR(har, { url: dataUrl, update: record, updateContent: 'embed', notFound: 'abort' })
const extra = args['local-storage'] ? JSON.parse(args['local-storage']) : {}
// As the app's WebView paints it: inside the Capacitor shell the page's <head> boot script (first-paint-boot.ts) tags
// <html> ios-native / android-native, and every native-only layout rule hangs off that class (the page never changes
// layout from a mount effect). Safe-area insets are 0 here, as on the native render (system bars hidden / no insets).
const nativeClass = args.engine === 'webkit' ? 'ios-native' : 'android-native'
await ctx.addInitScript(cls => {
  const tag = () => document.documentElement && !document.documentElement.classList.contains(cls) && document.documentElement.classList.add(cls)
  tag()
  new MutationObserver(tag).observe(document, { childList: true, subtree: true, attributes: true, attributeFilter: ['class'] })
}, nativeClass)
// Capacitor core decides it is native from the bridge the WebView injects (androidBridge / webkit.messageHandlers.bridge),
// and some components show native-only UI from that at runtime (Balfour's wallet button). A no-op bridge: plugin calls
// simply never answer, as a slow device would, which no layout depends on.
await ctx.addInitScript(platform => {
  const bridge = { postMessage() {} }
  if (platform === 'ios') {
    try { Object.defineProperty(window, 'webkit', { value: { messageHandlers: { bridge } }, configurable: true }) } catch (e) {}
  } else {
    window.androidBridge = bridge
  }
}, args.engine === 'webkit' ? 'ios' : 'android')
await ctx.addInitScript(([k, t, more]) => {
  localStorage.setItem(k, t)
  for (const [mk, mv] of Object.entries(more)) localStorage.setItem(mk, mv)
}, [tokenKey, token, extra])
const page = await ctx.newPage()
await page.goto(site + path, { waitUntil: 'networkidle' })
// --click <selector>: reach the element the way the app does (e.g. Balfour's My ID overlay over the dashboard).
// A DOM click (the link may sit in an off-canvas drawer, as Balfour's My ID link does on a phone).
if (args.click) { await page.evaluate(sel => document.querySelector(sel).click(), args.click); await page.waitForLoadState('networkidle') }
await page.evaluate(() => document.fonts.ready)
await page.waitForTimeout(Number(args['wait-ms'] || 2500))
await page.screenshot({ path: out, animations: 'disabled', caret: 'hide' })
// --eval JS: print the JSON of an expression evaluated in the rendered page (e.g. layout positions while fitting a native rule).
if (args.eval) console.log(JSON.stringify(await page.evaluate(args.eval)))
// --raster SELECTOR --raster-out PNG: the element alone, as this engine paints it on the page (transparent around it),
// cut on the device-pixel grid it covers. A native element ships rasters (a logo the page rescales, an SVG badge) as this
// paint drawn 1:1 at that box, never resampled natively (RENDER-GATE-WEBKIT-RASTERS-2026-10-23, healer:4b3d7c53).
if (args.raster) {
  const box = await page.evaluate(sel => {
    const el = document.querySelector(sel)
    if (!el) return null
    const s = document.createElement('style')
    s.textContent = 'html, body { background: transparent !important } * { visibility: hidden !important }'
    document.head.appendChild(s)
    for (const n of [el, ...el.querySelectorAll('*')]) n.style.setProperty('visibility', 'visible', 'important')
    const r = el.getBoundingClientRect()
    return { x: r.x, y: r.y, w: r.width, h: r.height }
  }, args.raster)
  if (!box) { console.error(`reference.mjs: no element for --raster ${args.raster}`); process.exit(2) }
  const x0 = Math.floor(box.x * dpr), y0 = Math.floor(box.y * dpr)
  const x1 = Math.ceil((box.x + box.w) * dpr), y1 = Math.ceil((box.y + box.h) * dpr)
  await page.screenshot({ path: need('raster-out'), omitBackground: true, animations: 'disabled', caret: 'hide',
    clip: { x: x0 / dpr, y: y0 / dpr, width: (x1 - x0) / dpr, height: (y1 - y0) / dpr } })
  console.log(`raster ${args.raster}: css ${box.x},${box.y} ${box.w}x${box.h} -> device px ${x0},${y0} ${x1 - x0}x${y1 - y0}`)
}
await ctx.close()
await browser.close()
if (record) {
  // The recorded session token is a 15-minute synthetic one; replay uses a placeholder, so strip it from the HAR.
  writeFileSync(har, readFileSync(har, 'utf8').split(token).join('render-gate-fixture-token'))
}
console.log(`reference ${path} ${w}x${h}@${dpr} ${args.engine || 'chromium'} -> ${out}`)
