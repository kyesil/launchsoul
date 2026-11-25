package dev.yesil.launchsoul

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.yesil.launchsoul.auto.HomeDnd
import dev.yesil.launchsoul.auto.Reminders
import dev.yesil.launchsoul.data.AppRepo
import dev.yesil.launchsoul.data.IconCache
import dev.yesil.launchsoul.data.Prefs
import dev.yesil.launchsoul.notif.History
import java.util.concurrent.Executors

class App : Application() {
    lateinit var prefs: Prefs
    lateinit var apps: AppRepo
    lateinit var icons: IconCache
    lateinit var history: History

    val main = Handler(Looper.getMainLooper())
    val io = Executors.newFixedThreadPool(3)

    /** One thread, so notification rows are written in the order they came. */
    val db = Executors.newSingleThreadExecutor()

    override fun onCreate() {
        super.onCreate()
        me = this
        prefs = Prefs(this)
        dev.yesil.launchsoul.system.DebugLog.init(this)
        icons = IconCache(this)
        apps = AppRepo(this)
        history = History(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_REMINDER, "Hatırlatıcılar", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_TIMER, "Uyku zamanlayıcı", NotificationManager.IMPORTANCE_LOW),
        )
        HomeDnd.init(this)
        io.execute { runCatching { Reminders.rescheduleAll(this) } }
    }

    companion object {
        const val CH_REMINDER = "reminder"
        const val CH_TIMER = "timer"

        @JvmStatic
        lateinit var me: App
            private set
    }
}

val Context.app: App get() = applicationContext as App
