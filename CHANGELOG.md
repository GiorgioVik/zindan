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
- Toolbar freeze/unfreeze icons from Zindan2 (snowflake / ice).

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

- Fork from upstream Shelter (`shelter_orig`) with Zindan branding only.
- Zindan name, launcher icons, and green/yellow theme from Zindan2 assets.
- Fixed version 1.3.0; no changes to installApp, auto-freeze, or refresh logic.
- No Samsung SUSPENDED patches in this release (baseline stage).

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
