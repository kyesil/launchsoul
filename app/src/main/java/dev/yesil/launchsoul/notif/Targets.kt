package dev.yesil.launchsoul.notif

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.util.LruCache
import dev.yesil.launchsoul.system.DebugLog

/**
 * Where a notification leads when it is tapped, such as one WhatsApp chat. Android gives it only as a live object, so
 * it is kept in memory while the app runs and is lost when the process ends; then the app itself is opened.
 */
object Targets {
    private val cache = LruCache<String, PendingIntent>(800)

    fun keep(key: String, pi: PendingIntent?) {
        if (pi != null) cache.put(key, pi)
    }

    private fun find(key: String): PendingIntent? =
        Listener.instance?.let { l -> runCatching { l.getActiveNotifications(arrayOf(key)) }.getOrNull()?.firstOrNull() }
            ?.notification?.contentIntent ?: cache.get(key)

    fun has(key: String) = key.isNotEmpty() && find(key) != null

    /** False when it can no longer be opened: the app has withdrawn it. */
    fun open(ctx: Context, key: String): Boolean {
        val pi = find(key) ?: return false
        val opts = ActivityOptions.makeBasic()
        if (Build.VERSION.SDK_INT >= 34) {
            @Suppress("DEPRECATION")
            opts.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
        }
        return try {
            pi.send(ctx, 0, null, null, null, null, opts.toBundle())
            true
        } catch (e: PendingIntent.CanceledException) {
            cache.remove(key)
            DebugLog.log("history", "target gone")
            false
        }
    }
}
