package com.noxautomate

import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import org.json.JSONObject
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(11, 16, 21)
        window.navigationBarColor = android.graphics.Color.rgb(11, 16, 21)
        window.decorView.systemUiVisibility = 0
        val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results[android.Manifest.permission.RECEIVE_SMS] == true &&
                AutomationStore.enabledScripts(this).isNotEmpty()
            ) {
                refreshAutomationService()
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val required = listOf(
                android.Manifest.permission.POST_NOTIFICATIONS,
                android.Manifest.permission.ACCESS_FINE_LOCATION
            ).filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
            if (required.isNotEmpty()) permissionLauncher.launch(required.toTypedArray())
        } else if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION))
        }
        setContent {
            val preferences = remember { getSharedPreferences("nox_automate", MODE_PRIVATE) }
            val scripts = remember {
                val restored = mutableStateMapOf<String, String>()
                val json = preferences.getString("scripts", null)
                if (json != null) {
                    val saved = JSONObject(json)
                    saved.keys().forEach { key -> restored[key] = saved.getString(key) }
                }
                if (restored.isEmpty()) {
                    restored["Сценарий 1"] =
                        "on battery.changed(threshold=20, direction=\"below\"):\n" +
                            "    system.notify(title=\"Battery low\", body=\"Please charge\")"
                }
                restored
            }
            var selectedScript by remember {
                mutableStateOf(preferences.getString("selected_script", null)?.takeIf { it in scripts } ?: scripts.keys.first())
            }
            val enabledScripts = remember {
                mutableStateListOf<String>().also { it.addAll(AutomationStore.enabledScripts(this@MainActivity)) }
            }
            var nextScriptId by remember { mutableIntStateOf(scripts.size + 1) }
            var runStatus by remember { mutableStateOf(RunStatus.read(this@MainActivity)) }
            var smsNumber by remember { mutableStateOf("") }
            var autoStartEnabled by remember { mutableStateOf(AutomationStore.autoStartEnabled(this@MainActivity)) }
            var allowedSmsSender by remember { mutableStateOf(AutomationStore.smsAllowedSender(this@MainActivity)) }
            val activityScope = rememberCoroutineScope()
            val script = scripts[selectedScript].orEmpty()
            LaunchedEffect(scripts.toMap()) {
                AutomationStore.saveScripts(this@MainActivity, scripts.toMap())
            }
            LaunchedEffect(selectedScript) {
                preferences.edit().putString("selected_script", selectedScript).apply()
            }
            DisposableEffect(Unit) {
                val receiver = object : android.content.BroadcastReceiver() {
                    override fun onReceive(context: android.content.Context, intent: Intent) {
                        runStatus = RunStatus.read(context)
                    }
                }
                ContextCompat.registerReceiver(
                    this@MainActivity, receiver, IntentFilter(RunStatus.ACTION_UPDATED),
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
                onDispose { unregisterReceiver(receiver) }
            }
            var selectedTab by remember { mutableStateOf(MainTab.SCRIPTS) }
            NoxTheme {
                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    bottomBar = {
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                            MainTab.entries.forEach { tab ->
                                NavigationBarItem(
                                    selected = selectedTab == tab,
                                    onClick = { selectedTab = tab },
                                    icon = { Text(tab.shortLabel) },
                                    label = { Text(tab.label) }
                                )
                            }
                        }
                    }
                ) { contentPadding ->
                    Column(
                        Modifier.fillMaxSize().padding(contentPadding).padding(horizontal = 18.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("NOX AUTOMATE", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            Text(tabTitle(selectedTab), style = MaterialTheme.typography.headlineSmall)
                        }
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                            Text(
                                text = runStatus,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        when (selectedTab) {
                            MainTab.SCRIPTS -> ScriptsScreen(
                                scripts = scripts,
                                enabledScripts = enabledScripts,
                                selectedScript = selectedScript,
                                onSelect = {
                                    selectedScript = it
                                    selectedTab = MainTab.EDITOR
                                },
                                onToggle = { name -> toggleScript(name, enabledScripts, scripts) },
                                onAdd = {
                                    while ("Сценарий $nextScriptId" in scripts) nextScriptId++
                                    val name = "Сценарий ${nextScriptId++}"
                                    scripts[name] = ""
                                    selectedScript = name
                                    selectedTab = MainTab.EDITOR
                                },
                                onDelete = { name ->
                                    if (scripts.size > 1) {
                                        scripts.remove(name)
                                        enabledScripts.remove(name)
                                        AutomationStore.setEnabledScripts(this@MainActivity, enabledScripts.toSet())
                                        AutomationStore.saveScripts(this@MainActivity, scripts.toMap())
                                        if (selectedScript == name) selectedScript = scripts.keys.first()
                                        if (enabledScripts.isEmpty()) {
                                            stopService(Intent(this@MainActivity, AutomationForegroundService::class.java))
                                        } else {
                                            refreshAutomationService()
                                        }
                                    }
                                }
                            )
                            MainTab.EDITOR -> EditorScreen(
                                scripts = scripts,
                                selectedScript = selectedScript,
                                onSelect = { selectedScript = it },
                                onScriptChange = {
                                    scripts[selectedScript] = it
                                    AutomationStore.saveScripts(this@MainActivity, scripts.toMap())
                                },
                                onRun = {
                                    try {
                                        AutomationStore.saveScripts(this@MainActivity, scripts.toMap())
                                        if (selectedScript !in enabledScripts) enabledScripts.add(selectedScript)
                                        AutomationStore.setEnabledScripts(this@MainActivity, enabledScripts.toSet())
                                        startForegroundService(Intent(this@MainActivity, AutomationForegroundService::class.java))
                                    } catch (error: Exception) {
                                        RunStatus.write(this@MainActivity, "Ошибка запуска сервиса: ${error.message}")
                                    }
                                },
                                onStop = {
                                    enabledScripts.clear()
                                    AutomationStore.setEnabledScripts(this@MainActivity, emptySet())
                                    stopService(Intent(this@MainActivity, AutomationForegroundService::class.java))
                                    RunStatus.write(this@MainActivity, "Сервис остановлен")
                                },
                                onRunOnce = {
                                    val request = OneTimeWorkRequestBuilder<ScriptWorker>()
                                        .setInputData(workDataOf(AutomationForegroundService.EXTRA_SCRIPT to script))
                                        .build()
                                    WorkManager.getInstance(this@MainActivity).enqueue(request)
                                    RunStatus.write(this@MainActivity, "Разовый запуск поставлен в очередь")
                                },
                                onDelete = {
                                    if (scripts.size > 1) {
                                        scripts.remove(selectedScript)
                                        enabledScripts.remove(selectedScript)
                                        AutomationStore.setEnabledScripts(this@MainActivity, enabledScripts.toSet())
                                        AutomationStore.saveScripts(this@MainActivity, scripts.toMap())
                                        selectedScript = scripts.keys.first()
                                        if (enabledScripts.isEmpty()) {
                                            stopService(Intent(this@MainActivity, AutomationForegroundService::class.java))
                                        } else {
                                            refreshAutomationService()
                                        }
                                    }
                                }
                            )
                            MainTab.DEVICE -> DeviceScreen(
                                smsNumber = smsNumber,
                                onSmsNumberChange = { smsNumber = it },
                                onFind = {
                                    val missing = buildList {
                                        if (ContextCompat.checkSelfPermission(this@MainActivity, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                                            add(android.Manifest.permission.CAMERA)
                                        }
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                            ContextCompat.checkSelfPermission(this@MainActivity, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                                        ) {
                                            add(android.Manifest.permission.POST_NOTIFICATIONS)
                                        }
                                    }
                                    if (missing.isNotEmpty()) {
                                        permissionLauncher.launch(missing.toTypedArray())
                                        RunStatus.write(this@MainActivity, "Разреши запрошенные права и нажми «Найти телефон» ещё раз")
                                    } else {
                                        startForegroundService(
                                            Intent(this@MainActivity, AutomationForegroundService::class.java)
                                                .setAction(AutomationForegroundService.ACTION_FIND_START)
                                        )
                                    }
                                },
                                onStopFind = {
                                    startForegroundService(
                                        Intent(this@MainActivity, AutomationForegroundService::class.java)
                                            .setAction(AutomationForegroundService.ACTION_FIND_STOP)
                                    )
                                },
                                onShareLocation = {
                                    if (smsNumber.isBlank()) {
                                        RunStatus.write(this@MainActivity, "Введи номер телефона для SMS")
                                    } else {
                                        activityScope.launch {
                                            try {
                                                val location = LocationSharing.currentLocation(this@MainActivity)
                                                if (location == null) {
                                                    RunStatus.write(this@MainActivity, "Не удалось определить геопозицию")
                                                } else {
                                                    val message = "Мои координаты: https://maps.google.com/?q=${location.latitude},${location.longitude}"
                                                    val sms = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(smsNumber)}"))
                                                        .putExtra("sms_body", message)
                                                    if (sms.resolveActivity(packageManager) == null) {
                                                        RunStatus.write(this@MainActivity, "На устройстве нет приложения для SMS")
                                                    } else {
                                                        startActivity(Intent.createChooser(sms, "Отправить координаты по SMS"))
                                                    }
                                                }
                                            } catch (error: Exception) {
                                                RunStatus.write(this@MainActivity, "Не удалось получить координаты: ${error.message}")
                                            }
                                        }
                                    }
                                }
                            )
                            MainTab.SETTINGS -> SettingsScreen(
                                autoStartEnabled = autoStartEnabled,
                                allowedSmsSender = allowedSmsSender,
                                onAutoStartChange = {
                                    autoStartEnabled = it
                                    AutomationStore.setAutoStartEnabled(this@MainActivity, it)
                                },
                                onAllowedSenderChange = {
                                    allowedSmsSender = it
                                    AutomationStore.setSmsAllowedSender(this@MainActivity, it)
                                },
                                onRequestSmsPermission = {
                                    if (ContextCompat.checkSelfPermission(this@MainActivity, android.Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED) {
                                        if (enabledScripts.isEmpty()) {
                                            RunStatus.write(this@MainActivity, "Сначала включи сценарий с on sms.received")
                                        } else {
                                            refreshAutomationService()
                                            RunStatus.write(this@MainActivity, "Обработка входящих SMS включена")
                                        }
                                    } else {
                                        permissionLauncher.launch(arrayOf(android.Manifest.permission.RECEIVE_SMS))
                                    }
                                },
                                onWriteSettings = {
                                    startActivity(
                                        Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
                                            .setData(Uri.parse("package:$packageName"))
                                    )
                                },
                                onAccessibility = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                                onBatterySettings = {
                                    try {
                                        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                                    } catch (error: Exception) {
                                        RunStatus.write(this@MainActivity, "Не удалось открыть настройки батареи: ${error.message}")
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun refreshAutomationService() {
        try {
            startForegroundService(Intent(this, AutomationForegroundService::class.java))
        } catch (error: Exception) {
            RunStatus.write(this, "Ошибка запуска сервиса: ${error.message}")
        }
    }

    private fun toggleScript(name: String, enabledScripts: MutableList<String>, scripts: Map<String, String>) {
        if (name in enabledScripts) enabledScripts.remove(name) else enabledScripts.add(name)
        AutomationStore.setEnabledScripts(this, enabledScripts.toSet())
        AutomationStore.saveScripts(this, scripts)
        if (enabledScripts.isEmpty()) {
            stopService(Intent(this, AutomationForegroundService::class.java))
            RunStatus.write(this, "Все сценарии автоматизации остановлены")
        } else {
            refreshAutomationService()
            RunStatus.write(this, "Активные сценарии обновлены")
        }
    }
}

private enum class MainTab(val label: String, val shortLabel: String) {
    SCRIPTS("Сценарии", "●"),
    EDITOR("Редактор", "✎"),
    DEVICE("Устройство", "◉"),
    SETTINGS("Настройки", "⚙")
}

private fun tabTitle(tab: MainTab): String = when (tab) {
    MainTab.SCRIPTS -> "Мои сценарии"
    MainTab.EDITOR -> "Редактор кода"
    MainTab.DEVICE -> "Инструменты"
    MainTab.SETTINGS -> "Настройки"
}

@Composable
private fun NoxTheme(content: @Composable () -> Unit) {
    val colors = darkColorScheme(
        primary = Color(0xFF80CBC4),
        onPrimary = Color(0xFF06201D),
        secondary = Color(0xFF82B1FF),
        background = Color(0xFF0B1015),
        surface = Color(0xFF111820),
        surfaceVariant = Color(0xFF1B2731),
        onSurface = Color(0xFFE7EDF2),
        onSurfaceVariant = Color(0xFFB5C2CC),
        outline = Color(0xFF45545F)
    )
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
private fun ColumnScope.ScriptsScreen(
    scripts: Map<String, String>,
    enabledScripts: List<String>,
    selectedScript: String,
    onSelect: (String) -> Unit,
    onToggle: (String) -> Unit,
    onAdd: () -> Unit,
    onDelete: (String) -> Unit
) {
    Column(
        Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "${scripts.size} сценариев · ${enabledScripts.size} активно",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Text("＋  Новый сценарий") }
        scripts.forEach { (name, source) ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (name == selectedScript) MaterialTheme.colorScheme.surfaceVariant
                    else MaterialTheme.colorScheme.surface
                ),
                onClick = { onSelect(name) }
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (name in enabledScripts) "Работает в фоне" else "Отключён",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = name in enabledScripts, onCheckedChange = { onToggle(name) })
                    }
                    Text(
                        source.lineSequence().firstOrNull()?.take(72)?.ifBlank { "Пустой сценарий" }
                            ?: "Пустой сценарий",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { onSelect(name) }) { Text("Редактировать") }
                        if (scripts.size > 1) {
                            OutlinedButton(onClick = { onDelete(name) }) { Text("Удалить") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.EditorScreen(
    scripts: Map<String, String>,
    selectedScript: String,
    onSelect: (String) -> Unit,
    onScriptChange: (String) -> Unit,
    onRun: () -> Unit,
    onStop: () -> Unit,
    onRunOnce: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        BoxDropdown(
            selectedScript = selectedScript,
            scriptNames = scripts.keys.toList(),
            expanded = menuExpanded,
            onExpandedChange = { menuExpanded = it },
            onSelect = {
                onSelect(it)
                menuExpanded = false
            }
        )
        OutlinedTextField(
            value = scripts[selectedScript].orEmpty(),
            onValueChange = onScriptChange,
            modifier = Modifier.fillMaxWidth().height(360.dp),
            label = { Text("Код сценария") },
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onRun, modifier = Modifier.weight(1f)) { Text("Запустить") }
            OutlinedButton(onClick = onStop, modifier = Modifier.weight(1f)) { Text("Остановить всё") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(onClick = onRunOnce, modifier = Modifier.weight(1f)) { Text("Один раз") }
            if (scripts.size > 1) {
                OutlinedButton(onClick = onDelete, modifier = Modifier.weight(1f)) { Text("Удалить") }
            }
        }
        Text(
            "«Запустить» сохраняет сценарий и включает его автоматическое выполнение. " +
                "Для событийных сценариев оставь сервис работающим.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun BoxDropdown(
    selectedScript: String,
    scriptNames: List<String>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (String) -> Unit
) {
    Column {
        OutlinedButton(onClick = { onExpandedChange(true) }, modifier = Modifier.fillMaxWidth()) {
            Text("Сценарий: $selectedScript")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
            scriptNames.forEach { name ->
                DropdownMenuItem(text = { Text(name) }, onClick = { onSelect(name) })
            }
        }
    }
}

@Composable
private fun ColumnScope.DeviceScreen(
    smsNumber: String,
    onSmsNumberChange: (String) -> Unit,
    onFind: () -> Unit,
    onStopFind: () -> Unit,
    onShareLocation: () -> Unit
) {
    Column(
        Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        FeatureCard(
            title = "Найти телефон",
            description = "Включает звуковой сигнал и мигает фонариком, пока не остановишь поиск."
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onFind, modifier = Modifier.weight(1f)) { Text("Начать поиск") }
                OutlinedButton(onClick = onStopFind, modifier = Modifier.weight(1f)) { Text("Остановить") }
            }
        }
        FeatureCard(
            title = "Поделиться местоположением",
            description = "Откроет SMS с координатами. Перед отправкой сообщение можно проверить."
        ) {
            OutlinedTextField(
                value = smsNumber,
                onValueChange = onSmsNumberChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Номер телефона") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                singleLine = true
            )
            Button(onClick = onShareLocation, modifier = Modifier.fillMaxWidth()) {
                Text("Подготовить SMS с координатами")
            }
        }
    }
}

@Composable
private fun ColumnScope.SettingsScreen(
    autoStartEnabled: Boolean,
    allowedSmsSender: String,
    onAutoStartChange: (Boolean) -> Unit,
    onAllowedSenderChange: (String) -> Unit,
    onRequestSmsPermission: () -> Unit,
    onWriteSettings: () -> Unit,
    onAccessibility: () -> Unit,
    onBatterySettings: () -> Unit
) {
    Column(
        Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        FeatureCard(
            title = "Фоновая работа",
            description = "Foreground service показывает постоянное уведомление. Ограничения фоновой работы также зависят от прошивки."
        ) {
            SettingSwitch(
                title = "Автозапуск после перезагрузки",
                supporting = "Запускаются только сценарии, отмеченные «Авто».",
                checked = autoStartEnabled,
                onCheckedChange = onAutoStartChange
            )
            OutlinedButton(onClick = onBatterySettings, modifier = Modifier.fillMaxWidth()) {
                Text("Параметры батареи")
            }
        }
        FeatureCard(
            title = "Входящие SMS",
            description = "Событие sms.received передаёт отправителя и текст в переменные сценария."
        ) {
            OutlinedTextField(
                value = allowedSmsSender,
                onValueChange = onAllowedSenderChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Фильтр по номеру (необязательно)") },
                supportingText = {
                    Text("Пусто — сообщения от всех номеров; задан номер — только от него.")
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                singleLine = true
            )
            Button(onClick = onRequestSmsPermission, modifier = Modifier.fillMaxWidth()) {
                Text("Разрешить обработку SMS")
            }
        }
        FeatureCard(
            title = "Доступы Android",
            description = "Некоторым действиям нужны специальные разрешения системы."
        ) {
            OutlinedButton(onClick = onWriteSettings, modifier = Modifier.fillMaxWidth()) {
                Text("Доступ к системным настройкам")
            }
            OutlinedButton(onClick = onAccessibility, modifier = Modifier.fillMaxWidth()) {
                Text("Специальные возможности")
            }
        }
        Text(
            "Свайп приложения из списка недавних обычно не останавливает сервис. " +
                "Кнопка «Принудительно остановить» в системных настройках остановит его до следующего ручного запуска.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun FeatureCard(
    title: String,
    description: String,
    content: @Composable () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            content()
        }
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    supporting: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
