// Writes an F-Droid index-v2.json (one package, one version) next to the APK, so Obtainium's "F-Droid third-party repo"
// source reads real versionName/versionCode from metadata instead of guessing from a link.
// usage: node obtainium-repo-index.mjs <out.json> <pkg> <name> <versionName> <versionCode> <apkFile> <apkFileName> <baseUrl>
import { createHash } from "node:crypto";
import { readFileSync, writeFileSync, statSync } from "node:fs";
const [out, pkg, name, versionName, versionCode, apkFile, apkName, baseUrl] = process.argv.slice(2);
const sha256 = createHash("sha256").update(readFileSync(apkFile)).digest("hex");
const now = Date.now(), loc = (s) => ({ "en-GB": s });
const index = {
  repo: { name: loc(name), description: loc(`${name} builds from media.seq1.net`), address: baseUrl, icon: {}, timestamp: now },
  packages: { [pkg]: {
    metadata: { name: loc(name), summary: loc(name), authorName: "SEQ1", added: now, lastUpdated: now },
    versions: { [sha256]: { added: now, file: { name: "/" + apkName, sha256, size: statSync(apkFile).size },
      manifest: { versionName, versionCode: Number(versionCode) } } },
  } },
};
writeFileSync(out, JSON.stringify(index, null, 1));
console.log(`index-v2.json: ${pkg} ${versionName} (${versionCode}) ${apkName}`);
