package com.noxautomate

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.media.AudioManager
import android.media.ToneGenerator
import android.telephony.SmsManager
import android.telephony.SmsMessage
import android.widget.Toast
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.noxautomate.dsl.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf

class AutomationForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val reloadMutex = Mutex()
    private val flows = mutableMapOf<String, RunningFlow>()
    private val receivers = mutableListOf<android.content.BroadcastReceiver>()
    private val scheduleJobs = mutableListOf<Job>()
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var findJob: Job? = null
    private var findCameraManager: CameraManager? = null
    private var findCameraId: String? = null
    private var findToneGenerator: ToneGenerator? = null
    private var findAudioManager: AudioManager? = null
    private var previousRingerMode: Int? = null
    private var previousAlarmVolume: Int? = null

    private data class RunningFlow(val name: String, val program: Program, val interpreter: Interpreter, val mutex: Mutex = Mutex())

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, notification("Ожидание скрипта"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        serviceScope.launch {
            when (intent?.action) {
                ACTION_FIND_STOP -> {
                    stopPhoneFinder()
                    if (AutomationStore.enabledScripts(this@AutomationForegroundService).isEmpty()) {
                        stopSelf(startId)
                    } else {
                        updateNotification("Поиск остановлен")
                    }
                    return@launch
                }
                ACTION_FIND_START -> startPhoneFinder()
            }
            reloadMutex.withLock {
                clearTriggers()
                synchronized(flows) { flows.clear() }
                val scripts = AutomationStore.scripts(this@AutomationForegroundService)
                val enabled = AutomationStore.enabledScripts(this@AutomationForegroundService)
                if (enabled.isEmpty()) {
                    if (findJob?.isActive != true) stopSelf(startId)
                    return@withLock
                }
                val errors = mutableListOf<String>()
                for (name in enabled) {
                    val source = scripts[name]
                    if (source == null) {
                        errors += "$name: сценарий удалён"
                        continue
                    }
                    try {
                        val parsed = withContext(Dispatchers.Default) { Parser(Lexer(source).tokenize()).parse() }
                        validateEvents(parsed)
                        val runtime = RunningFlow(name, parsed, Interpreter(AndroidDslHost(this@AutomationForegroundService)))
                        runtime.interpreter.execute(parsed)
                        synchronized(flows) { flows[name] = runtime }
                    } catch (error: Exception) {
                        errors += "$name: ${error.message}"
                    }
                }
                if (flowSnapshot().isEmpty()) {
                    if (findJob?.isActive == true) {
                        updateNotification("Поиск телефона: сигнал и вспышка активны")
                    } else {
                        updateNotification(errors.joinToString("; ").ifEmpty { "Нет активных сценариев" })
                        stopSelf(startId)
                    }
                    return@withLock
                }
                try {
                    registerTriggers()
                    updateNotification("Активны сценарии: ${flowSnapshot().joinToString { it.name }}")
                    if (errors.isNotEmpty()) updateNotification("Ошибка сценария: ${errors.joinToString("; ")}")
                } catch (error: Exception) {
                    clearTriggers()
                    synchronized(flows) { flows.clear() }
                    updateNotification("Не удалось запустить триггеры: ${error.message}")
                    stopSelf(startId)
                }
            }
        }
        return START_STICKY
    }

    private fun clearTriggers() {
        scheduleJobs.forEach(Job::cancel)
        scheduleJobs.clear()
        receivers.forEach { receiver -> runCatching { unregisterReceiver(receiver) } }
        receivers.clear()
        networkCallback?.let { callback ->
            val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            runCatching { manager.unregisterNetworkCallback(callback) }
        }
        networkCallback = null
    }

    private fun startPhoneFinder() {
        stopPhoneFinder()
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            updateNotification("Для поиска требуется разрешение CAMERA")
            return
        }
        try {
            val cameraManager = getSystemService(CameraManager::class.java)
            val cameraId = cameraManager.cameraIdList.firstOrNull()
                ?: throw IllegalStateException("Фонарик недоступен")
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            findCameraManager = cameraManager
            findCameraId = cameraId
            findAudioManager = audioManager
            previousRingerMode = audioManager.ringerMode
            previousAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
            audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
            audioManager.setStreamVolume(
                AudioManager.STREAM_ALARM,
                audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM),
                0
            )
            findToneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 100)
            updateNotification("Поиск телефона: сигнал и вспышка активны")
            findJob = serviceScope.launch {
                try {
                    while (isActive) {
                        cameraManager.setTorchMode(cameraId, true)
                        findToneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 400)
                        delay(500)
                        cameraManager.setTorchMode(cameraId, false)
                        delay(500)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    updateNotification("Ошибка поиска телефона: ${error.message}")
                    stopPhoneFinder()
                }
            }
        } catch (error: Exception) {
            stopPhoneFinder()
            updateNotification("Не удалось запустить поиск телефона: ${error.message}")
        }
    }

    private fun stopPhoneFinder() {
        findJob?.cancel()
        findJob = null
        val cleanupErrors = mutableListOf<String>()
        val cameraManager = findCameraManager
        val cameraId = findCameraId
        if (cameraManager != null && cameraId != null) {
            try {
                cameraManager.setTorchMode(cameraId, false)
            } catch (error: Exception) {
                cleanupErrors += "фонарик: ${error.message}"
            }
        }
        findToneGenerator?.let { tone ->
            tone.stopTone()
            tone.release()
        }
        findToneGenerator = null
        val audioManager = findAudioManager
        if (audioManager != null) {
            try {
                previousAlarmVolume?.let {
                    audioManager.setStreamVolume(AudioManager.STREAM_ALARM, it, 0)
                }
                previousRingerMode?.let { audioManager.ringerMode = it }
            } catch (error: Exception) {
                cleanupErrors += "звук: ${error.message}"
            }
        }
        findCameraManager = null
        findCameraId = null
        findAudioManager = null
        previousRingerMode = null
        previousAlarmVolume = null
        if (cleanupErrors.isNotEmpty()) {
            updateNotification("Не удалось полностью остановить поиск: ${cleanupErrors.joinToString()}")
        }
    }

    private fun validateEvents(program: Program) {
        val handlers = program.statements.filterIsInstance<EventStmt>()
        val supported = setOf(
            "battery.changed", "power.connected", "power.disconnected",
            "screen.on", "screen.off", "wifi.connected", "network.connected",
            "network.disconnected", "time.every", "app.foreground", "sms.received"
        )
        val unknown = handlers.map { it.event }.filterNot(supported::contains).distinct()
        if (unknown.isNotEmpty()) throw DslException("Неизвестные события: ${unknown.joinToString()}")
        if (handlers.any { it.event == "sms.received" } &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED
        ) {
            throw DslException("Для обработчика sms.received требуется разрешение RECEIVE_SMS")
        }
        if (handlers.any { it.event == "wifi.connected" } &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            throw DslException("Для обработчика wifi.connected требуется разрешение ACCESS_FINE_LOCATION")
        }
        if (handlers.any { it.event == "wifi.connected" }) {
            val location = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            val enabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                location.isLocationEnabled
            } else {
                @Suppress("DEPRECATION")
                location.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ||
                    location.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
            }
            if (!enabled) throw DslException("Для wifi.connected включите службы геолокации")
        }
        if (handlers.filter { it.event == "time.every" }.any { event ->
                val literal = event.filters["minutes"] as? LiteralExpr
                val value = (literal?.value as? Number)?.toDouble() ?: return@any true
                value % 1.0 != 0.0 || value !in 1.0..1440.0
            }
        ) {
            throw DslException("Каждый time.every требует целое число minutes от 1 до 1440")
        }
    }

    private fun registerTriggers() {
        val events = flowSnapshot().flatMap { runtime ->
            runtime.program.statements.filterIsInstance<EventStmt>()
        }.map { it.event }.toSet()

        if ("sms.received" in events) {
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action != "android.provider.Telephony.SMS_RECEIVED") return
                    val pdus = intent.extras?.get("pdus") as? Array<*> ?: run {
                        updateNotification("Получено SMS без данных для чтения")
                        return
                    }
                    val format = intent.getStringExtra("format") ?: "3gpp"
                    val messages = pdus.mapNotNull { pdu ->
                        (pdu as? ByteArray)?.let { SmsMessage.createFromPdu(it, format) }
                    }
                    if (messages.isEmpty()) {
                        updateNotification("Не удалось прочитать входящее SMS")
                        return
                    }
                    val sender = messages.first().originatingAddress.orEmpty()
                    val body = messages.joinToString(separator = "") { it.messageBody.orEmpty() }
                    val allowedSender = AutomationStore.smsAllowedSender(this@AutomationForegroundService)
                    if (allowedSender.isNotBlank() && normalizePhone(sender) != normalizePhone(allowedSender)) return
                    emitEvent(
                        "sms.received",
                        mapOf(
                            "sender" to DslValue.Text(sender),
                            "message" to DslValue.Text(body),
                            "body" to DslValue.Text(body),
                            "timestamp_ms" to DslValue.Number(messages.first().timestampMillis.toDouble(), true)
                        )
                    )
                }
            }
            val filter = IntentFilter("android.provider.Telephony.SMS_RECEIVED")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, filter, android.Manifest.permission.RECEIVE_SMS, null, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(receiver, filter, android.Manifest.permission.RECEIVE_SMS, null)
            }
            receivers += receiver
        }

        if (events.any { it in setOf("battery.changed", "power.connected", "power.disconnected", "screen.on", "screen.off", "wifi.connected") }) {
            val filter = IntentFilter().apply {
                if ("battery.changed" in events) addAction(Intent.ACTION_BATTERY_CHANGED)
                if ("power.connected" in events) addAction(Intent.ACTION_POWER_CONNECTED)
                if ("power.disconnected" in events) addAction(Intent.ACTION_POWER_DISCONNECTED)
                if ("screen.on" in events) addAction(Intent.ACTION_SCREEN_ON)
                if ("screen.off" in events) addAction(Intent.ACTION_SCREEN_OFF)
                if ("wifi.connected" in events) addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            }
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    when (intent.action) {
                        Intent.ACTION_BATTERY_CHANGED -> {
                            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                            if (level >= 0 && scale > 0) emitEvent("battery.changed", mapOf(
                                "level" to DslValue.Number((level * 100 / scale).toDouble(), true)
                            ))
                        }
                        Intent.ACTION_POWER_CONNECTED -> emitEvent("power.connected")
                        Intent.ACTION_POWER_DISCONNECTED -> emitEvent("power.disconnected")
                        Intent.ACTION_SCREEN_ON -> emitEvent("screen.on")
                        Intent.ACTION_SCREEN_OFF -> emitEvent("screen.off")
                        WifiManager.NETWORK_STATE_CHANGED_ACTION -> {
                            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO, android.net.NetworkInfo::class.java)
                            } else {
                                @Suppress("DEPRECATION")
                                intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO)
                            }
                            if (info?.isConnected == true) {
                                val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                                val ssid = wifi.connectionInfo?.ssid?.removeSurrounding("\"")
                                emitEvent("wifi.connected", ssid?.takeUnless { it == "<unknown ssid>" }
                                    ?.let { mapOf("ssid" to DslValue.Text(it)) } ?: emptyMap())
                            }
                        }
                    }
                }
            }
            registerSystemReceiver(receiver, filter)
            receivers += receiver
        }

        if ("app.foreground" in events) {
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action != AutomationAccessibilityService.ACTION_APP_FOREGROUND) return
                    val packageName = intent.getStringExtra(AutomationAccessibilityService.EXTRA_PACKAGE_NAME) ?: return
                    emitEvent("app.foreground", mapOf("package_name" to DslValue.Text(packageName)))
                }
            }
            ContextCompat.registerReceiver(
                this, receiver, IntentFilter(AutomationAccessibilityService.ACTION_APP_FOREGROUND),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receivers += receiver
        }

        if (events.any { it == "network.connected" || it == "network.disconnected" }) {
            val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val callback = object : ConnectivityManager.NetworkCallback() {
                private var lastTransport: String? = null

                override fun onCapabilitiesChanged(network: android.net.Network, capabilities: android.net.NetworkCapabilities) {
                    val transport = when {
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                        else -> "other"
                    }
                    if (transport == lastTransport) return
                    lastTransport = transport
                    emitEvent("network.connected", mapOf("transport" to DslValue.Text(transport)))
                }

                override fun onLost(network: android.net.Network) {
                    lastTransport = null
                    emitEvent("network.disconnected")
                }
            }
            manager.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        }

        val timedHandlers = flowSnapshot().flatMap { runtime ->
            runtime.program.statements.filterIsInstance<EventStmt>()
                .filter { it.event == "time.every" }
        }
        val parsedIntervals = timedHandlers.mapNotNull { event ->
            val literal = event.filters["minutes"] as? LiteralExpr
            val numericValue = (literal?.value as? Number)?.toDouble() ?: return@mapNotNull null
            val minutes = numericValue.toInt()
            minutes.takeIf { numericValue == it.toDouble() && it in 1..1440 }
        }
        if (parsedIntervals.size != timedHandlers.size) {
            throw DslException("Каждый time.every требует целое число minutes от 1 до 1440")
        }
        val intervals = parsedIntervals.filterNotNull().toSet()
        intervals.forEach { minutes ->
            scheduleJobs += serviceScope.launch {
                while (isActive) {
                    delay(minutes * 60_000L)
                    emitEvent("time.every", mapOf("minutes" to DslValue.Number(minutes.toDouble(), true)))
                }
            }
        }
    }

    private fun emitEvent(event: String, parameters: Map<String, DslValue> = emptyMap()) {
        serviceScope.launch {
            flowSnapshot().forEach { runtime ->
                try {
                    runtime.mutex.withLock {
                        runtime.interpreter.executeEvent(runtime.program, event, parameters)
                    }
                } catch (error: Exception) {
                    updateNotification("Ошибка $event: ${error.message}")
                }
            }
        }
    }

    private fun flowSnapshot(): List<RunningFlow> = synchronized(flows) { flows.values.toList() }

    private fun normalizePhone(value: String): String {
        val digits = value.filter(Char::isDigit)
        return if (value.trimStart().startsWith("+")) "+$digits" else digits
    }

    private fun registerSystemReceiver(receiver: android.content.BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, filter)
        }
    }

    override fun onDestroy() {
        stopPhoneFinder()
        clearTriggers()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Automation", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun notification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Nox Automate").setContentText(text).setOngoing(true).build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
        RunStatus.write(this, text)
    }

    companion object {
        const val EXTRA_SCRIPT = "script"
        const val ACTION_FIND_START = "com.noxautomate.action.FIND_START"
        const val ACTION_FIND_STOP = "com.noxautomate.action.FIND_STOP"
        private const val CHANNEL_ID = "automation"
        private const val NOTIFICATION_ID = 1001
    }
}

private class AndroidDslHost(private val context: Context) : DslHost {
    override fun invoke(function: String, positional: List<DslValue>, named: Map<String, DslValue>): DslValue {
        fun arg(index: Int, name: String): DslValue = named[name] ?: positional.getOrNull(index)
            ?: throw DslException("Не указан аргумент '$name' для $function")
        fun text(value: DslValue): String = (value as? DslValue.Text)?.value ?: throw DslException("Ожидалась строка в $function")
        fun int(value: DslValue): Int = (value as? DslValue.Number)?.value?.toInt() ?: throw DslException("Ожидалось число в $function")
        fun bool(value: DslValue): Boolean = (value as? DslValue.Bool)?.value ?: throw DslException("Ожидался Boolean в $function")
        return when (function) {
            "device.get_battery" -> {
                val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
                DslValue.Number(if (level >= 0 && scale > 0) level * 100 / scale.toDouble() else 0.0, true)
            }
            "device.is_charging" -> {
                val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                    ?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
                DslValue.Bool(status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
            }
            "device.set_brightness" -> {
                if (!Settings.System.canWrite(context)) throw DslException("Нет разрешения WRITE_SETTINGS")
                Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, int(arg(0, "level")).coerceIn(0, 255))
                DslValue.Null
            }
            "device.get_volume" -> {
                val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val stream = when (text(arg(0, "stream")).lowercase()) {
                    "music" -> AudioManager.STREAM_MUSIC; "ring" -> AudioManager.STREAM_RING
                    "alarm" -> AudioManager.STREAM_ALARM; "notification" -> AudioManager.STREAM_NOTIFICATION
                    "call" -> AudioManager.STREAM_VOICE_CALL; "system" -> AudioManager.STREAM_SYSTEM
                    else -> throw DslException("Неизвестный аудиопоток")
                }
                DslValue.Number(manager.getStreamVolume(stream).toDouble(), true)
            }
            "device.set_volume" -> {
                val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val stream = when (text(arg(0, "stream")).lowercase()) {
                    "music" -> AudioManager.STREAM_MUSIC; "ring" -> AudioManager.STREAM_RING
                    "alarm" -> AudioManager.STREAM_ALARM; "notification" -> AudioManager.STREAM_NOTIFICATION
                    "call" -> AudioManager.STREAM_VOICE_CALL; "system" -> AudioManager.STREAM_SYSTEM
                    else -> throw DslException("Неизвестный аудиопоток")
                }
                val max = manager.getStreamMaxVolume(stream)
                manager.setStreamVolume(stream, int(arg(1, "level")).coerceIn(0, max), 0)
                DslValue.Null
            }
            "device.get_ringer_mode" -> {
                val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                DslValue.Text(when (manager.ringerMode) {
                    AudioManager.RINGER_MODE_SILENT -> "silent"
                    AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                    AudioManager.RINGER_MODE_NORMAL -> "normal"
                    else -> "unknown"
                })
            }
            "device.set_ringer_mode" -> {
                val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                when (text(arg(0, "mode")).lowercase()) {
                    "silent" -> manager.ringerMode = AudioManager.RINGER_MODE_SILENT
                    "vibrate" -> manager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
                    "normal" -> manager.ringerMode = AudioManager.RINGER_MODE_NORMAL
                    else -> throw DslException("Неизвестный режим звонка: silent, vibrate, normal")
                }
                DslValue.Null
            }
            "device.vibrate" -> {
                if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.VIBRATE) != PackageManager.PERMISSION_GRANTED) {
                    throw DslException("Для device.vibrate требуется разрешение VIBRATE")
                }
                val duration = int(arg(0, "duration_ms")).coerceAtLeast(0)
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(duration.toLong(), VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(duration.toLong())
                }
                DslValue.Null
            }
            "device.flash_lamp" -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                    throw DslException("device.flash_lamp требует Android 6+")
                }
                val cameraManager = context.getSystemService(CameraManager::class.java)
                val cameraId = cameraManager.cameraIdList.firstOrNull() ?: throw DslException("Фонарик недоступен")
                val enabled = bool(arg(0, "enabled"))
                cameraManager.setTorchMode(cameraId, enabled)
                DslValue.Bool(enabled)
            }
            "device.set_screen_timeout" -> {
                if (!Settings.System.canWrite(context)) throw DslException("Нет разрешения WRITE_SETTINGS")
                val timeout = int(arg(0, "ms")).coerceAtLeast(0)
                Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, timeout)
                DslValue.Null
            }
            "wifi.is_connected" -> {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                DslValue.Bool(cm.activeNetwork?.let { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } == true)
            }
            "wifi.get_ssid" -> {
                if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    throw DslException("Для чтения SSID требуется разрешение ACCESS_FINE_LOCATION")
                }
                val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && !locationManager.isLocationEnabled) {
                    throw DslException("Чтобы прочитать SSID, включите службы геолокации")
                }
                val ssid = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo?.ssid
                DslValue.Text(ssid?.removeSurrounding("\"")?.takeUnless { it == "<unknown ssid>" } ?: "")
            }
            "wifi.set_state" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) throw DslException("Android 10+ запрещает приложению напрямую переключать Wi-Fi")
                @Suppress("DEPRECATION")
                (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled = bool(arg(0, "enabled"))
                DslValue.Null
            }
            "system.notify" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    throw DslException("Для system.notify требуется разрешение POST_NOTIFICATIONS")
                }
                val title = text(arg(0, "title"))
                val body = named["body"] ?: named["message"] ?: positional.getOrNull(1)
                    ?: throw DslException("Не указан аргумент 'body' для system.notify")
                val manager = context.getSystemService(NotificationManager::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel("script") == null) {
                    manager.createNotificationChannel(NotificationChannel("script", "Script notifications", NotificationManager.IMPORTANCE_DEFAULT))
                }
                manager.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(),
                    NotificationCompat.Builder(context, "script").setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle(title).setContentText(text(body)).build())
                DslValue.Null
            }
            "system.toast" -> {
                val message = text(arg(0, "message"))
                android.os.Handler(context.mainLooper).post { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
                DslValue.Null
            }
            "system.sleep" -> {
                val ms = (arg(0, "ms") as? DslValue.Number)?.value?.toLong() ?: throw DslException("Ожидалось число для ms")
                if (ms !in 0..60_000) throw DslException("system.sleep ограничен 60000 мс")
                Thread.sleep(ms)
                DslValue.Null
            }
            "location.get_last_known_coordinates" -> {
                if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
                ) {
                    throw DslException("Для location.get_last_known_coordinates требуется разрешение ACCESS_FINE_LOCATION или ACCESS_COARSE_LOCATION")
                }
                val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                val provider = locationManager.getProviders(true).firstOrNull { it == LocationManager.GPS_PROVIDER }
                    ?: locationManager.getProviders(true).firstOrNull()
                    ?: throw DslException("Нет доступного провайдера геолокации")
                val location = runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
                val latitude = location?.latitude ?: 0.0
                val longitude = location?.longitude ?: 0.0
                val accuracy = location?.accuracy?.toDouble() ?: 0.0
                DslValue.Mapping(mutableMapOf(
                    DslValue.Text("latitude") to DslValue.Number(latitude, latitude % 1.0 == 0.0),
                    DslValue.Text("longitude") to DslValue.Number(longitude, longitude % 1.0 == 0.0),
                    DslValue.Text("accuracy") to DslValue.Number(accuracy, accuracy % 1.0 == 0.0),
                    DslValue.Text("provider") to DslValue.Text(location?.provider ?: "unknown")
                ))
            }
            "location.get_last_known_location" -> invoke("location.get_last_known_coordinates", positional, named)
            "sms.compose" -> {
                val phone = text(arg(0, "phone"))
                val message = text(named["message"] ?: positional.getOrNull(1) ?: DslValue.Text(""))
                val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(phone)}")).apply {
                    putExtra("sms_body", message)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                DslValue.Bool(true)
            }
            "sms.send" -> {
                if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                    throw DslException("Для sms.send требуется разрешение SEND_SMS")
                }
                val phone = text(arg(0, "phone"))
                val message = text(named["message"] ?: positional.getOrNull(1) ?: DslValue.Text(""))
                val smsManager = SmsManager.getDefault()
                smsManager.sendTextMessage(phone, null, message, null, null)
                DslValue.Bool(true)
            }
            "app.launch" -> {
                val packageName = text(arg(0, "package_name"))
                val launch = context.packageManager.getLaunchIntentForPackage(packageName)
                if (launch != null) context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                DslValue.Bool(launch != null)
            }
            "app.is_running" -> {
                val packageName = text(arg(0, "package_name"))
                val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                DslValue.Bool(manager.runningAppProcesses.orEmpty().any { process ->
                    process.processName == packageName || packageName in process.pkgList.orEmpty()
                })
            }
            "accessibility.click_text" -> {
                val service = AutomationAccessibilityService.current()
                    ?: throw DslException("Сначала включите службу Nox Automate в настройках специальных возможностей")
                DslValue.Bool(service.clickText(text(arg(0, "text"))))
            }
            "accessibility.get_active_app" -> {
                val service = AutomationAccessibilityService.current()
                    ?: throw DslException("Сначала включите службу Nox Automate в настройках специальных возможностей")
                DslValue.Text(service.activePackageName.orEmpty())
            }
            "accessibility.find_text" -> {
                val service = AutomationAccessibilityService.current()
                    ?: throw DslException("Сначала включите службу Nox Automate в настройках специальных возможностей")
                DslValue.Bool(service.findText(text(arg(0, "text"))))
            }
            "accessibility.set_text" -> {
                val service = AutomationAccessibilityService.current()
                    ?: throw DslException("Сначала включите службу Nox Automate в настройках специальных возможностей")
                DslValue.Bool(service.setText(text(arg(0, "target")), text(arg(1, "text"))))
            }
            "accessibility.scroll" -> {
                val service = AutomationAccessibilityService.current()
                    ?: throw DslException("Сначала включите службу Nox Automate в настройках специальных возможностей")
                DslValue.Bool(service.scroll(text(arg(0, "direction"))))
            }
            "accessibility.tap" -> {
                val service = AutomationAccessibilityService.current()
                    ?: throw DslException("Сначала включите службу Nox Automate в настройках специальных возможностей")
                DslValue.Bool(service.tap(int(arg(0, "x")), int(arg(1, "y"))))
            }
            "accessibility.swipe" -> {
                val service = AutomationAccessibilityService.current()
                    ?: throw DslException("Сначала включите службу Nox Automate в настройках специальных возможностей")
                val duration = named["duration_ms"]?.let(::int) ?: positional.getOrNull(4)?.let(::int) ?: 400
                DslValue.Bool(service.swipe(
                    int(arg(0, "x1")), int(arg(1, "y1")),
                    int(arg(2, "x2")), int(arg(3, "y2")), duration.toLong()
                ))
            }
            "accessibility.press_back" -> {
                val service = AutomationAccessibilityService.current()
                    ?: throw DslException("Сначала включите службу Nox Automate в настройках специальных возможностей")
                DslValue.Bool(service.pressBack())
            }
            else -> throw DslException("Неизвестная встроенная функция '$function'")
        }
    }

}

class ScriptWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val script = inputData.getString(AutomationForegroundService.EXTRA_SCRIPT)
            ?: return Result.failure(workDataOf("error" to "Не передан текст скрипта")).also {
                RunStatus.write(applicationContext, "Ошибка: не передан текст скрипта")
            }
        return try {
            val program = Parser(Lexer(script).tokenize()).parse()
            if (program.statements.any { it is EventStmt }) {
                val message = "Обработчики событий требуют запуска через foreground service"
                RunStatus.write(applicationContext, "Ошибка: $message")
                return Result.failure(workDataOf("error" to message))
            }
            Interpreter(AndroidDslHost(applicationContext)).execute(program)
            RunStatus.write(applicationContext, "Сценарий выполнен")
            Result.success()
        } catch (error: DslException) {
            RunStatus.write(applicationContext, "Ошибка: ${error.message}")
            Result.failure(workDataOf("error" to error.message))
        } catch (error: Exception) {
            RunStatus.write(applicationContext, "Ошибка выполнения: ${error.message}")
            Result.failure(workDataOf("error" to "Ошибка выполнения: ${error.message}"))
        }
    }
}
