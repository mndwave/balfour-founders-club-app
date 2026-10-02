// Builds the Obtainium one-tap import link. mode "code": compare versionCode (-<digits>.apk); mode "name": compare dated versionName (-YYYY.MM.DD.HHMM.apk).
const [pkg, name, url, mode = "code"] = process.argv.slice(2);
const re = mode === "name" ? "-(\\d{4}\\.\\d{2}\\.\\d{2}\\.\\d{4})\\.apk" : "-(\\d{6,})\\.apk";
const add = { versionExtractionRegEx: re, matchGroupToUse: "1", useVersionCodeAsOSVersion: mode !== "name", filterApkUrlsByArch: false, customLinkFilterRegex: "\\.apk$", appName: name };
const app = { id: pkg, url, author: "media.seq1.net", name, preferredApkIndex: 0, additionalSettings: JSON.stringify(add) };
console.log("obtainium://app/" + encodeURIComponent(JSON.stringify(app)));
