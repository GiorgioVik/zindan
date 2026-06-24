1.5.4 (242)
===

**APK:** `Zindan-1.5.4-(242)-debug.apk`

- Unified freeze engine: all work-profile hide paths use `WorkProfileBatchFreeze` with PM verify + reconcile (`ShelterService`, `FreezeService`, batch).
- VPN batch-freeze coordinator in work profile: single in-flight job, bounded retries, dedupe across receiver / `BatchFreezeService` / `:vpnwatch`.
- Last batch-freeze summary persisted (newly frozen count, still-visible packages).
- **Install APK:** VPN gate blocks without displacing the tunnel; re-check before install in work profile and before `PackageInstaller` commit.
- Work profile list auto-refresh after VPN batch-freeze (hidden-state polling, multi-path delivery, session-complete receiver).
- Frozen app rows use dark olive background (distinct from green navigation bar).
- `FreezeService`: fix `unlockReceiver` leak after lock/unlock cycles.
- Memory: Cursor `use {}` in File Shuttle / MediaStore; byte-bounded icon LRU cache; safer `decodeSampledBitmap`; pipe cleanup in thumbnails.

1.5.3 (235)
===

**APK:** `Zindan-1.5.3-(235)-debug.apk`

- Work profile list after RuStore (or other store) install: auto-refresh while Zindan is open — no tab switch; missing cross-profile refresh intent-filters; periodic work-app-set polling in `MainActivity`.
- VPN auto-freeze for store-installed apps (build 231): work-profile DPM path, authoritative auto-freeze list sync, session completion to stop Samsung poll loop.
- Menu **Install APK into Zindan** gated behind Anti Spy VPN clear (build 229).
- VPN batch-freeze routed through main-profile auto-freeze list; auto-freeze snowflake on work-profile store install (field-tested builds 215–231).

1.5.3 (211)
===

**APK:** `Zindan-1.5.3-(211)-debug.apk`

- Flat freeze/unfreeze toolbar and shortcut icons (no drop shadow; regenerated from sketch sources via `tools/generate_freeze_icons.ps1`).
- User guide rewritten for 1.5.3: always-on Anti Spy, automatic VPN batch freeze, current UI strings.
- Work profile list refresh after VPN batch freeze (`notifyBatchFreezeComplete` with follow-up delays).
- VPN batch freeze: reconcile DPM/PM desync on first hide failure; poll retries until all auto-freeze apps are hidden.

1.5.3 (208)
===

**Baseline tag (recommended):** `v1.5.3-baseline` · commit `784a616`  
**APK:** `Zindan-1.5.3-(208)-debug.apk`

- VPN auto-freeze: reconcile DPM vs PackageManager `hidden` desync — foreground apps no longer skip batch freeze permanently (fixes VPN Inspector and similar cases).
- Work profile list sorted by frozen state, then auto-freeze membership, then A–Z.
- Auto-freeze on VPN connect (without displacing the VPN tunnel).
- Cross-profile delivery fixes for work-profile list refresh after batch operations.

See [BASELINE_1.5.3.md](BASELINE_1.5.3.md) and [PROBLEMS.md](PROBLEMS.md) (build 208 field test).

1.3.10 (140)
===

- Anti Spy VPN prompts (freeze, permission, failure) always as on-screen dialogs over the launcher, not in the notification shade.
- **Baseline / rollback tag** `v1.3.10-baseline` after field testing; see [BASELINE_1.3.10.md](BASELINE_1.3.10.md) and [PROBLEMS.md](PROBLEMS.md) (scenario 2: VPN-up while work app in background — open gap).

1.3.9 (139)
===

- Fix VPN displacement from `:vpnwatch`: run `AntiSpyDummyVpnService` in the same process as the watcher FGS.
- Show freeze prompt only after external VPN was actually cleared; add dialog fallback when notifications are denied.
- Status notifications when displacement fails or VPN permission is missing.

1.3.8 (138)
===

- Anti Spy: on external VPN while watcher is active — brief dummy-VPN displacement, then notification (Yes = batch freeze after user closes work app; No = no action, VPN not restored by Zindan).
- Cross-process dummy-VPN status broadcasts for the `:vpnwatch` process.

1.3.3 (133)
===

- Batch freeze and batch unfreeze (toolbar, settings, launcher shortcuts).
- Frozen apps sorted at the top of the work profile list.
- Toolbar freeze/unfreeze icons (snowflake / ice motif).

1.3.2 (132)
===

- Setup wizard colors: golden header text on dark green; dark green body text on white.

1.3.1 (131)
===

- Fix unreadable Russian (and other) locale strings: UTF-8 encoding was corrupted during locale sync.
- Fix text contrast on setup wizard and light surfaces: `colorTextOnLight` on gold/light backgrounds.
- Apply Zindan dark theme overrides for Android 12+ (`values-v31`) on Samsung devices.

1.3.0 (130)
===

- Fork from upstream Shelter with Zindan branding.
- Zindan name, launcher icons, and green/yellow theme.
- Fixed version 1.3.0; baseline stage on Samsung devices.

1.9.1 (445)
===

- Hotfix crashes below Android 11.

1.9
===

- Updated targetSDK to 34 (Android 14) with compatibility fixes.
- More reliable delayed freezing using AlarmManager (thanks parmaster84).
- Support for cross-profile interactions allowlisting (e.g. for Gboard).
- Removed "Fake Camera" feature as it has not been supported since R.
- Version displayed within the app has now been changed to also reflect the exact Git commit when the app is built.
- File Shuttle no longer appends ".null" or ".bin" suffixes unnecessarily. This should make it work much better with file managers such as Material Files.
- File Shuttle now triggers media scanning much more robustly. Media files (pictures, videos, etc.) copied into the work profile should now show up much quicker in gallery apps.
- Added a fake NFC payment service to workaround a bug in Android that prevents payment apps inside the work profile from being used if none is present in the main profile.
- Fixed unintuitive colors of navigation icons under dark mode.

1.8
===

- Updated targetSDK to 33 (Android 13) with compatibility fixes.
- UI style revamp with Material You support on Android 12+.

1.7
===

- Revamped the initial setup process to include a full setup guide for better clarity and less confusion.
- Upgraded targetSDK to 31 (Android 12) with compatibility fixes.
- Upgraded dependencies.
- Translation updates thanks to our wonderful community.

1.6
===

- Start of in-repo changelogs
- Add support for Android 11 (c147377, 3852bd, and more) (__Note__: For now, File Shuttle is not available in the version on Google Play due to policy reasons, as they will not be allowing apps with All Files permission before 2021.)
- Shelter can no longer be installed to external storage (removable SD cards) (9a6777 by Camilio Alejo)
- Allow more browsable intents to be passed across work / main profile boundary (43444b by Camilio Alejo)
- A new shortcut to Documents UI is available in the three-dot menu of Shelter, because on Pixels the Google Files app may not be able to open File Shuttle correctly (a121ee)
- You can now choose to block or allow cross-profile contact access via a settings option (749ad1)
- Thanks to translators participating in our Weblate instance (https://weblate.typeblog.net), Shelter is now available in more languages. You can now contribute translations easier than ever by using the Weblate interface.
