package dev.yesil.launchsoul.system

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import dev.yesil.launchsoul.app

/**
 * When the phone ends this app's process (low memory, a task swiped away), Android marks the accessibility service as
 * crashed and does not bind it again: the gestures stop until it is turned on by hand. With WRITE_SECURE_SETTINGS,
 * given once over ADB, the app turns it back on itself the next time its process starts.
 */
object Heal {
    const val COMMAND = "adb shell pm grant dev.yesil.launchsoul android.permission.WRITE_SECURE_SETTINGS"

    fun allowed(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** Waits a little: the service may simply be on its way. */
    fun later(ctx: Context) {
        val app = ctx.app
        app.main.postDelayed({ now(app) }, 6000)
    }

    /** How the service stands: running, turned on but not running (the phone ended it), or off. */
    enum class State { RUNNING, STOPPED, OFF }

    fun state(ctx: Context): State {
        if (SoulAccessibility.instance != null) return State.RUNNING
        val me = ComponentName(ctx, SoulAccessibility::class.java)
        val on = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            .split(':').any { ComponentName.unflattenFromString(it) == me }
        return if (on) State.STOPPED else State.OFF
    }

    /** Turns the service on, or off and on again; [asked] when the user tapped for it, even if it was turned off. */
    fun now(ctx: Context, asked: Boolean = false): Boolean {
        val app = ctx.app
        if (SoulAccessibility.instance != null || !allowed(ctx)) return false
        if (asked) app.prefs.a11yWanted = true else if (!app.prefs.a11yWanted) return false
        val me = ComponentName(ctx, SoulAccessibility::class.java)
        val r = ctx.contentResolver
        val others = Settings.Secure.getString(r, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            .split(':').filter { it.isNotBlank() && ComponentName.unflattenFromString(it) != me }
        return runCatching {
            // Off and on again: the system binds it anew.
            Settings.Secure.putString(r, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, others.joinToString(":"))
            Settings.Secure.putString(r, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (others + me.flattenToString()).joinToString(":"))
            Settings.Secure.putInt(r, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            DebugLog.log("a11y", if (asked) "turned on by hand" else "turned on again")
        }.onFailure { DebugLog.log("a11y", "could not turn on again", it) }.isSuccess
    }
}
