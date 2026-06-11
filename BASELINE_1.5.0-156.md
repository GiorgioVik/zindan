# Точка отката — Zindan 1.5.0 (build 156)

**Дата фиксации:** 2026-06-11  
**Git-тег:** `v1.5.0-build156`  
**APK:** `Zindan-1.5.0-(156)-debug.apk`

Экспериментальная ветка поверх `v1.5.0-baseline`: уведомления VPN, MacroDroid-детект, попытка сценария 2 через AlarmManager + dummy-VPN.

---

## Откат

```powershell
cd D:\zindan4
git checkout v1.5.0-build156
```

На чистую baseline (153):

```powershell
git checkout v1.5.0-baseline
```

---

## Сборка

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:GRADLE_USER_HOME = "D:\zindan4\.gradle-user-home"
cd D:\zindan4
.\gradlew.bat :app:assembleDebug
```

---

## Состояние build 156

| Область | Статус |
|---------|--------|
| Исходный код в «О программе»: `D:\Zindan4` | ✅ |
| FGS текст: «Контроль VPN» | ✅ |
| Уведомления VPN вкл/выкл (Anti Spy) | ✅ |
| MacroDroid-style reconcile (VpnTrigger edges) | ✅ |
| Сценарий 2: dummy-VPN → диалог → PUBLIC_FREEZE_ALL | ⚠️ нестабильно на устройстве |
| Сценарий 1 (запуск app при VPN) | без изменений от baseline |

---

## Ключевые файлы

- `AntiSpyVpnWatchService.kt` — scenario2 + reconcile
- `AntiSpyVpnPromptManager.kt` — AlarmManager → Activity
- `Utility.kt` — `postUserAlert`
- `strings.xml` / `values-ru-rRU/strings.xml`
