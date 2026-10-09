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
- обработчики `battery.changed`, `power.connected`, `power.disconnected`, `power.save_mode`, `device.idle`, `screen.on`, `screen.off`, `wifi.connected`, `network.connected`, `network.disconnected`, `app.foreground`, `sms.received`, package-scoped `app.broadcast` и периодический `time.every(minutes=...)`;
- `parallel` с независимыми ветками `branch` и запуск сохранённого сценария через `flow.start(name)`;
- набор встроенных функций для батареи/питания, звука/медиа, соединений, локации, SMS/телефонии, сенсоров, файлов, контактов, календаря, Accessibility и приложений; DSL не имеет доступа к произвольным классам или Java/Kotlin reflection.
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

parallel:
    branch:
        system.toast(message="Первая ветка")
    branch:
        system.toast(message="Вторая ветка")

flow.start(name="Дополнительный сценарий")
```

`parallel` запускает 2–8 веток одновременно, ожидает завершения каждой и передаёт каждой ветке копию текущих переменных. Изменения переменных внутри ветки не влияют на другие ветки и вызывающий код. Вложенность ограничена двумя уровнями; ошибка ветки останавливает остальные ветки и возвращается как ошибка сценария. `flow.start(name=...)` запускает сохранённый сценарий в фоне сервиса: его обработчики событий остаются активны; сценарий без обработчиков выполняется один раз. Имя должно совпадать с именем сценария в редакторе. Второй запуск уже активного сценария не создаёт дубликат.

`battery.changed` сравнивает текущий уровень (доступен в теле обработчика как `level`) с `threshold`; `direction` принимает `below` или `above`. Обработчик Wi-Fi фильтрует событие по `ssid`.

`on app.broadcast(action="com.noxautomate.SIGNAL")` принимает только действия с префиксом идентификатора этого приложения и регистрируется как непубличный receiver. Отправить сигнал можно через `app.broadcast(action="com.noxautomate.SIGNAL", message="готово")`; тело обработчика получает `action` и `message`.

Обработчик SMS передаёт текст и номер отправителя в переменные `message`, `body` и `sender`, чтобы сценарий мог проверять их через `if`:

```python
on sms.received():
    if sender == "+79990000000" and message == "СТАТУС":
        system.notify(title="Команда получена", body="Сценарий активен")
    elif "проверка" in message:
        system.toast(message="Получено тестовое сообщение")
```

Событие обрабатывает новые входящие SMS, пока запущен foreground service; оно не читает историю SMS из системного приложения. Нужны явное runtime-разрешение `RECEIVE_SMS` и включённый сценарий с `on sms.received`. Текст SMS передаётся в DSL как обычная строка и никогда не интерпретируется как код. В настройках можно задать один разрешённый номер отправителя; пустое поле означает обработку сообщений от всех номеров.

## Встроенные функции

- `device.get_battery()`, `device.is_charging()`, `device.set_brightness(level)`, `device.get_volume(stream)`, `device.set_volume(stream, level)`, `device.get_ringer_mode()`, `device.set_ringer_mode(mode)`, `device.vibrate(duration_ms)`, `device.flash_lamp(enabled)`, `device.set_screen_timeout(ms)`;
- питание: `device.is_power_save_mode()`, `device.is_device_idle()`, `device.is_interactive()`, `device.keep_awake(duration_ms)`, `device.release_awake()`, `device.is_dnd()`;
- приложения: `app.launch(package_name)`, `app.is_running(package_name)`, `app.is_installed(package_name)`, `app.get_foreground()`, `app.start_service(package_name, class_name)`, `app.broadcast(action, message)`;
- `wifi.is_connected()`, `wifi.get_ssid()`, `wifi.set_state(enabled)`, `wifi.open_settings()`;
- настройки соединений: `network.is_connected()`, `network.get_transport()`, `network.open_settings()`, `bluetooth.is_enabled()`, `bluetooth.open_settings()`, `nfc.is_enabled()`, `nfc.open_settings()`, `usb.is_connected()`, `hotspot.open_settings()`;
- media/audio: `media.control(action)`, `audio.record(filename, duration_ms)`, `camera.has_flash()`, `camera.open()`;
- датчики: `sensor.light()`, `sensor.proximity()`, `sensor.accelerometer()`, `sensor.gyroscope()`, `sensor.magnetic_field()`, `sensor.pressure()`, `sensor.rotation_vector()`, `sensor.step_counter()`, `sensor.step_detector()`;
- `clipboard.get()`, `clipboard.set(text)`, `file.read(path)`, `file.write(path, content)`;
- `location.is_enabled()`, `location.is_provider_enabled(provider)`, `location.in_radius(latitude, longitude, center_latitude, center_longitude, radius_m)`, `location.reverse_geocode(latitude, longitude)`;
- `phone.open_dialer(phone)`, `phone.get_call_state()`, `phone.get_sim_count()`, `contacts.search(name)`, `calendar.events(from_ms, to_ms)`;
- время: `time.now()`, `time.get_timezone()`, `time.format(timestamp, pattern, timezone)`, `time.is_between(start_hour, end_hour)`;
- `system.notify(title, body)` (также принимает `message`), `system.toast(message)`, `system.sleep(ms)`;
- `location.get_last_known_coordinates()`, `location.get_last_known_location()`;
- `sms.compose(phone, message)`, `sms.send(phone, message)`;
- `flow.start(name)` для запуска другого сохранённого сценария;
- `accessibility.click_text(text)`, `accessibility.get_active_app()`.
- дополнительно для Accessibility: `find_text`, `set_text`, `scroll`, `tap`, `swipe` и `press_back`.

## Android и ограничения

Foreground service совместим с Android 8.1 и показывает постоянное уведомление. Он продолжает работать после закрытия экрана редактора, если включены сценарии; ОС может завершать процессы под ограничениями батареи, поэтому для надёжной работы настройте исключение/разрешение фоновой работы в системных настройках. Принудительная остановка приложения останавливает автоматизацию до следующего ручного запуска. Несколько включённых сценариев могут работать одновременно; после перезагрузки запускаются включённые сценарии, если настройка автозапуска активна. Автозапуск можно отключить в разделе настроек приложения. Android 13+ дополнительно требует runtime-разрешение на уведомления; приложение запрашивает его при запуске. Разовое выполнение без триггеров идёт через WorkManager. Периодический `time.every(minutes=N)` работает, пока активен foreground service.

Android API и права ограничивают ряд функций. Панель «Запросить разрешения функций» запрашивает runtime-права на микрофон, телефон, контакты, календарь, геолокацию и Bluetooth; SMS запрашивается отдельно. Для яркости предоставьте приложению доступ к системным настройкам. Для Wi-Fi SSID на Android 8.1 нужны разрешение местоположения и включённая геолокация. `file.read/write` работает только во внутреннем каталоге приложения и ограничивает один файл 1 МиБ; `audio.record` сохраняет новый файл туда же, длительность записи — до 60 секунд. `contacts.search` ограничен 50 контактами (до 10 номеров на контакт), а `calendar.events` — 200 событиями на интервал до 366 дней. Чтение календаря возвращает события из таблицы Events, а не разворачивает повторения в Instances.

Android 10+ не разрешает приложению напрямую переключать Wi-Fi; Bluetooth/NFC/hotspot намеренно открывают системные настройки. `camera.open()` передаёт управление установленному приложению камеры, а не снимает изображение незаметно. `media.control()` отправляет медиаклавиши и зависит от активного медиаплеера. `phone.open_dialer()` только открывает системный набор номера, звонок не инициируется. Пока не реализованы QR-сканирование, NFC-обмен, чтение истории SMS, обработчики состояния звонка, файловый picker, геозоны как фоновые события, диалоги или полное управление мобильной сетью/точкой доступа. Необходимые для них отдельные системные API, пользовательский интерфейс или специальные платформенные/магазинные привилегии не заявляются как готовые.

Для функций `accessibility.*` и события `app.foreground` включите службу через кнопку «Специальные возможности» и системный экран; действия ограничены доступными Accessibility-узлами/экраном. Видимость и состояние процессов других приложений ограничены Android, поэтому `app.is_running()` — best effort, а не гарантированный мониторинг. Фоновый запуск Activity через `app.launch()` также может ограничиваться Android. Некоторые производители требуют вручную разрешить автозапуск/работу в фоне; принудительная остановка приложения блокирует перезапуск до следующего ручного открытия.

Интерфейс использует тёмную тему и вкладки «Сценарии» и «Настройки». Нажатие на карточку открывает экран сценария с запуском/остановкой, журналом последних 100 сообщений и кнопкой редактирования. Редактор занимает основную область экрана; сохранение и удаление доступны в меню верхней панели. Список запущенных сценариев сохраняется, сервис восстанавливает их после перезапуска процесса, если пользователь не остановил сценарий; после перезагрузки устройства дополнительно учитывается настройка автозапуска. Проверка ядра запускается командой `gradle :core:test`, APK — `gradle :app:assembleDebug`; workflow `.github/workflows/android.yml` запускает обе проверки и публикует debug APK как artifact.
