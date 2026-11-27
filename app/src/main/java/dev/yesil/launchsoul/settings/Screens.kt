package dev.yesil.launchsoul.settings

import dev.yesil.launchsoul.R
import dev.yesil.launchsoul.tx

import android.Manifest
import android.app.TimePickerDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import dev.yesil.launchsoul.App
import dev.yesil.launchsoul.app
import dev.yesil.launchsoul.auto.HomeDnd
import dev.yesil.launchsoul.auto.Reminders
import dev.yesil.launchsoul.auto.SleepTimer
import dev.yesil.launchsoul.auto.canExact
import dev.yesil.launchsoul.auto.canNotify
import dev.yesil.launchsoul.auto.clock
import dev.yesil.launchsoul.auto.hasPermission
import dev.yesil.launchsoul.core.ReminderParser
import dev.yesil.launchsoul.core.fold
import dev.yesil.launchsoul.notif.Ending
import dev.yesil.launchsoul.notif.Entry
import dev.yesil.launchsoul.notif.Listener
import dev.yesil.launchsoul.notif.Rule
import dev.yesil.launchsoul.notif.RuleAction
import dev.yesil.launchsoul.notif.ending
import dev.yesil.launchsoul.system.Actions
import dev.yesil.launchsoul.system.SoulAccessibility
import dev.yesil.launchsoul.ui.AppIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.ZoneId

/** Reads from the database off the main thread; [key] makes it read again. */
@Composable
private fun <T> load(vararg key: Any?, f: () -> T): T? {
    val s by produceState<T?>(null, *key) { value = withContext(Dispatchers.IO) { runCatching(f).getOrNull() } }
    return s
}

fun a11yOn(ctx: Context): Boolean =
    Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        .split(':').any { ComponentName.unflattenFromString(it) == ComponentName(ctx, SoulAccessibility::class.java) }

/** Honor and Huawei "app launch" screens, where "manage manually" lives. */
private fun batteryScreen(ctx: Context) {
    val known = listOf(
        "com.hihonor.systemmanager" to "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        "com.hihonor.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
    )
    for ((p, c) in known) {
        if (runCatching { ctx.startActivity(Intent().setClassName(p, c).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
    }
    details(ctx)
}

private fun details(ctx: Context) {
    Actions.start(ctx, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${ctx.packageName}".toUri()))
}

@Composable
fun PermissionScreen(tick: Int) {
    val ctx = LocalContext.current
    var again by remember { mutableIntStateOf(0) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { again++ }
    val state = remember(tick, again) {
        mapOf(
            "notif" to Listener.allowed(ctx), "a11y" to a11yOn(ctx), "post" to canNotify(ctx),
            "exact" to canExact(ctx), "dnd" to HomeDnd.allowed(ctx.app),
            "battery" to ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName),
            "contacts" to hasPermission(ctx, Manifest.permission.READ_CONTACTS),
        )
    }

    @Composable
    fun row(key: String, title: String, why: String, run: () -> Unit) {
        val ok = state[key] == true
        Line(title, why, {
            Text(if (ok) tx(R.string.on) else tx(R.string.turn_on), color = if (ok) Color(0xFF7ED492) else MaterialTheme.colorScheme.primary)
        }, run)
    }
    Page(tx(R.string.permissions)) {
        Note(tx(R.string.perm_note))
        Card(tx(R.string.basics)) {
            row("a11y", tx(R.string.accessibility), tx(R.string.perm_a11y_why)) {
                Actions.start(ctx, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            row("notif", tx(R.string.notification_access), tx(R.string.perm_listener_why)) {
                Actions.start(ctx, Intent(Listener.settingsAction))
            }
        }
        Card(tx(R.string.automation)) {
            row("post", tx(R.string.show_notifications), tx(R.string.perm_post_why)) {
                if (Build.VERSION.SDK_INT >= 33 && !canNotify(ctx)) ask.launch(Manifest.permission.POST_NOTIFICATIONS)
                else Actions.start(
                    ctx, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName),
                )
            }
            row("exact", tx(R.string.alarms_reminders), tx(R.string.perm_exact_why)) {
                if (Build.VERSION.SDK_INT >= 31) Actions.start(
                    ctx, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${ctx.packageName}".toUri()),
                )
            }
            row("dnd", tx(R.string.dnd_access), tx(R.string.dnd_title)) {
                Actions.start(ctx, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            }
            row("contacts", tx(R.string.contacts), tx(R.string.perm_contacts_why)) { ask.launch(Manifest.permission.READ_CONTACTS) }
        }
        Card(tx(R.string.background)) {
            row("battery", tx(R.string.no_battery_limit), tx(R.string.perm_battery_why)) {
                Actions.start(ctx, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
            Line(tx(R.string.honor_manual), tx(R.string.honor_manual_how)) {
                batteryScreen(ctx)
            }
        }
        Card(tx(R.string.if_trouble)) {
            Line(tx(R.string.app_info), tx(R.string.restricted_hint)) { details(ctx) }
        }
    }
}

@Composable
fun SearchSettings(tick: Int) {
    val ctx = LocalContext.current
    val p = ctx.app.prefs
    var again by remember { mutableIntStateOf(0) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        p.searchContacts = it
        again++
    }
    val logAsk = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        p.searchPeopleCallLog = it
        again++
    }
    Page(tx(R.string.act_search)) {
        BeforeTyping(again, { ask.launch(Manifest.permission.READ_CONTACTS) }, { logAsk.launch(Manifest.permission.READ_CALL_LOG) })
        Card(tx(R.string.results)) {
            Toggle(tx(R.string.phone_settings), "", { p.searchSettings }, { p.searchSettings = it })
            androidx.compose.runtime.key(again) {
                Toggle(tx(R.string.contacts), tx(R.string.needs_contacts), { p.searchContacts }, {
                    if (it && !hasPermission(ctx, Manifest.permission.READ_CONTACTS)) ask.launch(Manifest.permission.READ_CONTACTS)
                    else p.searchContacts = it
                })
            }
            Line(
                tx(R.string.recent_and_most), if (remember(tick) { ctx.app.apps.usageAllowed() }) tx(R.string.by_phone_usage)
                else tx(R.string.by_search_only),
            ) { Actions.start(ctx, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        }
        Card(tx(R.string.contacts)) {
            Choice(tx(R.string.call_button), mapOf("phone" to tx(R.string.phone), "whatsapp" to "WhatsApp"), { p.searchCall }, { p.searchCall = it })
            Choice(tx(R.string.message_button), mapOf("whatsapp" to "WhatsApp", "sms" to "SMS"), { p.searchMessage }, { p.searchMessage = it })
        }
        Card(tx(R.string.apps)) {
            if (p.hidden.isNotEmpty()) Line(tx(R.string.unhide_apps), tx(R.string.hidden_count, p.hidden.size)) {
                p.hidden = emptySet()
                Actions.toast(ctx, tx(R.string.back_again))
            }
            Toggle(tx(R.string.web_buttons), "", { p.searchWeb }, { p.searchWeb = it })
        }
        Note(tx(R.string.search_note))
        Note(tx(R.string.search_examples))
    }
}

/** Which rows the search shows before typing, in which order and how many. */
@Composable
private fun BeforeTyping(again: Int, askContacts: () -> Unit, askLog: () -> Unit) {
    val ctx = LocalContext.current
    val p = ctx.app.prefs
    var order by remember { mutableStateOf(p.searchOrder.split(',').filter { it.isNotBlank() }) }
    fun move(i: Int, by: Int) {
        val l = order.toMutableList()
        val j = i + by
        if (j !in l.indices) return
        l[i] = l[j].also { l[j] = l[i] }
        order = l
        p.searchOrder = l.joinToString(",")
    }
    Card(tx(R.string.before_typing_title)) {
        order.forEachIndexed { i, part ->
            androidx.compose.runtime.key(part, again) {
                val title = tx(when (part) {
                    "frequent" -> R.string.most_used
                    "recent" -> R.string.recently_used
                    "searches" -> R.string.recent_searches
                    else -> R.string.recent_people
                })
                var on by remember {
                    mutableStateOf(when (part) {
                        "frequent" -> p.searchShowFrequent
                        "recent" -> p.searchShowRecentApps
                        "searches" -> p.searchRecentOn
                        else -> p.searchShowPeople && hasPermission(ctx, Manifest.permission.READ_CONTACTS)
                    })
                }
                Row(Modifier.fillMaxWidth().padding(18.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f), fontSize = 16.sp, color = Color.White)
                    Text("▲", Modifier.clickable(enabled = i > 0) { move(i, -1) }.padding(10.dp),
                        color = if (i > 0) MaterialTheme.colorScheme.primary else Color(0x33FFFFFF))
                    Text("▼", Modifier.clickable(enabled = i < order.size - 1) { move(i, 1) }.padding(10.dp),
                        color = if (i < order.size - 1) MaterialTheme.colorScheme.primary else Color(0x33FFFFFF))
                    androidx.compose.material3.Switch(on, { v ->
                        on = v
                        when (part) {
                            "frequent" -> p.searchShowFrequent = v
                            "recent" -> p.searchShowRecentApps = v
                            "searches" -> {
                                p.searchRecentOn = v
                                if (!v) p.searchRecent = ""
                            }
                            else -> {
                                p.searchShowPeople = v
                                if (v && !hasPermission(ctx, Manifest.permission.READ_CONTACTS)) askContacts()
                            }
                        }
                    })
                }
                if (on) when (part) {
                    "frequent" -> Number(tx(R.string.how_many), 5..30, { p.searchNFrequent }, { p.searchNFrequent = it })
                    "recent" -> Number(tx(R.string.how_many), 5..30, { p.searchNRecent }, { p.searchNRecent = it })
                    "people" -> {
                        Number(tx(R.string.how_many), 3..20, { p.searchNPeople }, { p.searchNPeople = it })
                        Toggle(tx(R.string.people_from_calls), tx(R.string.people_from_calls_sub), { p.searchPeopleCallLog }, {
                            if (it && !hasPermission(ctx, Manifest.permission.READ_CALL_LOG)) askLog() else p.searchPeopleCallLog = it
                        })
                    }
                    else -> {}
                }
            }
        }
    }
}

@Composable
fun HistoryScreen(tick: Int, start: String, go: (String) -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.app
    var q by remember(start) { mutableStateOf(start) }
    var missed by remember { mutableStateOf(false) }
    var pkg by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var again by remember { mutableIntStateOf(0) }
    var open by remember { mutableStateOf<Long?>(null) }
    val list = load(q, missed, pkg, again, tick) {
        val byText = app.history.entries(q, pkg, missed)
        // Then the notifications of apps whose name matches, after those whose text does.
        val f = fold(q.trim())
        val apps = if (f.length < 2 || pkg != null) emptySet() else app.history.packages().map { it.first }
            .filter { fold(app.apps.labelOf(it)).contains(f) }.toSet()
        val seen = byText.map { it.id }.toSet()
        byText + apps.flatMap { app.history.entries("", it, missed, 200) }.filter { it.id !in seen }.sortedByDescending { it.time }
    }.orEmpty()
    val pkgs = load(tick, again) { app.history.packages() }.orEmpty()
    val gaps = load(tick) { app.history.gaps() }.orEmpty()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp)) {
        item {
            Text(tx(R.string.notification_history), Modifier.padding(4.dp, 20.dp, 4.dp, 12.dp), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
            if (!app.prefs.historyOn) Card {
                Line(tx(R.string.history_is_off), tx(R.string.history_off_tap)) { go("historyset") }
            } else Row {
                Small(tx(R.string.settings)) { go("historyset") }
                Small(tx(R.string.refresh)) { again++ }
            }
            OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), label = { Text(tx(R.string.search_field)) }, singleLine = true)
            Spacer(Modifier.height(6.dp))
            Chips(
                listOf(
                    tx(R.string.all) to { missed = false },
                    tx(R.string.missed) to { missed = true },
                    (pkg?.let { app.apps.labelOf(it) } ?: tx(R.string.app_filter)) to { if (pkg != null) pkg = null else menu = true },
                ),
            ) { if (it == 0) !missed else if (it == 1) missed else pkg != null }
            DropdownMenu(menu, { menu = false }) {
                for ((n, c) in pkgs) DropdownMenuItem({ Text("${app.apps.labelOf(n)} ($c)") }, {
                    pkg = n
                    menu = false
                })
            }
            if (gaps.isNotEmpty()) Note(tx(R.string.last_gap, clock(gaps.first().first), clock(gaps.first().second)))
            if (list.isEmpty()) Note(if (app.prefs.historyOn) tx(R.string.no_entries) else "")
        }
        items(list, key = { it.id }) { e ->
            EntryRow(app, e, open == e.id, { open = if (open == e.id) null else e.id }, {
                app.db.execute {
                    app.history.delete(e.id)
                    app.main.post { again++ }
                }
            }, {
                app.prefs.historyExcluded = app.prefs.historyExcluded + e.pkg
                Actions.toast(ctx, tx(R.string.app_not_saved_now))
            })
        }
    }
}

@Composable
private fun EntryRow(app: App, e: Entry, open: Boolean, flip: () -> Unit, del: () -> Unit, exclude: () -> Unit) = Column(
    Modifier.fillMaxWidth().clickable(onClick = flip).padding(4.dp, 10.dp),
) {
    Row {
        val k = app.apps.keyForPackage(e.pkg)
        if (k != null) AppIcon(k, 38.dp) else Spacer(Modifier.width(38.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    app.apps.labelOf(e.pkg), Modifier.weight(1f), fontSize = 12.sp, maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(clock(e.time), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (e.title.isNotEmpty()) Text(e.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = if (open) 5 else 1)
            if (e.text.isNotEmpty()) Text(
                e.text, fontSize = 14.sp, color = Color(0xFFD3D8E1), maxLines = if (open) 60 else 2, overflow = TextOverflow.Ellipsis,
            )
            val end = e.ending()
            if (end == Ending.MISSED) Text(
                end.label + if (e.removedAt > 0) " · ${clock(e.removedAt)}" else "", fontSize = 12.sp,
                color = if (end == Ending.MISSED) Color(0xFFFFB36B) else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (open) Row {
                // The notification's own target (the chat, the mail) while it is known, else the app.
                val target = remember(e.key) { dev.yesil.launchsoul.notif.Targets.has(e.key) }
                if (target) Small(tx(R.string.open_notification)) {
                    if (!dev.yesil.launchsoul.notif.Targets.open(app, e.key) && k != null) {
                        Actions.toast(app, tx(R.string.target_gone))
                        app.apps.launch(k)
                    }
                } else if (k != null) Small(tx(R.string.open_app)) { app.apps.launch(k) }
                Small(tx(R.string.delete), del)
                Small(tx(R.string.dont_save_app), exclude)
            }
        }
    }
}

@Composable
fun HistorySettings(tick: Int, go: (String) -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.app
    val p = app.prefs
    var again by remember { mutableIntStateOf(0) }
    val n = load(tick, again) { app.history.count() } ?: 0
    Page(tx(R.string.history_settings)) {
        Card {
            Toggle(tx(R.string.notification_history), if (Listener.allowed(ctx)) tx(R.string.entries_x, n) else tx(R.string.give_listener_first), { p.historyOn }, {
                p.historyOn = it
                if (it && !Listener.allowed(ctx)) Actions.start(ctx, Intent(Listener.settingsAction))
            })
            Number(tx(R.string.keep_for), 7..365, { p.historyDays }, { p.historyDays = it }) { tx(R.string.days_x, it) }
        }
        val ex = p.historyExcluded
        if (ex.isNotEmpty()) Card(tx(R.string.not_saved_apps)) {
            for (e in ex) Line(app.apps.labelOf(e), tx(R.string.tap_to_save_again)) {
                p.historyExcluded = p.historyExcluded - e
                again++
            }
        }
        Card {
            var sure by remember { mutableStateOf(false) }
            Line(if (sure) tx(R.string.sure_delete_all) else tx(R.string.delete_all_history)) {
                if (!sure) sure = true else {
                    sure = false
                    app.db.execute {
                        app.history.deleteAll()
                        app.main.post { again++ }
                    }
                }
            }
        }
        Note(tx(R.string.history_note))
    }
}

@Composable
/** The rules; with [block] only those that remove notifications, which this page calls blocking. */
fun RulesScreen(tick: Int, block: Boolean = false) {
    val ctx = LocalContext.current
    val app = ctx.app
    var again by remember { mutableIntStateOf(0) }
    var edit by remember { mutableStateOf<Rule?>(null) }
    val rules = load(tick, again) { app.history.rules() }.orEmpty().filter { !block || it.action == RuleAction.DISMISS }
    val save = { r: Rule ->
        if (block && r.on) app.prefs.rulesOn = true
        app.db.execute {
            app.history.saveRule(r)
            app.main.post { again++ }
        }
    }
    val e = edit
    if (e != null) {
        androidx.activity.compose.BackHandler { edit = null }
        RuleEditor(e, {
            save(it)
            edit = null
        }, if (e.id == 0L) null else {
            {
                app.db.execute {
                    app.history.deleteRule(e.id)
                    app.main.post { again++ }
                }
                edit = null
            }
        })
        return
    }
    Page(tx(if (block) R.string.blocking else R.string.notification_rules)) {
        if (block) Note(tx(R.string.blocking_note))
        Card {
            Toggle(tx(R.string.rules_run), if (Listener.allowed(ctx)) "" else tx(R.string.needs_listener), { app.prefs.rulesOn }, {
                app.prefs.rulesOn = it
                if (it && !Listener.allowed(ctx)) Actions.start(ctx, Intent(Listener.settingsAction))
            })
        }
        if (rules.isNotEmpty()) Card {
            for (r in rules) Line(
                (if (r.pkg.isEmpty()) tx(R.string.every_app) else app.apps.labelOf(r.pkg)) + (if (r.contains.isEmpty()) "" else " · “${r.contains}”"),
                "${RuleAction.names[r.action]} ${r.arg}" + (if (r.dryRun) tx(R.string.test_only_tag) else "") + (if (r.on) "" else tx(R.string.off_tag)),
            ) { edit = r }
        }
        Big(tx(if (block) R.string.add_block else R.string.add_rule)) { edit = Rule(0, "", "", RuleAction.DISMISS, "30", dryRun = !block, on = true) }
        Note(tx(if (block) R.string.blocking_sound else R.string.rules_note))
    }
}

@Composable
private fun RuleEditor(r: Rule, save: (Rule) -> Unit, delete: (() -> Unit)?) {
    val app = LocalContext.current.app
    var pkg by remember { mutableStateOf(r.pkg) }
    var contains by remember { mutableStateOf(r.contains) }
    var action by remember { mutableStateOf(r.action) }
    var arg by remember { mutableStateOf(r.arg) }
    var dry by remember { mutableStateOf(r.dryRun) }
    var on by remember { mutableStateOf(r.on) }
    var menu by remember { mutableStateOf(false) }
    Page(if (r.id == 0L) tx(R.string.new_rule) else tx(R.string.rule)) {
        Card(tx(R.string.rule_when)) {
            Line(tx(R.string.app), if (pkg.isEmpty()) tx(R.string.every_app) else app.apps.labelOf(pkg), {
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text(tx(R.string.every_app)) }, {
                        pkg = ""
                        menu = false
                    })
                    for (a in app.apps.list) DropdownMenuItem({ Text(a.label) }, {
                        pkg = a.pkg
                        menu = false
                    })
                }
            }) { menu = true }
            OutlinedTextField(
                contains, { contains = it }, Modifier.fillMaxWidth().padding(18.dp, 8.dp),
                label = { Text(tx(R.string.contains_word)) }, singleLine = true,
            )
        }
        Card(tx(R.string.then_do)) {
            Choice(tx(R.string.action), RuleAction.names, { action }, { action = it })
            if (action == RuleAction.SNOOZE || action == RuleAction.OPEN_LATER) OutlinedTextField(
                arg, { s -> arg = s.filter { it.isDigit() }.take(4) }, Modifier.fillMaxWidth().padding(18.dp, 8.dp),
                label = { Text(tx(R.string.minutes)) }, singleLine = true,
            )
            Toggle(tx(R.string.test_only), tx(R.string.dont_do_log), { dry }, { dry = it })
            Toggle(tx(R.string.on), "", { on }, { on = it })
        }
        if (pkg.isEmpty() && contains.isBlank()) Note(tx(R.string.pick_app_or_word))
        else Big(tx(R.string.save)) { save(Rule(r.id, pkg, contains.trim(), action, arg, dry, on)) }
        if (delete != null) Small(tx(R.string.delete_rule), delete)
    }
}

private fun hm(m: Int) = "%02d:%02d".format(m / 60, m % 60)

private fun pickTime(ctx: Context, m: Int, set: (Int) -> Unit) =
    TimePickerDialog(ctx, { _, h, n -> set(h * 60 + n) }, m / 60, m % 60, true).show()

@Composable
fun DndScreen(tick: Int) {
    val ctx = LocalContext.current
    val app = ctx.app
    val p = app.prefs
    var again by remember { mutableIntStateOf(0) }
    fun changed() {
        again++
        app.io.execute {
            HomeDnd.evaluate(app, "ayar")
            app.main.post { again++ }
        }
    }
    val print = remember(tick, again) { HomeDnd.fingerprint() }
    val fine = remember(tick, again) { hasPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) }
    val always = remember(tick, again) { hasPermission(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION) }
    val located = remember(tick, again) {
        androidx.core.location.LocationManagerCompat.isLocationEnabled(ctx.getSystemService(android.location.LocationManager::class.java))
    }
    val ssid = remember(tick, again) { HomeDnd.ssid(app) }
    val around = remember(tick, again) { if (fine) HomeDnd.nearby(app) else emptyList() }
    var menu by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { changed() }
    Page(tx(R.string.dnd_title)) {
        if (!HomeDnd.allowed(app)) Card {
            Line(tx(R.string.dnd_access_needed), tx(R.string.tap_to_turn_on)) {
                Actions.start(ctx, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            }
        }
        Card {
            Toggle(tx(R.string.on), tx(R.string.dnd_on_sub), { p.dndOn }, {
                p.dndOn = it
                changed()
            })
            androidx.compose.runtime.key(again) {
                Line(tx(R.string.start), hm(p.dndStart)) { pickTime(ctx, p.dndStart) { p.dndStart = it; changed() } }
                Line(tx(R.string.end), hm(p.dndEnd)) { pickTime(ctx, p.dndEnd) { p.dndEnd = it; changed() } }
            }
            Toggle(tx(R.string.only_charging), tx(R.string.only_charging_why), { p.dndCharging }, {
                p.dndCharging = it
                changed()
            })
        }
        Card(tx(R.string.home_networks)) {
            androidx.compose.runtime.key(again, tick) {
                val home = print != null && (print in p.dndHomes || HomeDnd.nameKnown(app, ssid))
                Line(
                    if (print == null) tx(R.string.no_wifi) else (ssid ?: tx(R.string.name_unreadable)),
                    if (print == null) "" else if (home) tx(if (HomeDnd.active) R.string.home_net_on else R.string.home_net_off) else tx(R.string.not_home_net),
                )
                val labels = p.dndLabels.mapNotNull { it.split('\t').takeIf { l -> l.size == 2 } }.associate { it[0] to it[1] }
                val unnamed = p.dndHomes.filter { labels[it] == null || labels[it] !in p.dndNames }
                for (n in p.dndNames.sorted()) Line(n, "", {
                    Small(tx(R.string.delete)) {
                        p.dndNames = p.dndNames - n
                        val prints = labels.filterValues { it == n }.keys
                        p.dndHomes = p.dndHomes - prints
                        p.dndLabels = p.dndLabels.filter { it.substringBefore('\t') !in prints }.toSet()
                        changed()
                    }
                })
                unnamed.forEachIndexed { i, f ->
                    Line(labels[f] ?: (if (unnamed.size > 1) tx(R.string.unnamed_net_x, i + 1) else tx(R.string.unnamed_net)), tx(R.string.known_by_router), {
                        Small(tx(R.string.delete)) {
                            p.dndHomes = p.dndHomes - f
                            p.dndLabels = p.dndLabels.filter { it.substringBefore('\t') != f }.toSet()
                            changed()
                        }
                    })
                }
                if (p.dndNames.isEmpty() && p.dndHomes.isEmpty()) Note(tx(R.string.no_home_nets))
            }
        }
        Card(tx(R.string.add_home_net)) {
            androidx.compose.runtime.key(again, tick) {
                val add = { n: String ->
                    if (n.isNotBlank()) p.dndNames = p.dndNames + n.trim()
                    changed()
                }
                if (print != null && print !in p.dndHomes) Line(
                    tx(R.string.add_connected), ssid ?: tx(R.string.add_connected_noname),
                ) {
                    // Both ways are kept: the address works without location, the name also on a second router.
                    p.dndHomes = p.dndHomes + print
                    if (ssid != null) {
                        p.dndNames = p.dndNames + ssid
                        p.dndLabels = p.dndLabels + "$print\t$ssid"
                    }
                    changed()
                }
                Line(tx(R.string.pick_nearby), if (!fine) tx(R.string.needs_location) else if (around.isEmpty()) tx(R.string.nearby_empty) else "", {
                    DropdownMenu(menu, { menu = false }) {
                        for (n in around) DropdownMenuItem({ Text((if (n in p.dndNames) "✓ " else "") + n) }, {
                            if (n in p.dndNames) {
                                p.dndNames = p.dndNames - n
                                changed()
                            } else add(n)
                        })
                        DropdownMenuItem({ Text(tx(R.string.ok)) }, { menu = false })
                    }
                }) {
                    if (fine) menu = around.isNotEmpty()
                    else ask.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }
            }
            OutlinedTextField(
                typed, { typed = it }, Modifier.fillMaxWidth().padding(18.dp, 8.dp), label = { Text(tx(R.string.or_type_name)) }, singleLine = true,
            )
            if (typed.isNotBlank()) Line(tx(R.string.add_x, typed.trim())) {
                p.dndNames = p.dndNames + typed.trim()
                typed = ""
                changed()
            }
            if (p.dndNames.isNotEmpty() && !(fine && always)) Line(
                tx(R.string.location_missing), tx(R.string.location_missing_how),
            ) {
                if (!fine) ask.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                else if (Build.VERSION.SDK_INT >= 29) ask.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
            }
            if (p.dndNames.isNotEmpty() && fine && !located) Line(tx(R.string.location_off), tx(R.string.location_off_how)) {
                Actions.start(ctx, Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
        }
        Note(tx(R.string.dnd_note_names))
        Note(tx(R.string.dnd_note_calls))
        Note(tx(R.string.dnd_note_own))
    }
}

@Composable
fun RemindersScreen(tick: Int) {
    val ctx = LocalContext.current
    val app = ctx.app
    var again by remember { mutableIntStateOf(0) }
    var text by remember { mutableStateOf("") }
    val list = load(tick, again) { app.history.reminders() }.orEmpty()
    val parsed = ReminderParser.parse(text, LocalDateTime.now())
    Page(tx(R.string.reminders)) {
        if (!canExact(ctx)) Card {
            Line(tx(R.string.exact_off), tx(R.string.exact_off_how)) {
                if (Build.VERSION.SDK_INT >= 31) Actions.start(
                    ctx, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${ctx.packageName}".toUri()),
                )
            }
        }
        if (!canNotify(ctx)) Card { Line(tx(R.string.post_off), tx(R.string.reminder_invisible)) { details(ctx) } }
        OutlinedTextField(
            text, { text = it }, Modifier.fillMaxWidth(), label = { Text(tx(R.string.reminder_hint)) }, singleLine = true,
        )
        if (parsed != null) {
            val at = parsed.at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            Big("${clock(at)} – ${parsed.text}") {
                Reminders.add(app, at, parsed.text)
                text = ""
                app.db.execute { app.main.post { again++ } }
            }
        }
        Spacer(Modifier.height(10.dp))
        if (list.isNotEmpty()) Card(tx(R.string.pending)) {
            for (r in list) Line(r.text, clock(r.at), {
                Small(tx(R.string.delete)) {
                    app.db.execute {
                        Reminders.delete(app, r.id)
                        app.main.post { again++ }
                    }
                }
            })
        } else Note(tx(R.string.no_reminders))
        Note(tx(R.string.reminders_note))
    }
}

@Composable
fun TimerScreen(tick: Int) {
    val ctx = LocalContext.current
    val app = ctx.app
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }
    val left = app.prefs.timerEnd - now
    Page(tx(R.string.timer)) {
        if (left > 0) {
            Card {
                Line(tx(R.string.left), "%d:%02d".format(left / 60_000, left / 1000 % 60))
                Line(tx(R.string.plus_15_minutes)) { SleepTimer.extend(app, 15); now = System.currentTimeMillis() }
                Line(tx(R.string.cancel)) { SleepTimer.cancel(app); now = System.currentTimeMillis() }
            }
        } else Card {
            for (m in listOf(1, 15, 30, 45, 60, 90)) Line(if (m == 1) tx(R.string.one_min_test) else tx(R.string.x_minutes, m)) {
                SleepTimer.start(app, m)
                now = System.currentTimeMillis()
            }
        }
        if (!Listener.allowed(ctx)) Note(tx(R.string.timer_note_listener))
        Note(tx(R.string.timer_note))
    }
}

@Composable
fun LogScreen() {
    val app = LocalContext.current.app
    var again by remember { mutableIntStateOf(0) }
    val l = load(again) { app.history.logs() }.orEmpty()
    Page(tx(R.string.log)) {
        Small(tx(R.string.refresh)) { again++ }
        if (l.isEmpty()) Note(tx(R.string.nothing_yet))
        else Card { for ((t, m) in l) Line(m, clock(t)) }
    }
}
