package dev.yesil.launchsoul.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.LruCache
import android.util.TypedValue
import androidx.core.graphics.createBitmap
import dev.yesil.launchsoul.App
import dev.yesil.launchsoul.system.DebugLog
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

/** Superellipse of the given exponent: 2 is a circle, 5 the iOS-like squircle, larger is squarer. */
fun squircle(left: Float, top: Float, w: Float, h: Float, n: Float): Path {
    val p = Path()
    val steps = 180
    for (i in 0..steps) {
        val t = 2.0 * Math.PI * i / steps
        val x = sign(cos(t)) * abs(cos(t)).pow(2.0 / n)
        val y = sign(sin(t)) * abs(sin(t)).pow(2.0 / n)
        val px = left + w / 2f * (1f + x.toFloat())
        val py = top + h / 2f * (1f + y.toFloat())
        if (i == 0) p.moveTo(px, py) else p.lineTo(px, py)
    }
    p.close()
    return p
}

/** Every icon is drawn once at a fixed size in the chosen shape. */
class IconCache(private val app: App) {
    private val size = (app.resources.displayMetrics.density * 72).toInt().coerceAtLeast(96)
    private val cache = LruCache<String, Bitmap>(400)
    private val pending = HashSet<String>()

    /** Called on the main thread when an icon has been loaded. */
    val listeners = mutableListOf<(String) -> Unit>()

    /** The icon if it is ready; otherwise null, and it is loaded in the background. */
    fun get(key: String): Bitmap? {
        cache.get(key)?.let { return it }
        synchronized(pending) { if (!pending.add(key)) return null }
        app.io.execute {
            val b = runCatching { load(key) }.getOrNull()
            app.main.post {
                synchronized(pending) { pending.remove(key) }
                if (b != null) {
                    cache.put(key, b)
                    listeners.toList().forEach { it(key) }
                }
            }
        }
        return null
    }

    fun forgetPackage(pkg: String) {
        for (k in cache.snapshot().keys) if (k.startsWith("$pkg/")) cache.remove(k)
    }

    fun clear() = cache.evictAll()

    private fun load(key: String): Bitmap? {
        val a = app.apps[key] ?: return null
        val d = own(a) ?: a.info.getIcon(640) ?: a.info.getIcon(0) ?: return null
        val shaped = shape(d)
        return if (a.user == android.os.Process.myUserHandle()) shaped else {
            // Work profile mark.
            val badged = app.packageManager.getUserBadgedIcon(
                android.graphics.drawable.BitmapDrawable(app.resources, shaped), a.user,
            )
            val out = createBitmap(size, size)
            badged.setBounds(0, 0, size, size)
            badged.draw(Canvas(out))
            out
        }
    }

    /**
     * The icon as the app ships it. Some phones (Honor) give launchers an already framed bitmap instead, so the
     * resource is read by hand, which their icon theme does not touch.
     */
    private fun own(a: AppInfo): Drawable? = try {
        val pm = app.packageManager
        val ai = if (Build.VERSION.SDK_INT >= 31) a.info.activityInfo else pm.getActivityInfo(a.component, 0)
        val id = if (ai.icon != 0) ai.icon else ai.applicationInfo.icon
        if (id == 0) null else {
            val res = pm.getResourcesForApplication(a.info.applicationInfo)
            val v = TypedValue()
            res.getValueForDensity(id, 640, v, true)
            if (v.string?.endsWith(".xml") == true) res.getXml(id).use { Drawable.createFromXml(res, it, null) }
            else BitmapFactory.decodeStream(res.openRawResource(id, v))?.let { BitmapDrawable(res, it) }
        }
    } catch (e: Exception) {
        DebugLog.log("icon", "own icon of ${a.pkg} failed: $e")
        null
    }

    /** A bitmap that already is a rounded square (maybe with a margin): its area and edge colour, else null. */
    private fun framed(b: Bitmap): Pair<Rect, Int>? {
        var l = size
        var t = size
        var r = -1
        var bo = -1
        val px = IntArray(size * size)
        b.getPixels(px, 0, size, 0, 0, size, size)
        for (y in 0 until size) for (x in 0 until size) if (Color.alpha(px[y * size + x]) > 200) {
            if (x < l) l = x
            if (x > r) r = x
            if (y < t) t = y
            if (y > bo) bo = y
        }
        val w = r - l + 1
        val h = bo - t + 1
        if (w < size * 0.5f || abs(w - h) > size * 0.06f) return null
        var solid = 0
        for (y in t..bo) for (x in l..r) if (Color.alpha(px[y * size + x]) > 200) solid++
        // A circle covers 0.785 of its square, a rounded square more.
        if (solid < w * h * 0.86f) return null
        val c = px[(t + (h * 0.04f).toInt()) * size + l + w / 2]
        return Rect(l, t, r + 1, bo + 1) to (c or 0xFF000000.toInt())
    }

    fun shape(d: Drawable): Bitmap {
        val n = app.prefs.iconCurve / 10f
        val out = createBitmap(size, size)
        val c = Canvas(out)
        val s = size.toFloat()
        if (d is AdaptiveIconDrawable) {
            // The layers are 108 units for a 72 unit window: draw them 1.5 times as large.
            val extra = (s * 0.25f).toInt()
            for (layer in listOf(d.background, d.foreground)) {
                layer ?: continue
                layer.setBounds(-extra, -extra, size + extra, size + extra)
                layer.draw(c)
            }
        } else {
            val raw = createBitmap(size, size)
            d.setBounds(0, 0, size, size)
            d.draw(Canvas(raw))
            val frame = if (isFullSquare(raw)) null else framed(raw)
            if (frame != null) {
                // Grow it over our shape; its own rounded corners are filled with its edge colour.
                c.drawColor(frame.second)
                c.drawBitmap(
                    raw, frame.first, android.graphics.RectF(0f, 0f, s, s),
                    Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG),
                )
            } else if (isFullSquare(raw)) {
                c.drawBitmap(raw, 0f, 0f, null)
            } else {
                c.drawColor(plateColor(raw))
                val pad = s * 0.13f
                c.drawBitmap(
                    raw, null, android.graphics.RectF(pad, pad, s - pad, s - pad),
                    Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG),
                )
            }
        }
        // Keep only what is inside the shape, with smooth edges.
        val mask = createBitmap(size, size)
        Canvas(mask).drawPath(squircle(0.5f, 0.5f, s - 1f, s - 1f, n), Paint(Paint.ANTI_ALIAS_FLAG))
        c.drawBitmap(mask, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
        return out
    }

    private fun isFullSquare(b: Bitmap): Boolean {
        val m = size - 1
        val e = (size * 0.06f).toInt()
        return listOf(e to e, m - e to e, e to m - e, m - e to m - e, size / 2 to e, size / 2 to m - e, e to size / 2, m - e to size / 2)
            .all { (x, y) -> Color.alpha(b.getPixel(x, y)) > 240 }
    }

    /** White, or dark when the picture itself is almost white. */
    private fun plateColor(b: Bitmap): Int {
        val small = Bitmap.createScaledBitmap(b, 16, 16, true)
        var lum = 0.0
        var cnt = 0
        for (y in 0 until 16) for (x in 0 until 16) {
            val p = small.getPixel(x, y)
            if (Color.alpha(p) < 200) continue
            lum += Color.luminance(p)
            cnt++
        }
        return if (cnt > 0 && lum / cnt > 0.8) Color.rgb(44, 46, 52) else Color.WHITE
    }
}
