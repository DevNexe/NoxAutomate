package com.noxautomate

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

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
        val packageName = event?.packageName?.toString() ?: return
        if (packageName == activePackageName) return
        activePackageName = packageName
        sendBroadcast(
            android.content.Intent(ACTION_APP_FOREGROUND)
                .setPackage(applicationContext.packageName)
                .putExtra(EXTRA_PACKAGE_NAME, packageName)
        )
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun clickText(text: String): Boolean = onMainThread { performTextClick(text) }

    fun setText(target: String, value: String): Boolean = onMainThread {
        performOnTextNode(target) { node ->
            if (!node.isEditable) {
                false
            } else {
                val arguments = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            }
        }
    }

    fun findText(text: String): Boolean = onMainThread { performOnTextNode(text) { true } }

    fun scroll(direction: String): Boolean = onMainThread {
        val root = rootInActiveWindow ?: return@onMainThread false
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        pending.add(root)
        val action = when (direction.lowercase()) {
            "down", "right", "forward" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            "up", "left", "backward" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            else -> throw IllegalArgumentException("Направление прокрутки должно быть up/down/left/right")
        }
        var visited = 0
        try {
            while (pending.isNotEmpty() && visited++ < MAX_NODES) {
                val node = pending.removeFirst()
                val scrolled = node.isScrollable && node.performAction(action)
                if (!scrolled) for (index in 0 until node.childCount) node.getChild(index)?.let(pending::addLast)
                node.recycle()
                if (scrolled) return@onMainThread true
            }
        } finally {
            while (pending.isNotEmpty()) pending.removeFirst().recycle()
        }
        false
    }

    fun tap(x: Int, y: Int): Boolean {
        val display = resources.displayMetrics
        require(x in 0 until display.widthPixels && y in 0 until display.heightPixels) {
            "Координаты tap должны находиться на экране"
        }
        return dispatchGesture(Path().apply { moveTo(x.toFloat(), y.toFloat()) }, durationMs = 80)
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean {
        require(durationMs in 100..5_000) { "Продолжительность swipe должна быть от 100 до 5000 мс" }
        val display = resources.displayMetrics
        require(
            x1 in 0 until display.widthPixels && x2 in 0 until display.widthPixels &&
                y1 in 0 until display.heightPixels && y2 in 0 until display.heightPixels
        ) { "Координаты swipe должны находиться на экране" }
        return dispatchGesture(
            Path().apply {
                moveTo(x1.toFloat(), y1.toFloat())
                lineTo(x2.toFloat(), y2.toFloat())
            },
            durationMs
        )
    }

    fun pressBack(): Boolean = onMainThread { performGlobalAction(GLOBAL_ACTION_BACK) }

    private fun dispatchGesture(path: Path, durationMs: Long): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            throw UnsupportedOperationException("Жесты Accessibility требуют Android 7.0 или новее")
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw IllegalStateException("Жесты Accessibility следует вызывать из фонового потока")
        }
        val result = CompletableFuture<Boolean>()
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        val accepted = onMainThread { dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                result.complete(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                result.complete(false)
            }
        }, null) }
        if (!accepted) return false
        return try {
            result.get(5, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            throw IllegalStateException("Тайм-аут выполнения жеста")
        }
    }

    private fun performOnTextNode(text: String, action: (AccessibilityNodeInfo) -> Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        pending.add(root)
        var visited = 0
        try {
            while (pending.isNotEmpty() && visited++ < MAX_NODES) {
                val node = pending.removeFirst()
                if (node.text?.toString() == text || node.contentDescription?.toString() == text) {
                    return try {
                        action(node)
                    } finally {
                        node.recycle()
                    }
                }
                for (index in 0 until node.childCount) node.getChild(index)?.let(pending::addLast)
                node.recycle()
            }
        } finally {
            while (pending.isNotEmpty()) pending.removeFirst().recycle()
        }
        return false
    }

    private fun onMainThread(action: () -> Boolean): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) return action()
        val latch = CountDownLatch(1)
        var result = false
        var failure: Exception? = null
        if (!mainHandler.post {
                try {
                    result = action()
                } catch (error: Exception) {
                    failure = error
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
        failure?.let { throw it }
        return result
    }

    private fun performTextClick(text: String): Boolean {
        return performOnTextNode(text) { node ->
            var current: AccessibilityNodeInfo? = node
            var isMatch = true
            var clicked = false
            while (current != null && !clicked) {
                if (current.isClickable) clicked = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (clicked) {
                    if (!isMatch) current.recycle()
                    break
                }
                val parent = current.parent
                if (!isMatch) current.recycle()
                isMatch = false
                current = parent
            }
            clicked
        }
    }

    companion object {
        const val ACTION_APP_FOREGROUND = "com.noxautomate.APP_FOREGROUND"
        const val EXTRA_PACKAGE_NAME = "package_name"
        @Volatile
        private var instance: AutomationAccessibilityService? = null
        private const val MAX_NODES = 1_000

        fun current(): AutomationAccessibilityService? = instance
    }
}
