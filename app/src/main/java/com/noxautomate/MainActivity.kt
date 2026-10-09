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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
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
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("Nox Automate", style = MaterialTheme.typography.headlineMedium)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            scripts.keys.toList().forEach { name ->
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Button(onClick = { selectedScript = name }) { Text(name) }
                                    Button(onClick = {
                                        if (name in enabledScripts) enabledScripts.remove(name)
                                        else enabledScripts.add(name)
                                        AutomationStore.setEnabledScripts(this@MainActivity, enabledScripts.toSet())
                                        AutomationStore.saveScripts(this@MainActivity, scripts.toMap())
                                        if (enabledScripts.isEmpty()) {
                                            stopService(Intent(this@MainActivity, AutomationForegroundService::class.java))
                                            RunStatus.write(this@MainActivity, "Все сценарии автоматизации остановлены")
                                        } else {
                                            try {
                                                startForegroundService(Intent(this@MainActivity, AutomationForegroundService::class.java))
                                                RunStatus.write(this@MainActivity, "Активные сценарии обновлены")
                                            } catch (error: Exception) {
                                                RunStatus.write(this@MainActivity, "Ошибка запуска сервиса: ${error.message}")
                                            }
                                        }
                                    }) { Text(if (name in enabledScripts) "Авто: вкл." else "Авто: выкл.") }
                                }
                            }
                            Button(onClick = {
                                while ("Сценарий $nextScriptId" in scripts) nextScriptId++
                                val name = "Сценарий ${nextScriptId++}"
                                scripts[name] = ""
                                selectedScript = name
                            }) { Text("+") }
                            if (scripts.size > 1) {
                                Button(onClick = {
                                    scripts.remove(selectedScript)
                                    enabledScripts.remove(selectedScript)
                                    AutomationStore.setEnabledScripts(this@MainActivity, enabledScripts.toSet())
                                    AutomationStore.saveScripts(this@MainActivity, scripts.toMap())
                                    selectedScript = scripts.keys.first()
                                    if (enabledScripts.isEmpty()) stopService(
                                        Intent(this@MainActivity, AutomationForegroundService::class.java)
                                    ) else refreshAutomationService()
                                }) { Text("Удалить") }
                            }
                        }
                        Text("Скрипт автоматизации", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            value = script,
                            onValueChange = {
                                scripts[selectedScript] = it
                                AutomationStore.saveScripts(this@MainActivity, scripts.toMap())
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 260.dp),
                            minLines = 10,
                            label = { Text(selectedScript) }
                        )
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(onClick = {
                                try {
                                    AutomationStore.saveScripts(this@MainActivity, scripts.toMap())
                                    if (selectedScript !in enabledScripts) enabledScripts.add(selectedScript)
                                    AutomationStore.setEnabledScripts(this@MainActivity, enabledScripts.toSet())
                                    startForegroundService(Intent(this@MainActivity, AutomationForegroundService::class.java))
                                } catch (error: Exception) {
                                    RunStatus.write(this@MainActivity, "Ошибка запуска сервиса: ${error.message}")
                                }
                            }) { Text("Запустить") }
                            Button(onClick = {
                                enabledScripts.clear()
                                AutomationStore.setEnabledScripts(this@MainActivity, emptySet())
                                stopService(Intent(this@MainActivity, AutomationForegroundService::class.java))
                                RunStatus.write(this@MainActivity, "Сервис остановлен")
                            }) {
                                Text("Остановить")
                            }
                            Button(onClick = {
                                val request = OneTimeWorkRequestBuilder<ScriptWorker>()
                                    .setInputData(workDataOf(AutomationForegroundService.EXTRA_SCRIPT to script))
                                    .build()
                                WorkManager.getInstance(this@MainActivity).enqueue(request)
                                RunStatus.write(this@MainActivity, "Разовый запуск поставлен в очередь")
                            }) { Text("Выполнить один раз") }
                            Button(onClick = {
                                val settings = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
                                    .setData(Uri.parse("package:$packageName"))
                                startActivity(settings)
                            }) { Text("Доступ к настройкам") }
                            Button(onClick = {
                                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            }) { Text("Спец. возможности") }
                        }
                        Text("Вывод: $runStatus", style = MaterialTheme.typography.bodyMedium)
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
}
