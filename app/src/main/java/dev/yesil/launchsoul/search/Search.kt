package dev.yesil.launchsoul.search

import dev.yesil.launchsoul.R
import dev.yesil.launchsoul.tx

import android.Manifest
import android.app.SearchManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.yesil.launchsoul.app
import dev.yesil.launchsoul.auto.Reminders
import dev.yesil.launchsoul.auto.SleepTimer
import dev.yesil.launchsoul.auto.clock
import dev.yesil.launchsoul.auto.hasPermission
import dev.yesil.launchsoul.core.ReminderParser
import dev.yesil.launchsoul.core.calculate
import dev.yesil.launchsoul.core.fold
import dev.yesil.launchsoul.core.matchRanges
import dev.yesil.launchsoul.core.matchScore
import dev.yesil.launchsoul.core.numberRange
import dev.yesil.launchsoul.data.AppInfo
import dev.yesil.launchsoul.settings.SettingsActivity
import dev.yesil.launchsoul.system.Actions
import dev.yesil.launchsoul.ui.AppIcon
import dev.yesil.launchsoul.ui.SoulTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime
import java.time.ZoneId

class Person(val id: Long, val lookup: String, val name: String, val number: String, val photo: String?,
    /** What else than the name and the number contains the searched text. */
    val why: String = "",
) {
    val key get() = "contact:$lookup"
}

class Result(val title: String, val sub: String = "", val mark: String = "", val run: (Context) -> Unit)

class Results(
    val answers: List<Result> = emptyList(), val apps: List<AppInfo> = emptyList(),
    val contacts: List<Person> = emptyList(), val settings: List<Result> = emptyList(), val web: List<Result> = emptyList(),
)

/** Phone settings that can be searched: the name in the phone's language, words in both languages, the screen. */
private val settingPages = listOf(
    Triple(R.string.set_wifi, "wifi wi-fi kablosuz internet wireless", Settings.ACTION_WIFI_SETTINGS),
    Triple(R.string.set_bluetooth, "bluetooth", Settings.ACTION_BLUETOOTH_SETTINGS),
    Triple(R.string.set_sound, "ses titreşim zil sound vibration ringtone volume", Settings.ACTION_SOUND_SETTINGS),
    Triple(R.string.set_display, "ekran parlaklık display brightness", Settings.ACTION_DISPLAY_SETTINGS),
    Triple(R.string.set_battery, "pil batarya tasarruf battery saver", Settings.ACTION_BATTERY_SAVER_SETTINGS),
    Triple(R.string.set_location, "konum gps location", Settings.ACTION_LOCATION_SOURCE_SETTINGS),
    Triple(R.string.set_apps, "uygulamalar apps applications", Settings.ACTION_APPLICATION_SETTINGS),
    Triple(R.string.set_storage, "depolama storage", Settings.ACTION_INTERNAL_STORAGE_SETTINGS),
    Triple(R.string.set_security, "güvenlik parmak izi security fingerprint", Settings.ACTION_SECURITY_SETTINGS),
    Triple(R.string.set_privacy, "gizlilik privacy", Settings.ACTION_PRIVACY_SETTINGS),
    Triple(R.string.set_a11y, "erişilebilirlik accessibility", Settings.ACTION_ACCESSIBILITY_SETTINGS),
    Triple(R.string.set_date, "tarih saat date time", Settings.ACTION_DATE_SETTINGS),
    Triple(R.string.set_language, "dil language", Settings.ACTION_LOCALE_SETTINGS),
    Triple(R.string.set_keyboard, "klavye giriş keyboard input", Settings.ACTION_INPUT_METHOD_SETTINGS),
    Triple(R.string.set_nfc, "nfc", Settings.ACTION_NFC_SETTINGS),
    Triple(R.string.set_airplane, "uçak modu airplane flight", Settings.ACTION_AIRPLANE_MODE_SETTINGS),
    Triple(R.string.set_data, "mobil veri kullanımı mobile data usage", Settings.ACTION_DATA_USAGE_SETTINGS),
    Triple(R.string.set_vpn, "vpn", Settings.ACTION_VPN_SETTINGS),
    Triple(R.string.set_developer, "geliştirici seçenekleri developer options", Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
    Triple(R.string.set_default_apps, "varsayılan uygulamalar default apps", Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
    Triple(R.string.set_listeners, "bildirim erişimi notification access", Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
    Triple(R.string.set_about, "telefon hakkında yazılım about phone software", Settings.ACTION_DEVICE_INFO_SETTINGS),
    Triple(R.string.set_accounts, "hesaplar eşitleme accounts sync", Settings.ACTION_SYNC_SETTINGS),
    Triple(R.string.set_cast, "yayınla ekran yansıtma cast screen mirroring", Settings.ACTION_CAST_SETTINGS),
    Triple(R.string.set_print, "yazdırma printing print", Settings.ACTION_PRINT_SETTINGS),
)

/** 4 start of the name, 3 start of a later word, 2 inside the name, 1 something else. */
private fun tier(score: Int) = if (score >= 90) 4 else if (score >= 70) 3 else if (score >= 60) 2 else 1

/** As for apps; a match in the number or the other fields is last, unless the contact is picked often. */
private fun tier(f: String, q: String, p: Person, picked: Int): Int = when {
    matchRanges(f, p.name).isNotEmpty() -> tier(matchScore(f, fold(p.name)))
    picked > 0 -> 2
    numberRange(q, p.number) != null -> 1
    else -> 0
}

/** Runs off the main thread. */
private fun find(ctx: Context, raw: String): Results {
    val q = raw.trim()
    if (q.isEmpty()) return Results()
    val app = ctx.app
    val p = app.prefs
    val f = fold(q)

    val answers = mutableListOf<Result>()
    calculate(q)?.let {
        val s = BigDecimal(it).setScale(10, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString().replace('.', ',')
        answers += Result(s, tx(R.string.calc_copy), "=") { c ->
            c.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(tx(R.string.calc_clip), s))
            Actions.toast(c, tx(R.string.copied))
        }
    }
    ReminderParser.parse(q, LocalDateTime.now())?.let { r ->
        val at = r.at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        answers += Result(tx(R.string.remind_x, r.text), clock(at), "⏰") { c ->
            Reminders.add(c.app, at, r.text)
            Actions.toast(c, tx(R.string.set_for_x, clock(at)))
        }
    }
    Regex("""^(?:uyku|zamanlayici|timer|sleep)\s+(\d{1,3})\s*(?:dk|dakika|min|mins|minutes?)?$""").find(f)?.let { m ->
        val n = m.groupValues[1].toInt().coerceIn(1, 600)
        answers += Result(tx(R.string.timer_x_min, n), tx(R.string.timer_explain), "☾") { c ->
            SleepTimer.start(c.app, n)
            Actions.toast(c, tx(R.string.timer_set))
        }
    }

    val apps = app.apps.visible().map { it to matchScore(f, it.folded) }.filter { it.second > 0 }
        .sortedWith(
        compareByDescending<Pair<AppInfo, Int>> { tier(it.second) }.thenByDescending { app.apps.count(it.first.key) }
            .thenByDescending { it.second },
    )
        .take(8).map { it.first }

    val contacts = if (p.searchContacts && f.length >= 2 && hasPermission(ctx, Manifest.permission.READ_CONTACTS)) {
        contacts(ctx, q).sortedWith(
            compareByDescending<Person> { tier(f, q, it, app.apps.count(it.key)) }.thenByDescending { app.apps.count(it.key) },
        ).take(5).map { c ->
            if (matchRanges(f, c.name).isNotEmpty() || numberRange(q, c.number) != null) c
            else Person(c.id, c.lookup, c.name, c.number, c.photo, why(ctx, c.id, f))
        }
    } else emptyList()

    val settings = if (!p.searchSettings || f.length < 2) emptyList() else settingPages.mapNotNull { (id, words, action) ->
        val n = tx(id)
        val s = fold("$n $words").split(' ').maxOf { w -> matchScore(f, w) }.coerceAtLeast(matchScore(f, fold(n)))
        if (s < 60) return@mapNotNull null
        val i = Intent(action)
        if (ctx.packageManager.resolveActivity(i, 0) == null) return@mapNotNull null
        s to Result(n, tx(R.string.phone_setting), "⚙") { c -> Actions.start(c, i) }
    }.sortedByDescending { it.first }.take(4).map { it.second }

    val web = if (!p.searchWeb) emptyList() else {
        val e = Uri.encode(q)
        listOf(
            Result(tx(R.string.web_search_x, q), "", "G") { c ->
                if (!Actions.start(c, Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q))) {
                    Actions.start(c, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=$e")))
                }
            },
            Result(tx(R.string.maps_search), "", "⌖") { c -> Actions.start(c, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$e"))) },
            Result(tx(R.string.youtube_search), "", "▶") { c ->
                Actions.start(c, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$e")))
            },
            Result(tx(R.string.play_search), "", "▼") { c ->
                Actions.start(c, Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=$e")))
            },
        )
    }
    val inNotifications = Result(tx(R.string.in_notifications), if (p.historyOn) "" else tx(R.string.history_off), "🔔") { c ->
        Actions.start(c, Intent(c, SettingsActivity::class.java).putExtra("screen", "history").putExtra("q", q))
    }
    return Results(answers, apps, contacts, settings, listOf(inNotifications) + web)
}

/** The field of the contact that contains [f]: a note, a company, a nickname, an e-mail, another number. */
private fun why(ctx: Context, id: Long, f: String): String = try {
    ctx.contentResolver.query(
        ContactsContract.Data.CONTENT_URI,
        arrayOf(
            ContactsContract.Data.MIMETYPE, ContactsContract.Data.DATA1, ContactsContract.Data.DATA4, ContactsContract.Data.DATA2,
            ContactsContract.Data.DATA3,
        ),
        ContactsContract.Data.CONTACT_ID + "=?", arrayOf(id.toString()), null,
    )?.use { c ->
        var out = ""
        while (c.moveToNext() && out.isEmpty()) {
            val name = when (c.getString(0)) {
                ContactsContract.CommonDataKinds.Nickname.CONTENT_ITEM_TYPE -> tx(R.string.f_nickname)
                ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE -> tx(R.string.f_company)
                ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE -> tx(R.string.f_email)
                ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE -> tx(R.string.f_note)
                ContactsContract.CommonDataKinds.Relation.CONTENT_ITEM_TYPE -> tx(R.string.f_relation)
                ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE -> tx(R.string.f_address)
                ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE -> tx(R.string.f_number)
                ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE -> tx(R.string.f_name)
                else -> tx(R.string.f_other)
            }
            for (k in 1..4) {
                val v = c.getString(k)?.replace('\n', ' ') ?: continue
                val i = fold(v).indexOf(f)
                if (i < 0) continue
                out = "$name: " + if (v.length > 40 && i > 20) "…" + v.drop(i - 12) else v
                break
            }
        }
        out
    }.orEmpty()
} catch (_: Exception) {
    ""
}

private fun contacts(ctx: Context, q: String): List<Person> = try {
    val u = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(q))
    ctx.contentResolver.query(
        u,
        arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID, ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY,
            ContactsContract.CommonDataKinds.Phone.PHOTO_URI, ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY,
        ),
        null, null, null,
    )?.use { c ->
        val out = LinkedHashMap<Long, Person>()
        while (c.moveToNext() && out.size < 40) {
            val number = c.getString(1) ?: continue
            val id = c.getLong(2)
            // The number chosen as the default of the contact, else the first one.
            if (id !in out || c.getInt(5) != 0) {
                out[id] = Person(id, c.getString(3) ?: continue, c.getString(0) ?: number, number, c.getString(4))
            }
        }
        out.values.toList()
    }.orEmpty()
} catch (_: Exception) {
    emptyList()
}

/** A contact found by its lookup key or by a phone number, with the number to call. */
private fun person(ctx: Context, lookup: String? = null, number: String? = null): Person? = runCatching {
    val r = ctx.contentResolver
    val uri = if (lookup != null) Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_LOOKUP_URI, lookup)
    else Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
    val cols = if (lookup != null) arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.LOOKUP_KEY, ContactsContract.Contacts.DISPLAY_NAME, ContactsContract.Contacts.PHOTO_URI)
    else arrayOf(ContactsContract.PhoneLookup._ID, ContactsContract.PhoneLookup.LOOKUP_KEY, ContactsContract.PhoneLookup.DISPLAY_NAME, ContactsContract.PhoneLookup.PHOTO_URI)
    r.query(uri, cols, null, null, null)?.use { c ->
        if (!c.moveToFirst()) return@use null
        val id = c.getLong(0)
        val n = number ?: r.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID + "=?", arrayOf(id.toString()),
            ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY + " DESC",
        )?.use { p -> if (p.moveToFirst()) p.getString(0) else null } ?: return@use null
        Person(id, c.getString(1) ?: return@use null, c.getString(2) ?: n, n, c.getString(3))
    }
}.getOrNull()

/** The people called or picked last: from the call log when that is allowed, and those opened from this search. */
fun recentPeople(ctx: Context, n: Int): List<Person> {
    if (!hasPermission(ctx, Manifest.permission.READ_CONTACTS)) return emptyList()
    val seen = LinkedHashMap<String, Pair<Person, Long>>()
    fun add(p: Person?, at: Long) {
        if (p != null && (seen[p.lookup]?.second ?: 0) < at) seen[p.lookup] = p to at
    }
    for ((key, at) in ctx.app.apps.lastPicked("contact:").take(n)) add(person(ctx, lookup = key.removePrefix("contact:")), at)
    if (ctx.app.prefs.searchPeopleCallLog && hasPermission(ctx, Manifest.permission.READ_CALL_LOG)) runCatching {
        ctx.contentResolver.query(
            android.provider.CallLog.Calls.CONTENT_URI,
            arrayOf(android.provider.CallLog.Calls.NUMBER, android.provider.CallLog.Calls.DATE),
            null, null, android.provider.CallLog.Calls.DATE + " DESC",
        )?.use { c ->
            val numbers = LinkedHashMap<String, Long>()
            while (c.moveToNext() && numbers.size < n * 3) {
                val num = c.getString(0) ?: continue
                numbers.putIfAbsent(num, c.getLong(1))
            }
            for ((num, at) in numbers) {
                add(person(ctx, number = num), at)
                if (seen.size >= n * 2) break
            }
        }
    }
    return seen.values.sortedByDescending { it.second }.map { it.first }.take(n)
}

/** The contact's page in the phone app the user has chosen, or in any other app that can show it. */
private fun openPerson(ctx: Context, p: Person) {
    val u = ContactsContract.Contacts.getLookupUri(p.id, p.lookup)
    val dialer = ctx.getSystemService(android.telecom.TelecomManager::class.java)?.defaultDialerPackage
    for (pkg in listOf(dialer, null)) {
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(u, ContactsContract.Contacts.CONTENT_ITEM_TYPE).setPackage(pkg)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { ctx.startActivity(i) }.isSuccess) return
    }
    Actions.start(ctx, Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", p.number, null)))
}

/** WhatsApp's own "voice call" line of the contact; false when the contact has none. */
private fun whatsappCall(ctx: Context, p: Person): Boolean = runCatching {
    val type = "vnd.android.cursor.item/vnd.com.whatsapp.voip.call"
    val want = p.number.filter { it.isDigit() }.takeLast(9)
    var id = -1L
    ctx.contentResolver.query(
        ContactsContract.Data.CONTENT_URI, arrayOf(ContactsContract.Data._ID, ContactsContract.Data.DATA1, ContactsContract.Data.DATA3),
        "${ContactsContract.Data.CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?", arrayOf(p.id.toString(), type), null,
    )?.use { c ->
        while (c.moveToNext()) {
            val same = listOf(1, 2).any { k -> c.getString(k).orEmpty().filter { it.isDigit() }.contains(want) }
            if (id < 0 || same) id = c.getLong(0)
            if (same) break
        }
    }
    if (id < 0) return false
    ctx.startActivity(
        Intent(Intent.ACTION_VIEW).setDataAndType(android.content.ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, id), type)
            .setPackage("com.whatsapp").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
    true
}.getOrDefault(false)

/** WhatsApp unless the settings say SMS or WhatsApp is not there. */
private fun message(ctx: Context, p: Person) {
    if (ctx.app.prefs.searchMessage == "whatsapp") {
        val u = Uri.parse("https://wa.me/" + international(ctx, p.number).filter { it.isDigit() })
        for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
            val i = Intent(Intent.ACTION_VIEW, u).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { ctx.startActivity(i) }.isSuccess) return
        }
    }
    Actions.start(ctx, Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", p.number, null)))
}

/** WhatsApp wants the country code. */
private fun international(ctx: Context, number: String): String {
    if (number.trim().startsWith("+")) return number
    val tm = ctx.getSystemService(android.telephony.TelephonyManager::class.java)
    val land = (tm?.simCountryIso?.ifEmpty { null } ?: tm?.networkCountryIso?.ifEmpty { null } ?: java.util.Locale.getDefault().country).uppercase()
    return android.telephony.PhoneNumberUtils.formatNumberToE164(number, land) ?: number
}

private val found = androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Black, color = Color(0xFF8FB8FF))

/** The matching part in bold. */
private fun marked(s: String, ranges: List<IntRange>) = androidx.compose.ui.text.buildAnnotatedString {
    append(s)
    for (r in ranges) if (r.first >= 0 && r.last < s.length) addStyle(found, r.first, r.last + 1)
}

@Composable
private fun Face(p: Person) {
    val ctx = LocalContext.current
    val bmp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, p.photo) {
        value = p.photo?.let { u ->
            withContext(Dispatchers.IO) {
                runCatching {
                    android.graphics.ImageDecoder.decodeBitmap(
                        android.graphics.ImageDecoder.createSource(ctx.contentResolver, Uri.parse(u)),
                    ) { d, info, _ ->
                        val s = minOf(1f, 160f / minOf(info.size.width, info.size.height))
                        d.setTargetSize((info.size.width * s).toInt().coerceAtLeast(1), (info.size.height * s).toInt().coerceAtLeast(1))
                    }.asImageBitmap()
                }.getOrNull()
            }
        }
    }
    Box(Modifier.size(46.dp).clip(RoundedCornerShape(23.dp)).background(Color(0xFF3A4A66)), Alignment.Center) {
        val b = bmp
        if (b != null) androidx.compose.foundation.Image(
            b, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        ) else Text(p.name.take(1).uppercase(), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun People(l: List<Person>, q: String, open: (Person) -> Unit, call: (Person) -> Unit, write: (Person) -> Unit) = Column(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Color(0xCC23262D)),
) {
    for (p in l) Row(
        Modifier.fillMaxWidth().clickable { open(p) }.padding(14.dp, 9.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Face(p)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            val f = fold(q.trim())
            Text(marked(p.name, matchRanges(f, p.name)), color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(marked(p.number, listOfNotNull(numberRange(q, p.number))), color = Color(0xFF9AA2B0), fontSize = 13.sp, maxLines = 1)
            if (p.why.isNotEmpty()) Text(
                marked(p.why, fold(p.why).indexOf(f).let { i -> if (i < 0) emptyList() else listOf(i until i + f.length) }),
                color = Color(0xFF9AA2B0), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            Modifier.size(46.dp).clip(RoundedCornerShape(23.dp)).background(Color(0xFF3F7DD8)).clickable { write(p) }, Alignment.Center,
        ) {
            androidx.compose.foundation.Image(
                androidx.compose.ui.res.painterResource(dev.yesil.launchsoul.R.drawable.ic_message), tx(R.string.message_x, p.name),
                Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier.size(46.dp).clip(RoundedCornerShape(23.dp)).background(Color(0xFF2E9E57)).clickable { call(p) },
            Alignment.Center,
        ) {
            androidx.compose.foundation.Image(
                androidx.compose.ui.res.painterResource(android.R.drawable.sym_action_call), tx(R.string.call_x, p.name), Modifier.size(24.dp),
                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color.White),
            )
        }
    }
}

@Composable
fun SearchScreen(active: Boolean, close: () -> Unit) {
    val ctx = LocalContext.current
    // Some phones (Samsung) do not blur what is behind a window; then it is covered.
    val blurred = remember {
        android.os.Build.VERSION.SDK_INT >= 31 &&
            ctx.getSystemService(android.view.WindowManager::class.java).isCrossWindowBlurEnabled
    }
    var q by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val fm = LocalFocusManager.current
    val list = rememberLazyListState()

    LaunchedEffect(active) {
        if (active) {
            q = ""
            list.scrollToItem(0)
            runCatching { focus.requestFocus() }
            delay(60)
            keyboard?.show()
        } else fm.clearFocus()
    }
    val res by produceState(Results(), q) { value = withContext(Dispatchers.Default) { find(ctx, q) } }
    val prefs = ctx.app.prefs
    var recent by remember { mutableStateOf(prefs.searchRecent.lines().filter { it.isNotBlank() }) }
    val remember1 = {
        val t = q.trim().replace('\n', ' ')
        if (prefs.searchRecentOn && t.length >= 2) {
            recent = (listOf(t) + recent.filter { !it.equals(t, ignoreCase = true) }).take(10)
            prefs.searchRecent = recent.joinToString("\n")
        }
    }
    var calling by remember { mutableStateOf<Person?>(null) }
    val dial = { p: Person, direct: Boolean ->
        // Without WhatsApp for this contact nothing is called by surprise: the dialer opens.
        val wa = direct && prefs.searchCall == "whatsapp"
        val done = wa && whatsappCall(ctx, p)
        if (wa && !done) Actions.toast(ctx, tx(R.string.no_whatsapp_call))
        if (!done) Actions.start(ctx, Intent(if (direct && !wa) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.fromParts("tel", p.number, null)))
        close()
    }
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { ok -> calling?.let { dial(it, ok) } }
    val call: (Person) -> Unit = { p ->
        remember1()
        ctx.app.apps.picked(p.key)
        if (hasPermission(ctx, Manifest.permission.CALL_PHONE)) dial(p, true) else {
            calling = p
            ask.launch(Manifest.permission.CALL_PHONE)
        }
    }
    val write: (Person) -> Unit = { p ->
        remember1()
        ctx.app.apps.picked(p.key)
        message(ctx, p)
        close()
    }
    val person: (Person) -> Unit = { p ->
        remember1()
        ctx.app.apps.picked(p.key)
        openPerson(ctx, p)
        close()
    }
    val people by produceState(emptyList<Person>(), active) {
        value = withContext(Dispatchers.IO) { if (prefs.searchShowPeople) recentPeople(ctx, prefs.searchNPeople) else emptyList() }
    }
    val lists by produceState(emptyList<AppInfo>() to emptyList<AppInfo>(), active) {
        value = withContext(Dispatchers.IO) { ctx.app.apps.run { suggested(prefs.searchNFrequent) to recent(prefs.searchNRecent) } }
    }
    val go: (Result) -> Unit = {
        remember1()
        it.run(ctx)
        close()
    }
    val launch: (AppInfo) -> Unit = {
        remember1()
        ctx.app.apps.launch(it.key)
        close()
    }

    Column(
        Modifier.fillMaxSize().background(if (blurred) Color(0x8C0B0D11) else Color(0xF20B0D11)).statusBarsPadding().navigationBarsPadding().imePadding(),
    ) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), list, contentPadding = PaddingValues(16.dp, 12.dp)) {
            if (q.isBlank()) {
                for (part in prefs.searchOrder.split(',')) when (part) {
                    "frequent" -> {
                    if (prefs.searchShowFrequent && lists.first.isNotEmpty()) {
                        item { Head(tx(R.string.most_used)) }
                        item { AppStrip(lists.first, launch) }
                    }
                    }
                    "recent" -> {
                    if (prefs.searchShowRecentApps && lists.second.isNotEmpty()) {
                        item { Head(tx(R.string.recently_used)) }
                        item { AppStrip(lists.second, launch) }
                    }
                    }
                    "searches" -> {
                    if (prefs.searchRecentOn && recent.isNotEmpty()) {
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Head(tx(R.string.recent_searches))
                                Spacer(Modifier.weight(1f))
                                Text(
                                    tx(R.string.clear),
                                    Modifier.clip(RoundedCornerShape(12.dp)).clickable {
                                        recent = emptyList()
                                        prefs.searchRecent = ""
                                    }.padding(8.dp, 6.dp),
                                    color = Color(0xFF8FB8FF), fontSize = 13.sp,
                                )
                            }
                        }
                        item {
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                            ) {
                                for (r in recent) Text(
                                    r,
                                    Modifier.clip(RoundedCornerShape(18.dp)).background(Color(0xCC2B2E35)).clickable { q = r }
                                        .padding(14.dp, 9.dp),
                                    color = Color.White, fontSize = 14.sp, maxLines = 1,
                                )
                            }
                        }
                    }
                    }
                    "people" -> if (prefs.searchShowPeople && people.isNotEmpty()) {
                        item { Head(tx(R.string.recent_people)) }
                        item { People(people, "", person, call, write) }
                    }
                }
            } else {
                section("", res.answers, go)
                // The better match comes first, then what was picked more often.
                val fq = fold(q.trim())
                val person1 = res.contacts.firstOrNull()
                val app1 = res.apps.firstOrNull()
                val peopleFirst = person1 != null && (app1 == null || run {
                    val c = ctx.app.apps.count(person1.key)
                    val a = ctx.app.apps.count(app1.key)
                    val d = tier(fq, q, person1, c) - tier(matchScore(fq, app1.folded))
                    if (d != 0) d > 0 else c >= a
                })
                for (people in listOf(peopleFirst, !peopleFirst)) {
                    if (people && res.contacts.isNotEmpty()) {
                        item { Head(tx(R.string.contacts)) }
                        item { People(res.contacts, q, person, call, write) }
                    }
                    if (!people && res.apps.isNotEmpty()) {
                        item { Head(tx(R.string.apps)) }
                        item { AppGrid(res.apps, q, launch) }
                    }
                }
                section(tx(R.string.settings), res.settings, go, q)
                section(tx(R.string.search_elsewhere), res.web, go)
            }
        }
        Row(
            Modifier.padding(16.dp, 8.dp).fillMaxWidth().height(52.dp).clip(RoundedCornerShape(26.dp))
                .background(Color(0xFF2B2E35)).padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                if (q.isEmpty()) Text(tx(R.string.search_hint), color = Color(0xFF9AA2B0), fontSize = 16.sp)
                BasicTextField(
                    q, { q = it },
                    Modifier.fillMaxWidth().focusRequester(focus),
                    textStyle = TextStyle(Color.White, 16.sp), singleLine = true, cursorBrush = SolidColor(Color.White),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        val first = res.answers.firstOrNull()
                        when {
                            first != null -> go(first)
                            res.apps.isNotEmpty() -> launch(res.apps.first())
                            res.contacts.isNotEmpty() -> person(res.contacts.first())
                            else -> (res.settings + res.web.drop(1)).firstOrNull()?.let(go)
                        }
                    }),
                )
            }
            if (q.isNotEmpty()) androidx.compose.material3.Icon(
                androidx.compose.material.icons.Icons.Outlined.Close, null,
                Modifier.clip(RoundedCornerShape(20.dp)).clickable { q = "" }.padding(8.dp).size(22.dp), tint = Color(0xFFB9C0CC),
            ) else androidx.compose.material3.Icon(
                androidx.compose.material.icons.Icons.Outlined.Settings, tx(R.string.settings),
                Modifier.clip(RoundedCornerShape(20.dp)).clickable {
                    Actions.start(ctx, Intent(ctx, SettingsActivity::class.java))
                    close()
                }.padding(8.dp).size(22.dp),
                tint = Color(0xFFB9C0CC),
            )
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(title: String, rs: List<Result>, go: (Result) -> Unit, q: String = "") {
    if (rs.isEmpty()) return
    if (title.isNotEmpty()) item { Head(title) }
    item {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Color(0xCC23262D))) {
            for (r in rs) Row(
                Modifier.fillMaxWidth().clickable { go(r) }.padding(16.dp, 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(Color(0xFF3A3F49)), Alignment.Center) {
                    Text(r.mark, color = Color.White, fontSize = 15.sp)
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(marked(r.title, matchRanges(fold(q.trim()), r.title)), color = Color.White, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (r.sub.isNotEmpty()) Text(r.sub, color = Color(0xFF9AA2B0), fontSize = 13.sp, maxLines = 1)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun Head(s: String) = Text(
    s, Modifier.padding(4.dp, 10.dp, 4.dp, 8.dp), color = Color(0xFFD3D8E1), fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
)

@Composable
private fun AppGrid(l: List<AppInfo>, q: String, launch: (AppInfo) -> Unit) = Column(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(Color(0xCC23262D)).padding(vertical = 6.dp),
) {
    for (r in l.chunked(COLS)) AppRow(r, q, launch)
}

private const val COLS = 5

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppRow(r: List<AppInfo>, q: String, launch: (AppInfo) -> Unit) = Row(Modifier.fillMaxWidth()) {
    for (a in r) AppCell(a, q, launch, Modifier.weight(1f))
    repeat(COLS - r.size) { Spacer(Modifier.weight(1f)) }
}

/** One row that scrolls sideways; five apps fit on the screen. */
@Composable
private fun AppStrip(l: List<AppInfo>, launch: (AppInfo) -> Unit) = androidx.compose.foundation.layout.BoxWithConstraints(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(Color(0xCC23262D)).padding(vertical = 6.dp),
) {
    val w = maxWidth / COLS
    androidx.compose.foundation.lazy.LazyRow(Modifier.fillMaxWidth()) {
        items(l.size, key = { l[it].key }) { AppCell(l[it], "", launch, Modifier.width(w)) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppCell(a: AppInfo, q: String, launch: (AppInfo) -> Unit, modifier: Modifier) {
    val ctx = LocalContext.current
    var menu by remember(a.key) { mutableStateOf(false) }
    Column(
        modifier.clip(RoundedCornerShape(18.dp))
            .combinedClickable(onLongClick = { menu = true }, onClick = { launch(a) }).padding(4.dp, 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AppIcon(a.key, 54.dp)
        Text(
            marked(a.label, matchRanges(fold(q.trim()), a.label)), Modifier.padding(top = 6.dp), color = Color.White, fontSize = 12.sp,
            lineHeight = 14.sp, minLines = 2, maxLines = 2,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
        )
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem({ Text(tx(R.string.find_on_home)) }, {
                menu = false
                val s = dev.yesil.launchsoul.system.SoulAccessibility.instance
                if (s == null) Actions.toast(ctx, tx(R.string.needs_a11y)) else {
                    s.find(a.label)
                    // Nothing may lie under the search that opens now.
                    var c: Context? = ctx
                    while (c is android.content.ContextWrapper && c !is android.app.Activity) c = c.baseContext
                    (c as? android.app.Activity)?.finish()
                }
            })
            DropdownMenuItem({ Text(tx(R.string.open)) }, {
                menu = false
                launch(a)
            })
            DropdownMenuItem({ Text(tx(R.string.app_info)) }, {
                menu = false
                ctx.app.apps.details(a.key)
            })
            DropdownMenuItem({ Text(tx(R.string.uninstall)) }, {
                menu = false
                ctx.app.apps.uninstall(ctx, a.key)
            })
            DropdownMenuItem({ Text(tx(R.string.hide_in_search)) }, {
                menu = false
                ctx.app.prefs.hidden = ctx.app.prefs.hidden + a.key
            })
        }
    }
}

/** Search over other apps, for the edge handles. */
class SearchActivity : ComponentActivity() {
    private val born = android.os.SystemClock.uptimeMillis()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bar = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(bar, bar)
        if (android.os.Build.VERSION.SDK_INT >= 31) window.setBackgroundBlurRadius((40 * resources.displayMetrics.density).toInt())
        setContent { SoulTheme { SearchScreen(active = true, close = { finish() }) } }
    }

    override fun onStop() {
        super.onStop()
        // Opened while the phone was still on its way home, whose end put the home screen in front: open once more.
        val lost = !isFinishing && android.os.SystemClock.uptimeMillis() - born < 800 && !intent.getBooleanExtra("again", false)
        finish()
        if (lost) app.main.postDelayed({
            dev.yesil.launchsoul.system.SoulAccessibility.instance?.let {
                Actions.start(it, Intent(it, SearchActivity::class.java).putExtra("again", true))
            }
        }, 120)
    }
}
