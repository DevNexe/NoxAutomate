package com.noxautomate

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AutomationAccessibilityService : AccessibilityService() {
    @Volatile
    var activePackageName: String? = null
        private set

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        activePackageName = event?.packageName?.toString()
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun clickText(text: String): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) return performTextClick(text)
        val latch = CountDownLatch(1)
        var clicked = false
        if (!mainHandler.post {
                try {
                    clicked = performTextClick(text)
                } finally {
                    latch.countDown()
                }
            }
        ) {
            throw IllegalStateException("Не удалось отправить команду AccessibilityService")
        }
        if (!latch.await(5, TimeUnit.SECONDS)) {
            throw IllegalStateException("Превышено время ожидания AccessibilityService")
        }
        return clicked
    }

    private fun performTextClick(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        pending.add(root)
        var visited = 0
        while (pending.isNotEmpty() && visited++ < MAX_NODES) {
            val node = pending.removeFirst()
            val matches = node.text?.toString() == text || node.contentDescription?.toString() == text
            if (matches) {
                var clickable: AccessibilityNodeInfo? = node
                while (clickable != null) {
                    if (clickable.isClickable && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                    clickable = clickable.parent
                }
            }
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(pending::addLast)
            }
        }
        return false
    }

    companion object {
        @Volatile
        private var instance: AutomationAccessibilityService? = null
        private const val MAX_NODES = 1_000

        fun current(): AutomationAccessibilityService? = instance
    }
}
