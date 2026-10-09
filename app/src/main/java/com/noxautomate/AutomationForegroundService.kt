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
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.media.AudioManager
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
    private val interpreterMutex = Mutex()
    private var program: Program? = null
    private var interpreter: Interpreter? = null
    private var batteryReceiver: android.content.BroadcastReceiver? = null
    private var wifiReceiver: android.content.BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, notification("Ожидание скрипта"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val preferences = getSharedPreferences("nox_automate", Context.MODE_PRIVATE)
        val script = intent?.getStringExtra(EXTRA_SCRIPT) ?: preferences.getString(EXTRA_ACTIVE_SCRIPT, null)
        if (script == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        preferences.edit().putString(EXTRA_ACTIVE_SCRIPT, script).apply()
        clearTriggers()
        serviceScope.launch {
            try {
                val parsed = withContext(Dispatchers.Default) { Parser(Lexer(script).tokenize()).parse() }
                val unsupportedEvents = parsed.statements.filterIsInstance<EventStmt>()
                    .map { it.event }.filterNot { it == "battery.changed" || it == "wifi.connected" }.distinct()
                if (unsupportedEvents.isNotEmpty()) {
                    throw DslException("Неизвестные события: ${unsupportedEvents.joinToString()}")
                }
                validateTriggerPermissions(parsed)
                program = parsed
                interpreter = Interpreter(AndroidDslHost(this@AutomationForegroundService))
                interpreter?.execute(parsed)
                registerTriggers(parsed)
                updateNotification("Скрипт запущен")
            } catch (error: DslException) {
                updateNotification("Ошибка: ${error.message}")
                stopSelf(startId)
            } catch (error: Exception) {
                updateNotification("Ошибка выполнения: ${error.message}")
                stopSelf(startId)
            }
        }
        return START_STICKY
    }

    private fun clearTriggers() {
        batteryReceiver?.let { unregisterReceiver(it) }
        wifiReceiver?.let { unregisterReceiver(it) }
        batteryReceiver = null
        wifiReceiver = null
    }

    private fun registerTriggers(parsed: Program) {
        val handlers = parsed.statements.filterIsInstance<EventStmt>()
        if (handlers.any { it.event == "battery.changed" }) {
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action != Intent.ACTION_BATTERY_CHANGED) return
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                    val percent = if (level >= 0 && scale > 0) level * 100 / scale else return
                    serviceScope.launch {
                        try {
                            interpreterMutex.withLock {
                                interpreter?.executeEvent(parsed, "battery.changed", mapOf(
                                    "level" to DslValue.Number(percent.toDouble(), true)
                                ))
                            }
                        } catch (error: Exception) { updateNotification("Ошибка события: ${error.message}") }
                    }
                }
            }
            registerSystemReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            batteryReceiver = receiver
        }
        if (handlers.any { it.event == "wifi.connected" }) {
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action != WifiManager.NETWORK_STATE_CHANGED_ACTION) return
                    val info = intent.getParcelableExtra<android.net.NetworkInfo>(WifiManager.EXTRA_NETWORK_INFO)
                    if (info?.isConnected != true) return
                    val ssid = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                        .connectionInfo?.ssid?.removeSurrounding("\"") ?: return
                    serviceScope.launch {
                        try {
                            interpreterMutex.withLock {
                                interpreter?.executeEvent(parsed, "wifi.connected", mapOf("ssid" to DslValue.Text(ssid)))
                            }
                        } catch (error: Exception) { updateNotification("Ошибка события: ${error.message}") }
                    }
                }
            }
            registerSystemReceiver(receiver, IntentFilter(WifiManager.NETWORK_STATE_CHANGED_ACTION))
            wifiReceiver = receiver
        }
    }

    private fun validateTriggerPermissions(parsed: Program) {
        val handlers = parsed.statements.filterIsInstance<EventStmt>()
        if (handlers.any { it.event == "wifi.connected" } &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            throw DslException("Для обработчика wifi.connected требуется разрешение ACCESS_FINE_LOCATION")
        }
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
        getSharedPreferences("nox_automate", Context.MODE_PRIVATE).edit().remove(EXTRA_ACTIVE_SCRIPT).apply()
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
        private const val EXTRA_ACTIVE_SCRIPT = "active_script"
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
            "device.set_volume" -> {
                val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val stream = when (text(arg(0, "stream")).lowercase()) {
                    "music" -> AudioManager.STREAM_MUSIC; "ring" -> AudioManager.STREAM_RING
                    "alarm" -> AudioManager.STREAM_ALARM; "notification" -> AudioManager.STREAM_NOTIFICATION
                    else -> throw DslException("Неизвестный аудиопоток")
                }
                val max = manager.getStreamMaxVolume(stream)
                manager.setStreamVolume(stream, int(arg(1, "level")).coerceIn(0, max), 0)
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
            "app.launch" -> {
                val launch = context.packageManager.getLaunchIntentForPackage(text(arg(0, "package_name")))
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
