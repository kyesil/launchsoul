package dev.yesil.launchsoul.data

import dev.yesil.launchsoul.R
import dev.yesil.launchsoul.tx

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.Rect
import android.os.Bundle
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.widget.Toast
import androidx.core.net.toUri
import dev.yesil.launchsoul.App
import dev.yesil.launchsoul.core.fold
import java.text.Collator
import java.util.Locale

class AppInfo(
    val key: String,
    val label: String,
    val component: ComponentName,
    val user: UserHandle,
    val info: LauncherActivityInfo,
) {
    val folded = fold(label)
    val pkg: String get() = component.packageName
}

/** Installed apps with a launcher icon, in all profiles. */
class AppRepo(private val app: App) {
    private val la = app.getSystemService(LauncherApps::class.java)
    private val um = app.getSystemService(UserManager::class.java)

    @Volatile var list: List<AppInfo> = emptyList()
        private set

    @Volatile private var byKey: Map<String, AppInfo> = emptyMap()

    /** Called on the main thread after the list changed, with the package that changed (or null for all). */
    val listeners = mutableListOf<(String?) -> Unit>()

    private val launches = app.getSharedPreferences("launches", Context.MODE_PRIVATE)

    init {
        load()
        la.registerCallback(object : LauncherApps.Callback() {
            override fun onPackageRemoved(p: String, u: UserHandle) = reload(p)
            override fun onPackageAdded(p: String, u: UserHandle) = reload(p)
            override fun onPackageChanged(p: String, u: UserHandle) = reload(p)
            override fun onPackagesAvailable(p: Array<out String>, u: UserHandle, r: Boolean) = reload(null)
            override fun onPackagesUnavailable(p: Array<out String>, u: UserHandle, r: Boolean) = reload(null)
        }, app.main)
    }

    private fun reload(pkg: String?) {
        app.io.execute {
            load()
            app.main.post {
                if (pkg != null) app.icons.forgetPackage(pkg) else app.icons.clear()
                listeners.toList().forEach { it(pkg) }
            }
        }
    }

    @Synchronized
    private fun load() {
        val out = ArrayList<AppInfo>()
        for (u in la.profiles) {
            val serial = um.getSerialNumberForUser(u)
            val acts = runCatching { la.getActivityList(null, u) }.getOrDefault(emptyList())
            for (a in acts) {
                if (a.componentName.packageName == app.packageName) continue
                val key = a.componentName.flattenToString() + "#" + serial
                out += AppInfo(key, a.label?.toString() ?: a.name, a.componentName, u, a)
            }
        }
        val c = Collator.getInstance(Locale.getDefault())
        out.sortWith { a, b -> c.compare(a.label, b.label) }
        list = out
        byKey = out.associateBy { it.key }
    }

    operator fun get(key: String): AppInfo? = byKey[key]

    fun visible(): List<AppInfo> {
        val h = app.prefs.hidden
        return list.filter { it.key !in h }
    }

    fun launch(key: String, bounds: Rect? = null, opts: Bundle? = null): Boolean {
        val a = byKey[key]
        if (a == null) {
            Toast.makeText(app, tx(R.string.app_not_found), Toast.LENGTH_SHORT).show()
            return false
        }
        return try {
            la.startMainActivity(a.component, a.user, bounds, opts)
            launches.edit().putInt(key, launches.getInt(key, 0) + 1).putLong("last:$key", System.currentTimeMillis()).apply()
            true
        } catch (e: Exception) {
            dev.yesil.launchsoul.system.DebugLog.log("launch", key, e)
            Toast.makeText(app, tx(R.string.could_not_open_x, e.message), Toast.LENGTH_LONG).show()
            false
        }
    }

    fun count(key: String) = launches.getInt(key, 0)

    /** For things that are not apps, such as contacts. */
    fun picked(key: String) = launches.edit().putInt(key, count(key) + 1).apply()

    /** The most used apps: first what was started here, then by the phone's usage time when that is allowed. */
    fun suggested(n: Int): List<AppInfo> {
        val use = usage()
        return visible().sortedWith(
            compareByDescending<AppInfo> { if (use.isEmpty()) count(it.key).toLong() else use[it.pkg] ?: 0L }
                .thenByDescending { count(it.key) },
        ).filter { use.isEmpty() || (use[it.pkg] ?: 0) > 0 || count(it.key) > 0 }.take(n)
    }

    fun usageAllowed(): Boolean = app.getSystemService(android.app.AppOpsManager::class.java)
        .unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), app.packageName) ==
        android.app.AppOpsManager.MODE_ALLOWED

    private var usageAt = 0L
    private var usage = emptyMap<String, Long>()

    /** The apps used last: by the phone when that is allowed, else those started here. Reads the system, so not on the main thread. */
    fun recent(n: Int): List<AppInfo> {
        val now = System.currentTimeMillis()
        val last = HashMap<String, Long>()
        if (usageAllowed()) runCatching {
            val ev = app.getSystemService(android.app.usage.UsageStatsManager::class.java).queryEvents(now - 3L * 86_400_000, now)
            val e = android.app.usage.UsageEvents.Event()
            while (ev.getNextEvent(e)) {
                if (e.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED) last[e.packageName] = e.timeStamp
            }
        }
        return visible().map { it to maxOf(last[it.pkg] ?: 0, launches.getLong("last:${it.key}", 0)) }
            .filter { it.second > 0 }.sortedByDescending { it.second }.take(n).map { it.first }
    }

    /** Time in front in the last 30 days per package; empty without permission. Read at most once in ten minutes. */
    @Synchronized
    private fun usage(): Map<String, Long> {
        val now = System.currentTimeMillis()
        if (now - usageAt < 600_000 && usage.isNotEmpty()) return usage
        usage = if (!usageAllowed()) emptyMap() else runCatching {
            app.getSystemService(android.app.usage.UsageStatsManager::class.java)
                .queryAndAggregateUsageStats(now - 30L * 86_400_000, now).mapValues { it.value.totalTimeInForeground }
        }.getOrDefault(emptyMap())
        usageAt = now
        return usage
    }

    fun details(key: String) {
        val a = byKey[key] ?: return
        runCatching { la.startAppDetailsActivity(a.component, a.user, null, null) }
    }

    fun uninstall(ctx: Context, key: String) {
        val a = byKey[key] ?: return
        runCatching {
            ctx.startActivity(
                Intent(Intent.ACTION_DELETE, "package:${a.pkg}".toUri()).putExtra(Intent.EXTRA_USER, a.user)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    fun keyForPackage(pkg: String) = list.firstOrNull { it.pkg == pkg && it.user == Process.myUserHandle() }?.key

    fun labelOf(pkg: String): String = list.firstOrNull { it.pkg == pkg }?.label ?: runCatching {
        app.packageManager.run { getApplicationLabel(getApplicationInfo(pkg, 0)).toString() }
    }.getOrDefault(pkg)
}
