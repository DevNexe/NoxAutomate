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
- обработчики `battery.changed`, `power.connected`, `power.disconnected`, `screen.on`, `screen.off`, `wifi.connected`, `network.connected`, `network.disconnected`, `app.foreground`, `sms.received` и периодический `time.every(minutes=...)`;
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

Обработчик SMS передаёт текст и номер отправителя в переменные `message`, `body` и `sender`, чтобы сценарий мог проверять их через `if`:

```python
on sms.received():
    if sender == "+79990000000" and message == "НАЙДИ ТЕЛЕФОН":
        system.notify(title="Команда получена", body="Запускаю поиск")
    elif "проверка" in message:
        system.toast(message="Получено тестовое сообщение")
```

Событие обрабатывает новые входящие SMS, пока запущен foreground service; оно не читает историю SMS из системного приложения. Нужны явное runtime-разрешение `RECEIVE_SMS` и включённый сценарий с `on sms.received`. Текст SMS передаётся в DSL как обычная строка и никогда не интерпретируется как код. В настройках можно задать один разрешённый номер отправителя; пустое поле означает обработку сообщений от всех номеров.

Пример модульного сценария «поиск телефона»:

```python
coords = location.get_last_known_coordinates()
device.set_ringer_mode(mode="silent")
device.set_volume(stream="ring", level=100)

system.notify(title="Поиск устройства", body="Телефон включён в режиме поиска")
while true:
    device.flash_lamp(enabled=true)
    system.sleep(ms=250)
    device.flash_lamp(enabled=false)
    system.sleep(ms=250)
    if app.is_running(package_name="com.android.settings"):
        break

if coords["latitude"] != 0:
    sms.send(phone="+123456789", message="Местоположение: " + str(coords["latitude"]) + ", " + str(coords["longitude"]))
```

Модульность здесь важнее, чем один «магический» вызов: каждая готовая функция выполняет ровно одну задачу, а сценарий сам собирает нужную логику.

## Встроенные функции

- `device.get_battery()`, `device.is_charging()`, `device.set_brightness(level)`, `device.get_volume(stream)`, `device.set_volume(stream, level)`, `device.get_ringer_mode()`, `device.set_ringer_mode(mode)`, `device.vibrate(duration_ms)`, `device.flash_lamp(enabled)`, `device.set_screen_timeout(ms)`;
- `wifi.is_connected()`, `wifi.get_ssid()`, `wifi.set_state(enabled)`;
- `system.notify(title, body)` (также принимает `message`), `system.toast(message)`, `system.sleep(ms)`;
- `location.get_last_known_coordinates()`, `location.get_last_known_location()`;
- `sms.compose(phone, message)`, `sms.send(phone, message)`;
- `app.launch(package_name)`, `app.is_running(package_name)`;
- `accessibility.click_text(text)`, `accessibility.get_active_app()`.
- дополнительно для Accessibility: `find_text`, `set_text`, `scroll`, `tap`, `swipe` и `press_back`.

## Android и ограничения

Foreground service совместим с Android 8.1 и показывает постоянное уведомление. Он продолжает работать после закрытия экрана редактора, если включены сценарии; ОС может завершать процессы под ограничениями батареи, поэтому для надёжной работы настройте исключение/разрешение фоновой работы в системных настройках. Принудительная остановка приложения останавливает автоматизацию до следующего ручного запуска. Несколько включённых сценариев могут работать одновременно; после перезагрузки запускаются включённые сценарии, если настройка автозапуска активна. Автозапуск можно отключить в разделе настроек приложения. Android 13+ дополнительно требует runtime-разрешение на уведомления; приложение запрашивает его при запуске. Разовое выполнение без триггеров идёт через WorkManager. Периодический `time.every(minutes=N)` работает, пока активен foreground service.

Для изменения яркости предоставьте приложению доступ к системным настройкам кнопкой «Доступ к настройкам». Для фильтрации Wi-Fi по SSID и `wifi.get_ssid()` нужно разрешение местоположения; на Android 8.1 также должна быть включена геолокация. Для `sms.received` предоставьте приложению разрешение чтения входящих SMS отдельной кнопкой в интерфейсе. Android может ограничивать SMS-разрешения для приложений, распространяемых через Google Play; личная установка APK не отменяет системный экран разрешения. На Android 10+ приложение не может напрямую включать/выключать Wi-Fi. Для управления звуком, вибрацией, вспышкой и отправкой SMS нужны соответствующие разрешения (`VIBRATE`, `CAMERA`, `SEND_SMS`). Для функций `accessibility.*` и события `app.foreground` включите службу через кнопку «Спец. возможности» и системный экран специальных возможностей; она предоставляет нажатия/жесты, поиск и ввод текста, прокрутку и возврат назад. Жесты и действия работают только для доступных Accessibility-узлов/экрана и требуют пользовательского согласия на Accessibility. Видимость и состояние процессов других приложений ограничены Android, поэтому `app.is_running()` — best effort, а не гарантированный мониторинг foreground-приложений. Фоновый запуск Activity через `app.launch()` также может ограничиваться новыми версиями Android. Включённые сценарии запускаются после `BOOT_COMPLETED`, но некоторые производители требуют вручную разрешить автозапуск/работу в фоне; принудительная остановка приложения блокирует перезапуск до следующего ручного открытия.

Редактор сохраняет несколько сценариев локально и показывает последний статус/ошибку выполнения. Проверка ядра запускается командой `gradle :core:test`, APK — `gradle :app:assembleDebug`; workflow `.github/workflows/android.yml` запускает обе проверки и публикует debug APK как artifact.
