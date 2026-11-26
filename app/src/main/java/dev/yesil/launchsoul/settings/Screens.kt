package dev.yesil.launchsoul.settings

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
            Text(if (ok) "Açık" else "Aç", color = if (ok) Color(0xFF7ED492) else MaterialTheme.colorScheme.primary)
        }, run)
    }
    Page("İzinler") {
        Note("Hepsi isteğe bağlı. Vermediğin izin yalnızca o özelliği kapatır.")
        Card("Temel") {
            row("a11y", "Erişilebilirlik", "Kaydırma ve kenar hareketleri, kilitleme. Ekran içeriğini okumaz.") {
                Actions.start(ctx, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            row("notif", "Bildirim erişimi", "Rozet, bildirim geçmişi, kurallar, medyayı durdurma") {
                Actions.start(ctx, Intent(Listener.settingsAction))
            }
        }
        Card("Otomasyon") {
            row("post", "Bildirim gösterme", "Hatırlatıcılar ve uyku zamanlayıcı") {
                if (Build.VERSION.SDK_INT >= 33 && !canNotify(ctx)) ask.launch(Manifest.permission.POST_NOTIFICATIONS)
                else Actions.start(
                    ctx, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName),
                )
            }
            row("exact", "Alarmlar ve hatırlatıcılar", "Hatırlatıcının tam zamanında çalması") {
                if (Build.VERSION.SDK_INT >= 31) Actions.start(
                    ctx, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${ctx.packageName}".toUri()),
                )
            }
            row("dnd", "Rahatsız Etme erişimi", "Uyku modu otomasyonu") {
                Actions.start(ctx, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            }
            row("contacts", "Kişiler", "Aramada kişi bulma") { ask.launch(Manifest.permission.READ_CONTACTS) }
        }
        Card("Arka planda kalma") {
            row("battery", "Pil kısıtlaması yok", "Hatırlatıcı ve geçmiş kesilmesin") {
                Actions.start(ctx, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
            Line("Honor: “Elle yönet”", "Uygulama başlatma → Better Launch Soul → otomatik yönetimi kapat, üç anahtarı aç") {
                batteryScreen(ctx)
            }
        }
        Card("Sorun olursa") {
            Line("Uygulama bilgisi", "Bir izin griyse: sağ üstteki ⋮ → “Kısıtlı ayarlara izin ver”") { details(ctx) }
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
    Page("Arama") {
        Card {
            Toggle("Telefon ayarları", "", { p.searchSettings }, { p.searchSettings = it })
            androidx.compose.runtime.key(again) {
                Toggle("Kişiler", "Kişi izni ister", { p.searchContacts }, {
                    if (it && !hasPermission(ctx, Manifest.permission.READ_CONTACTS)) ask.launch(Manifest.permission.READ_CONTACTS)
                    else p.searchContacts = it
                })
            }
            Line(
                "Son ve sık kullanılanlar", if (remember(tick) { ctx.app.apps.usageAllowed() }) "Telefondaki kullanıma göre"
                else "Yalnızca aramadan açtıklarına göre; telefondaki kullanıma göre olsun diye dokun",
            ) { Actions.start(ctx, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
            Choice("Arama düğmesi", mapOf("phone" to "Telefon", "whatsapp" to "WhatsApp"), { p.searchCall }, { p.searchCall = it })
            Choice("Mesaj düğmesi", mapOf("whatsapp" to "WhatsApp", "sms" to "SMS"), { p.searchMessage }, { p.searchMessage = it })
            Toggle("Sık kullanılanları göster", "Aramaya başlamadan önce, yana kaydırılır", { p.searchShowFrequent }, { p.searchShowFrequent = it })
            Toggle("Son kullanılanları göster", "", { p.searchShowRecentApps }, { p.searchShowRecentApps = it })
            Toggle("Son aramaları göster", "Yalnızca bu telefonda saklanır", { p.searchRecentOn }, {
                p.searchRecentOn = it
                if (!it) p.searchRecent = ""
            })
            if (p.hidden.isNotEmpty()) Line("Gizlenen uygulamaları geri getir", "${p.hidden.size} uygulama") {
                p.hidden = emptySet()
                Actions.toast(ctx, "Geri geldi")
            }
            Toggle("İnternet, Haritalar, YouTube, Play düğmeleri", "", { p.searchWeb }, { p.searchWeb = it })
        }
        Note("Arama telefonda yapılır. Yazdığın, ancak “internette ara” gibi bir satıra dokunursan başka uygulamaya gider.")
        Note("Örnekler: 12,5*4 · yarın 9 süt al · 20 dk sonra çayı kapat · cuma 14:30 toplantı · uyku 45")
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
    val list = load(q, missed, pkg, again, tick) { app.history.entries(q, pkg, missed) }.orEmpty()
    val pkgs = load(tick, again) { app.history.packages() }.orEmpty()
    val gaps = load(tick) { app.history.gaps() }.orEmpty()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp)) {
        item {
            Text("Bildirim geçmişi", Modifier.padding(4.dp, 20.dp, 4.dp, 12.dp), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
            if (!app.prefs.historyOn) Card {
                Line("Geçmiş kapalı", "Açmak için dokun. Banka ve mesaj içeriği de saklanır; yalnızca bu telefonda.") { go("historyset") }
            } else Row {
                Small("Ayarlar") { go("historyset") }
                Small("Yenile") { again++ }
            }
            OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), label = { Text("Ara") }, singleLine = true)
            Spacer(Modifier.height(6.dp))
            Chips(
                listOf(
                    "Hepsi" to { missed = false },
                    "Kaçırılanlar" to { missed = true },
                    (pkg?.let { app.apps.labelOf(it) } ?: "Uygulama ▾") to { if (pkg != null) pkg = null else menu = true },
                ),
            ) { if (it == 0) !missed else if (it == 1) missed else pkg != null }
            DropdownMenu(menu, { menu = false }) {
                for ((n, c) in pkgs) DropdownMenuItem({ Text("${app.apps.labelOf(n)} ($c)") }, {
                    pkg = n
                    menu = false
                })
            }
            if (gaps.isNotEmpty()) Note("Son kesinti: ${clock(gaps.first().first)} – ${clock(gaps.first().second)}. Arada gelip silinenler kaydedilemedi.")
            if (list.isEmpty()) Note(if (app.prefs.historyOn) "Kayıt yok." else "")
        }
        items(list, key = { it.id }) { e ->
            EntryRow(app, e, open == e.id, { open = if (open == e.id) null else e.id }, {
                app.db.execute {
                    app.history.delete(e.id)
                    app.main.post { again++ }
                }
            }, {
                app.prefs.historyExcluded = app.prefs.historyExcluded + e.pkg
                Actions.toast(ctx, "Bu uygulama artık kaydedilmeyecek")
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
                if (k != null) Small("Uygulamayı aç") { app.apps.launch(k) }
                Small("Sil", del)
                Small("Bu uygulamayı kaydetme", exclude)
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
    Page("Geçmiş ayarları") {
        Card {
            Toggle("Bildirim geçmişi", if (Listener.allowed(ctx)) "$n kayıt" else "Önce bildirim erişimi ver", { p.historyOn }, {
                p.historyOn = it
                if (it && !Listener.allowed(ctx)) Actions.start(ctx, Intent(Listener.settingsAction))
            })
            Number("Saklama süresi", 7..365, { p.historyDays }, { p.historyDays = it }) { "$it gün" }
        }
        val ex = p.historyExcluded
        if (ex.isNotEmpty()) Card("Kaydedilmeyen uygulamalar") {
            for (e in ex) Line(app.apps.labelOf(e), "Yeniden kaydetmek için dokun") {
                p.historyExcluded = p.historyExcluded - e
                again++
            }
        }
        Card {
            var sure by remember { mutableStateOf(false) }
            Line(if (sure) "Emin misin? Geri alınamaz – dokun ve sil" else "Tüm geçmişi sil") {
                if (!sure) sure = true else {
                    sure = false
                    app.db.execute {
                        app.history.deleteAll()
                        app.main.post { again++ }
                    }
                }
            }
        }
        Note("Android, tek kullanımlık kod içeren bildirimleri gizleyebilir. Kayıt, izin verildiği andan başlar; yedeklemeye girmez.")
    }
}

@Composable
fun RulesScreen(tick: Int) {
    val ctx = LocalContext.current
    val app = ctx.app
    var again by remember { mutableIntStateOf(0) }
    var edit by remember { mutableStateOf<Rule?>(null) }
    val rules = load(tick, again) { app.history.rules() }.orEmpty()
    val save = { r: Rule ->
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
    Page("Bildirim kuralları") {
        Card {
            Toggle("Kurallar çalışsın", if (Listener.allowed(ctx)) "" else "Bildirim erişimi gerekli", { app.prefs.rulesOn }, {
                app.prefs.rulesOn = it
                if (it && !Listener.allowed(ctx)) Actions.start(ctx, Intent(Listener.settingsAction))
            })
        }
        if (rules.isNotEmpty()) Card {
            for (r in rules) Line(
                (if (r.pkg.isEmpty()) "Her uygulama" else app.apps.labelOf(r.pkg)) + (if (r.contains.isEmpty()) "" else " · “${r.contains}”"),
                "${RuleAction.names[r.action]} ${r.arg}" + (if (r.dryRun) " · yalnızca dene" else "") + (if (r.on) "" else " · kapalı"),
            ) { edit = r }
        }
        Big("Kural ekle") { edit = Rule(0, "", "", RuleAction.DISMISS, "30", dryRun = true, on = true) }
        Note("Yeni kural “yalnızca dene” ile başlar: ne yapacağını Günlük'e yazar, yapmaz. Bildirim önce ses çıkarabilir, sonra silinir.")
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
    Page(if (r.id == 0L) "Yeni kural" else "Kural") {
        Card("Ne zaman") {
            Line("Uygulama", if (pkg.isEmpty()) "Her uygulama" else app.apps.labelOf(pkg), {
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("Her uygulama") }, {
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
                label = { Text("İçinde geçen söz (boş = hepsi)") }, singleLine = true,
            )
        }
        Card("Ne yapılsın") {
            Choice("Eylem", RuleAction.names, { action }, { action = it })
            if (action == RuleAction.SNOOZE || action == RuleAction.OPEN_LATER) OutlinedTextField(
                arg, { s -> arg = s.filter { it.isDigit() }.take(4) }, Modifier.fillMaxWidth().padding(18.dp, 8.dp),
                label = { Text("Dakika") }, singleLine = true,
            )
            Toggle("Yalnızca dene", "Yapma, günlüğe yaz", { dry }, { dry = it })
            Toggle("Açık", "", { on }, { on = it })
        }
        if (pkg.isEmpty() && contains.isBlank()) Note("Uygulama ya da söz seç: boş kural her bildirime uygulanır.")
        else Big("Kaydet") { save(Rule(r.id, pkg, contains.trim(), action, arg, dry, on)) }
        if (delete != null) Small("Kuralı sil", delete)
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
    Page("Uyku modu otomasyonu") {
        if (!HomeDnd.allowed(app)) Card {
            Line("Rahatsız Etme erişimi gerekli", "Açmak için dokun") {
                Actions.start(ctx, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            }
        }
        Card {
            Toggle("Açık", "Gece + ev Wi-Fi'ı → Rahatsız Etme", { p.dndOn }, {
                p.dndOn = it
                changed()
            })
            androidx.compose.runtime.key(again) {
                Line("Başlangıç", hm(p.dndStart)) { pickTime(ctx, p.dndStart) { p.dndStart = it; changed() } }
                Line("Bitiş", hm(p.dndEnd)) { pickTime(ctx, p.dndEnd) { p.dndEnd = it; changed() } }
            }
            Toggle("Yalnızca şarjdayken", "Evi yanlış tanımaya karşı güvenlik", { p.dndCharging }, {
                p.dndCharging = it
                changed()
            })
        }
        Card("Ev ağları") {
            androidx.compose.runtime.key(again, tick) {
                val home = print != null && (print in p.dndHomes || HomeDnd.nameKnown(app, ssid))
                Line(
                    if (print == null) "Wi-Fi'a bağlı değil" else (ssid ?: "Bağlı ağın adı okunamıyor"),
                    if (print == null) "" else if (home) "Ev ağı · uyku modu ${if (HomeDnd.active) "açık" else "kapalı"}" else "Ev ağı değil",
                )
                val labels = p.dndLabels.mapNotNull { it.split('\t').takeIf { l -> l.size == 2 } }.associate { it[0] to it[1] }
                val unnamed = p.dndHomes.filter { labels[it] == null || labels[it] !in p.dndNames }
                for (n in p.dndNames.sorted()) Line(n, "", {
                    Small("Sil") {
                        p.dndNames = p.dndNames - n
                        val prints = labels.filterValues { it == n }.keys
                        p.dndHomes = p.dndHomes - prints
                        p.dndLabels = p.dndLabels.filter { it.substringBefore('\t') !in prints }.toSet()
                        changed()
                    }
                })
                unnamed.forEachIndexed { i, f ->
                    Line(labels[f] ?: "Adsız ağ${if (unnamed.size > 1) " ${i + 1}" else ""}", "Yönlendiricinin adresinden tanınır", {
                        Small("Sil") {
                            p.dndHomes = p.dndHomes - f
                            p.dndLabels = p.dndLabels.filter { it.substringBefore('\t') != f }.toSet()
                            changed()
                        }
                    })
                }
                if (p.dndNames.isEmpty() && p.dndHomes.isEmpty()) Note("Henüz ev ağı yok.")
            }
        }
        Card("Ev ağı ekle") {
            androidx.compose.runtime.key(again, tick) {
                val add = { n: String ->
                    if (n.isNotBlank()) p.dndNames = p.dndNames + n.trim()
                    changed()
                }
                if (print != null && print !in p.dndHomes) Line(
                    "Bağlı olduğun ağı ekle", ssid ?: "Adı okunamıyor; yönlendiricinin adresinden tanınacak",
                ) {
                    // Both ways are kept: the address works without location, the name also on a second router.
                    p.dndHomes = p.dndHomes + print
                    if (ssid != null) {
                        p.dndNames = p.dndNames + ssid
                        p.dndLabels = p.dndLabels + "$print\t$ssid"
                    }
                    changed()
                }
                Line("Çevredeki ağlardan seç", if (!fine) "Konum izni ister" else if (around.isEmpty()) "Liste boş: konumu aç, Wi-Fi ayarını bir kez açıp dön" else "", {
                    DropdownMenu(menu, { menu = false }) {
                        for (n in around) DropdownMenuItem({ Text((if (n in p.dndNames) "✓ " else "") + n) }, {
                            if (n in p.dndNames) {
                                p.dndNames = p.dndNames - n
                                changed()
                            } else add(n)
                        })
                        DropdownMenuItem({ Text("Tamam") }, { menu = false })
                    }
                }) {
                    if (fine) menu = around.isNotEmpty()
                    else ask.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }
            }
            OutlinedTextField(
                typed, { typed = it }, Modifier.fillMaxWidth().padding(18.dp, 8.dp), label = { Text("Ya da ağın adını yaz") }, singleLine = true,
            )
            if (typed.isNotBlank()) Line("Ekle: ${typed.trim()}") {
                p.dndNames = p.dndNames + typed.trim()
                typed = ""
                changed()
            }
            if (p.dndNames.isNotEmpty() && !(fine && always)) Line(
                "✗ Konum izni eksik", "Ağ adı ekran kapalıyken de okunsun: Konum → “Her zaman izin ver”, kesin konum açık",
            ) {
                if (!fine) ask.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                else if (Build.VERSION.SDK_INT >= 29) ask.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
            }
            if (p.dndNames.isNotEmpty() && fine && !located) Line("✗ Telefonun konumu kapalı", "Kapalıyken ağ adı okunamaz; açmak için dokun") {
                Actions.start(ctx, Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
        }
        Note("Android, telefonun kayıtlı ağlar listesini uygulamalara vermez; bu yüzden çevredeki ağlar gösterilir ya da ad elle yazılır. Ağın adı konum izniyle okunur, konum hiçbir yere gönderilmez; aynı adlı başka bir ağ da ev sayılır. Ad okunamazsa ağ, yönlendiricinin adreslerinden tanınır.")
        Note("Alarmlar, yıldızlı kişiler ve 15 dk içinde tekrar arayanlar geçer. Wi-Fi koparsa 3 dk sonra kapanır. Emin olunamazsa telefon çalar.")
        Note("Telefonun kendi zamanlanmış Rahatsız Etme / Uyku modunu KAPAT: iki kuraldan en sessiz olanı kazanır.")
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
    Page("Hatırlatıcılar") {
        if (!canExact(ctx)) Card {
            Line("“Alarmlar ve hatırlatıcılar” kapalı", "Bu kapalıyken hatırlatıcı gecikebilir; açmak için dokun") {
                if (Build.VERSION.SDK_INT >= 31) Actions.start(
                    ctx, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${ctx.packageName}".toUri()),
                )
            }
        }
        if (!canNotify(ctx)) Card { Line("Bildirim izni kapalı", "Hatırlatıcı görünmez") { details(ctx) } }
        OutlinedTextField(
            text, { text = it }, Modifier.fillMaxWidth(), label = { Text("yarın 9 süt al · 20 dk sonra çayı kapat") }, singleLine = true,
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
        if (list.isNotEmpty()) Card("Bekleyenler") {
            for (r in list) Line(r.text, clock(r.at), {
                Small("Sil") {
                    app.db.execute {
                        Reminders.delete(app, r.id)
                        app.main.post { again++ }
                    }
                }
            })
        } else Note("Bekleyen hatırlatıcı yok.")
        Note("Uygulamayı “zorla durdur” yaparsan alarmlar silinir; uygulama yeniden açılınca kurulur.")
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
    Page("Uyku zamanlayıcı") {
        if (left > 0) {
            Card {
                Line("Kalan", "%d:%02d".format(left / 60_000, left / 1000 % 60))
                Line("+15 dakika") { SleepTimer.extend(app, 15); now = System.currentTimeMillis() }
                Line("İptal") { SleepTimer.cancel(app); now = System.currentTimeMillis() }
            }
        } else Card {
            for (m in listOf(1, 15, 30, 45, 60, 90)) Line(if (m == 1) "1 dakika (deneme)" else "$m dakika") {
                SleepTimer.start(app, m)
                now = System.currentTimeMillis()
            }
        }
        if (!Listener.allowed(ctx)) Note("Bildirim erişimi verilirse her oynatıcıyı durdurabilir; verilmezse yalnızca medya tuşu gönderir.")
        Note("Süre dolunca ses kısılır, çalan durdurulur, ses düzeyi geri gelir. Aramaya “uyku 45” yazarak da kurulur. Bluetooth'u kapatmaz; çoğu kulaklık ses durunca kendisi kapanır.")
    }
}

@Composable
fun LogScreen() {
    val app = LocalContext.current.app
    var again by remember { mutableIntStateOf(0) }
    val l = load(again) { app.history.logs() }.orEmpty()
    Page("Günlük") {
        Small("Yenile") { again++ }
        if (l.isEmpty()) Note("Henüz bir şey olmadı.")
        else Card { for ((t, m) in l) Line(m, clock(t)) }
    }
}
