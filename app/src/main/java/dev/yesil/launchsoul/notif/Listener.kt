package dev.yesil.launchsoul.notif

import dev.yesil.launchsoul.R
import dev.yesil.launchsoul.tx

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import dev.yesil.launchsoul.App
import dev.yesil.launchsoul.app
import dev.yesil.launchsoul.auto.Reminders
import dev.yesil.launchsoul.core.fold
import java.util.Locale

/** Unread counts per package, for the icon badges. Main thread only. */
object Badges {
    var counts: Map<String, Int> = emptyMap()
        private set
    val listeners = mutableListOf<() -> Unit>()

    fun set(c: Map<String, Int>) {
        if (c == counts) return
        counts = c
        listeners.toList().forEach { it() }
    }
}

class Listener : NotificationListenerService() {
    private var tts: TextToSpeech? = null
    private val lastRun = HashMap<String, Long>()

    override fun onListenerConnected() {
        instance = this
        dev.yesil.launchsoul.system.DebugLog.log("listener", "connected")
        val p = app.prefs
        val now = System.currentTimeMillis()
        val active = runCatching { activeNotifications.toList() }.getOrDefault(emptyList())
        if (p.historyOn) app.db.execute {
            safe {
                val last = p.lastHeartbeat
                if (last > 0 && now - last > 2 * 60_000L) app.history.addGap(last, now)
                app.history.closeMissing(active.map { it.key }.toSet())
                for (s in active) record(s)
                app.history.prune(p.historyDays)
            }
        }
        beat()
        updateBadges()
    }

    override fun onListenerDisconnected() {
        instance = null
        dev.yesil.launchsoul.system.DebugLog.log("listener", "disconnected")
        app.main.removeCallbacks(beatRun)
        app.prefs.lastHeartbeat = System.currentTimeMillis()
        app.main.post { Badges.set(emptyMap()) }
    }

    private val beatRun = Runnable { beat() }

    /** The time we were last known to be listening; a hole in it is shown in the history. */
    private fun beat() {
        app.prefs.lastHeartbeat = System.currentTimeMillis()
        app.main.removeCallbacks(beatRun)
        app.main.postDelayed(beatRun, 60_000L)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        app.prefs.lastHeartbeat = System.currentTimeMillis()
        updateBadges()
        if (sbn.packageName == packageName) return
        if (app.prefs.historyOn) app.db.execute { safe { record(sbn) } }
        if (app.prefs.rulesOn && !sbn.isOngoing) app.db.execute { safe { applyRules(sbn) } }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, r: RankingMap?, reason: Int) {
        app.prefs.lastHeartbeat = System.currentTimeMillis()
        updateBadges()
        if (app.prefs.historyOn) app.db.execute { safe { app.history.removed(sbn.key, reason) } }
    }

    private fun record(s: StatusBarNotification) {
        val n = s.notification
        if (s.packageName == packageName || s.isOngoing || s.packageName in app.prefs.historyExcluded) return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (n.category == Notification.CATEGORY_TRANSPORT || n.category == Notification.CATEGORY_PROGRESS) return
        val title = title(n)
        val text = text(n)
        if (title.isBlank() && text.isBlank()) return
        app.history.posted(s.key, s.packageName, title, text, s.postTime)
    }

    private fun applyRules(s: StatusBarNotification) {
        val words = fold(title(s.notification) + " " + text(s.notification))
        for (r in app.history.rules()) {
            if (!r.on) continue
            if (r.pkg.isNotEmpty() && r.pkg != s.packageName) continue
            if (r.contains.isNotBlank() && !words.contains(fold(r.contains.trim()))) continue
            // The same rule on the same notification at most once per 10 s, against update loops.
            val k = "${r.id}|${s.key}"
            val now = System.currentTimeMillis()
            if (now - (lastRun[k] ?: 0) < 10_000) continue
            lastRun[k] = now
            if (lastRun.size > 500) lastRun.clear()
            val what = "${RuleAction.names[r.action]}: ${app.apps.labelOf(s.packageName)} – ${title(s.notification).take(60)}"
            if (r.dryRun) {
                app.history.log(tx(R.string.log_rule_test, what))
                continue
            }
            run(r, s)
            app.history.log(tx(R.string.log_rule_done, what))
        }
    }

    private fun run(r: Rule, s: StatusBarNotification) {
        val minutes = r.arg.toLongOrNull()?.coerceIn(1, 1440) ?: 30
        when (r.action) {
            RuleAction.DISMISS -> cancelNotification(s.key)
            RuleAction.SNOOZE -> snoozeNotification(s.key, minutes * 60_000)
            RuleAction.VIBRATE -> getSystemService(Vibrator::class.java)
                .vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 200, 600, 200, 600), -1))
            RuleAction.SPEAK -> speak("${app.apps.labelOf(s.packageName)}. ${title(s.notification)}. ${text(s.notification)}")
            RuleAction.OPEN_LATER -> Reminders.add(
                app, System.currentTimeMillis() + minutes * 60_000,
                "${app.apps.labelOf(s.packageName)}: ${title(s.notification)} ${text(s.notification)}".take(300),
            )
        }
    }

    private fun speak(t: String) {
        val e = tts
        if (e != null) {
            e.speak(t, TextToSpeech.QUEUE_ADD, null, "n")
            return
        }
        app.main.post {
            tts = TextToSpeech(this) { ok ->
                if (ok == TextToSpeech.SUCCESS) {
                    tts?.language = Locale.getDefault()
                    tts?.speak(t, TextToSpeech.QUEUE_ADD, null, "n")
                } else tts = null
            }
        }
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }

    private fun updateBadges() {
        val counts = HashMap<String, Int>()
        for (s in runCatching { activeNotifications }.getOrNull() ?: return) {
            if (s.isOngoing || !s.isClearable) continue
            val n = s.notification
            if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) continue
            val r = Ranking()
            if (currentRanking.getRanking(s.key, r) && !r.canShowBadge()) continue
            counts[s.packageName] = (counts[s.packageName] ?: 0) + n.number.coerceIn(1, 99)
        }
        app.main.post { Badges.set(counts) }
    }

    private inline fun safe(f: () -> Unit) {
        try {
            f()
        } catch (e: Exception) {
            Log.e("LaunchSoul", "notification", e)
            dev.yesil.launchsoul.system.DebugLog.log("listener", "error", e)
        }
    }

    companion object {
        @Volatile var instance: Listener? = null
            private set

        fun title(n: Notification): String = n.extras.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString()
            ?: n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""

        fun text(n: Notification): String {
            val e = n.extras
            e.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
            e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.takeIf { it.isNotEmpty() }
                ?.let { return it.joinToString("\n") }
            return e.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        }

        fun component(ctx: Context) = ComponentName(ctx, Listener::class.java)

        fun allowed(ctx: Context) = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

        /** Tells the system to connect again; used when a battery saver killed us. */
        fun rebind(app: App) {
            if (allowed(app) && instance == null) runCatching { requestRebind(component(app)) }
        }

        val settingsAction get() = Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
    }
}
