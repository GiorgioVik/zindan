# Anti Spy — отладка (Zindan 1.5.0+)

## Режим работы

**Anti Spy всегда включён** — переключателя на панели и в настройках нет. VPN-мониторинг и launch gate активны с первого запуска после настройки профиля.

## Компоненты

- `AntiSpyVpnWatchService` — FGS, мониторинг VPN в основном и рабочем профиле.
- `AntiSpyDummyVpnService` — снятие стороннего VPN перед запуском приложения.
- `AntiSpyLaunchGate` — проверка VPN в `DummyActivity` перед unfreeze & launch.
- `AntiSpyManager` — startup freeze, batch freeze по VPN, синхронизация списка автозаморозки.

## Подготовка

1. Установить `Zindan-1.5.0-debug.apk` в **основной профиль**.
2. Пройти мастер настройки рабочего профиля.
3. Один раз выдать разрешение **VPN** (при первом запуске приложения при активном VPN или из диалога Anti Spy).

## Logcat

```powershell
adb logcat -c
adb logcat -s AntiSpyManager AntiSpyVpnWatch AntiSpyDummyVpn VpnTunnelDetector AntiSpyLaunchGate
```

| Сообщение | Значение |
|-----------|----------|
| `startup freeze scheduled` | Заморозка после boot / обновления |
| `auto-freeze requested` | Групповая заморозка (кнопка снежинки или VPN) |
| `AntiSpyVpnWatch` start/stop | FGS VPN watcher |
| `AntiSpyDummyVpn` establish/disconnect | Снятие стороннего VPN |

## Чеклист

| # | Проверка | Ожидание |
|---|----------|----------|
| 1 | Панель инструментов | Поиск → снежинка → лёд → Настройки (без щита) |
| 2 | Запуск work-приложения без VPN | Запуск без диалога |
| 3 | Запуск work-приложения с VPN | Запрос VPN-разрешения или снятие VPN, затем запуск |
| 4 | Включить VPN снаружи | Уведомление / диалог «заморозить всё?» |
| 5 | Перезагрузка | После открытия Zindan — startup batch freeze (если есть список автозаморозки) |
| 6 | Обновление с 1.4.x | Однократная startup freeze при первом запуске 1.5.0 |

## Сборка

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:GRADLE_USER_HOME = "D:\zindan4\.gradle-user-home"
cd D:\zindan4
.\gradlew.bat :app:assembleDebug
```

APK: `D:\zindan4\Zindan-1.5.0-debug.apk`
