package dev.yesil.launchsoul.system

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import dev.yesil.launchsoul.app
import dev.yesil.launchsoul.data.Prefs
import kotlin.math.abs

/**
 * Invisible strips over the phone's own home screen. A vertical swipe that starts on a strip runs our action;
 * a tap, a long press or a sideways swipe is handed to the home screen again once the finger is up.
 */
class HomeZones(private val service: SoulAccessibility) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private val d = service.resources.displayMetrics.density
    private val strips = mutableListOf<Strip>()
    private var replaying = false

    /** After a long press the strips stay away, so that icons can be dragged; every touch makes it longer. */
    private var watcher: View? = null
    private val resume = Runnable {
        endPause()
        service.recheck()
    }

    private fun pause() {
        service.app.main.removeCallbacks(resume)
        service.app.main.postDelayed(resume, service.app.prefs.zonePause * 1000L)
        if (watcher != null) return
        DebugLog.log("zone", "paused")
        // One pixel that takes no touch; it is only told that something else was touched.
        val v = object : View(service) {
            @SuppressLint("ClickableViewAccessibility")
            override fun onTouchEvent(e: MotionEvent): Boolean {
                if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) pause()
                return false
            }
        }
        val lp = WindowManager.LayoutParams(
            1, 1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.TOP or Gravity.LEFT
        watcher = v
        runCatching { wm.addView(v, lp) }
    }

    private fun endPause() {
        service.app.main.removeCallbacks(resume)
        watcher?.let { v -> runCatching { wm.removeView(v) } }
        watcher = null
    }

    /** True when the strips were put up just now. */
    fun show(on: Boolean): Boolean {
        if (!on) {
            // Leaving the home screen ends the pause, too.
            clear()
            if (!replaying) endPause()
        } else if (strips.isEmpty() && !replaying && watcher == null) {
            build()
            return true
        }
        return false
    }

    fun clear() {
        for (s in strips) runCatching { wm.removeView(s) }
        strips.clear()
    }

    private fun build() {
        val p = service.app.prefs
        val b = if (Build.VERSION.SDK_INT >= 30) wm.maximumWindowMetrics.bounds
        else service.resources.displayMetrics.let { Rect(0, 0, it.widthPixels, it.heightPixels) }
        val w = b.width()
        // The status bar and the bottom gesture area stay the phone's.
        val top = (p.tTop * d).toInt()
        val low = b.height() - (p.tBottom * d).toInt()
        val bar = (p.zoneBottom * d).toInt().coerceAtMost(low - b.height() / 2)
        val side = (p.zoneSide * d).toInt().coerceAtMost(w / 2)
        if (side > 0) {
            add(Rect(0, top, side, low - bar))
            add(Rect(w - side, top, w, low - bar))
        }
        if (bar > 0) add(Rect(0, low - bar, w, low), true)
    }

    private fun add(r: Rect, bottom: Boolean = false) {
        val lp = WindowManager.LayoutParams(
            r.width(), r.height(), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.TOP or Gravity.LEFT
        lp.x = r.left
        lp.y = r.top
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        val v = Strip(bottom)
        if (service.app.prefs.zoneVisible) v.setBackgroundColor(0x3366AAFF)
        runCatching {
            wm.addView(v, lp)
            strips += v
        }.onFailure { DebugLog.log("zone", "add failed", it) }
    }

    /** What lies under a finger: an icon or folder that can be clicked directly, something else, or nothing. */
    private class Under(val icon: AccessibilityNodeInfo?, val empty: Boolean, val pager: AccessibilityNodeInfo? = null)

    /** Looked up while the finger is still down, off the main thread; only the place and kind of the parts is used. */
    private fun under(x: Int, y: Int): Under {
        val root = runCatching { service.freshRoot() }.getOrNull() ?: return Under(null, false)
        val pager = runCatching {
            root.findAccessibilityNodeInfosByViewId("${root.packageName}:id/workspace").firstOrNull { it.isScrollable && it.isVisibleToUser }
        }.getOrNull()
        var best: AccessibilityNodeInfo? = null
        var size = Long.MAX_VALUE
        val r = Rect()
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            n.getBoundsInScreen(r)
            if (!r.contains(x, y) || !n.isVisibleToUser) return
            val a = r.width().toLong() * r.height()
            if (n.isClickable && a <= size) {
                best = n
                size = a
            }
            if (depth < 30) for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1) }
        }
        runCatching { walk(root, 0) }
        val b = best ?: return Under(null, true, pager)
        val cls = b.className?.toString() ?: ""
        return when {
            b.packageName != root.packageName || cls.endsWith("AppWidgetHostView") -> Under(null, false, pager)
            cls == "android.widget.TextView" || cls == "android.widget.LinearLayout" -> Under(b, false, pager)
            else -> Under(null, true, pager)
        }
    }

    /** The strips are taken away first, so the gesture reaches what is under them. */
    private fun replay(points: List<FloatArray>, millis: Long) {
        if (replaying) return
        replaying = true
        clear()
        val path = Path()
        path.moveTo(points[0][0], points[0][1])
        for (q in points.drop(1)) path.lineTo(q[0].coerceAtLeast(0f), q[1].coerceAtLeast(0f))
        fun end() {
            replaying = false
            service.recheck()
        }
        service.app.main.postDelayed({
            val ok = runCatching {
                service.dispatchGesture(
                    GestureDescription.Builder()
                        .addStroke(GestureDescription.StrokeDescription(path, 0, millis.coerceIn(40, 1500))).build(),
                    object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(g: GestureDescription?) = end()
                        override fun onCancelled(g: GestureDescription?) {
                            DebugLog.log("zone", "replay cancelled")
                            end()
                        }
                    },
                    null,
                )
            }.getOrDefault(false)
            if (!ok) {
                DebugLog.log("zone", "replay refused")
                end()
            }
        }, 40)
    }

    private inner class Strip(val bottom: Boolean) : View(service) {
        private val slop = ViewConfiguration.get(service).scaledTouchSlop
        private val points = mutableListOf<FloatArray>()
        private var t0 = 0L
        private var done = false
        private var held = false
        private var first: FloatArray? = null
        private var second = false
        private var touch = 0
        private var seen: Under? = null

        /** The finger is up, but what lay under it is not known yet. */
        private var waiting = false

        /** Whether the launcher has been asked to turn the page in this touch; it may refuse. */
        private var turned = false

        /** No second tap came, so it was a single one for the home screen. */
        private val single = Runnable {
            first?.let { tap(it[0], it[1], 50) }
            first = null
        }

        // A press at one point; jitter must not turn it into a drag.
        private fun tap(x: Float, y: Float, ms: Long) = replay(listOf(floatArrayOf(x, y), floatArrayOf(x, y + 0.5f)), ms)

        private val hold = Runnable {
            held = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    removeCallbacks(single)
                    second = first?.let { abs(it[0] - e.rawX) < 60 * d && abs(it[1] - e.rawY) < 60 * d } == true
                    first = null
                    points.clear()
                    points += floatArrayOf(e.rawX, e.rawY)
                    t0 = SystemClock.uptimeMillis()
                    done = false
                    held = false
                    turned = false
                    val px = e.rawX.toInt()
                    val py = e.rawY.toInt()
                    val n = ++touch
                    seen = null
                    // A second finger-down before the answer: then the first touch counts as one on an empty place.
                    if (waiting) {
                        waiting = false
                        second = true
                    }
                    val from = SystemClock.uptimeMillis()
                    service.app.io.execute {
                        val u = under(px, py)
                        val ms = SystemClock.uptimeMillis() - from
                        post {
                            if (n == touch) {
                                DebugLog.log("zone", "under: icon=${u.icon != null} empty=${u.empty} $ms ms")
                                seen = u
                                if (waiting) decide(u)
                            }
                        }
                    }
                    removeCallbacks(hold)
                    postDelayed(hold, service.app.prefs.tHold.toLong())
                }
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> {
                    removeCallbacks(hold)
                    done = true
                }
                MotionEvent.ACTION_MOVE -> if (!done) {
                    if (points.size < 200) points += floatArrayOf(e.rawX, e.rawY)
                    val dx = e.rawX - points[0][0]
                    val dy = e.rawY - points[0][1]
                    if (abs(dx) > slop || abs(dy) > slop) removeCallbacks(hold)
                    if (!held && abs(dy) >= service.app.prefs.tSwipe * d && abs(dy) > 1.3f * abs(dx)) {
                        done = true
                        swipe(dy < 0)
                    } else if (!held && !turned && abs(dx) >= service.app.prefs.tPage * d && abs(dx) > 1.3f * abs(dy)) {
                        // The page is turned while the finger is still moving; where the launcher does not offer that, it is replayed when the finger is up.
                        val pager = seen?.pager
                        if (pager != null) {
                            turned = true
                            val a = if (dx < 0) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                            // Honor turns the page and still answers "no", so what it offers counts, not the answer.
                            val offered = pager.actionList.any { it.id == a }
                            if (offered) {
                                done = true
                                val ok = pager.performAction(a)
                                DebugLog.log("zone", "page turned=$ok")
                            }
                        }
                    }
                }
                MotionEvent.ACTION_UP -> if (!done) {
                    removeCallbacks(hold)
                    done = true
                    val (x, y) = points[0]
                    val dx = abs(e.rawX - x)
                    val dy = abs(e.rawY - y)
                    val ms = SystemClock.uptimeMillis() - t0
                    if (held) {
                        pause()
                        tap(x, y, 700)
                    } else if (dx < slop && dy < slop) {
                        val u = seen
                        if (u != null) decide(u) else waiting = true
                    } else if (dx > dy) replay(points + listOf(floatArrayOf(e.rawX, e.rawY)), ms)
                }
            }
            return true
        }

        private fun decide(u: Under) {
            waiting = false
            val (x, y) = points[0]
            val twice = service.app.prefs.gestureDoubleTap
            when {
                // Only on an empty place a second tap is waited for; an icon opens at once.
                u.icon != null -> if (!u.icon.performAction(AccessibilityNodeInfo.ACTION_CLICK)) tap(x, y, 50)
                !bottom || twice == Prefs.ACT_NONE || !u.empty -> tap(x, y, 50)
                second -> {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    DebugLog.log("zone", "double tap")
                    Actions.run(service, twice)
                }
                else -> {
                    first = floatArrayOf(x, y)
                    postDelayed(single, service.app.prefs.tDouble.toLong())
                }
            }
        }

        private fun swipe(up: Boolean) {
            val p = service.app.prefs
            val (x, y) = points[0]
            val act = when {
                up -> p.gestureUp
                x < resources.displayMetrics.widthPixels / 2f -> p.gestureDownLeft
                else -> p.gestureDownRight
            }
            DebugLog.log("zone", "swipe ${if (up) "up" else "down"} from ${x.toInt()},${y.toInt()}")
            if (act == Prefs.ACT_NONE) return
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            Actions.run(service, act)
        }
    }

}
