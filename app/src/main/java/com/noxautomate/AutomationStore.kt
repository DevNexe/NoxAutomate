package com.noxautomate

import android.content.Context
import org.json.JSONObject

internal object AutomationStore {
    private const val PREFERENCES = "nox_automate"
    private const val SCRIPTS_KEY = "scripts"
    private const val ENABLED_KEY = "enabled_scripts"
    private const val AUTO_START_KEY = "auto_start"
    private const val SMS_ALLOWED_SENDER_KEY = "sms_allowed_sender"

    fun scripts(context: Context): Map<String, String> {
        val json = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(SCRIPTS_KEY, null) ?: return emptyMap()
        val objectValue = JSONObject(json)
        return buildMap {
            val keys = objectValue.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                put(key, objectValue.getString(key))
            }
        }
    }

    fun saveScripts(context: Context, scripts: Map<String, String>) {
        val json = JSONObject()
        scripts.forEach { (name, source) -> json.put(name, source) }
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString(SCRIPTS_KEY, json.toString())
            .apply()
    }

    fun enabledScripts(context: Context): Set<String> =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getStringSet(ENABLED_KEY, emptySet()).orEmpty()

    fun setEnabledScripts(context: Context, names: Set<String>) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putStringSet(ENABLED_KEY, names.toSet())
            .apply()
    }

    fun autoStartEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(AUTO_START_KEY, true)

    fun setAutoStartEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putBoolean(AUTO_START_KEY, enabled)
            .apply()
    }

    fun smsAllowedSender(context: Context): String =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(SMS_ALLOWED_SENDER_KEY, "").orEmpty()

    fun setSmsAllowedSender(context: Context, sender: String) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString(SMS_ALLOWED_SENDER_KEY, sender.trim())
            .apply()
    }
}
