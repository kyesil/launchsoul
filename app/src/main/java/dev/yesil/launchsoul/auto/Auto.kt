package dev.yesil.launchsoul.auto

import dev.yesil.launchsoul.tx

import android.Manifest
import android.app.AlarmManager
import android.app.AutomaticZenRule
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.service.notification.Condition
import android.service.notification.ZenPolicy
import android.util.Log
import android.view.KeyEvent
import dev.yesil.launchsoul.App
import dev.yesil.launchsoul.R
import dev.yesil.launchsoul.app
import dev.yesil.launchsoul.notif.Listener
import dev.yesil.launchsoul.settings.SettingsActivity
import java.text.DateFormat
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Date
import kotlin.math.roundToInt

private const val A_REMINDER = "dev.yesil.launchsoul.REMINDER"
private const val A_REMINDER_DONE = "dev.yesil.launchsoul.REMINDER_DONE"
private const val A_REMINDER_LATER = "dev.yesil.launchsoul.REMINDER_LATER"
private const val A_TIMER = "dev.yesil.launchsoul.TIMER"
private const val A_TIMER_MORE = "dev.yesil.launchsoul.TIMER_MORE"
private const val A_TIMER_STOP = "dev.yesil.launchsoul.TIMER_STOP"
private const val A_DND = "dev.yesil.launchsoul.DND"
private const val TIMER_ID = 900_001

private fun pending(ctx: Context, action: String, id: Long = 0): PendingIntent = PendingIntent.getBroadcast(
    ctx, id.toInt(), Intent(ctx, AutoReceiver::class.java).setAction(action).putExtra("id", id),
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
)

private fun openSettings(ctx: Context, screen: String): PendingIntent = PendingIntent.getActivity(
    ctx, screen.hashCode(), Intent(ctx, SettingsActivity::class.java).putExtra("screen", screen),
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
)

fun canExact(ctx: Context): Boolean =
    Build.VERSION.SDK_INT < 31 || ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

/** Not shown as an alarm clock on the lock screen; may be late by minutes when exact alarms are not allowed. */
private fun wakeAt(ctx: Context, at: Long, p: PendingIntent) {
    val am = ctx.getSystemService(AlarmManager::class.java)
    if (canExact(ctx)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
    else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
}

fun clock(t: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(t))

class AutoReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val app = ctx.app
        dev.yesil.launchsoul.system.DebugLog.log("auto", "${i.action}")
        val done = goAsync()
        val wl = ctx.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "launchsoul:auto").apply { acquire(30_000) }
        app.io.execute {
            try {
                val id = i.getLongExtra("id", 0)
                when (i.action) {
                    A_REMINDER -> Reminders.fire(app, id)
                    A_REMINDER_DONE -> Reminders.hide(app, id)
                    A_REMINDER_LATER -> Reminders.later(app, id)
                    A_TIMER -> SleepTimer.finish(app)
                    A_TIMER_MORE -> SleepTimer.extend(app, 15)
                    A_TIMER_STOP -> SleepTimer.cancel(app)
                    A_DND -> HomeDnd.evaluate(app, "saat")
                    else -> { // boot, update, clock or time zone change, exact alarm permission
                        Reminders.rescheduleAll(app)
                        SleepTimer.restore(app)
                        HomeDnd.evaluate(app, tx(R.string.why_restart))
                        Listener.rebind(app)
                    }
                }
            } catch (e: Exception) {
                Log.e("LaunchSoul", "auto ${i.action}", e)
                dev.yesil.launchsoul.system.DebugLog.log("auto", "error", e)
            } finally {
                runCatching { wl.release() }
                done.finish()
            }
        }
    }
}

/** Our own reminders: the system treats them as alarm clocks, so it does not shift them. */
object Reminders {
    fun add(app: App, at: Long, text: String) {
        app.db.execute {
            runCatching {
                val id = app.history.addReminder(at, text)
                schedule(app, id, at)
            }.onFailure { Log.e("LaunchSoul", "reminder", it) }
        }
    }

    fun delete(app: App, id: Long) {
        app.history.markFired(id)
        app.getSystemService(AlarmManager::class.java).cancel(pending(app, A_REMINDER, id))
    }

    fun rescheduleAll(app: App) {
        for (r in app.history.reminders()) schedule(app, r.id, r.at)
    }

    private fun schedule(app: App, id: Long, at: Long) {
        val am = app.getSystemService(AlarmManager::class.java)
        val p = pending(app, A_REMINDER, id)
        if (canExact(app)) {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(at, openSettings(app, "reminders")), p)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
        }
    }

    fun fire(app: App, id: Long) {
        val r = app.history.reminder(id) ?: return
        app.history.markFired(id)
        val late = System.currentTimeMillis() - r.at
        dev.yesil.launchsoul.system.DebugLog.log("reminder", "late ${late / 1000}s")
        app.history.log(tx(R.string.log_reminder_late, r.text.take(60), late / 1000))
        val n = Notification.Builder(app, App.CH_REMINDER)
            .setSmallIcon(R.drawable.ic_bell)
            .setContentTitle(r.text)
            .setContentText(if (late > 90_000) tx(R.string.reminder_was_due, clock(r.at)) else clock(r.at))
            .setStyle(Notification.BigTextStyle().bigText(r.text))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setWhen(r.at)
            .setShowWhen(true)
            .setContentIntent(openSettings(app, "reminders"))
            .addAction(Notification.Action.Builder(null, tx(R.string.ok), pending(app, A_REMINDER_DONE, id)).build())
            .addAction(Notification.Action.Builder(null, tx(R.string.in_10_min), pending(app, A_REMINDER_LATER, id)).build())
            .build()
        app.getSystemService(NotificationManager::class.java).notify(id.toInt(), n)
    }

    fun hide(app: App, id: Long) = app.getSystemService(NotificationManager::class.java).cancel(id.toInt())

    fun later(app: App, id: Long) {
        hide(app, id)
        app.history.reminder(id)?.let { add(app, System.currentTimeMillis() + 10 * 60_000, it.text) }
    }
}

/** Fades the sound, pauses whatever is playing, then puts the volume back. */
object SleepTimer {
    fun start(app: App, minutes: Int) = set(app, System.currentTimeMillis() + minutes * 60_000L)

    fun extend(app: App, minutes: Int) =
        set(app, maxOf(app.prefs.timerEnd, System.currentTimeMillis()) + minutes * 60_000L)

    private fun set(app: App, end: Long) {
        app.prefs.timerEnd = end
        wakeAt(app, end, pending(app, A_TIMER))
        val n = Notification.Builder(app, App.CH_TIMER)
            .setSmallIcon(R.drawable.ic_moon)
            .setContentTitle(tx(R.string.timer))
            .setContentText(tx(R.string.timer_running))
            .setOngoing(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setWhen(end)
            .setShowWhen(true)
            .setContentIntent(openSettings(app, "timer"))
            .addAction(Notification.Action.Builder(null, tx(R.string.plus_15_min), pending(app, A_TIMER_MORE)).build())
            .addAction(Notification.Action.Builder(null, tx(R.string.cancel), pending(app, A_TIMER_STOP)).build())
            .build()
        app.getSystemService(NotificationManager::class.java).notify(TIMER_ID, n)
    }

    fun cancel(app: App) {
        app.prefs.timerEnd = 0
        app.getSystemService(AlarmManager::class.java).cancel(pending(app, A_TIMER))
        app.getSystemService(NotificationManager::class.java).cancel(TIMER_ID)
    }

    fun restore(app: App) {
        val end = app.prefs.timerEnd
        if (end == 0L) return
        if (end > System.currentTimeMillis()) set(app, end) else cancel(app)
    }

    fun finish(app: App) {
        val planned = app.prefs.timerEnd
        if (planned == 0L) return
        if (planned - System.currentTimeMillis() > 5_000) return // extended meanwhile
        cancel(app)
        val am = app.getSystemService(AudioManager::class.java)
        if (!am.isMusicActive) {
            app.history.log(tx(R.string.log_timer_nothing))
            return
        }
        val start = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        try {
            // Stays under the time a broadcast may take.
            for (step in 1..12) {
                val v = (start * (1 - step / 12f)).roundToInt()
                runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0) }
                Thread.sleep(400)
            }
            pauseAll(app, am)
            Thread.sleep(800)
        } finally {
            runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, start, 0) }
        }
        Thread.sleep(300)
        app.history.log(
            tx(R.string.log_timer_done, (System.currentTimeMillis() - planned) / 1000) +
                if (am.isMusicActive) tx(R.string.log_timer_not_stopped) else tx(R.string.log_timer_stopped),
        )
    }

    private fun pauseAll(app: App, am: AudioManager) {
        if (Listener.allowed(app)) runCatching {
            val msm = app.getSystemService(MediaSessionManager::class.java)
            for (c in msm.getActiveSessions(Listener.component(app))) {
                val st = c.playbackState?.state
                if (st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_BUFFERING) c.transportControls.pause()
            }
        }
        Thread.sleep(400)
        if (am.isMusicActive) {
            // Works without any permission for the app that last had the media button.
            for (a in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                am.dispatchMediaKeyEvent(KeyEvent(a, KeyEvent.KEYCODE_MEDIA_PAUSE))
            }
        }
    }
}

/**
 * "Do not disturb at night, only at home." Safe direction: whenever something is unknown the phone rings.
 * Uses our own zen rule, so it can only switch off what it switched on itself.
 */
object HomeDnd {
    private const val LOST_GRACE = 3 * 60_000L
    private val condition: Uri = Uri.parse("condition://dev.yesil.launchsoul/night")

    @Volatile private var wifi: LinkProperties? = null

    fun init(app: App) {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        runCatching {
            cm.registerNetworkCallback(req, object : ConnectivityManager.NetworkCallback() {
                override fun onLinkPropertiesChanged(n: Network, lp: LinkProperties) {
                    wifi = lp
                    app.prefs.dndLostAt = 0
                    app.io.execute { runCatching { evaluate(app, tx(R.string.why_wifi_on)) } }
                }

                override fun onLost(n: Network) {
                    wifi = null
                    app.prefs.dndLostAt = System.currentTimeMillis()
                    app.io.execute { runCatching { evaluate(app, tx(R.string.why_wifi_lost)) } }
                }
            })
        }
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                app.io.execute { runCatching { evaluate(app, tx(R.string.why_power)) } }
            }
        }, f)
        app.io.execute { runCatching { if (app.prefs.dndOn) evaluate(app, tx(R.string.why_start)) } }
    }

    /** Gateway, DNS and local prefixes of the current Wi-Fi: no location permission needed, but not unique. */
    fun fingerprint(): String? {
        val lp = wifi ?: return null
        val gw = lp.routes.filter { it.isDefaultRoute }.mapNotNull { it.gateway?.hostAddress?.substringBefore('%') }.sorted()
        if (gw.isEmpty()) return null
        val dns = lp.dnsServers.mapNotNull { it.hostAddress?.substringBefore('%') }.sorted()
        val extra = listOfNotNull(lp.domains, if (Build.VERSION.SDK_INT >= 30) lp.dhcpServerAddress?.hostAddress else null)
        // Link-local IPv6 gateways come from the router's hardware address, which makes the print much stronger.
        return (gw + "|" + dns + "|" + extra).joinToString(",")
    }

    /** Name of the connected Wi-Fi; null when not connected, or without location permission or with location off. */
    @Suppress("DEPRECATION")
    fun ssid(app: App): String? = runCatching {
        app.getSystemService(android.net.wifi.WifiManager::class.java).connectionInfo?.ssid
            ?.takeIf { it.startsWith("\"") && it.length > 2 }?.trim('"')
    }.getOrNull()

    /** Names of the networks around, from the phone's last scan. */
    @Suppress("DEPRECATION", "MissingPermission")
    fun nearby(app: App): List<String> = runCatching {
        app.getSystemService(android.net.wifi.WifiManager::class.java).scanResults
            .sortedByDescending { it.level }.mapNotNull { it.SSID?.takeIf { s -> s.isNotBlank() } }.distinct()
    }.getOrDefault(emptyList())

    fun nameKnown(app: App, ssid: String?) = ssid != null && app.prefs.dndNames.any { it.equals(ssid, ignoreCase = true) }

    fun allowed(app: App) = app.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted

    private fun charging(app: App): Boolean {
        val i = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        return i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    }

    private fun inNight(app: App, now: LocalTime = LocalTime.now()): Boolean {
        val m = now.hour * 60 + now.minute
        val s = app.prefs.dndStart
        val e = app.prefs.dndEnd
        return if (s <= e) m in s until e else m >= s || m < e
    }

    @Synchronized
    fun evaluate(app: App, why: String) {
        val p = app.prefs
        schedule(app)
        if (!allowed(app)) return
        if (!p.dndOn && p.dndRuleId.isEmpty()) return
        val night = inNight(app)
        val print = fingerprint()
        val name = if (print == null || p.dndNames.isEmpty()) null else ssid(app)
        var home = print != null && (print in p.dndHomes || nameKnown(app, name))
        val lost = p.dndLostAt
        if (!home && print == null && lost > 0 && System.currentTimeMillis() - lost < LOST_GRACE && active) home = true
        val power = !p.dndCharging || charging(app)
        val on = p.dndOn && night && home && power
        val reason = when {
            !p.dndOn -> tx(R.string.dnd_r_off)
            !night -> tx(R.string.dnd_r_day)
            !home -> if (print == null) tx(R.string.dnd_r_no_wifi) else tx(R.string.dnd_r_not_home)
            !power -> tx(R.string.dnd_r_not_charging)
            else -> tx(R.string.dnd_r_home_night)
        }
        val nm = app.getSystemService(NotificationManager::class.java)
        val id = ensureRule(app, nm) ?: return
        nm.setAutomaticZenRuleState(
            id, Condition(condition, reason, if (on) Condition.STATE_TRUE else Condition.STATE_FALSE),
        )
        if (on != active || first) app.history.log(tx(if (on) R.string.log_dnd_on else R.string.log_dnd_off, reason, why))
        dev.yesil.launchsoul.system.DebugLog.log("dnd", "on=$on night=$night home=$home power=$power wifi=${print != null} name=${name != null}/${p.dndNames.size} ($why)")
        active = on
        first = false
    }

    @Volatile var active = false
        private set
    private var first = true

    private fun ensureRule(app: App, nm: NotificationManager): String? = try {
        val have = app.prefs.dndRuleId
        if (have.isNotEmpty() && nm.getAutomaticZenRule(have) != null) have
        else if (!app.prefs.dndOn) {
            app.prefs.dndRuleId = ""
            null
        } else {
            val policy = ZenPolicy.Builder().allowAlarms(true).allowReminders(true).allowMedia(true)
                .allowCalls(ZenPolicy.PEOPLE_TYPE_STARRED).allowRepeatCallers(true).build()
            val rule = AutomaticZenRule(
                tx(R.string.dnd_rule_name), null, ComponentName(app, SettingsActivity::class.java), condition, policy,
                NotificationManager.INTERRUPTION_FILTER_PRIORITY, true,
            )
            nm.addAutomaticZenRule(rule).also { app.prefs.dndRuleId = it }
        }
    } catch (e: Exception) {
        app.history.log(tx(R.string.log_dnd_rule_failed, e.message))
        null
    }

    /** Next start or end; during the night also a check every 15 minutes in case a signal was missed. */
    private fun schedule(app: App) {
        val pi = pending(app, A_DND)
        val am = app.getSystemService(AlarmManager::class.java)
        if (!app.prefs.dndOn) {
            am.cancel(pi)
            return
        }
        val now = ZonedDateTime.now()
        fun next(min: Int): Long {
            var t = now.toLocalDate().atTime(min / 60, min % 60).atZone(ZoneId.systemDefault())
            if (!t.isAfter(now)) t = t.plusDays(1)
            return t.toInstant().toEpochMilli()
        }
        var at = minOf(next(app.prefs.dndStart), next(app.prefs.dndEnd)) + 1_000
        if (inNight(app)) {
            at = minOf(at, System.currentTimeMillis() + 15 * 60_000)
            val lost = app.prefs.dndLostAt
            if (lost > 0 && lost + LOST_GRACE > System.currentTimeMillis()) at = minOf(at, lost + LOST_GRACE + 1_000)
        }
        wakeAt(app, at, pi)
    }
}

fun hasPermission(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

fun canNotify(ctx: Context) =
    Build.VERSION.SDK_INT < 33 || hasPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
