package com.sami.assistant.system

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.sami.assistant.commands.Command
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Optional, user-enabled screen control. Sami only reads the window content when the
 * user issues a screen command; it does not monitor or log screen content in the background.
 */
class SamiAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: SamiAccessibilityService? = null
            private set
        val isRunning get() = instance != null
    }

    override fun onServiceConnected() { instance = this; Log.i("SamiA11y", "connected") }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Step aside for UPI/banking apps — they refuse to run while an accessibility service is on.
        val pkg = event?.packageName?.toString() ?: return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg in PaymentGuard.protectedApps) PaymentGuard.pauseFor(this, pkg)
    }

    override fun onInterrupt() {}
    override fun onDestroy() { instance = null; super.onDestroy() }

    fun global(op: Command.GlobalOp): Boolean = performGlobalAction(
        when (op) {
            Command.GlobalOp.BACK -> GLOBAL_ACTION_BACK
            Command.GlobalOp.RECENTS -> GLOBAL_ACTION_RECENTS
            Command.GlobalOp.NOTIFICATIONS -> GLOBAL_ACTION_NOTIFICATIONS
            Command.GlobalOp.QUICK_SETTINGS -> GLOBAL_ACTION_QUICK_SETTINGS
            Command.GlobalOp.SCREENSHOT -> GLOBAL_ACTION_TAKE_SCREENSHOT
            Command.GlobalOp.LOCK -> GLOBAL_ACTION_LOCK_SCREEN
        }
    )

    /** Root of the top-most application window that is not Sami itself. */
    fun targetRoot(): AccessibilityNodeInfo? {
        val appWindows = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .sortedByDescending { it.layer }
        for (w in appWindows) {
            val root = w.root ?: continue
            if (root.packageName?.toString() != packageName) return root
        }
        return rootInActiveWindow?.takeIf { it.packageName?.toString() != packageName }
    }

    // ---- traversal helpers ------------------------------------------------------------------

    private fun walk(node: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>, depth: Int = 0) {
        if (node == null || depth > 60) return
        out += node
        for (i in 0 until node.childCount) walk(node.getChild(i), out, depth + 1)
    }

    fun allNodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = mutableListOf<AccessibilityNodeInfo>().also { walk(root, it) }

    fun labelOf(n: AccessibilityNodeInfo): String =
        (n.text?.toString()?.takeIf { it.isNotBlank() } ?: n.contentDescription?.toString() ?: "").trim()

    fun clickableTarget(n: AccessibilityNodeInfo, long: Boolean): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = n
        var hops = 0
        while (cur != null && hops < 8) {
            if (if (long) cur.isLongClickable else cur.isClickable) return cur
            cur = cur.parent; hops++
        }
        return null
    }

    fun bounds(n: AccessibilityNodeInfo) = Rect().also { n.getBoundsInScreen(it) }

    fun largestScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        allNodes(root).filter { it.isScrollable && it.isVisibleToUser }
            .maxByOrNull { bounds(it).let { r -> r.width().toLong() * r.height() } }

    suspend fun tapAt(x: Float, y: Float, long: Boolean): Boolean = gesture(
        Path().apply { moveTo(x, y) }, if (long) 700 else 60
    )

    suspend fun swipe(dir: Command.ScrollDir): Boolean {
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat(); val h = dm.heightPixels.toFloat()
        val p = Path()
        when (dir) {
            Command.ScrollDir.DOWN -> { p.moveTo(w / 2, h * 0.72f); p.lineTo(w / 2, h * 0.30f) }
            Command.ScrollDir.UP -> { p.moveTo(w / 2, h * 0.30f); p.lineTo(w / 2, h * 0.72f) }
            Command.ScrollDir.LEFT -> { p.moveTo(w * 0.85f, h / 2); p.lineTo(w * 0.15f, h / 2) }
            Command.ScrollDir.RIGHT -> { p.moveTo(w * 0.15f, h / 2); p.lineTo(w * 0.85f, h / 2) }
        }
        return gesture(p, 300)
    }

    private suspend fun gesture(path: Path, duration: Long): Boolean = suspendCancellableCoroutine { cont ->
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build()
        val ok = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { if (cont.isActive) cont.resume(true) }
            override fun onCancelled(d: GestureDescription?) { if (cont.isActive) cont.resume(false) }
        }, null)
        if (!ok && cont.isActive) cont.resume(false)
    }
}
