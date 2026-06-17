# Точка отката — Zindan 1.5.0 (versionCode 153)

**Дата фиксации:** 2026-06-11  
**Git-тег:** `v1.5.0-baseline`  
**APK:** `Zindan-1.5.0-(153)-debug.apk`

Используйте этот коммит/тег, если дальнейшие эксперименты ухудшат поведение и нужно вернуться к текущему состоянию.

---

## Сборка

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:assembleDebug
```

APK копируется в корень: `Zindan-{VERSION_NAME}-({VERSION_CODE})-debug.apk`.  
Номер сборки (`VERSION_CODE`) увеличивается автоматически при каждом `assemble*` — см. `version.properties`.

---

## Состояние на момент baseline

| Область | Статус |
|---------|--------|
| Kotlin, Anti Spy всегда включён (без переключателя) | ✅ |
| Версия в «О программе»: `1.5.0 (153)` | ✅ |
| Автоинкремент `VERSION_CODE` при сборке | ✅ |
| Удаление ярлыка «Разморозить и запустить» при деинсталле (улучшенная цепочка) | ⚠️ требует полевой проверки |
| Автозаморозка при клонировании (callback + refresh) | ⚠️ требует полевой проверки |
| VPN: запуск app при активном VPN (Dummy VPN) | ✅ |
| VPN-watcher: события + poll 2 с + debounce 1,5 с | ✅ (как задумано) |

---

## Откат

```powershell
cd D:\zindan4
git checkout v1.5.0-baseline
```

Или посмотреть коммит: `git show v1.5.0-baseline`
