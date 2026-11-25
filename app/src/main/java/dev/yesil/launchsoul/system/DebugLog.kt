package dev.yesil.launchsoul.system

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import androidx.core.content.FileProvider
import dev.yesil.launchsoul.App
import dev.yesil.launchsoul.app
import dev.yesil.launchsoul.auto.HomeDnd
import dev.yesil.launchsoul.auto.canExact
import dev.yesil.launchsoul.auto.canNotify
import dev.yesil.launchsoul.notif.Listener
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * A small log on the phone that the user can share. It never gets notification texts, contacts, search words,
 * reminders or network names; app package names do appear.
 */
object DebugLog {
    private const val MAX = 400_000L
    private val exec = Executors.newSingleThreadExecutor()
    private lateinit var app: App

    private val now get() = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT).format(Date())
    private val file get() = File(app.filesDir, "debug.log")
    private val old get() = File(app.filesDir, "debug.old.log")

    fun init(a: App) {
        app = a
        val next = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            // Written at once: the process is about to end.
            if (app.prefs.debugLog) runCatching { file.appendText("$now CRASH ${t.name}\n${e.stackTraceToString()}\n") }
            next?.uncaughtException(t, e)
        }
        log("start", "process ${Process.myPid()}")
    }

    fun log(tag: String, msg: String, e: Throwable? = null) {
        if (!::app.isInitialized || !app.prefs.debugLog) return
        android.util.Log.i("LaunchSoul", "$tag: $msg")
        val line = "$now $tag: $msg" + (e?.let { "\n" + it.stackTraceToString() } ?: "") + "\n"
        exec.execute {
            runCatching {
                if (file.length() > MAX) file.renameTo(old)
                file.appendText(line)
            }
        }
    }

    fun clear() = exec.execute {
        file.delete()
        old.delete()
    }

    private fun header(): String = buildString {
        val v = app.packageManager.getPackageInfo(app.packageName, 0)
        val m = app.resources.displayMetrics
        val p = app.prefs
        appendLine("Better Launch Soul ${v.versionName} (${v.longVersionCode})")
        appendLine("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (${Build.VERSION.SDK_INT}) · ${Build.DISPLAY}")
        appendLine("${m.widthPixels}x${m.heightPixels} ${m.densityDpi}dpi font ${app.resources.configuration.fontScale}")
        appendLine(
            "listener allowed=${Listener.allowed(app)} connected=${Listener.instance != null} · a11y=${SoulAccessibility.instance != null}" +
                " · zones=${app.prefs.zoneOn} · notify=${canNotify(app)} exact=${canExact(app)} dnd=${HomeDnd.allowed(app)}",
        )
        appendLine(
            "zones ${p.zoneSide}/${p.zoneBottom} curve ${p.iconCurve} panel=${p.panelPath}" +
                " down=${p.gestureDownLeft}/${p.gestureDownRight} up=${p.gestureUp} double=${p.gestureDoubleTap}",
        )
        appendLine(
            "history=${p.historyOn} rules=${p.rulesOn} edge=${p.edgeEnabled} dndOn=${p.dndOn} homes=${p.dndHomes.size} apps=${app.apps.list.size}",
        )
        if (Build.VERSION.SDK_INT >= 30) runCatching {
            appendLine("-- last exits")
            for (x in app.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(app.packageName, 0, 10)) {
                appendLine("${Date(x.timestamp)} reason=${x.reason} status=${x.status} importance=${x.importance} ${x.description}")
            }
        }
    }

    /** Blocks; call off the main thread. */
    private fun build(): File {
        val dir = File(app.cacheDir, "share").apply { mkdirs() }
        val out = File(dir, "launch-soul-log.txt")
        val logcat = runCatching {
            val p = ProcessBuilder("logcat", "-d", "-t", "600", "--pid=${Process.myPid()}", "*:W").redirectErrorStream(true).start()
            p.inputStream.bufferedReader().readText()
        }.getOrDefault("")
        exec.submit {
            out.writeText(
                header() + "-- log\n" + (if (old.exists()) old.readText() else "") + (if (file.exists()) file.readText() else "") +
                    "-- logcat\n" + logcat,
            )
        }.get()
        return out
    }

    fun share(ctx: Context) {
        ctx.app.io.execute {
            try {
                val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", build())
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                send.clipData = android.content.ClipData.newRawUri("", uri)
                Actions.start(ctx, Intent.createChooser(send, "Hata günlüğünü paylaş").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            } catch (e: Exception) {
                Actions.toast(ctx, "Günlük hazırlanamadı: ${e.message}")
            }
        }
    }
}
