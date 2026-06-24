## Zindan 1.5.4 (242)

**APK:** `Zindan-1.5.4-(242)-debug.apk` (debug-signed)

### Highlights

- Unified work-profile freeze engine with PM verify and reconcile across all hide paths
- VPN batch-freeze coordinator: single in-flight job, dedupe, bounded retries
- Work profile list auto-refresh after VPN batch-freeze (hidden-state polling)
- Install APK blocked while VPN is active — tunnel is not displaced
- Frozen app rows use dark olive background (distinct from green navigation bar)
- Memory hygiene: Cursor cleanup, byte-bounded icon cache, safer bitmap decode

### Install

Download the APK below. Update over an existing install in **both** profiles (personal first, then work) — no need to recreate the work profile when `versionCode` increases.

See [CHANGELOG.md](https://github.com/GiorgioVik/zindan/blob/main/CHANGELOG.md) and [USER_GUIDE.md](https://github.com/GiorgioVik/zindan/blob/main/USER_GUIDE.md).
