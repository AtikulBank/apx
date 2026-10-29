package ai.apx.ctrl

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ApxAccessibility : AccessibilityService() {

    companion object { @Volatile var inst: ApxAccessibility? = null }

    override fun onServiceConnected() {
        super.onServiceConnected()
        inst = this
    }

    override fun onDestroy() {
        inst = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun gesture(ms: Long, build: (Path) -> Unit): Boolean {
        val p = Path()
        build(p)
        val stroke = GestureDescription.StrokeDescription(p, 0, ms.coerceIn(24L, 5000L))
        val g = GestureDescription.Builder().addStroke(stroke).build()
        val latch = CountDownLatch(1)
        var sent = false
        Handler(Looper.getMainLooper()).post {
            sent = dispatchGesture(g, object : GestureResultCallback() {
                override fun onCompleted(d: GestureDescription?) { latch.countDown() }
                override fun onCancelled(d: GestureDescription?) { latch.countDown() }
            }, null)
            if (!sent) latch.countDown()
        }
        latch.await(4, TimeUnit.SECONDS)
        return sent
    }

    fun tap(x: Float, y: Float): Boolean = gesture(30L) { it.moveTo(x, y) }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long): Boolean =
        gesture(ms) { it.moveTo(x1, y1); it.lineTo(x2, y2) }

    fun global(action: String): Boolean {
        val a = when (action) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick" -> GLOBAL_ACTION_QUICK_SETTINGS
            "lock" -> GLOBAL_ACTION_LOCK_SCREEN
            else -> return false
        }
        return performGlobalAction(a)
    }

    private fun focusedEditable(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        var n = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        var guard = 0
        while (n != null && guard++ < 12) {
            if (n.isEditable) return n
            n = n.parent
        }
        return findEditable(root, 0)
    }

    private fun findEditable(n: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
        if (depth > 25) return null
        if (n.isEditable) return n
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            findEditable(c, depth + 1)?.let { return it }
        }
        return null
    }

    fun setText(t: String): Boolean {
        val n = focusedEditable() ?: return false
        val b = Bundle()
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, t)
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)
    }

    fun paste(): Boolean {
        val n = focusedEditable() ?: return false
        return n.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    fun key(name: String): Boolean {
        val n = focusedEditable() ?: return false
        return when (name.lowercase()) {
            "enter" -> if (Build.VERSION.SDK_INT >= 30) n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                       else n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            "del", "backspace" -> {
                val t = n.text?.toString() ?: return false
                if (t.isEmpty()) return false
                setText(t.substring(0, t.length - 1))
            }
            "clear" -> setText("")
            else -> false
        }
    }

    fun dump(): String {
        val root = rootInActiveWindow ?: return "no-root"
        val sb = StringBuilder()
        walk(root, 0, sb)
        return sb.toString()
    }

    private fun walk(n: AccessibilityNodeInfo, d: Int, sb: StringBuilder) {
        if (d > 30) return
        val txt = n.text?.toString() ?: n.contentDescription?.toString()
        if (!txt.isNullOrBlank()) {
            val r = Rect()
            n.getBoundsInScreen(r)
            sb.append("[").append(n.className?.toString()?.substringAfterLast('.')).append("] ")
              .append(txt.replace("\n", " ").take(90))
              .append(" @").append(r.centerX()).append(",").append(r.centerY()).append("\n")
        }
        for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, d + 1, sb) }
    }

    fun clickText(t: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val list = root.findAccessibilityNodeInfosByText(t) ?: return false
        for (node in list) {
            var n: AccessibilityNodeInfo? = node
            var guard = 0
            while (n != null && guard++ < 8) {
                if (n.isClickable) {
                    return n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                n = n.parent
            }
            val r = Rect()
            node.getBoundsInScreen(r)
            if (!r.isEmpty) return tap(r.centerX().toFloat(), r.centerY().toFloat())
        }
        return false
    }

    fun scroll(fwd: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        return root.performAction(
            if (fwd) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        )
    }
}