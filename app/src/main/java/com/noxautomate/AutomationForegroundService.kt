package com.noxautomate

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.hardware.camera2.CameraManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.LocationManager
import android.location.Geocoder
import android.media.MediaRecorder
import android.net.Uri
import android.nfc.NfcAdapter
import android.hardware.usb.UsbManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.media.AudioManager
import android.telephony.SmsManager
import android.telephony.SmsMessage
import android.telephony.TelephonyManager
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.widget.Toast
import android.content.pm.PackageManager
import android.bluetooth.BluetoothAdapter
import android.view.KeyEvent
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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

    private data class RunningFlow(val name: String, val program: Program, val interpreter: Interpreter, val mutex: Mutex = Mutex())

    override fun onCreate() {
        super.onCreate()
        isActive = true
        createChannel()
        startForeground(NOTIFICATION_ID, notification("Ожидание скрипта"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        serviceScope.launch {
            reloadMutex.withLock {
                clearTriggers()
                synchronized(flows) { flows.clear() }
                val scripts = AutomationStore.scripts(this@AutomationForegroundService)
                val enabled = AutomationStore.enabledScripts(this@AutomationForegroundService)
                if (enabled.isEmpty()) {
                    stopSelf(startId)
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
                        val runtime = RunningFlow(name, parsed, Interpreter(createDslHost()))
                        runtime.interpreter.execute(parsed)
                        synchronized(flows) { flows[name] = runtime }
                    } catch (error: Exception) {
                        errors += "$name: ${error.message}"
                    }
                }
                if (flowSnapshot().isEmpty()) {
                    updateNotification(errors.joinToString("; ").ifEmpty { "Нет активных сценариев" })
                    stopSelf(startId)
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

    private fun createDslHost(): DslHost = AndroidDslHost(this) { scriptName ->
        requestFlowStart(scriptName)
    }

    private fun requestFlowStart(scriptName: String) {
        serviceScope.launch {
            reloadMutex.withLock {
                if (flowSnapshot().any { it.name == scriptName }) {
                    updateNotification("Сценарий уже запущен: $scriptName")
                    return@withLock
                }
                val enabledBefore = AutomationStore.enabledScripts(this@AutomationForegroundService)
                var addedToFlows = false
                try {
                    val source = AutomationStore.scripts(this@AutomationForegroundService)[scriptName]
                        ?: throw DslException("Сценарий '$scriptName' не найден")
                    val program = withContext(Dispatchers.Default) {
                        Parser(Lexer(source).tokenize()).parse()
                    }
                    validateEvents(program)
                    val runtime = RunningFlow(
                        scriptName,
                        program,
                        Interpreter(createDslHost())
                    )
                    runtime.interpreter.execute(program)
                    if (program.statements.any { it is EventStmt }) {
                        synchronized(flows) { flows[scriptName] = runtime }
                        addedToFlows = true
                        AutomationStore.setEnabledScripts(
                            this@AutomationForegroundService,
                            enabledBefore + scriptName
                        )
                        clearTriggers()
                        registerTriggers()
                    }
                    updateNotification("Запущен сценарий: $scriptName")
                } catch (error: Exception) {
                    var message = error.message
                    if (addedToFlows) {
                        synchronized(flows) { flows.remove(scriptName) }
                        AutomationStore.setEnabledScripts(this@AutomationForegroundService, enabledBefore)
                        clearTriggers()
                        try {
                            registerTriggers()
                        } catch (restoreError: Exception) {
                            message = "$message; не удалось восстановить триггеры: ${restoreError.message}"
                        }
                    }
                    updateNotification("Не удалось запустить '$scriptName': $message")
                }
            }
        }
    }

    private fun validateEvents(program: Program) {
        val handlers = program.statements.filterIsInstance<EventStmt>()
        val supported = setOf(
            "battery.changed", "power.connected", "power.disconnected",
            "screen.on", "screen.off", "wifi.connected", "network.connected",
            "network.disconnected", "time.every", "app.foreground", "sms.received",
            "power.save_mode", "device.idle", "app.broadcast"
        )
        val unknown = handlers.map { it.event }.filterNot(supported::contains).distinct()
        if (unknown.isNotEmpty()) throw DslException("Неизвестные события: ${unknown.joinToString()}")
        handlers.filter { it.event == "app.broadcast" }.forEach { handler ->
            val action = (handler.filters["action"] as? LiteralExpr)?.value as? String
                ?: throw DslException("app.broadcast требует строковый action")
            if (!action.startsWith("$packageName.")) {
                throw DslException("app.broadcast разрешён только для действий с префиксом $packageName.")
            }
        }
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

        val broadcastActions = flowSnapshot()
            .flatMap { it.program.statements.filterIsInstance<EventStmt>() }
            .filter { it.event == "app.broadcast" }
            .mapNotNull { (it.filters["action"] as? LiteralExpr)?.value as? String }
            .toSet()
        if (broadcastActions.isNotEmpty()) {
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val action = intent.action ?: return
                    emitEvent(
                        "app.broadcast",
                        mapOf(
                            "action" to DslValue.Text(action),
                            "message" to DslValue.Text(intent.getStringExtra("message").orEmpty())
                        )
                    )
                }
            }
            ContextCompat.registerReceiver(
                this,
                receiver,
                IntentFilter().apply { broadcastActions.forEach(::addAction) },
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receivers += receiver
        }

        if (events.any { it == "power.save_mode" || it == "device.idle" }) {
            val filter = IntentFilter().apply {
                if ("power.save_mode" in events) addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
                if ("device.idle" in events && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
                }
            }
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    when (intent.action) {
                        PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> emitEvent(
                            "power.save_mode",
                            mapOf("enabled" to DslValue.Bool(getSystemService(PowerManager::class.java).isPowerSaveMode))
                        )
                        PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            emitEvent(
                                "device.idle",
                                mapOf("active" to DslValue.Bool(getSystemService(PowerManager::class.java).isDeviceIdleMode))
                            )
                        }
                    }
                }
            }
            registerSystemReceiver(receiver, filter)
            receivers += receiver
        }

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
        clearTriggers()
        serviceScope.cancel()
        isActive = false
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
        @Volatile
        var isActive: Boolean = false
            private set

        const val EXTRA_SCRIPT = "script"
        private const val CHANNEL_ID = "automation"
        private const val NOTIFICATION_ID = 1001
    }
}

private class AndroidDslHost(
    private val context: Context,
    private val startFlow: ((String) -> Unit)? = null
) : DslHost {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun invoke(function: String, positional: List<DslValue>, named: Map<String, DslValue>): DslValue {
        fun arg(index: Int, name: String): DslValue = named[name] ?: positional.getOrNull(index)
            ?: throw DslException("Не указан аргумент '$name' для $function")
        fun text(value: DslValue): String = (value as? DslValue.Text)?.value ?: throw DslException("Ожидалась строка в $function")
        fun int(value: DslValue): Int = (value as? DslValue.Number)?.value?.toInt() ?: throw DslException("Ожидалось число в $function")
        fun long(value: DslValue): Long = (value as? DslValue.Number)?.value?.toLong() ?: throw DslException("Ожидалось число в $function")
        fun bool(value: DslValue): Boolean = (value as? DslValue.Bool)?.value ?: throw DslException("Ожидался Boolean в $function")
        return when (function) {
            "flow.start" -> {
                val callback = startFlow ?: throw DslException("flow.start доступен только в запущенном foreground service")
                callback(text(arg(0, "name")))
                DslValue.Bool(true)
            }
            "app.is_installed" -> {
                val packageName = text(arg(0, "package_name"))
                DslValue.Bool(runCatching {
                    context.packageManager.getApplicationInfo(packageName, 0)
                }.isSuccess)
            }
            "app.get_foreground" -> DslValue.Text(
                AutomationAccessibilityService.current()?.activePackageName.orEmpty()
            )
            "app.start_service" -> {
                val packageName = text(arg(0, "package_name"))
                val className = text(arg(1, "class_name"))
                val intent = Intent().setComponent(ComponentName(packageName, className))
                val resolved = context.packageManager.resolveService(intent, 0)
                    ?: throw DslException("Служба не найдена или не экспортирована")
                if (!resolved.serviceInfo.exported) throw DslException("Служба приложения не экспортирована")
                context.startService(intent)
                DslValue.Bool(true)
            }
            "app.broadcast" -> {
                val action = text(arg(0, "action"))
                if (!action.startsWith("${context.packageName}.")) {
                    throw DslException("Разрешены только broadcast-действия с префиксом ${context.packageName}.")
                }
                val intent = Intent(action).setPackage(context.packageName)
                named["message"]?.let { intent.putExtra("message", text(it)) }
                context.sendBroadcast(intent)
                DslValue.Null
            }
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
            "device.is_power_save_mode" -> DslValue.Bool(
                context.getSystemService(PowerManager::class.java).isPowerSaveMode
            )
            "device.is_device_idle" -> DslValue.Bool(
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                    context.getSystemService(PowerManager::class.java).isDeviceIdleMode
            )
            "device.is_interactive" -> DslValue.Bool(
                context.getSystemService(PowerManager::class.java).isInteractive
            )
            "device.keep_awake" -> {
                val duration = int(arg(0, "duration_ms"))
                if (duration !in 1..600_000) throw DslException("duration_ms должен быть от 1 до 600000")
                val manager = context.getSystemService(PowerManager::class.java)
                val lock = wakeLock ?: manager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "${context.packageName}:dsl"
                ).also {
                    it.setReferenceCounted(false)
                    wakeLock = it
                }
                if (lock.isHeld) lock.release()
                lock.acquire(duration.toLong())
                DslValue.Null
            }
            "device.release_awake" -> {
                wakeLock?.takeIf { it.isHeld }?.release()
                DslValue.Null
            }
            "device.is_dnd" -> DslValue.Bool(
                context.getSystemService(NotificationManager::class.java).currentInterruptionFilter !=
                    NotificationManager.INTERRUPTION_FILTER_ALL
            )
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
            "media.control" -> {
                val keyCode = when (text(arg(0, "action")).lowercase()) {
                    "play_pause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                    "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
                    "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
                    "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
                    "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                    "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
                    else -> throw DslException("Действие media.control: play_pause, play, pause, next, previous, stop")
                }
                val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val now = System.currentTimeMillis()
                audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
                audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
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
            "location.is_provider_enabled" -> {
                val provider = text(arg(0, "provider")).lowercase()
                if (provider !in setOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
                    throw DslException("provider должен быть gps, network или passive")
                }
                val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                DslValue.Bool(manager.isProviderEnabled(provider))
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
            "network.is_connected" -> {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                DslValue.Bool(cm.activeNetwork?.let { cm.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } == true)
            }
            "network.get_transport" -> {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val capabilities = cm.activeNetwork?.let(cm::getNetworkCapabilities)
                DslValue.Text(
                    when {
                        capabilities == null -> "none"
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "bluetooth"
                        else -> "other"
                    }
                )
            }
            "network.open_settings" -> {
                context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                DslValue.Null
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
            "wifi.open_settings" -> {
                context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                DslValue.Null
            }
            "camera.has_flash" -> DslValue.Bool(
                context.getSystemService(CameraManager::class.java).cameraIdList.any { id ->
                    context.getSystemService(CameraManager::class.java)
                        .getCameraCharacteristics(id)
                        .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                }
            )
            "camera.open" -> {
                val intent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (intent.resolveActivity(context.packageManager) == null) throw DslException("Камера недоступна")
                context.startActivity(intent)
                DslValue.Null
            }
            "audio.record" -> {
                if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    throw DslException("Для audio.record требуется разрешение RECORD_AUDIO")
                }
                val filename = text(arg(0, "filename"))
                val duration = int(arg(1, "duration_ms"))
                if (duration !in 1..60_000) throw DslException("duration_ms должен быть от 1 до 60000")
                val file = appFile(filename)
                file.parentFile?.mkdirs()
                if (!file.createNewFile()) throw DslException("Файл записи уже существует")
                recordAudio(file, duration)
                DslValue.Text(file.absolutePath)
            }
            "sensor.light" -> sensorValue(Sensor.TYPE_LIGHT)
            "sensor.proximity" -> sensorValue(Sensor.TYPE_PROXIMITY)
            "sensor.accelerometer" -> sensorValues(Sensor.TYPE_ACCELEROMETER)
            "sensor.gyroscope" -> sensorValues(Sensor.TYPE_GYROSCOPE)
            "sensor.magnetic_field" -> sensorValues(Sensor.TYPE_MAGNETIC_FIELD)
            "sensor.pressure" -> sensorValue(Sensor.TYPE_PRESSURE)
            "sensor.rotation_vector" -> sensorValues(Sensor.TYPE_ROTATION_VECTOR)
            "sensor.step_counter" -> sensorValue(Sensor.TYPE_STEP_COUNTER)
            "sensor.step_detector" -> sensorValue(Sensor.TYPE_STEP_DETECTOR)
            "clipboard.get" -> {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val value = if (clipboard.hasPrimaryClip()) clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() else null
                value?.let(DslValue::Text) ?: DslValue.Null
            }
            "clipboard.set" -> {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Nox Automate", text(arg(0, "text"))))
                DslValue.Null
            }
            "file.read" -> {
                val file = appFile(text(arg(0, "path")))
                if (!file.exists()) throw DslException("Файл не найден")
                if (file.length() > MAX_SCRIPT_FILE_BYTES.toLong()) throw DslException("Чтение ограничено 1 МиБ")
                DslValue.Text(file.readText(Charsets.UTF_8))
            }
            "file.write" -> {
                val file = appFile(text(arg(0, "path")))
                val content = text(arg(1, "content"))
                if (content.toByteArray(Charsets.UTF_8).size > MAX_SCRIPT_FILE_BYTES) throw DslException("Запись ограничена 1 МиБ")
                file.parentFile?.mkdirs()
                file.writeText(content, Charsets.UTF_8)
                DslValue.Text(file.absolutePath)
            }
            "bluetooth.is_enabled" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
                ) throw DslException("Для bluetooth.is_enabled требуется разрешение BLUETOOTH_CONNECT")
                @Suppress("DEPRECATION")
                DslValue.Bool(BluetoothAdapter.getDefaultAdapter()?.isEnabled == true)
            }
            "bluetooth.open_settings" -> {
                context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                DslValue.Null
            }
            "nfc.is_enabled" -> DslValue.Bool(NfcAdapter.getDefaultAdapter(context)?.isEnabled == true)
            "nfc.open_settings" -> {
                context.startActivity(Intent(Settings.ACTION_NFC_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                DslValue.Null
            }
            "usb.is_connected" -> {
                val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
                DslValue.Bool(manager.deviceList.isNotEmpty())
            }
            "hotspot.open_settings" -> {
                context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                DslValue.Null
            }
            "phone.open_dialer" -> {
                val number = text(arg(0, "phone"))
                context.startActivity(
                    Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(number)}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                DslValue.Null
            }
            "phone.get_call_state" -> {
                if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
                    throw DslException("Для phone.get_call_state требуется разрешение READ_PHONE_STATE")
                }
                val manager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                DslValue.Text(when (manager.callState) {
                    TelephonyManager.CALL_STATE_IDLE -> "idle"
                    TelephonyManager.CALL_STATE_RINGING -> "ringing"
                    TelephonyManager.CALL_STATE_OFFHOOK -> "offhook"
                    else -> "unknown"
                })
            }
            "phone.get_sim_count" -> {
                if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
                    throw DslException("Для phone.get_sim_count требуется разрешение READ_PHONE_STATE")
                }
                @Suppress("DEPRECATION")
                DslValue.Number(
                    (context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager).phoneCount.toDouble(),
                    true
                )
            }
            "contacts.search" -> {
                if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                    throw DslException("Для contacts.search требуется разрешение READ_CONTACTS")
                }
                val query = text(arg(0, "name"))
                val result = mutableListOf<DslValue>()
                try {
                    context.contentResolver.query(
                        ContactsContract.Contacts.CONTENT_URI,
                        arrayOf(
                            ContactsContract.Contacts._ID,
                            ContactsContract.Contacts.DISPLAY_NAME,
                            ContactsContract.Contacts.HAS_PHONE_NUMBER
                        ),
                        "${ContactsContract.Contacts.DISPLAY_NAME} LIKE ?",
                        arrayOf("%$query%"),
                        "${ContactsContract.Contacts.DISPLAY_NAME} ASC"
                    )?.use { cursor ->
                        val idColumn = cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
                        val nameColumn = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME)
                        val phoneColumn = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.HAS_PHONE_NUMBER)
                        while (cursor.moveToNext() && result.size < 50) {
                            val contactId = cursor.getString(idColumn)
                            val name = cursor.getString(nameColumn).orEmpty()
                            val numbers = mutableListOf<String>()
                            if (cursor.getInt(phoneColumn) != 0) {
                                context.contentResolver.query(
                                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                                    arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                                    "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                                    arrayOf(contactId),
                                    null
                                )?.use { phones ->
                                    val numberColumn = phones.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                                    while (phones.moveToNext() && numbers.size < 10) {
                                        numbers += phones.getString(numberColumn).orEmpty()
                                    }
                                }
                            }
                            result += mappingOf(
                                "name" to DslValue.Text(name),
                                "phones" to DslValue.Sequence(numbers.map { DslValue.Text(it) }.toMutableList())
                            )
                        }
                    } ?: throw DslException("Не удалось прочитать контакты")
                } catch (error: SecurityException) {
                    throw DslException("Доступ к контактам запрещён: ${error.message}")
                }
                DslValue.Sequence(result)
            }
            "calendar.events" -> {
                if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
                    throw DslException("Для calendar.events требуется разрешение READ_CALENDAR")
                }
                val from = long(arg(0, "from_ms"))
                val to = long(arg(1, "to_ms"))
                val maximumRange = 366L * 24 * 60 * 60 * 1000
                if (to <= from || from > Long.MAX_VALUE - maximumRange || to > from + maximumRange) {
                    throw DslException("Интервал calendar.events должен быть положительным и не длиннее 366 дней")
                }
                val result = mutableListOf<DslValue>()
                try {
                    context.contentResolver.query(
                        CalendarContract.Events.CONTENT_URI,
                        arrayOf(
                            CalendarContract.Events._ID,
                            CalendarContract.Events.TITLE,
                            CalendarContract.Events.DTSTART,
                            CalendarContract.Events.DTEND
                        ),
                        "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?",
                        arrayOf(from.toString(), to.toString()),
                        "${CalendarContract.Events.DTSTART} ASC"
                    )?.use { cursor ->
                        val titleColumn = cursor.getColumnIndexOrThrow(CalendarContract.Events.TITLE)
                        val startColumn = cursor.getColumnIndexOrThrow(CalendarContract.Events.DTSTART)
                        val endColumn = cursor.getColumnIndexOrThrow(CalendarContract.Events.DTEND)
                        while (cursor.moveToNext() && result.size < 200) {
                            result += mappingOf(
                                "title" to DslValue.Text(cursor.getString(titleColumn).orEmpty()),
                                "start_ms" to DslValue.Number(cursor.getLong(startColumn).toDouble(), true),
                                "end_ms" to DslValue.Number(cursor.getLong(endColumn).toDouble(), true)
                            )
                        }
                    } ?: throw DslException("Не удалось прочитать календарь")
                } catch (error: SecurityException) {
                    throw DslException("Доступ к календарю запрещён: ${error.message}")
                }
                DslValue.Sequence(result)
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
            "time.now" -> DslValue.Number(System.currentTimeMillis() / 1000.0, false)
            "time.get_timezone" -> DslValue.Text(ZoneId.systemDefault().id)
            "time.format" -> {
                val timestamp = (arg(0, "timestamp") as? DslValue.Number)?.value
                    ?: throw DslException("Ожидалось число timestamp (Unix seconds)")
                val pattern = named["pattern"]?.let(::text) ?: "yyyy-MM-dd HH:mm:ss"
                val zone = named["timezone"]?.let(::text)?.let(ZoneId::of) ?: ZoneId.systemDefault()
                val formatter = try {
                    DateTimeFormatter.ofPattern(pattern, Locale.getDefault())
                } catch (error: IllegalArgumentException) {
                    throw DslException("Неверный формат даты: ${error.message}")
                }
                DslValue.Text(formatter.withZone(zone).format(Instant.ofEpochMilli((timestamp * 1000).toLong())))
            }
            "time.is_between" -> {
                val startHour = int(arg(0, "start_hour"))
                val endHour = int(arg(1, "end_hour"))
                if (startHour !in 0..23 || endHour !in 0..23) {
                    throw DslException("Часы должны быть от 0 до 23")
                }
                val hour = java.time.ZonedDateTime.now().hour
                DslValue.Bool(if (startHour <= endHour) hour in startHour until endHour else hour >= startHour || hour < endHour)
            }
            "location.get_last_known_coordinates" -> {
                if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
                ) {
                    throw DslException("Для location.get_last_known_coordinates требуется разрешение ACCESS_FINE_LOCATION или ACCESS_COARSE_LOCATION")
                }
                val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                val location = locationManager.getProviders(true).mapNotNull { provider ->
                    runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
                }.maxByOrNull { it.time } ?: throw DslException("Нет сохранённого местоположения")
                val latitude = location.latitude
                val longitude = location.longitude
                val accuracy = location.accuracy.toDouble()
                DslValue.Mapping(mutableMapOf(
                    DslValue.Text("latitude") to DslValue.Number(latitude, latitude % 1.0 == 0.0),
                    DslValue.Text("longitude") to DslValue.Number(longitude, longitude % 1.0 == 0.0),
                    DslValue.Text("accuracy") to DslValue.Number(accuracy, accuracy % 1.0 == 0.0),
                    DslValue.Text("provider") to DslValue.Text(location.provider.orEmpty()),
                    DslValue.Text("time_ms") to DslValue.Number(location.time.toDouble(), true)
                ))
            }
            "location.get_last_known_location" -> invoke("location.get_last_known_coordinates", positional, named)
            "location.is_enabled" -> {
                val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                val enabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) locationManager.isLocationEnabled
                else {
                    @Suppress("DEPRECATION")
                    locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                        locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                }
                DslValue.Bool(enabled)
            }
            "location.in_radius" -> {
                val latitude = (arg(0, "latitude") as? DslValue.Number)?.value ?: throw DslException("Ожидалось latitude")
                val longitude = (arg(1, "longitude") as? DslValue.Number)?.value ?: throw DslException("Ожидалось longitude")
                val centerLatitude = (arg(2, "center_latitude") as? DslValue.Number)?.value ?: throw DslException("Ожидалось center_latitude")
                val centerLongitude = (arg(3, "center_longitude") as? DslValue.Number)?.value ?: throw DslException("Ожидалось center_longitude")
                val radius = (arg(4, "radius_m") as? DslValue.Number)?.value ?: throw DslException("Ожидалось radius_m")
                if (radius < 0.0) throw DslException("radius_m не может быть отрицательным")
                val results = FloatArray(1)
                android.location.Location.distanceBetween(latitude, longitude, centerLatitude, centerLongitude, results)
                DslValue.Bool(results[0] <= radius)
            }
            "location.reverse_geocode" -> {
                val latitude = (arg(0, "latitude") as? DslValue.Number)?.value ?: throw DslException("Ожидалось latitude")
                val longitude = (arg(1, "longitude") as? DslValue.Number)?.value ?: throw DslException("Ожидалось longitude")
                if (!Geocoder.isPresent()) throw DslException("Геокодер недоступен на этом устройстве")
                @Suppress("DEPRECATION")
                val address = Geocoder(context, Locale.getDefault()).getFromLocation(latitude, longitude, 1)
                    ?.firstOrNull()?.getAddressLine(0)
                    ?: throw DslException("Адрес по координатам не найден")
                DslValue.Text(address)
            }
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

    private fun appFile(path: String): File {
        if (path.isBlank() || File(path).isAbsolute) {
            throw DslException("Путь файла должен быть относительным к закрытому хранилищу приложения")
        }

        val root = context.filesDir.canonicalFile
        val file = File(root, path).canonicalFile
        if (!file.path.startsWith(root.path + File.separator)) {
            throw DslException("Путь выходит за пределы хранилища приложения")
        }
        return file
    }

    private fun mappingOf(vararg values: Pair<String, DslValue>) = DslValue.Mapping(
        values.associateTo(mutableMapOf()) { (key, value) -> DslValue.Text(key) to value }
    )

    private fun recordAudio(file: File, durationMs: Int) {
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
            recorder.setOutputFile(file.absolutePath)
            recorder.prepare()
            recorder.start()
            Thread.sleep(durationMs.toLong())
            recorder.stop()
        } catch (error: Exception) {
            file.delete()
            throw DslException("Не удалось записать аудио: ${error.message}")
        } finally {
            recorder.release()
        }
    }

    private fun sensorValue(type: Int): DslValue {
        val values = sensorValues(type)
        return values.value.firstOrNull() ?: throw DslException("Датчик не вернул значение")
    }

    private fun sensorValues(type: Int): DslValue.Sequence {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = manager.getDefaultSensor(type) ?: throw DslException("Требуемый датчик отсутствует")
        val latch = CountDownLatch(1)
        var sample: FloatArray? = null
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (sample == null) {
                    sample = event.values.copyOf()
                    latch.countDown()
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        try {
            if (!manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)) {
                throw DslException("Не удалось подключить датчик")
            }
            if (!latch.await(SENSOR_SAMPLE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                throw DslException("Датчик не ответил за отведённое время")
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw DslException("Ожидание датчика прервано")
        } finally {
            manager.unregisterListener(listener)
        }
        return DslValue.Sequence(
            (sample ?: FloatArray(0)).map { DslValue.Number(it.toDouble(), false) }.toMutableList()
        )
    }

    private companion object {
        const val MAX_SCRIPT_FILE_BYTES = 1024 * 1024
        const val SENSOR_SAMPLE_TIMEOUT_MS = 2_000L
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
