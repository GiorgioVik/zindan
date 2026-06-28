# Zindan

Zindan is a fork of [Shelter](https://cgit.typeblog.net/Shelter/about/) — a Free and Open-Source (FOSS) Android app that uses the **Work Profile** feature to run apps in an isolated space. You can clone apps into the work profile, freeze them when not in use, and batch-freeze the auto-freeze list from the toolbar or launcher shortcuts.

**Current release:** **1.5.4** (versionCode **243**) — [latest release](https://github.com/GiorgioVik/zindan/releases/latest) · [RELEASING.md](RELEASING.md) (how release notes are built).

**Default branch on GitHub:** `main` (release line 1.5.4). The old branch name `v1.5.2` is a historical label for the same fork line, not the app version.

Shelter remains the upstream base; Zindan adds branding, Russian UX polish, Anti Spy VPN handling, top-of-screen toasts, and Samsung-focused field testing.

## Features

- Install or clone apps into an isolated work profile
- Freeze / unfreeze individual apps
- **Auto-freeze** list — apps frozen together on screen lock, batch freeze, Anti Spy VPN events, and shortcuts
- **Batch freeze / unfreeze** from the toolbar, settings, or home-screen shortcuts
- Frozen apps sorted to the top of the work profile list
- Anti Spy: detect third-party VPN, prompt for batch freeze, dummy-VPN displacement on app launch

User guide (Russian): [USER_GUIDE.md](USER_GUIDE.md) · [PDF](USER_GUIDE.pdf)

Known issues and test notes: [PROBLEMS.md](PROBLEMS.md)

## Requirements

- Android 7.0+ (API 24+)
- A device with a working Work Profile implementation (AOSP-like ROMs work best; heavily vendor-modified firmware may break profile features)

Tested on:

- Samsung Galaxy S24 Ultra (SM-S928B/DS)
- Samsung Galaxy Tab S9 FE+ (SM-X616B)

## Clone and build

The repository lives on disk at **`D:\Zindan5`** for local development. GitHub is used as the remote only — you do not need to move the project off `D:`.

```powershell
git clone --recurse-submodules https://github.com/GiorgioVik/zindan.git D:\Zindan5
cd D:\Zindan5
```

If you already cloned without submodules:

```powershell
git submodule update --init --recursive
```

Build a debug APK (Android Studio JBR or JDK 17+):

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/` and copied to the repo root as `Zindan-{version}-({code})-debug.apk`.  
`version.properties` is auto-incremented on each `assemble*` task — see [BASELINE_1.5.3.md](BASELINE_1.5.3.md) to reproduce an exact build number.

### Launcher icons (optional)

To regenerate mipmaps from a source PNG:

1. Place `assets/zindan_icon_source.png` in the repo (or set `$env:ZINDAN_ICON_SOURCE`).
2. Run `tools\generate_zindan_launcher_icons.ps1`.

## Publish to GitHub (maintainers)

First-time setup while keeping the working tree on `D:\Zindan5`:

1. Create an empty repository on GitHub (e.g. `GiorgioVik/zindan`).
2. Point `origin` at GitHub (already configured in this checkout):

   ```powershell
   cd D:\Zindan5
   git remote set-url origin https://github.com/GiorgioVik/zindan.git
   ```

3. Commit and push:

   ```powershell
   git push -u origin main
   git push origin --tags
   ```

Optional local backup remote (same machine, different folder):

```powershell
git remote add backup D:\Zindan4
git push backup v1.5.2
```

## Uninstalling

Delete the work profile first in **Settings → Accounts**, then uninstall Zindan normally. Removing only the launcher icon does not remove the work profile or cloned apps.

## License

GPL-3.0-or-later — see [LICENSE](LICENSE). Zindan is derived from Shelter; respect upstream licensing when redistributing.

## Upstream

- [Shelter](https://cgit.typeblog.net/Shelter/about/) by PeterCxy
- [SetupWizardLibrary](https://gitea.angry.im/PeterCxy/SetupWizardLibrary) (git submodule)
