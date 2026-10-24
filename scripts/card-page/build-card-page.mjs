#!/usr/bin/env node
// CARD-PAGE-WEBVIEW-2026-10-24 (healer:4b3d7c53, Kyle's answer to gate 339937c7): on Android the member card is the React
// card itself, drawn by the WebView from a bundled offline copy of the live page, so it is identical to the site by
// construction (a native copy could not reproduce Chrome's text rasterization). This script makes that copy:
//
//   build-card-page.mjs [--config scripts/card-page/config.json] [--out android/app/src/main/assets/card/card.html]
//
// It renders the live page as the app's WebView paints it (Android user agent, html.android-native, the Capacitor bridge
// present, a synthetic member answered from the render gate's HAR), waits for it to settle, then saves the painted DOM as
// one self-contained HTML file: scripts removed, every stylesheet inlined in its cascade position, fonts and images
// inlined as data: URIs, and the member-specific nodes marked (data-card="...") for a small runtime script that fills them
// from the device's card snapshot (card-runtime.js). The QR is redrawn by the site's own qrcode.react (qr-entry.mjs); the
// build refuses unless that markup equals the live page's for the fixture member, and unless the runtime, filled with the
// fixture member, leaves the page byte-identical to the capture. The render gate then compares the bundled copy, painted
// by the phone's WebView, with the live page on every release, so a site change shows up as a gate failure and a rebuild.
import { createRequire } from 'node:module'
import { homedir } from 'node:os'
import { execFileSync } from 'node:child_process'
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const APP = resolve(HERE, '../..')
const args = Object.fromEntries(process.argv.slice(2).reduce((acc, a, i, all) => {
  if (a.startsWith('--')) acc.push([a.slice(2), all[i + 1] && !all[i + 1].startsWith('--') ? all[i + 1] : true])
  return acc
}, []))
const cfg = JSON.parse(readFileSync(resolve(APP, args.config || 'scripts/card-page/config.json'), 'utf8'))
const out = resolve(APP, args.out || cfg.out)
const site = args.site || cfg.site

// Tooling from the web app's checkout (the app repo carries none): Playwright, and the site's own React + qrcode.react.
const web = `${homedir()}/${cfg.webRepo}`
const { chromium } = createRequire(`${web}/package.json`)('playwright')
const esbuild = process.env.CARD_PAGE_ESBUILD || `${homedir()}/boldthings/node_modules/.bin/esbuild`
const qrJs = execFileSync(esbuild, [join(HERE, cfg.qrEntry || 'qr-entry.mjs'), '--bundle', '--minify', '--format=iife',
  '--define:process.env.NODE_ENV="production"', '--log-level=warning'], { env: { ...process.env, NODE_PATH: `${web}/node_modules` } }).toString()
const runtimeJs = readFileSync(join(HERE, 'card-runtime.js'), 'utf8')
const fixture = JSON.parse(readFileSync(resolve(APP, cfg.fixtureCard), 'utf8'))

const browser = await chromium.launch({ args: ['--font-render-hinting=none'] })
const ctx = await browser.newContext({
  userAgent: 'Mozilla/5.0 (Linux; Android 16; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36 wv',
  viewport: { width: 412, height: 915 }, deviceScaleFactor: 3, isMobile: true, hasTouch: true,
  reducedMotion: 'reduce', timezoneId: 'Europe/London', locale: 'en-GB',
})
await ctx.routeFromHAR(resolve(APP, cfg.har), { url: /^https:\/\/api\.boldthin\.gs\/|^https:\/\/[^/]+\/api\//, notFound: 'abort' })
await ctx.addInitScript(() => {
  const tag = () => document.documentElement && !document.documentElement.classList.contains('android-native') && document.documentElement.classList.add('android-native')
  tag()
  new MutationObserver(tag).observe(document, { childList: true, subtree: true, attributes: true, attributeFilter: ['class'] })
  window.androidBridge = { postMessage() {} }
})
await ctx.addInitScript(([k, more]) => {
  localStorage.setItem(k, 'render-gate-fixture-token')
  for (const [mk, mv] of Object.entries(more)) localStorage.setItem(mk, mv)
}, [cfg.tokenKey, cfg.localStorage || {}])
const page = await ctx.newPage()
// The exact bytes the page painted with: an image optimiser (Next /_next/image) picks the format from the browser's Accept
// header, so a separate fetch can get another format (Balfour's transparent webp pattern came back as an opaque JPEG).
const painted = new Map()
page.on('response', res => { const u = res.url(); if (!u.startsWith('data:') && res.ok()) painted.set(u, res.body().then(b => ({ b, type: res.headers()['content-type'] })).catch(() => null)) })
await page.goto(site + cfg.path, { waitUntil: 'networkidle' })
if (cfg.click) { await page.evaluate(sel => document.querySelector(sel).click(), cfg.click); await page.waitForLoadState('networkidle') }
await page.evaluate(() => document.fonts.ready)
await page.waitForTimeout(2500)

// Mark the member-specific nodes (cfg.slots: name -> selector), then serialize the painted DOM with its CSS and assets.
const missing = await page.evaluate(slots => Object.entries(slots).filter(([name, sel]) => {
  const el = document.querySelector(sel)
  if (el) el.setAttribute('data-card', name)
  return !el
}).map(([name]) => name), cfg.slots)
if (missing.length) fail(`no element on ${cfg.path} for slot(s) ${missing.join(', ')} (config.json selectors no longer match the page)`)
const liveQr = await page.evaluate(() => document.querySelector('[data-card="qr"]').outerHTML)
const snap = await page.evaluate(async () => {
  const sheets = []
  for (const node of document.querySelectorAll('link[rel="stylesheet"], style')) {
    if (node.tagName === 'LINK') sheets.push({ href: node.href, text: await (await fetch(node.href)).text() })
    else sheets.push({ href: location.href, text: node.textContent })
  }
  const imgs = [...document.querySelectorAll('img')].map(i => i.currentSrc || i.src)
  // Inline-style images (the pattern overlay background) are inlined like <img> sources.
  for (const el of document.querySelectorAll('[style*="url("]')) for (const m of el.getAttribute('style').matchAll(/url\((['"]?)([^'")]+)\1\)/g)) imgs.push(new URL(m[2], location.href).href)
  // Font families any painted element names (its whole stack, fallbacks included): only their @font-face rules are kept.
  const families = new Set()
  for (const el of [document.body, ...document.body.querySelectorAll('*')]) for (const f of getComputedStyle(el).fontFamily.split(',')) families.add(f.trim().replace(/^['"]|['"]$/g, ''))
  return { sheets, imgs, base: location.href, families: [...families] }
})

const cache = new Map()
async function dataUri(url) {
  if (cache.has(url)) return cache.get(url)
  let got = painted.has(url) ? await painted.get(url) : null
  if (!got) {
    const res = await page.request.get(url, { headers: { Accept: 'image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8' } })
    if (!res.ok()) fail(`asset ${url}: HTTP ${res.status()}`)
    got = { b: await res.body(), type: res.headers()['content-type'] }
  }
  const type = (got.type || 'application/octet-stream').split(';')[0]
  const uri = `data:${type};base64,${got.b.toString('base64')}`
  cache.set(url, uri)
  return uri
}
async function inlineCss(text, base) {
  text = text.replace(/@font-face\s*\{[^}]*\}/g, rule => {
    const fam = /font-family:\s*([^;}]+)/.exec(rule)?.[1].trim().replace(/^['"]|['"]$/g, '')
    return fam && snap.families.includes(fam) ? rule : ''
  })
  const urls = [...new Set([...text.matchAll(/url\(\s*(['"]?)([^'")]+)\1\s*\)/g)].map(m => m[2]).filter(u => !u.startsWith('data:') && !u.startsWith('#') && !u.startsWith('%23')))]
  for (const u of urls) {
    const abs = new URL(u, base).href
    const uri = await dataUri(abs)
    text = text.split(u).join(uri)
  }
  return text
}
const css = []
for (const s of snap.sheets) css.push(await inlineCss(s.text, s.href))
const imgUris = {}
for (const src of snap.imgs) if (src && !src.startsWith('data:')) imgUris[src] = await dataUri(src)

const html = await page.evaluate(({ css, imgUris, drop, keepOnly }) => {
  const root = document.documentElement.cloneNode(true)
  // An overlay card (Balfour's My ID over the dashboard): only the opaque, full-screen overlay is kept, so the copy carries
  // no page data that never shows.
  if (keepOnly) for (const n of [...root.querySelector('body').children]) if (!n.matches(keepOnly)) n.remove()
  let i = 0
  for (const node of root.querySelectorAll('link[rel="stylesheet"], style')) {
    const s = document.createElement('style')
    s.textContent = css[i++]
    node.replaceWith(s)
  }
  for (const n of root.querySelectorAll(['script', 'noscript', 'iframe', 'template', 'link', 'next-route-announcer', ...drop].join(','))) n.remove()
  for (const img of root.querySelectorAll('img')) {
    const live = img.getAttribute('src') && new URL(img.getAttribute('src'), location.href).href
    const uri = imgUris[live] || Object.entries(imgUris).find(([k]) => k === img.currentSrc)?.[1]
    if (uri) img.setAttribute('src', uri)
    img.removeAttribute('srcset'); img.removeAttribute('sizes'); img.removeAttribute('loading')
  }
  for (const el of root.querySelectorAll('[style*="url("]')) {
    el.setAttribute('style', el.getAttribute('style').replace(/url\((['"]?)([^'")]+)\1\)/g, (m, q, u) => imgUris[new URL(u, location.href).href] ? `url("${imgUris[new URL(u, location.href).href]}")` : m))
  }
  return '<!DOCTYPE html>' + root.outerHTML
}, { css, imgUris, drop: cfg.drop || [], keepOnly: cfg.keepOnly || null })
const remaining = [...html.matchAll(/(?:src|href)="(https?:[^"]+)"|url\((['"]?)(https?:[^'")]+)\2\)/g)].map(m => m[1] || m[3])
if (remaining.length) {
  // style background images not seen as <img>: inline them too.
  console.warn(`card-page: inlining ${remaining.length} further url(s)`)
}
let doc = html
for (const u of new Set(remaining)) doc = doc.split(u).join(await dataUri(u))

// The runtime fills the slots; the QR helper is the site's component. Both run before first paint (end of <body>).
const safe = js => js.replace(/<\/script/gi, '<\\/script')
const cardPage = doc.replace('</body>', `<script>${safe(qrJs)}</script><script>window.CARD_PAGE_CONFIG=${JSON.stringify(cfg.runtime)};${safe(runtimeJs)}</script></body>`)

// Proof 1: the bundled QR helper draws the page's markup for the fixture member.
const fixtureQr = await page.evaluate(([js, value, fg, size]) => { (0, eval)(js); return window.cardQrSvg(value, size, fg) },
  [qrJs, fixture.scan, cfg.runtime.qrFg, Number((await page.evaluate(() => document.querySelector('[data-card="qr"]').getAttribute('width'))))])
if (strip(fixtureQr) !== strip(liveQr).replace(' data-card="qr"', '')) fail(`the bundled QR markup differs from the page's for the fixture member\n page: ${liveQr.slice(0, 300)}\n ours: ${fixtureQr.slice(0, 300)}`)

// Proof 2: the bundled page, filled by the runtime with the fixture member, is the captured DOM (nothing the runtime
// writes differs from what React wrote). Compared as the painted body markup, slots included.
const check = await ctx.newPage()
await check.route('**/*', r => r.request().url().startsWith('data:') ? r.continue() : r.abort())
await check.setContent(cardPage.replace('<script>window.CARD_PAGE_CONFIG', `<script>window.__CARD_FIXTURE=${JSON.stringify(fixture)}</script><script>window.CARD_PAGE_CONFIG`))
const filled = await check.evaluate(() => { const b = document.body.cloneNode(true); b.querySelectorAll('script').forEach(s => s.remove()); return b.innerHTML })
const captured = await page.evaluate(keepOnly => {
  const b = document.body.cloneNode(true)
  if (keepOnly) for (const n of [...b.children]) if (!n.matches(keepOnly)) n.remove()
  b.querySelectorAll('script, noscript, iframe, template, link, next-route-announcer').forEach(s => s.remove())
  return b.innerHTML
}, cfg.keepOnly || null)
// srcset / sizes / loading are dropped on purpose (the image is the one the page painted, inlined as src).
const norm = s => strip(s).replace(/ (srcset|sizes|loading)="[^"]*"/g, '').replace(/ src="[^"]*"/g, ' src=""').replace(/url\([^)]*\)/g, 'url()').replace(/ data-card="[^"]*"/g, '')
if (norm(filled) !== norm(captured)) {
  const a = norm(filled), b = norm(captured)
  let k = 0; while (k < a.length && a[k] === b[k]) k++
  fail(`the filled bundled card differs from the captured page at char ${k}:\n ours: ${a.slice(Math.max(0, k - 120), k + 200)}\n page: ${b.slice(Math.max(0, k - 120), k + 200)}`)
}
await browser.close()

mkdirSync(dirname(out), { recursive: true })
writeFileSync(out, cardPage)
console.log(`card page ${site}${cfg.path} -> ${out} (${(cardPage.length / 1024).toFixed(0)} KB; QR markup and filled DOM equal the live page)`)

function strip(s) { return s.replace(/\s+/g, ' ').trim() }
function fail(msg) { console.error(`build-card-page: ${msg}`); process.exit(1) }
