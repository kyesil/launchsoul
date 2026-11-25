package dev.yesil.launchsoul.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

class Prefs(ctx: Context) {
    val sp: SharedPreferences = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    // Search icons
    /** Superellipse exponent ×10: 20 circle, 35 rounder, 50 iOS-like, 80 nearly square. */
    var iconCurve by int(50)

    // Gestures on the home screen: values are ACT_* names
    var gestureDownLeft by str(ACT_NOTIFICATIONS)
    var gestureDownRight by str(ACT_QUICK)
    var gestureUp by str(ACT_SEARCH)
    var gestureDoubleTap by str(ACT_LOCK)

    /** "auto" (reflection, then accessibility) or "a11y". */
    var panelPath by str("auto")

    // Edge handles
    var edgeEnabled by bool(false)
    var edgeLeft by bool(true)
    var edgeRight by bool(true)
    var edgeWidth by int(14)
    var edgeHeight by int(160)
    var edgePosition by int(35)
    var edgeDown by str(ACT_LOCK)
    var edgeUp by str(ACT_SEARCH)
    var edgeVisible by bool(true)

    // Swipe strips on the phone's own home screen, dp
    var zoneOn by bool(false)
    var zoneSide by int(40)
    var zoneBottom by int(28)
    var zoneVisible by bool(false)

    /** Seconds without strips after a long press. */
    var zonePause by int(6)

    // Search
    var searchContacts by bool(false)
    var searchSettings by bool(true)
    var searchWeb by bool(true)
    var searchCall by str("phone")
    var searchMessage by str("whatsapp")
    var searchRecentOn by bool(true)

    /** Newest first, one per line. */
    var searchRecent by str("")

    // Notification history
    var historyOn by bool(false)
    var historyDays by int(365)
    var historyExcluded by set()
    var rulesOn by bool(false)

    // Night rule
    var dndOn by bool(false)
    var dndStart by int(23 * 60)
    var dndEnd by int(8 * 60)
    var dndCharging by bool(true)
    var dndHomes by set()
    var dndNames by set()
    var dndRuleId by str("")
    var dndLostAt by long(0)

    var hidden by set()
    var debugLog by bool(false)
    var timerEnd by long(0)
    var lastHeartbeat by long(0)

    private fun int(d: Int) = object : ReadWriteProperty<Any?, Int> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = sp.getInt(property.name, d)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) = sp.edit { putInt(property.name, value) }
    }

    private fun long(d: Long) = object : ReadWriteProperty<Any?, Long> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = sp.getLong(property.name, d)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Long) = sp.edit { putLong(property.name, value) }
    }

    private fun bool(d: Boolean) = object : ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = sp.getBoolean(property.name, d)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) =
            sp.edit { putBoolean(property.name, value) }
    }

    private fun str(d: String) = object : ReadWriteProperty<Any?, String> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = sp.getString(property.name, d) ?: d
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) =
            sp.edit { putString(property.name, value) }
    }

    private fun set() = object : ReadWriteProperty<Any?, Set<String>> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): Set<String> =
            sp.getStringSet(property.name, emptySet())!!.toSet()

        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Set<String>) =
            sp.edit { putStringSet(property.name, value) }
    }

    companion object {
        const val ACT_NONE = "none"
        const val ACT_NOTIFICATIONS = "notifications"
        const val ACT_QUICK = "quick"
        const val ACT_SEARCH = "search"
        const val ACT_PHONE_SEARCH = "phone_search"
        const val ACT_GOOGLE = "google"
        const val ACT_DIALER = "dialer"
        const val ACT_LOCK = "lock"
        const val ACT_RECENTS = "recents"
        const val ACT_APP = "app:"

        val actionNames = linkedMapOf(
            ACT_NONE to "Hiçbiri",
            ACT_NOTIFICATIONS to "Bildirimler",
            ACT_QUICK to "Denetim menüsü",
            ACT_SEARCH to "Arama",
            ACT_PHONE_SEARCH to "Telefonun kendi araması",
            ACT_GOOGLE to "Google (Discover)",
            ACT_DIALER to "Tuş takımı",
            ACT_LOCK to "Ekranı kilitle",
            ACT_RECENTS to "Son uygulamalar",
        )
    }
}
