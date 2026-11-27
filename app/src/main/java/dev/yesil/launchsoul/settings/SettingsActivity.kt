package dev.yesil.launchsoul.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Timer
import dev.yesil.launchsoul.R
import dev.yesil.launchsoul.tx

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.yesil.launchsoul.app
import dev.yesil.launchsoul.data.Prefs
import dev.yesil.launchsoul.ui.SoulTheme

class SettingsActivity : ComponentActivity() {
    private val stack = mutableStateListOf("main")

    private val query = mutableStateOf("")

    /** Goes up on every resume, so permission states are read again. */
    private val tick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bar = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(bar, bar)
        intent.getStringExtra("screen")?.let { stack += it }
        query.value = intent.getStringExtra("q") ?: ""
        setContent {
            SoulTheme {
                BackHandler(stack.size > 1) { stack.removeAt(stack.lastIndex) }
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
                    val go: (String) -> Unit = { stack += it }
                    val t = tick.intValue
                    when (val s = stack.last()) {
                        "main" -> MainScreen(go, t)
                        "gestures" -> GestureScreen(t, go)
                        "edge" -> EdgeScreen(t, go)
                        "search" -> SearchSettings(t)
                        "notifs" -> NotificationsScreen(go)
                        "auto" -> AutomationScreen(go)
                        "about" -> AboutScreen()
                        "history" -> HistoryScreen(t, query.value, go)
                        "historyset" -> HistorySettings(t, go)
                        "rules" -> RulesScreen(t)
                        "block" -> RulesScreen(t, block = true)
                        "tuning" -> TuningScreen()
                        "dnd" -> DndScreen(t)
                        "reminders" -> RemindersScreen(t)
                        "timer" -> TimerScreen(t)
                        "permissions" -> PermissionScreen(t)
                        "log" -> LogScreen()
                        else -> Text(s)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        query.value = intent.getStringExtra("q") ?: ""
        intent.getStringExtra("screen")?.let { if (stack.last() != it) stack += it }
    }

    override fun onResume() {
        super.onResume()
        tick.intValue++
    }
}

@Composable
fun Page(title: String, content: @Composable () -> Unit) = Column(
    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp, 8.dp),
) {
    Text(title, Modifier.padding(4.dp, 20.dp, 4.dp, 16.dp), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
    content()
    Spacer(Modifier.height(40.dp))
}

@Composable
fun Card(title: String? = null, content: @Composable () -> Unit) {
    if (title != null) Text(
        title, Modifier.padding(6.dp, 12.dp, 6.dp, 6.dp), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(vertical = 4.dp),
    ) { content() }
    Spacer(Modifier.height(10.dp))
}

@Composable
fun Line(title: String, sub: String = "", end: @Composable () -> Unit = {}, onClick: (() -> Unit)? = null) = Row(
    Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(18.dp, 13.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    Column(Modifier.weight(1f)) {
        Text(title, fontSize = 16.sp, color = Color.White)
        if (sub.isNotEmpty()) Text(sub, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(Modifier.width(12.dp))
    end()
}

@Composable
fun Toggle(title: String, sub: String = "", get: () -> Boolean, set: (Boolean) -> Unit) {
    var v by remember { mutableStateOf(get()) }
    LaunchedEffect(get()) { v = get() }
    val flip = { b: Boolean ->
        set(b)
        v = get()
    }
    Line(title, sub, { Switch(v, flip) }) { flip(!v) }
}

@Composable
fun Number(title: String, range: IntRange, get: () -> Int, set: (Int) -> Unit, show: (Int) -> String = { "$it" }) {
    var v by remember { mutableIntStateOf(get()) }
    Column(Modifier.padding(18.dp, 10.dp)) {
        Row {
            Text(title, Modifier.weight(1f), fontSize = 16.sp, color = Color.White)
            Text(show(v), color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            v.toFloat(), { v = it.toInt() }, valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = if (range.last - range.first <= 12) range.last - range.first - 1 else 0,
            onValueChangeFinished = { set(v) },
        )
    }
}

@Composable
fun Choice(title: String, names: Map<String, String>, get: () -> String, set: (String) -> Unit) {
    val app = LocalContext.current.app
    var v by remember { mutableStateOf(get()) }
    var open by remember { mutableStateOf(false) }
    var apps by remember { mutableStateOf(false) }
    val label = names[v] ?: v.takeIf { it.startsWith(Prefs.ACT_APP) }
        ?.let { app.apps[it.removePrefix(Prefs.ACT_APP)]?.label } ?: v
    val pick = { s: String ->
        set(s)
        v = s
        open = false
        apps = false
    }
    Line(title, label, {
        Box {
            Text("▾", color = MaterialTheme.colorScheme.onSurfaceVariant)
            DropdownMenu(open, { open = false }) {
                for ((k, n) in names) DropdownMenuItem({ Text(n) }, { pick(k) })
                if (Prefs.ACT_NONE in names) DropdownMenuItem({ Text(tx(R.string.an_app)) }, {
                    open = false
                    apps = true
                })
            }
            DropdownMenu(apps, { apps = false }) {
                for (a in app.apps.visible()) DropdownMenuItem({ Text(a.label) }, { pick(Prefs.ACT_APP + a.key) })
            }
        }
    }) { open = true }
}

@Composable
fun Chips(names: List<Pair<String, () -> Unit>>, selected: (Int) -> Boolean) = Row(
    Modifier.horizontalScroll(rememberScrollState()).padding(18.dp, 4.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
) {
    names.forEachIndexed { i, (n, f) -> FilterChip(selected(i), f, { Text(n) }) }
}

@Composable
fun Note(s: String) = Text(
    s, Modifier.padding(6.dp, 4.dp, 6.dp, 12.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
)

@Composable
fun Big(text: String, onClick: () -> Unit) = Button(onClick, Modifier.fillMaxWidth().padding(vertical = 6.dp)) { Text(text) }

@Composable
fun Small(text: String, onClick: () -> Unit) = TextButton(onClick) { Text(text) }

@Composable
private fun GestureScreen(tick: Int, go: (String) -> Unit) {
    val ctx = LocalContext.current
    val p = ctx.app.prefs
    val access = remember(tick) { a11yOn(ctx) }
    var on by remember { mutableStateOf(p.zoneOn) }
    val openAccess = {
        dev.yesil.launchsoul.system.Actions.start(
            ctx, android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS),
        )
        Unit
    }
    Page(tx(R.string.home_gestures)) {
        Card {
            Toggle(tx(R.string.home_swipes), tx(R.string.home_swipes_sub), { p.zoneOn }, {
                p.zoneOn = it
                on = it
                if (it && !access) openAccess()
            })
            if (on && !access) Line(tx(R.string.a11y_off_x), tx(R.string.tap_to_turn_on_needed)) { openAccess() }
        }
        Card(tx(R.string.gestures)) {
            Choice(tx(R.string.down_left), Prefs.actionNames, { p.gestureDownLeft }, { p.gestureDownLeft = it })
            Choice(tx(R.string.down_right), Prefs.actionNames, { p.gestureDownRight }, { p.gestureDownRight = it })
            Choice(tx(R.string.up), Prefs.actionNames, { p.gestureUp }, { p.gestureUp = it })
            Choice(tx(R.string.double_bottom), Prefs.actionNames, { p.gestureDoubleTap }, { p.gestureDoubleTap = it })
        }
        Card(tx(R.string.areas)) {
            Number(tx(R.string.side_width), 0..200, { p.zoneSide }, { p.zoneSide = it }) { if (it == 0) tx(R.string.off) else "$it dp" }
            Number(tx(R.string.bottom_height), 0..480, { p.zoneBottom }, { p.zoneBottom = it }) { if (it == 0) tx(R.string.off) else "$it dp" }
            Number(tx(R.string.pause_after_hold), 1..30, { p.zonePause }, { p.zonePause = it }) { tx(R.string.x_s, it) }
            Toggle(tx(R.string.show_areas), tx(R.string.show_areas_sub), { p.zoneVisible }, { p.zoneVisible = it })
        }
        Note(tx(R.string.areas_note))
        Card(tx(R.string.panel_path)) {
            Choice(
                tx(R.string.way), mapOf("auto" to tx(R.string.automatic), "a11y" to tx(R.string.a11y_service)), { p.panelPath },
                { p.panelPath = it },
            )
        }
        Note(tx(R.string.panel_note))
        Card { NavLine(Icons.Outlined.Timer, tx(R.string.tuning), tx(R.string.tuning_sub)) { go("tuning") } }
    }
}

@Composable
private fun EdgeScreen(tick: Int, go: (String) -> Unit) {
    val p = LocalContext.current.app.prefs
    val on = dev.yesil.launchsoul.system.SoulAccessibility.instance != null
    Page(tx(R.string.edge_gestures)) {
        if (!on) Card { Line(tx(R.string.a11y_service_off), tx(R.string.edge_needs_a11y)) { go("permissions") } }
        Card {
            Toggle(tx(R.string.on), tx(R.string.edge_on_sub), { p.edgeEnabled }, { p.edgeEnabled = it })
            Toggle(tx(R.string.left_edge), "", { p.edgeLeft }, { p.edgeLeft = it })
            Toggle(tx(R.string.right_edge), "", { p.edgeRight }, { p.edgeRight = it })
            Choice(tx(R.string.edge_down), Prefs.actionNames, { p.edgeDown }, { p.edgeDown = it })
            Choice(tx(R.string.edge_up), Prefs.actionNames, { p.edgeUp }, { p.edgeUp = it })
        }
        Card(tx(R.string.handle)) {
            Toggle(tx(R.string.show_line), "", { p.edgeVisible }, { p.edgeVisible = it })
            Number(tx(R.string.width), 8..60, { p.edgeWidth }, { p.edgeWidth = it }) { "$it dp" }
            Number(tx(R.string.length), 80..1000, { p.edgeHeight }, { p.edgeHeight = it }) { if (it >= 900) tx(R.string.full_screen) else "$it dp" }
            Number(tx(R.string.position), 0..100, { p.edgePosition }, { p.edgePosition = it }) { tx(R.string.percent_x, it) }
        }
        Note(tx(R.string.edge_note))
    }
}
