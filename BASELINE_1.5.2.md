# Точка отката — Zindan 1.5.2 (versionCode 177)

**Дата фиксации:** 2026-06-13  
**Git tag:** `v1.5.2-baseline`  
**Base:** git tag `v1.5.0-baseline` (Zindan 1.5.0, build 153)  
**APK:** `Zindan-1.5.2-(177)-debug.apk`

Используйте этот коммит/тег, если дальнейшие эксперименты ухудшат поведение и нужно вернуться к текущему состоянию 1.5.2.

---

## Изменения относительно 1.5.0 (153)

| Область | Изменение |
|---------|-----------|
| Toast-уведомления | Все показываются **вверху** экрана (`ZindanToast`) |
| VPN | Системные уведомления «VPN включен» / «VPN выключен» (основной профиль) |
| Групповая заморозка | Heads-up при успешной заморозке; «Заморозить всё» учитывает уже замороженные приложения |
| Иконка приложения | `ic_launcher_zindan`, тёмно-красный фон `#75081B` (как в 153 — отдельный PNG-слой фона) |
| «О программе» | Ссылка на GitHub-репозиторий |
| Anti Spy служба | Текст: «Контроль VPN» |

Логика ярлыка «Разморозить и запустить» и вытеснения VPN при запуске — **без изменений** относительно 153.

---

## Сборка

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
# Optional local Gradle cache (example on D:):
# $env:GRADLE_USER_HOME = "D:\Zindan5\.gradle-user-home"
.\gradlew.bat :app:assembleDebug
```

APK копируется в корень: `Zindan-{VERSION_NAME}-({VERSION_CODE})-debug.apk`.  
Номер сборки (`VERSION_CODE`) увеличивается автоматически при каждом `assemble*` — см. `version.properties`.

Иконки лаунчера (при смене исходника): `tools\generate_zindan_launcher_icons.ps1`

---

## Подпись

Debug builds use the standard Android debug keystore (`~/.android/debug.keystore`).

---

## Откат

```powershell
git checkout v1.5.2-baseline
```

Или посмотреть коммит: `git show v1.5.2-baseline`

Откат к 1.5.0 (153):

```powershell
git checkout v1.5.0-baseline
```
