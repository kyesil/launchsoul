package dev.yesil.launchsoul.system

import dev.yesil.launchsoul.R
import dev.yesil.launchsoul.tx

import android.accessibilityservice.AccessibilityService
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import dev.yesil.launchsoul.app
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Shows where an app is on the phone's own home screen: looks at the icon names there, page by page and then in the
 * folders, and puts a ring around the icon. Runs only when the user asks for it.
 */
class Finder(private val service: SoulAccessibility) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var ring: View? = null
    private var dockDone = false

    /** While the pages are turned our own strips must not lie over them. */
    val busy get() = job?.isActive == true

    fun stop() {
        job?.cancel()
        job = null
        hideRing()
    }

    fun find(label: String, launchers: Set<String>, done: () -> Unit) {
        stop()
        dockDone = false
        job = scope.launch {
            if (honor(label)) return@launch
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            settle(900)
            rewind(launchers)
            val r = pages(label, launchers, false) ?: run {
                if (root(launchers) == null) return@launch
                Actions.toast(service, tx(R.string.find_folders))
                rewind(launchers)
                pages(label, launchers, true)
            }
            DebugLog.log("find", "found=${r != null}")
            if (r != null) showRing(r)
            else if (root(launchers) != null) Actions.toast(service, tx(R.string.not_on_home_x, label))
        }.also { it.invokeOnCompletion { service.app.main.post(done) } }
    }

    /**
     * Honor's search shows the place of an app itself, folders included, but lets only its own apps ask for it.
     * So its "show app location" button is pressed like the user would.
     */
    private suspend fun honor(label: String): Boolean {
        val i = service.packageManager.getLaunchIntentForPackage(HONOR_SEARCH) ?: return false
        i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION)
        if (runCatching { service.startActivity(i) }.isFailure) return false
        fun nodes() = service.freshRoot()?.takeIf { it.packageName?.toString() == HONOR_SEARCH }?.let { all(it) }.orEmpty()
        fun id(n: AccessibilityNodeInfo, name: String) = n.viewIdResourceName?.endsWith(":id/$name") == true

        var typed = false
        repeat(30) {
            delay(150)
            val l = nodes()
            // The search clears the field while it opens, so it is filled again until the name stays there.
            val edit = l.firstOrNull { id(it, "et_search_input") } ?: return@repeat
            if (edit.text?.startsWith(label, ignoreCase = true) != true || edit.isShowingHintText) {
                typed = edit.performAction(
                    AccessibilityNodeInfo.ACTION_SET_TEXT,
                    android.os.Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, label) },
                )
                delay(150)
                return@repeat
            }
            val name = l.firstOrNull { id(it, "tvCardName") && it.text?.toString().equals(label, ignoreCase = true) } ?: return@repeat
            val y = bounds(name).centerY()
            val pin = l.firstOrNull { id(it, "ivCardLocal") && it.isVisibleToUser && bounds(it).let { b -> y in b.top..b.bottom } }
                ?: return@repeat
            // The list may still be moving.
            delay(200)
            if (!pin.refresh()) return@repeat
            val b = bounds(pin)
            val path = android.graphics.Path().apply {
                moveTo(b.exactCenterX(), b.exactCenterY())
                lineTo(b.exactCenterX(), b.exactCenterY() + 0.5f)
            }
            val ok = service.dispatchGesture(
                android.accessibilityservice.GestureDescription.Builder()
                    .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 50)).build(), null, null,
            )
            DebugLog.log("find", "honor pressed=$ok")
            return ok
        }
        DebugLog.log("find", "honor: not found, typed=$typed")
        return false
    }

    private suspend fun rewind(launchers: Set<String>) {
        repeat(20) {
            val p = page(launchers)
            if (p == null) {
                // Where the number is not told, the home button leads to the first page.
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                return settle(1200)
            }
            if (p.first <= 1) return
            turn(launchers, forward = false)
        }
    }

    /** "Screen 2 of 6" on Honor, "Page 2 of 7." on Samsung, "Home screen 2 of 3" on a Pixel. */
    private fun page(launchers: Set<String>): Pair<Int, Int>? {
        val re = Regex("""^\D*(\d+)\D+(\d+)\W*$""")
        for (n in all(root(launchers) ?: return null)) {
            if (n.isClickable || !n.isVisibleToUser) continue
            val m = re.find(n.contentDescription ?: continue) ?: continue
            val (a, b) = m.destructured
            if (a.length < 3 && b.length < 3 && a.toInt() in 1..b.toInt()) return a.toInt() to b.toInt()
        }
        return null
    }

    /** Null also when the user has left the home screen meanwhile. */
    private fun root(launchers: Set<String>): AccessibilityNodeInfo? =
        service.rootInActiveWindow?.takeIf { it.packageName?.toString() in launchers }

    private suspend fun pages(label: String, launchers: Set<String>, folders: Boolean): Rect? {
        repeat(20) {
            val top = root(launchers) ?: return null
            if (folders) inFolders(label, launchers)?.let { return it }
            else icon(top, label)?.let { return it }
            val before = names(root(launchers) ?: return null)
            val p = page(launchers)
            DebugLog.log("find", "page $p folders=$folders")
            if (p != null && p.first >= p.second) return null
            turn(launchers, forward = true)
            // Samsung tells the new page a while after the swipe.
            repeat(6) { if (p == page(launchers) && before == root(launchers)?.let { names(it) }) settle(350) }
            // Nothing moved: that was the last page.
            if (p == page(launchers) && before == names(root(launchers) ?: return null)) {
                DebugLog.log("find", "stopped at $p")
                return null
            }
        }
        return null
    }

    /** By the launcher's own scroll action, else by a swipe over the upper half. */
    private suspend fun turn(launchers: Set<String>, forward: Boolean) {
        val pager = all(root(launchers) ?: return).firstOrNull {
            it.isScrollable && it.viewIdResourceName?.endsWith(":id/workspace") == true
        }
        if (pager != null) repeat(4) {
            val a = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            // Refused while it is still moving, so it is asked a few times.
            if (pager.performAction(a)) return settle(800)
            delay(400)
        }
        val m = service.resources.displayMetrics
        val y = m.heightPixels * 0.4f
        val x = listOf(m.widthPixels * 0.85f, m.widthPixels * 0.15f).let { if (forward) it else it.reversed() }
        val path = android.graphics.Path().apply {
            moveTo(x[0], y)
            lineTo(x[1], y)
        }
        service.dispatchGesture(
            android.accessibilityservice.GestureDescription.Builder()
                .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 200)).build(), null, null,
        )
        settle(900)
    }

    /** Waits, then forgets what the window looked like before. */
    private suspend fun settle(millis: Long) {
        delay(millis)
        service.freshRoot()
    }

    private suspend fun inFolders(label: String, launchers: Set<String>): Rect? {
        val top = root(launchers) ?: return null
        // The dock is the same on every page.
        val low = service.resources.displayMetrics.heightPixels * 0.84f
        val places = all(top).filter { isFolder(it, top.packageName) }.map { bounds(it) }.distinct().filter { !dockDone || it.top < low }
        dockDone = true
        for (b in places) {
            val f = all(root(launchers) ?: return null).firstOrNull { isFolder(it, top.packageName) && bounds(it) == b } ?: continue
            val before = names(root(launchers) ?: return null)
            if (!f.performAction(AccessibilityNodeInfo.ACTION_CLICK)) continue
            DebugLog.log("find", "folder at $b")
            settle(700)
            val open = root(launchers) ?: return null
            icon(open, label)?.let { return it }
            // Only a folder that really opened is closed again.
            if (names(open) != before) {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                settle(600)
            }
        }
        return null
    }

    private fun isFolder(n: AccessibilityNodeInfo, home: CharSequence?): Boolean {
        if (!n.isClickable || !n.isVisibleToUser || n.packageName != home) return false
        if (bounds(n).let { it.width() <= 0 || it.top <= 0 }) return false
        val desc = n.contentDescription?.toString()?.lowercase() ?: ""
        return n.viewIdResourceName?.endsWith(":id/folder_icon_view") == true || "folder" in desc || "klasör" in desc ||
            n.className?.toString() == "android.widget.LinearLayout" && n.text.isNullOrEmpty()
    }

    private fun names(r: AccessibilityNodeInfo) = all(r).filter { it.isVisibleToUser }.mapNotNull { n -> n.text?.let { "$it ${bounds(n)}" } }.toSet()

    private fun icon(r: AccessibilityNodeInfo, label: String): Rect? = all(r).firstOrNull { n ->
        n.isVisibleToUser && n.isClickable && listOf(n.text, n.contentDescription).any { same(it?.toString(), label) }
    }?.let { bounds(it) }

    /** The launcher adds things like ", 1 notification" or a hint right behind the name. */
    private fun same(s: String?, label: String): Boolean {
        if (s == null || !s.startsWith(label, ignoreCase = true)) return false
        val rest = s.drop(label.length)
        return rest.isEmpty() || rest[0] == ',' || rest.startsWith("Swipe") || rest[0] == '\n'
    }

    private fun bounds(n: AccessibilityNodeInfo) = Rect().also { n.getBoundsInScreen(it) }

    private fun all(r: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (out.size > 3000 || depth > 40) return
            out += n
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1) }
        }
        walk(r, 0)
        return out
    }

    private fun showRing(r: Rect) {
        hideRing()
        val d = service.resources.displayMetrics.density
        val v = object : View(service) {
            private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 4 * d
                color = 0xFF8FB8FF.toInt()
            }

            override fun onDraw(c: Canvas) {
                val m = 3 * d
                c.drawRoundRect(m, m, width - m, height - m, 20 * d, 20 * d, p)
            }
        }
        val lp = WindowManager.LayoutParams(
            r.width(), r.height(), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.TOP or Gravity.LEFT
        lp.x = r.left
        lp.y = r.top
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        runCatching {
            wm.addView(v, lp)
            ring = v
            service.app.main.postDelayed({ if (ring === v) hideRing() }, 3500)
        }
    }

    private fun hideRing() {
        ring?.let { v -> runCatching { wm.removeView(v) } }
        ring = null
    }

    private companion object {
        const val HONOR_SEARCH = "com.hihonor.search"
    }
}
