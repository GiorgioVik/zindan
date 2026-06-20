# Optional assets (not required to build)

Place source PNGs here to regenerate icons:

| File | Script |
|------|--------|
| `zindan_icon_source.png` | `.\tools\generate_zindan_launcher_icons.ps1` |
| `zindan_icon_freeze_source.png` | `.\tools\generate_freeze_icons.ps1` (also needs unfreeze) |
| `zindan_icon_unfreeze_source.png` | same |

`generate_freeze_icons.ps1` writes:
- `ic_toolbar_*` — transparent background (toolbar, snowflake badge in app list)
- `ic_shortcut_*` — opaque `#223D2C` PNG for API 25 and older launchers

On Android 8+ (API 26+), `drawable-anydpi-v26/ic_shortcut_*.xml` adaptive icons use
`@color/zindanGreenDark` as background and `ic_toolbar_*` as foreground (fixes white
squircle on Samsung launchers). **Recreate pinned shortcuts** after updating the app.
