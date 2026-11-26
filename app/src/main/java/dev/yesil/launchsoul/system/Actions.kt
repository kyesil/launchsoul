package dev.yesil.launchsoul.system

import dev.yesil.launchsoul.R
import dev.yesil.launchsoul.tx

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import dev.yesil.launchsoul.app
import dev.yesil.launchsoul.data.Prefs
import dev.yesil.launchsoul.search.SearchActivity
import dev.yesil.launchsoul.settings.SettingsActivity

/** Everything a gesture can do. */
object Actions {
    const val GOOGLE = "com.google.android.googlequicksearchbox"

    fun run(ctx: Context, action: String) {
        DebugLog.log("action", action.substringBefore(':'))
        when {
            action == Prefs.ACT_NONE -> {}
            action == Prefs.ACT_NOTIFICATIONS -> panel(ctx, quick = false)
            action == Prefs.ACT_QUICK -> panel(ctx, quick = true)
            action == Prefs.ACT_SEARCH -> start(ctx, Intent(ctx, SearchActivity::class.java))
            action == Prefs.ACT_PHONE_SEARCH -> if (!phoneSearch(ctx)) toast(ctx, tx(R.string.no_phone_search))
            action == Prefs.ACT_GOOGLE -> google(ctx)
            action == Prefs.ACT_DIALER -> start(ctx, Intent(Intent.ACTION_DIAL))
            action == Prefs.ACT_LOCK -> global(ctx, AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN, tx(R.string.locking))
            action == Prefs.ACT_RECENTS -> global(ctx, AccessibilityService.GLOBAL_ACTION_RECENTS, tx(R.string.act_recents))
            action.startsWith(Prefs.ACT_APP) -> ctx.app.apps.launch(action.removePrefix(Prefs.ACT_APP))
        }
    }

    /**
     * Opens the notification shade or quick settings. The no-argument StatusBarManager methods are not part of
     * the SDK but are not blocked for any target SDK; accessibility is the official fallback.
     */
    fun panel(ctx: Context, quick: Boolean): Boolean {
        val service = SoulAccessibility.instance
        if (ctx.app.prefs.panelPath != "a11y" && viaStatusBar(ctx, quick)) return true
        DebugLog.log("panel", "quick=$quick by accessibility, service=${service != null}")
        val act = if (quick) AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
        else AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
        if (service != null && service.performGlobalAction(act)) return true
        toast(ctx, tx(R.string.panel_failed))
        return false
    }

    @SuppressLint("WrongConstant", "PrivateApi", "DiscouragedPrivateApi")
    private fun viaStatusBar(ctx: Context, quick: Boolean): Boolean = try {
        val bar = ctx.getSystemService("statusbar")
        Class.forName("android.app.StatusBarManager")
            .getMethod(if (quick) "expandSettingsPanel" else "expandNotificationsPanel").invoke(bar)
        true
    } catch (e: Throwable) {
        Log.w("LaunchSoul", "status bar panel failed", e)
        DebugLog.log("panel", "status bar failed", e)
        false
    }

    private fun global(ctx: Context, action: Int, what: String) {
        if (SoulAccessibility.instance?.performGlobalAction(action) == true) return
        toast(ctx, tx(R.string.x_needs_a11y, what))
        start(ctx, Intent(ctx, SettingsActivity::class.java).putExtra("screen", "permissions"))
    }

    fun google(ctx: Context) {
        // The package has a second launcher entry for voice search.
        val main = ctx.app.apps.list.filter { it.pkg == GOOGLE }.let { l ->
            l.firstOrNull { it.component.shortClassName == ".SearchActivity" }
                ?: l.firstOrNull { c -> listOf("Voice", "Opa").none { it in c.component.className } }
        }
        val i = if (main != null) Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(main.component)
        else ctx.packageManager.getLaunchIntentForPackage(GOOGLE)
        if (i == null) {
            toast(ctx, tx(R.string.no_google))
            return
        }
        val o = ActivityOptions.makeCustomAnimation(
            ctx, android.R.anim.slide_in_left, android.R.anim.fade_out,
        ).toBundle()
        runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED), o) }
    }

    /** Samsung's finder; other phones have no known public entry. */
    private fun phoneSearch(ctx: Context): Boolean {
        for (cn in listOf(
            ComponentName("com.sec.android.app.launcher", "com.sec.android.app.launcher.search.SearchActivity"),
            ComponentName("com.samsung.android.app.galaxyfinder", "com.samsung.android.app.galaxyfinder.GalaxyFinderActivity"),
        )) {
            if (runCatching {
                    ctx.startActivity(Intent().setComponent(cn).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.isSuccess
            ) return true
        }
        return false
    }

    fun start(ctx: Context, i: Intent): Boolean = try {
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        toast(ctx, tx(R.string.could_not_open))
        false
    }

    fun toast(ctx: Context, s: String) = ctx.app.main.post {
        Toast.makeText(ctx.applicationContext, s, Toast.LENGTH_SHORT).show()
    }
}
