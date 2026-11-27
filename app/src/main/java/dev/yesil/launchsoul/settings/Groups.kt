package dev.yesil.launchsoul.settings

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AutoMode
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Rule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.ListAlt
import androidx.compose.material.icons.outlined.SwipeVertical
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import dev.yesil.launchsoul.R
import dev.yesil.launchsoul.app
import dev.yesil.launchsoul.core.fold
import dev.yesil.launchsoul.system.Actions
import dev.yesil.launchsoul.system.DebugLog
import dev.yesil.launchsoul.tx

/** A page of the settings: its icon, name, line on what is in it, the screen, and more words a search finds it by. */
private class Dest(val icon: ImageVector, val title: Int, val sub: Int, val screen: String, val words: List<Int> = emptyList())

/** The groups on the first page, and what each holds. */
private val groups = listOf(
    Dest(Icons.Outlined.History, R.string.notification_history, R.string.history_sub, "history", listOf(R.string.history_settings, R.string.keep_for)),
    Dest(Icons.Outlined.TouchApp, R.string.home_gestures, R.string.home_gestures_sub, "gestures",
        listOf(R.string.home_swipes, R.string.down_left, R.string.down_right, R.string.double_bottom, R.string.areas, R.string.panel_path)),
    Dest(Icons.Outlined.SwipeVertical, R.string.edge_gestures, R.string.edge_sub, "edge",
        listOf(R.string.left_edge, R.string.right_edge, R.string.handle, R.string.edge_down, R.string.edge_up)),
    Dest(Icons.Outlined.Search, R.string.act_search, R.string.search_group_sub, "search",
        listOf(R.string.before_typing_title, R.string.most_used, R.string.recently_used, R.string.recent_searches, R.string.recent_people,
            R.string.call_button, R.string.message_button, R.string.phone_settings, R.string.web_buttons)),
    Dest(Icons.Outlined.Notifications, R.string.notifications_group, R.string.notifications_group_sub, "notifs",
        listOf(R.string.notification_history, R.string.blocking, R.string.notification_rules)),
    Dest(Icons.Outlined.AutoMode, R.string.automation, R.string.automation_sub, "auto"),
    Dest(Icons.Outlined.Lock, R.string.permissions, R.string.permissions_sub, "permissions",
        listOf(R.string.accessibility, R.string.notification_access, R.string.dnd_access, R.string.alarms_reminders, R.string.no_battery_limit)),
    Dest(Icons.Outlined.Info, R.string.about_group, R.string.about_group_sub, "about",
        listOf(R.string.share_log, R.string.keep_log, R.string.about_source, R.string.about_license)),
)

/** Pages found inside the groups. */
private val inner = listOf(
    Dest(Icons.Outlined.History, R.string.notification_history, R.string.history_sub, "history", listOf(R.string.history_settings, R.string.keep_for)),
    Dest(Icons.Outlined.Rule, R.string.notification_rules, R.string.rules_sub, "rules", listOf(R.string.rule_snooze, R.string.rule_speak)),
    Dest(Icons.Outlined.Block, R.string.blocking, R.string.blocking_sub, "block", listOf(R.string.add_block)),
    Dest(Icons.Outlined.Bedtime, R.string.dnd_title, R.string.dnd_sub, "dnd", listOf(R.string.home_networks, R.string.only_charging)),
    Dest(Icons.Outlined.Alarm, R.string.reminders, R.string.reminders_sub, "reminders"),
    Dest(Icons.Outlined.Timer, R.string.timer, R.string.timer_sub, "timer"),
    Dest(Icons.Outlined.ListAlt, R.string.log, R.string.log_sub, "log"),
)

/** A row that opens another page: a round icon, the name, a line on what is there, and an arrow. */
@Composable
fun NavLine(icon: ImageVector?, title: String, sub: String = "", onClick: () -> Unit) = Row(
    Modifier.fillMaxWidth().clickable(onClick = onClick).padding(14.dp, 12.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    if (icon != null) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), Alignment.Center) {
            Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.width(14.dp))
    }
    Column(Modifier.weight(1f)) {
        Text(title, fontSize = 16.sp, color = Color.White)
        if (sub.isNotEmpty()) Text(sub, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Rows(list: List<Dest>, go: (String) -> Unit) = Card {
    list.forEachIndexed { i, d ->
        if (i > 0) HorizontalDivider(Modifier.padding(start = 66.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        NavLine(d.icon, tx(d.title), tx(d.sub)) { go(d.screen) }
    }
}

@Composable
fun MainScreen(go: (String) -> Unit) = Page("Better Launch Soul") {
    var q by remember { mutableStateOf("") }
    OutlinedTextField(
        q, { q = it }, Modifier.fillMaxWidth().padding(bottom = 10.dp), label = { Text(tx(R.string.settings_search)) }, singleLine = true,
    )
    val f = fold(q.trim())
    if (f.isEmpty()) {
        Rows(groups, go)
        Note(tx(R.string.test_version_x, LocalContext.current.run { packageManager.getPackageInfo(packageName, 0).versionName }))
    } else {
        val found = (groups + inner).filter { d -> (listOf(d.title, d.sub) + d.words).any { fold(tx(it)).contains(f) } }
        if (found.isEmpty()) Note(tx(R.string.nothing_found)) else Rows(found, go)
    }
}

@Composable
fun NotificationsScreen(go: (String) -> Unit) = Page(tx(R.string.notifications_group)) {
    Rows(inner.filter { it.screen in setOf("history", "block", "rules") }, go)
    Card { NavLine(Icons.Outlined.NotificationsActive, tx(R.string.history_settings), tx(R.string.history_settings_sub)) { go("historyset") } }
}

@Composable
fun AutomationScreen(go: (String) -> Unit) = Page(tx(R.string.automation)) {
    Rows(inner.filter { it.screen in setOf("dnd", "reminders", "timer", "log") }, go)
}

/** Where the code is published. */
private const val SOURCE = "https://github.com/kyesil/launchsoul"

@Composable
private fun Link(label: String, text: String, url: String) {
    val ctx = LocalContext.current
    Row(Modifier.padding(18.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text(
            text, Modifier.clickable { Actions.start(ctx, Intent(Intent.ACTION_VIEW, url.toUri())) }.padding(vertical = 4.dp),
            fontSize = 14.sp, color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline,
        )
    }
}

@Composable
fun AboutScreen() {
    val ctx = LocalContext.current
    Page(tx(R.string.about_group)) {
        Card(tx(R.string.reporting)) {
            Line(tx(R.string.share_log), tx(R.string.share_log_sub), { Icon(Icons.Outlined.BugReport, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }) {
                DebugLog.share(ctx)
            }
            Toggle(tx(R.string.keep_log), tx(R.string.keep_log_sub), { ctx.app.prefs.debugLog }, { ctx.app.prefs.debugLog = it })
            Line(tx(R.string.clear_log)) {
                DebugLog.clear()
                Actions.toast(ctx, tx(R.string.cleared))
            }
        }
        Card(tx(R.string.about_title)) {
            Column(Modifier.padding(vertical = 6.dp)) {
                Text(
                    "Better Launch Soul · " + tx(R.string.about_version, ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""),
                    Modifier.padding(18.dp, 4.dp), fontSize = 15.sp, color = Color.White,
                )
                Link(tx(R.string.about_developer), "yesil.dev", "https://yesil.dev")
                Link(tx(R.string.about_source), "github.com/kyesil/launchsoul", SOURCE)
                Link(tx(R.string.about_license), "GPL-3.0", "$SOURCE/blob/main/LICENSE")
                Text(tx(R.string.about_open_source), Modifier.padding(18.dp, 4.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(tx(R.string.about_feedback), Modifier.padding(18.dp, 4.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Link("GitHub", tx(R.string.about_issue), "$SOURCE/issues/new")
                Text(tx(R.string.about_coauthor, "Claude Opus 5.5"), Modifier.padding(18.dp, 4.dp), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(tx(R.string.about_private), Modifier.padding(18.dp, 4.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
