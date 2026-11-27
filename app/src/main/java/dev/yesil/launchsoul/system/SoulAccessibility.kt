package dev.yesil.launchsoul.system

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import dev.yesil.launchsoul.app
import dev.yesil.launchsoul.core.looksLikeDrawer
import dev.yesil.launchsoul.core.looksLikeRecents
import kotlin.math.abs

/** Global actions (lock, panels, back), the edge handles and the swipe areas on the phone's own home screen.
 * Of other windows only the package, class, title, type and focus are looked at; never their content, but for Finder on request. */
class SoulAccessibility : AccessibilityService(), SharedPreferences.OnSharedPreferenceChangeListener {
    private val wm by lazy { getSystemService(WindowManager::class.java) }
    private val handles = mutableListOf<View>()

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            // The default launcher may have been changed meanwhile.
            if (i.action == Intent.ACTION_USER_PRESENT) listen()
            rebuild()
            check()
        }
    }

    override fun onServiceConnected() {
        instance = this
        DebugLog.log("a11y", "connected")
        androidx.core.content.ContextCompat.registerReceiver(this, screen, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        app.prefs.sp.registerOnSharedPreferenceChangeListener(this)
        listen()
        rebuild()
    }

    private var launchers = emptySet<String>()

    /** The launcher shows its recent apps, its app list or one of its other screens instead of the home screen. */
    private var covered = false
    private var recentsOpen = false
    private var recentsGoneAt = 0L
    private var homes = emptySet<String>()

    /** Until then the home screen counts as reached although it has no focus yet, so a quick second swipe is ours. */
    private var comingHome = 0L
    private val settled = Runnable { evaluate.run() }

    /** Entering and leaving the launcher's edit mode is not told by an event, so it is looked for now and then. */
    private val lookForEdit = Runnable { if (last == "home" || last == "edit") evaluate.run() }
    private val others = HashMap<String, Boolean>()

    /** A screen of the launcher app that is not the home screen, such as its settings. */
    private fun otherScreen(pkg: String, cls: String): Boolean = others.getOrPut("$pkg/$cls") {
        // Samsung names a class that is not an activity; such names and other launcher variants count as home.
        cls !in homes && !cls.endsWith("Launcher") &&
            runCatching { packageManager.getActivityInfo(android.content.ComponentName(pkg, cls), 0) }.isSuccess
    }

    /** Where the keyboard begins; the handles must not lie on its keys. */
    private var imeTop = Int.MAX_VALUE

    private fun keyboardTop(): Int = if (!app.prefs.edgeEnabled) Int.MAX_VALUE else runCatching {
        val w = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } ?: return Int.MAX_VALUE
        val r = android.graphics.Region()
        val b = Rect()
        if (android.os.Build.VERSION.SDK_INT >= 33) w.getRegionInScreen(r)
        if (r.isEmpty) w.getBoundsInScreen(b) else b.set(r.bounds)
        if (b.isEmpty) Int.MAX_VALUE else b.top
    }.getOrDefault(Int.MAX_VALUE)

    /** A tap on a handle belongs to what is under it. */
    private fun passTap(x: Float, y: Float) {
        clear()
        zones.clear()
        val back = Runnable {
            rebuild()
            check()
        }
        val path = android.graphics.Path().apply {
            moveTo(x, y)
            lineTo(x, y + 0.5f)
        }
        app.main.postDelayed({
            val ok = dispatchGesture(
                android.accessibilityservice.GestureDescription.Builder()
                    .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 50)).build(),
                object : GestureResultCallback() {
                    override fun onCompleted(g: android.accessibilityservice.GestureDescription?) = back.run()
                    override fun onCancelled(g: android.accessibilityservice.GestureDescription?) = back.run()
                },
                null,
            )
            if (!ok) back.run()
        }, 40)
    }
    private var last = ""
    private val zones by lazy { HomeZones(this) }
    private val evaluate = Runnable {
        // The edge handles must lie above the strips, so they are put up again after them.
        val top = keyboardTop()
        if (zones.show(onHome()) || top != imeTop) {
            imeTop = top
            rebuild()
        }
    }

    /** Windows come and go in bursts, so the state is read once they have settled. */
    private fun check() {
        app.main.removeCallbacks(evaluate)
        app.main.postDelayed(evaluate, 80)
    }

    /**
     * The front window as it is now. The system keeps a copy that is renewed by events, and this service asks for few of
     * them: after a page turn the copy can still show the page before.
     */
    fun freshRoot(): android.view.accessibility.AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 33) clearCache()
        val r = rootInActiveWindow ?: return null
        if (android.os.Build.VERSION.SDK_INT < 33) r.refresh()
        return r
    }

    private val finder by lazy { Finder(this) }

    fun find(label: String) {
        finder.find(label, launchers) { check() }
        check()
    }

    fun recheck() {
        app.main.post(evaluate)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (!app.prefs.zoneOn && !app.prefs.edgeEnabled) return
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = e.packageName?.toString() ?: ""
            val cls = e.className?.toString() ?: ""
            if (pkg in launchers) {
                val title = e.text.joinToString(" ")
                val recents = looksLikeRecents(cls, title)
                val r = recents || looksLikeDrawer(title) || otherScreen(pkg, cls)
                val gone = e.contentChangeTypes and AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_DISAPPEARED != 0
                // The list says its name when it closes, too. Until it has closed, the launcher's own window may come
                // to the front again (Honor does when recent apps are opened with a swipe), and that is not home.
                if (recents) {
                    // Swiping home from an app, Honor reports the list appearing and disappearing at once, sometimes the
                    // other way round: an appearance right after a disappearance is not taken as open.
                    val now = SystemClock.uptimeMillis()
                    if (gone) recentsGoneAt = now
                    recentsOpen = !gone && now - recentsGoneAt > 400
                }
                if (r || !cls.startsWith("android.")) covered = r && !gone || recentsOpen
                // This comes while the way home is still being swiped; focus follows up to half a second later.
                if (cls in homes && !covered) {
                    comingHome = SystemClock.uptimeMillis() + COMING
                    app.main.removeCallbacks(settled)
                    app.main.postDelayed(settled, COMING + 50)
                    evaluate.run()
                }
                DebugLog.log("zone", "class=$cls title=${title.take(40)} covered=$r gone=$gone")
            } else if (pkg != "com.android.systemui" && pkg != "android" && pkg != packageName && !cls.startsWith("android.")) {
                // Another app's window says nothing about the launcher: Honor sends one while its recent apps are shown.
                // Coming back home clears [covered] with the launcher's own event.
                comingHome = 0
            }
        }
        check()
    }

    /** Is the phone's own home screen in front, with no panel, keyboard, lock or recent apps screen over it? */
    private fun editing(root: android.view.accessibility.AccessibilityNodeInfo?): Boolean {
        val pkg = root?.packageName?.toString() ?: return false
        return EDITABLE[pkg].orEmpty().any { id -> root.findAccessibilityNodeInfosByViewId("$pkg:id/$id").any { it.isVisibleToUser } }
    }

    private fun onHome(): Boolean {
        val why = when {
            !app.prefs.zoneOn -> "off"
            finder.busy -> "finding"
            getSystemService(KeyguardManager::class.java).isKeyguardLocked -> "locked"
            resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE -> "landscape"
            else -> runCatching {
                val w = windows
                val f = w.firstOrNull { it.isFocused }
                when {
                    comingHome > SystemClock.uptimeMillis() && !covered && (f == null || f.type == AccessibilityWindowInfo.TYPE_APPLICATION) &&
                        w.none { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } && f?.root?.packageName?.toString() !in launchers -> "home"
                    f == null -> "no focus"
                    f.type != AccessibilityWindowInfo.TYPE_APPLICATION -> "focus type ${f.type}"
                    w.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } -> "keyboard"
                    f.root?.packageName?.toString() !in launchers -> {
                        // Left for an app: whatever the launcher showed is gone.
                        recentsOpen = false
                        "other app"
                    }
                    covered || looksLikeRecents("", f.title?.toString() ?: "") -> "covered"
                    editing(f.root) -> "edit"
                    else -> "home"
                }
            }.getOrElse { "error $it" }
        }
        if (why != last) DebugLog.log("zone", why)
        last = why
        app.main.removeCallbacks(lookForEdit)
        if ((why == "home" || why == "edit") && launchers.any { it in EDITABLE } &&
            getSystemService(android.os.PowerManager::class.java).isInteractive
        ) app.main.postDelayed(lookForEdit, 500)
        return why == "home"
    }

    /** The events and the window list are requested only while they are needed. */
    private fun listen() {
        launchers = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo.packageName }.filter { it != packageName && it != "com.android.settings" }.toSet()
        homes = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo.name }.toSet()
        others.clear()
        val info = serviceInfo ?: return
        val on = app.prefs.zoneOn || app.prefs.edgeEnabled
        info.eventTypes = if (on) AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOWS_CHANGED else 0
        // Finder needs the view ids.
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        val f = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        info.flags = if (on) info.flags or f else info.flags and f.inv()
        serviceInfo = info
        DebugLog.log("zone", "on=$on launchers=$launchers side=${app.prefs.zoneSide} bottom=${app.prefs.zoneBottom}")
        zones.clear()
        check()
    }

    override fun onInterrupt() {}

    override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
        if (key == null || key.startsWith("zone")) app.main.post { listen() }
        if (key == null || key.startsWith("edge")) app.main.post {
            if (key == null || key == "edgeEnabled") listen()
            rebuild()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        rebuild()
        zones.clear()
        check()
    }

    override fun onDestroy() {
        instance = null
        app.main.removeCallbacks(evaluate)
        app.main.removeCallbacks(settled)
        app.main.removeCallbacks(lookForEdit)
        zones.clear()
        zones.show(false)
        finder.stop()
        DebugLog.log("a11y", "destroyed")
        clear()
        runCatching { unregisterReceiver(screen) }
        app.prefs.sp.unregisterOnSharedPreferenceChangeListener(this)
        super.onDestroy()
    }

    private fun clear() {
        for (h in handles) runCatching { wm.removeView(h) }
        handles.clear()
    }

    private fun rebuild() {
        clear()
        if (instance == null) return
        val p = app.prefs
        if (!p.edgeEnabled) return
        if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) return
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) return
        val d = resources.displayMetrics.density
        val screenH = wm.currentBounds().height()
        // The status bar, the bottom gesture area and the keyboard stay free.
        val top = (44 * d).toInt()
        val room = minOf(screenH - (32 * d).toInt(), imeTop) - top
        if (room <= 0) return
        val h = (p.edgeHeight * d).toInt().coerceAtMost(room)
        val y = ((screenH - (p.edgeHeight * d).toInt()) * p.edgePosition / 100f).toInt().coerceIn(top, top + room - h)
        for (left in listOf(true, false)) {
            if (left && !p.edgeLeft || !left && !p.edgeRight) continue
            val lp = WindowManager.LayoutParams(
                (p.edgeWidth * d).toInt(), h,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            )
            lp.gravity = Gravity.TOP or if (left) Gravity.LEFT else Gravity.RIGHT
            lp.y = y
            val v = Handle(this, left)
            runCatching {
                wm.addView(v, lp)
                handles += v
            }
        }
    }

    private fun WindowManager.currentBounds(): Rect =
        if (android.os.Build.VERSION.SDK_INT >= 30) maximumWindowMetrics.bounds
        else resources.displayMetrics.let { Rect(0, 0, it.widthPixels, it.heightPixels) }

    private inner class Handle(ctx: Context, val left: Boolean) : View(ctx) {
        private val d = resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FFFFFF }
        private var sx = 0f
        private var sy = 0f
        private var done = false

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            systemGestureExclusionRects = listOf(Rect(0, 0, w, h))
        }

        override fun onDraw(c: Canvas) {
            if (!app.prefs.edgeVisible) return
            val w = 3 * d
            val x = if (left) 0f else width - w
            c.drawRoundRect(x, 0f, x + w, height.toFloat(), w, w, paint)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_CANCEL -> done = true
                MotionEvent.ACTION_UP -> if (!done && abs(e.rawX - sx) < 8 * d && abs(e.rawY - sy) < 8 * d) passTap(sx, sy)
                MotionEvent.ACTION_DOWN -> {
                    sx = e.rawX
                    sy = e.rawY
                    done = false
                }
                MotionEvent.ACTION_MOVE -> if (!done) {
                    val dx = e.rawX - sx
                    val dy = e.rawY - sy
                    if (abs(dy) >= 48 * d && abs(dy) > 1.5f * abs(dx)) {
                        done = true
                        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        Actions.run(this@SoulAccessibility, if (dy > 0) app.prefs.edgeDown else app.prefs.edgeUp)
                    } else if (abs(dx) >= 32 * d && abs(dx) > abs(dy) && (dx > 0) == left) {
                        // The handle covers the system's back area, so it gives "back" itself.
                        done = true
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        performGlobalAction(GLOBAL_ACTION_BACK)
                    }
                }
            }
            return true
        }
    }

    companion object {
        private const val COMING = 1_200L

        /** What is only seen in the edit mode of a launcher. */
        private val EDITABLE = mapOf("com.hihonor.android.launcher" to listOf("hat_manage_text", "cellbutton_container"))

        @Volatile var instance: SoulAccessibility? = null
            private set
    }
}
