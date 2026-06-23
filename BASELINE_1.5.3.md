# Baseline — Zindan 1.5.3 (versionCode 208)

**Date:** 2026-06-17  
**Git commit:** `784a616`  
**Branch:** `v1.5.2` (historical name; release is 1.5.3)  
**APK:** `Zindan-1.5.3-(208)-debug.apk`

Use this commit (or tag `v1.5.3-baseline` after it is created) to return to the field-tested 1.5.3 release.

---

## Changes since 1.5.2 (177)

| Area | Change |
|------|--------|
| VPN batch freeze | Reconcile `DevicePolicyManager.isApplicationHidden` with `PackageManager` when a foreground app leaves DPM/PM out of sync |
| VPN auto-freeze | Freeze auto-freeze list when external VPN connects (no tunnel displacement) |
| Work profile UI | Sort apps: frozen → auto-freeze enabled → rest; A–Z within each tier |
| List refresh | Fix cross-profile refresh loop after batch freeze / VPN events |

---

## Build

From the repository root (local path `D:\Zindan5` or any clone):

```powershell
git submodule update --init --recursive
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
# Optional: keep Gradle cache on D:
$env:GRADLE_USER_HOME = "D:\Zindan5\.gradle-user-home"
.\gradlew.bat :app:assembleDebug
```

To reproduce **exactly** build 208, set `VERSION_CODE=207` in `version.properties` before `assembleDebug` (Gradle increments it to 208).

APK output: `app/build/outputs/apk/debug/` and a copy in the repo root.

---

## Rollback

```powershell
git checkout 784a616
# or, after tagging:
git checkout v1.5.3-baseline
```

Previous baseline: [BASELINE_1.5.2.md](BASELINE_1.5.2.md) (`v1.5.2-baseline`, build 177).

---

## Field test update — build 235 (2026-06-23)

**Git commit:** `9c707c1`  
**APK:** `Zindan-1.5.3-(235)-debug.apk`  
**Devices:** Samsung Galaxy S24 Ultra, Tab S9 FE+

| Area | Result |
|------|--------|
| VPN-up auto-freeze (scenario 2) | ✅ confirmed (build 231+) |
| RuStore install → auto-freeze snowflake | ✅ confirmed (build 231) |
| RuStore install → work list refresh without tab switch | ✅ confirmed (build 235) |
| Menu APK install blocked while VPN active | ✅ confirmed (build 229) |
| In-place APK update (no work profile reinstall) | ✅ confirmed (build 235) |

See [PROBLEMS.md](PROBLEMS.md) for the full test matrix and [CHANGELOG.md](CHANGELOG.md) for release notes since build 211.
