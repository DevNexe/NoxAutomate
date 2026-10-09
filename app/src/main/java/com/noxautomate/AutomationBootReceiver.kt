package com.noxautomate

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class AutomationBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!AutomationStore.autoStartEnabled(context)) return
        if (AutomationStore.enabledScripts(context).isEmpty()) return
        val serviceIntent = Intent(context, AutomationForegroundService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(serviceIntent)
            else context.startService(serviceIntent)
        } catch (error: Exception) {
            RunStatus.write(context, "Не удалось восстановить автозапуск: ${error.message}")
        }
    }
}
