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
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(17, 17, 17)
        window.navigationBarColor = android.graphics.Color.rgb(17, 17, 17)
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
                } else {
                    restored["Сценарий 1"] =
                        "on battery.changed(threshold=20, direction=\"below\"):\n" +
                            "    system.notify(title=\"Battery low\", body=\"Please charge\")"
                }
                restored
            }
            var selectedScript by remember {
                mutableStateOf(preferences.getString("selected_script", null)?.takeIf { it in scripts } ?: scripts.keys.firstOrNull().orEmpty())
            }
            val enabledScripts = remember {
                mutableStateListOf<String>().also { it.addAll(AutomationStore.enabledScripts(this@MainActivity)) }
            }
            var nextScriptId by remember { mutableIntStateOf(scripts.size + 1) }
            var runStatus by remember { mutableStateOf(RunStatus.read(this@MainActivity)) }
            val runLogs = remember { mutableStateListOf<String>().also { it.addAll(RunStatus.readLogs(this@MainActivity)) } }
            var autoStartEnabled by remember { mutableStateOf(AutomationStore.autoStartEnabled(this@MainActivity)) }
            var allowedSmsSender by remember { mutableStateOf(AutomationStore.smsAllowedSender(this@MainActivity)) }
            var detailVisible by remember { mutableStateOf(false) }
            var editorVisible by remember { mutableStateOf(false) }
            val script = scripts[selectedScript].orEmpty()
            LaunchedEffect(scripts.toMap()) {
                AutomationStore.saveScripts(this@MainActivity, scripts.toMap())
            }
            LaunchedEffect(selectedScript) {
                if (selectedScript in scripts) preferences.edit().putString("selected_script", selectedScript).apply()
            }
            LaunchedEffect(Unit) {
                if (enabledScripts.isNotEmpty() && !AutomationForegroundService.isActive) {
                    refreshAutomationService()
                }
            }
            DisposableEffect(Unit) {
                val receiver = object : android.content.BroadcastReceiver() {
                    override fun onReceive(context: android.content.Context, intent: Intent) {
                        runStatus = RunStatus.read(context)
                        runLogs.clear()
                        runLogs.addAll(RunStatus.readLogs(context))
                    }
                }
                ContextCompat.registerReceiver(
                    this@MainActivity, receiver, IntentFilter(RunStatus.ACTION_UPDATED),
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
                onDispose { unregisterReceiver(receiver) }
            }
            var selectedTab by remember { mutableStateOf(MainTab.SCRIPTS) }
            BackHandler(enabled = editorVisible || detailVisible) {
                if (editorVisible) editorVisible = false else detailVisible = false
            }
            NoxTheme {
                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    bottomBar = {
                        if (!detailVisible && !editorVisible) {
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
                    }
                ) { contentPadding ->
                    when {
                        editorVisible -> ScriptEditorScreen(
                            scriptName = selectedScript,
                            source = script,
                            contentPadding = contentPadding,
                            onBack = { editorVisible = false },
                            onSave = { updatedSource ->
                                scripts[selectedScript] = updatedSource
                                AutomationStore.saveScripts(this@MainActivity, scripts.toMap())
                                if (selectedScript in enabledScripts) refreshAutomationService()
                                RunStatus.write(this@MainActivity, "Сценарий «$selectedScript» сохранён")
                            },
                            onDelete = {
                                deleteScript(selectedScript, scripts, enabledScripts)
                                selectedScript = scripts.keys.firstOrNull().orEmpty()
                                editorVisible = false
                                detailVisible = false
                            }
                        )
                        detailVisible -> ScriptDetailScreen(
                            scriptName = selectedScript,
                            isRunning = selectedScript in enabledScripts,
                            logs = runLogs,
                            contentPadding = contentPadding,
                            onBack = { detailVisible = false },
                            onRun = { setScriptEnabled(selectedScript, true, enabledScripts, scripts) },
                            onStop = { setScriptEnabled(selectedScript, false, enabledScripts, scripts) },
                            onEdit = { editorVisible = true },
                            onDelete = {
                                deleteScript(selectedScript, scripts, enabledScripts)
                                selectedScript = scripts.keys.firstOrNull().orEmpty()
                                detailVisible = false
                            }
                        )
                        else -> Column(
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
                                    onSelect = {
                                        selectedScript = it
                                        detailVisible = true
                                    },
                                    onAdd = {
                                        while ("Сценарий $nextScriptId" in scripts) nextScriptId++
                                        val name = "Сценарий ${nextScriptId++}"
                                        scripts[name] = ""
                                        selectedScript = name
                                        editorVisible = true
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
                                onRequestDevicePermissions = {
                                    val requested = buildList {
                                        add(android.Manifest.permission.RECORD_AUDIO)
                                        add(android.Manifest.permission.READ_PHONE_STATE)
                                        add(android.Manifest.permission.READ_CONTACTS)
                                        add(android.Manifest.permission.READ_CALENDAR)
                                        add(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                            add(android.Manifest.permission.BLUETOOTH_CONNECT)
                                        }
                                    }.filter {
                                        ContextCompat.checkSelfPermission(this@MainActivity, it) != PackageManager.PERMISSION_GRANTED
                                    }
                                    if (requested.isEmpty()) {
                                        RunStatus.write(this@MainActivity, "Все запрошенные разрешения уже выданы")
                                    } else {
                                        permissionLauncher.launch(requested.toTypedArray())
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
    }

    private fun refreshAutomationService() {
        try {
            startForegroundService(Intent(this, AutomationForegroundService::class.java))
        } catch (error: Exception) {
            RunStatus.write(this, "Ошибка запуска сервиса: ${error.message}")
        }
    }

    private fun setScriptEnabled(
        name: String,
        enabled: Boolean,
        enabledScripts: MutableList<String>,
        scripts: Map<String, String>
    ) {
        if (name !in scripts) return
        if (enabled) {
            if (name !in enabledScripts) enabledScripts.add(name)
        } else {
            enabledScripts.remove(name)
        }
        AutomationStore.setEnabledScripts(this, enabledScripts.toSet())
        AutomationStore.saveScripts(this, scripts)
        if (enabledScripts.isEmpty()) {
            stopService(Intent(this, AutomationForegroundService::class.java))
            RunStatus.write(this, "Сценарий «$name» остановлен")
        } else {
            refreshAutomationService()
            RunStatus.write(this, if (enabled) "Сценарий «$name» запущен" else "Сценарий «$name» остановлен")
        }
    }

    private fun deleteScript(
        name: String,
        scripts: MutableMap<String, String>,
        enabledScripts: MutableList<String>
    ) {
        if (scripts.remove(name) == null) return
        enabledScripts.remove(name)
        AutomationStore.setEnabledScripts(this, enabledScripts.toSet())
        AutomationStore.saveScripts(this, scripts.toMap())
        if (enabledScripts.isEmpty()) {
            stopService(Intent(this, AutomationForegroundService::class.java))
        } else {
            refreshAutomationService()
        }
        RunStatus.write(this, "Сценарий «$name» удалён")
    }
}

private enum class MainTab(val label: String, val shortLabel: String) {
    SCRIPTS("Сценарии", "●"),
    SETTINGS("Настройки", "⚙")
}

private fun tabTitle(tab: MainTab): String = when (tab) {
    MainTab.SCRIPTS -> "Мои сценарии"
    MainTab.SETTINGS -> "Настройки"
}

@Composable
private fun NoxTheme(content: @Composable () -> Unit) {
    val colors = darkColorScheme(
        primary = Color(0xFF999999),
        onPrimary = Color(0xFF111111),
        secondary = Color(0xFF666666),
        background = Color(0xFF111111),
        surface = Color(0xFF1A1A1A),
        surfaceVariant = Color(0xFF202020),
        onSurface = Color(0xFFE0E0E0),
        onSurfaceVariant = Color(0xFF888888),
        outline = Color(0xFF333333),
        tertiary = Color(0xFF555555)
    )
    val shapes = Shapes(
        small = RoundedCornerShape(8.dp),
        medium = RoundedCornerShape(10.dp),
        large = RoundedCornerShape(10.dp)
    )
    MaterialTheme(colorScheme = colors, shapes = shapes, content = content)
}

@Composable
private fun ColumnScope.ScriptsScreen(
    scripts: Map<String, String>,
    enabledScripts: List<String>,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit
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
        if (scripts.isEmpty()) {
            Text(
                "Пока нет сценариев. Создай новый, чтобы начать автоматизацию.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        scripts.forEach { (name, source) ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                onClick = { onSelect(name) }
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (name in enabledScripts) "Запущен" else "Остановлен",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text("›", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        source.lineSequence().firstOrNull()?.take(90)?.ifBlank { "Пустой сценарий" }
                            ?: "Пустой сценарий",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2
                    )
                }
            }
        }
    }
}

@Composable
private fun ScriptDetailScreen(
    scriptName: String,
    isRunning: Boolean,
    logs: List<String>,
    contentPadding: androidx.compose.foundation.layout.PaddingValues,
    onBack: () -> Unit,
    onRun: () -> Unit,
    onStop: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(contentPadding).padding(horizontal = 18.dp, vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("←") }
                Text(scriptName, style = MaterialTheme.typography.titleMedium)
            }
            BoxMenuButton(
                expanded = menuExpanded,
                onExpandedChange = { menuExpanded = it },
                onDelete = {
                    menuExpanded = false
                    onDelete()
                },
                saveLabel = null
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(onClick = onRun, modifier = Modifier.weight(1f), enabled = !isRunning) {
                Text("Запустить")
            }
            OutlinedButton(onClick = onStop, modifier = Modifier.weight(1f), enabled = isRunning) {
                Text("Остановить")
            }
        }
        Text("Логи", style = MaterialTheme.typography.titleMedium)
        Column(
            Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            logs.asReversed().forEach { line ->
                Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Button(onClick = onEdit, modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp)) {
            Text("Редактировать")
        }
    }
}

@Composable
private fun ScriptEditorScreen(
    scriptName: String,
    source: String,
    contentPadding: androidx.compose.foundation.layout.PaddingValues,
    onBack: () -> Unit,
    onSave: (String) -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var draft by remember(scriptName) { mutableStateOf(source) }
    Column(Modifier.fillMaxSize().padding(contentPadding).padding(horizontal = 18.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("←") }
                Text(scriptName, style = MaterialTheme.typography.titleMedium)
            }
            BoxMenuButton(
                expanded = menuExpanded,
                onExpandedChange = { menuExpanded = it },
                onDelete = {
                    menuExpanded = false
                    onDelete()
                },
                onSave = {
                    menuExpanded = false
                    onSave(draft)
                },
                saveLabel = "Сохранить"
            )
        }
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp, bottom = 8.dp),
            label = { Text("Код сценария") },
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
        )
    }
}

@Composable
private fun BoxMenuButton(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onSave: (() -> Unit)? = null,
    saveLabel: String?
) {
    Column {
        TextButton(onClick = { onExpandedChange(true) }) { Text("⋮", style = MaterialTheme.typography.headlineSmall) }
        DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
            if (saveLabel != null && onSave != null) {
                DropdownMenuItem(text = { Text(saveLabel) }, onClick = onSave)
            }
            DropdownMenuItem(text = { Text("Удалить скрипт") }, onClick = onDelete)
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
    onRequestDevicePermissions: () -> Unit,
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
                supporting = "Восстанавливаются сценарии, которые не были остановлены вручную.",
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
            description = "Выдавайте разрешения только функциям, которыми пользуетесь: запись аудио, телефон, контакты, календарь, геолокация и Bluetooth."
        ) {
            OutlinedButton(onClick = onRequestDevicePermissions, modifier = Modifier.fillMaxWidth()) {
                Text("Запросить разрешения функций")
            }
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
