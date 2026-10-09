package com.noxautomate

import android.content.Context
import android.content.Intent
import org.json.JSONArray

internal object RunStatus {
    const val ACTION_UPDATED = "com.noxautomate.RUN_STATUS_UPDATED"
    private const val PREFERENCES = "nox_automate"
    private const val KEY_STATUS = "last_run_status"
    private const val KEY_LOGS = "run_logs"

    fun read(context: Context): String =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(KEY_STATUS, "Готово") ?: "Готово"

    fun write(context: Context, status: String) {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val logs = readLogs(context).toMutableList()
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        logs += "$timestamp  $status"
        preferences.edit()
            .putString(KEY_STATUS, status)
            .putString(KEY_LOGS, JSONArray(logs.takeLast(MAX_LOG_ENTRIES)).toString())
            .apply()
        context.sendBroadcast(Intent(ACTION_UPDATED).setPackage(context.packageName))
    }

    fun readLogs(context: Context): List<String> {
        val raw = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(KEY_LOGS, null) ?: return listOf(read(context))
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
        } catch (_: org.json.JSONException) {
            listOf(read(context))
        }
    }

    private const val MAX_LOG_ENTRIES = 100
}
