// Builds the Obtainium one-tap import link: compare by versionCode (matches the installed app), not a filename string.
const [pkg, name, url] = process.argv.slice(2);
const add = { versionExtractionRegEx: "-(\\d{6,})\\.apk", matchGroupToUse: "1", useVersionCodeAsOSVersion: true, filterApkUrlsByArch: false, customLinkFilterRegex: "\\.apk$", appName: name };
const app = { id: pkg, url, author: "media.seq1.net", name, preferredApkIndex: 0, additionalSettings: JSON.stringify(add) };
console.log("obtainium://app/" + encodeURIComponent(JSON.stringify(app)));
