package com.noxautomate

import android.content.Context
import android.content.Intent

internal object RunStatus {
    const val ACTION_UPDATED = "com.noxautomate.RUN_STATUS_UPDATED"
    private const val PREFERENCES = "nox_automate"
    private const val KEY_STATUS = "last_run_status"

    fun read(context: Context): String =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(KEY_STATUS, "Готово") ?: "Готово"

    fun write(context: Context, status: String) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putString(KEY_STATUS, status).apply()
        context.sendBroadcast(Intent(ACTION_UPDATED).setPackage(context.packageName))
    }
}
