Zindan
===

Zindan is a fork of [Shelter](https://cgit.typeblog.net/Shelter/about/) — a Free and Open-Source (FOSS) app that leverages the "Work Profile" feature of Android to provide an isolated space that you can install or clone apps into.

This project (`D:\Zindan3`) is rebuilt from upstream Shelter at `D:\Zindan\shelter_orig\shelter` with Zindan branding only (stage 0). Application logic matches upstream Shelter; no Samsung patches or SUSPENDED changes yet.

Base: upstream Shelter  
Reference fork: `D:\Zindan2` (historical changes only)

Version: **1.3.10** (versionCode 140) — rollback baseline: tag `v1.3.10-baseline`, see [BASELINE_1.3.10.md](BASELINE_1.3.10.md)

Features
===

- Installing apps inside a work profile for isolation
- "Freeze" apps inside the work profile to prevent them from running or being woken up when you are not actively using them
- Installing two copies of the same app on the same device

User guide (Russian): [USER_GUIDE.md](USER_GUIDE.md) · [PDF](USER_GUIDE.pdf).

Known open issues: see [PROBLEMS.md](PROBLEMS.md).

Stage 0 checklist (baseline on Samsung devices)
===

- [x] Branding, setup wizard, locales
- [x] App action menu (tablet touch offset fixed)
- [x] Clone Play Store and user apps → work profile
- [x] Work app list loads without reboot
- [x] Manual freeze / unfreeze
- [x] Auto-freeze (like upstream Shelter)
- [x] Batch freeze / unfreeze (toolbar, settings, shortcuts)
- [x] Frozen apps listed at top of work profile
- [ ] Clone Galaxy Store → work profile — **deferred**, same as upstream Shelter ([PROBLEMS.md](PROBLEMS.md))

Test devices:

- Samsung Galaxy S24 Ultra (SM-S928B/DS)
- Samsung Galaxy Tab S9 FE+ (SM-X616B)

Building
===

```bash
cd D:\Zindan3
git submodule update --init --recursive
.\gradlew.bat assembleDebug
```

Uninstalling
===

To uninstall Zindan, delete the work profile first in Settings → Accounts, then uninstall the app normally.

Upstream Shelter documentation still applies for most behavior. See the original Shelter README for F-Droid downloads and upstream support channels.
