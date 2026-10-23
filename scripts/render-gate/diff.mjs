#!/usr/bin/env node
// Full-colour, per-pixel diff of a native render against its React reference (same size and density).
// A pixel differs when any of R, G, B moves by more than --channel (default 16 of 255). The element fails when
// more than --max-ratio of its pixels differ (default 0.002 = 0.2%) or the two images are not the same size.
// There is no baseline file and no allowed-gap list: the only reference is the React render.
// On failure a diff PNG is written: reference greyed out, differing pixels in red (native darker) / blue (native lighter).
// Usage: diff.mjs --ref A.png --native B.png --diff OUT.png [--channel 16] [--max-ratio 0.002] [--crop-top PX] [--crop-bottom PX]
import { createRequire } from 'node:module'
import { homedir } from 'node:os'
import { readFileSync, writeFileSync } from 'node:fs'

const args = Object.fromEntries(process.argv.slice(2).reduce((acc, a, i, all) => {
  if (a.startsWith('--')) acc.push([a.slice(2), all[i + 1]])
  return acc
}, []))
const pwRoot = process.env.RENDER_GATE_PLAYWRIGHT_FROM || `${homedir()}/randalls-rewards`
const { PNG } = createRequire(`${pwRoot}/package.json`)('playwright-core/lib/utilsBundle')

const ref = PNG.sync.read(readFileSync(args.ref))
const nat = PNG.sync.read(readFileSync(args.native))
const channel = Number(args.channel ?? 16), maxRatio = Number(args['max-ratio'] ?? 0.002)
const top = Number(args['crop-top'] ?? 0), bottom = Number(args['crop-bottom'] ?? 0)
const label = args.label || args.native

if (ref.width !== nat.width || ref.height !== nat.height) {
  console.log(`FAIL ${label}: size ${nat.width}x${nat.height} native vs ${ref.width}x${ref.height} reference`)
  process.exit(1)
}
const { width: w, height: h } = ref
const out = new PNG({ width: w, height: h })
let differ = 0, worst = 0
let minX = w, minY = h, maxX = -1, maxY = -1
for (let y = top; y < h - bottom; y++) {
  for (let x = 0; x < w; x++) {
    const i = (y * w + x) * 4
    const dr = nat.data[i] - ref.data[i], dg = nat.data[i + 1] - ref.data[i + 1], db = nat.data[i + 2] - ref.data[i + 2]
    const d = Math.max(Math.abs(dr), Math.abs(dg), Math.abs(db))
    const grey = Math.round(0.3 * ref.data[i] + 0.59 * ref.data[i + 1] + 0.11 * ref.data[i + 2])
    const faint = 255 - Math.round((255 - grey) * 0.25)
    if (d > channel) {
      differ++
      if (d > worst) worst = d
      if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y
      const darker = dr + dg + db < 0
      out.data.set(darker ? [220, 30, 30, 255] : [30, 80, 230, 255], i)
    } else {
      out.data.set([faint, faint, faint, 255], i)
    }
  }
}
const total = w * (h - top - bottom)
const ratio = differ / total
const box = differ ? ` region ${minX},${minY}-${maxX},${maxY}` : ''
const verdict = ratio <= maxRatio ? 'PASS' : 'FAIL'
console.log(`${verdict} ${label}: ${differ} of ${total} px differ (${(ratio * 100).toFixed(3)}%, limit ${(maxRatio * 100).toFixed(3)}%), worst channel delta ${worst}${box}`)
if (args.diff && (verdict === 'FAIL' || args['always-diff'])) writeFileSync(args.diff, PNG.sync.write(out))
process.exit(verdict === 'PASS' ? 0 : 1)
