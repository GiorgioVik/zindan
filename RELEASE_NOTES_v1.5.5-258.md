## Zindan 1.5.5 (258)

**APK:** `Zindan-1.5.5-(258)-debug.apk` (debug-signed)

### Highlights

- Fixes VPN auto-freeze when Zindan is in the background on Samsung devices, including the S24 Ultra Wildberries case.
- Keeps retrying in the work-profile watcher while VPN is active until every auto-freeze app is actually hidden.
- Hardens auto-freeze list storage across the normal app process and the `:vpnwatch` process.
- Cleans stale/missing work-profile packages from the auto-freeze list after batch freeze.
- Removes temporary VPN diagnostic notifications used during field testing.

### Install

Download the APK below. Update over an existing install in **both** profiles (personal first, then work) — no need to recreate the work profile when `versionCode` increases.

See [CHANGELOG.md](https://github.com/GiorgioVik/zindan/blob/main/CHANGELOG.md) and [USER_GUIDE.md](https://github.com/GiorgioVik/zindan/blob/main/USER_GUIDE.md).
