# Nox Automate

Android-приложение автоматизации со своим Python-подобным DSL. Минимальная версия Android — API 26 (Android 8.0), поэтому Android 8.1 (API 27) поддерживается. Ядро в `core` не зависит от Android и содержит лексер, рекурсивный парсер, AST, интерпретатор и unit-тесты.

## Сборка

Нужны JDK 17, Android SDK (API 35) и Gradle 8.9. CI на GitHub Actions сам устанавливает SDK, запускает тесты и собирает APK.

```powershell
gradle :core:test
gradle :app:assembleDebug
```

## Возможности DSL

- числа, строки, Boolean, `null`, списки и словари, включая чтение/изменение элементов по индексу;
- отступы блоков кратно четырём пробелам или табуляцией, `if`/`elif`/`else`, `while`, `for`, `break`, `continue`;
- выражения с арифметикой, сравнением, логическими операторами и `in`;
- обработчики `battery.changed`, `power.connected`, `power.disconnected`, `screen.on`, `screen.off`, `wifi.connected`, `network.connected`, `network.disconnected`, `app.foreground` и периодический `time.every(minutes=...)`;
- ограниченный набор `device`, `wifi`, `system` и `app` функций без доступа к произвольным классам или Java/Kotlin reflection.
- ограничение числа операций и количества проходов цикла для защиты от бесконечного выполнения.

Пример:

```python
on battery.changed(threshold=20, direction="below"):
    system.notify(title="Battery", body="Please charge")

on power.connected():
    system.toast(message="Зарядное устройство подключено")

on time.every(minutes=30):
    system.notify(title="Напоминание", body="Прошло 30 минут")

total = 0
for value in [1, 2, 3]:
    total = total + value
```

`battery.changed` сравнивает текущий уровень (доступен в теле обработчика как `level`) с `threshold`; `direction` принимает `below` или `above`. Обработчик Wi-Fi фильтрует событие по `ssid`.

## Встроенные функции

- `device.get_battery()`, `device.is_charging()`, `device.set_brightness(level)`, `device.set_volume(stream, level)`;
- `wifi.is_connected()`, `wifi.get_ssid()`, `wifi.set_state(enabled)`;
- `system.notify(title, body)` (также принимает `message`), `system.toast(message)`, `system.sleep(ms)`;
- `app.launch(package_name)`, `app.is_running(package_name)`;
- `accessibility.click_text(text)`, `accessibility.get_active_app()`.
- дополнительно для Accessibility: `find_text`, `set_text`, `scroll`, `tap`, `swipe` и `press_back`.

## Android и ограничения

Foreground service совместим с Android 8.1 и показывает постоянное уведомление. Несколько включённых сценариев могут работать одновременно; включённые сценарии сохраняются и восстанавливаются после перезагрузки. Android 13+ дополнительно требует runtime-разрешение на уведомления; приложение запрашивает его при запуске. Разовое выполнение без триггеров идёт через WorkManager. Периодический `time.every(minutes=N)` работает, пока активен foreground service.

Для изменения яркости предоставьте приложению доступ к системным настройкам кнопкой «Доступ к настройкам». Для фильтрации Wi-Fi по SSID и `wifi.get_ssid()` нужно разрешение местоположения; на Android 8.1 также должна быть включена геолокация. На Android 10+ приложение не может напрямую включать/выключать Wi-Fi. Для функций `accessibility.*` и события `app.foreground` включите службу через кнопку «Спец. возможности» и системный экран специальных возможностей; она предоставляет нажатия/жесты, поиск и ввод текста, прокрутку и возврат назад. Жесты и действия работают только для доступных Accessibility-узлов/экрана и требуют пользовательского согласия на Accessibility. Видимость и состояние процессов других приложений ограничены Android, поэтому `app.is_running()` — best effort, а не гарантированный мониторинг foreground-приложений. Фоновый запуск Activity через `app.launch()` также может ограничиваться новыми версиями Android. Включённые сценарии запускаются после `BOOT_COMPLETED`, но некоторые производители требуют вручную разрешить автозапуск/работу в фоне; принудительная остановка приложения блокирует перезапуск до следующего ручного открытия.

Редактор сохраняет несколько сценариев локально и показывает последний статус/ошибку выполнения. Проверка ядра запускается командой `gradle :core:test`, APK — `gradle :app:assembleDebug`; workflow `.github/workflows/android.yml` запускает обе проверки и публикует debug APK как artifact.
