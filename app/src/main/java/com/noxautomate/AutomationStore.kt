package com.noxautomate

import android.content.Context
import org.json.JSONObject

internal object AutomationStore {
    private const val PREFERENCES = "nox_automate"
    private const val SCRIPTS_KEY = "scripts"
    private const val ENABLED_KEY = "enabled_scripts"

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
}
