package dev.yesil.launchsoul.widget

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.yesil.launchsoul.R
import dev.yesil.launchsoul.app
import dev.yesil.launchsoul.auto.clock
import dev.yesil.launchsoul.core.fold
import dev.yesil.launchsoul.notif.Targets
import dev.yesil.launchsoul.search.SearchActivity
import dev.yesil.launchsoul.settings.SettingsActivity
import dev.yesil.launchsoul.tx
import dev.yesil.launchsoul.ui.AppIcon
import dev.yesil.launchsoul.ui.SoulTheme

private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

/** Asks every widget of [kind] to read its rows again. */
fun refreshWidgets(ctx: Context, kind: Class<*>) {
    val m = AppWidgetManager.getInstance(ctx)
    val ids = m.getAppWidgetIds(ComponentName(ctx, kind))
    if (ids.isNotEmpty()) m.notifyAppWidgetViewDataChanged(ids, R.id.widget_list)
}

/** A search bar that opens the search. */
class SearchWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, m: AppWidgetManager, ids: IntArray) {
        val open = PendingIntent.getActivity(ctx, 1, Intent(ctx, SearchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), FLAGS)
        for (id in ids) {
            val v = RemoteViews(ctx.packageName, R.layout.widget_search)
            v.setTextViewText(R.id.widget_hint, tx(R.string.search_hint))
            v.setOnClickPendingIntent(R.id.widget_root, open)
            m.updateAppWidget(id, v)
        }
    }
}

/** The apps opened last from the search; it can be made larger. */
class AppsWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, m: AppWidgetManager, ids: IntArray) {
        for (id in ids) {
            val v = RemoteViews(ctx.packageName, R.layout.widget_apps)
            v.setTextViewText(R.id.widget_title, tx(R.string.widget_apps_name))
            v.setRemoteAdapter(R.id.widget_list, Intent(ctx, AppsService::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .setData(Uri.parse("launchsoul://apps/$id")))
            v.setEmptyView(R.id.widget_list, R.id.widget_empty)
            v.setTextViewText(R.id.widget_empty, tx(R.string.widget_apps_empty))
            v.setPendingIntentTemplate(R.id.widget_list, PendingIntent.getActivity(
                ctx, 2, Intent(ctx, TapActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            ))
            m.updateAppWidget(id, v)
        }
        m.notifyAppWidgetViewDataChanged(ids, R.id.widget_list)
    }
}

class AppsService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = object : RemoteViewsFactory {
        private var keys = emptyList<String>()
        override fun onCreate() {}
        override fun onDataSetChanged() {
            keys = app.apps.lastPicked("").map { it.first }.filter { !it.startsWith("contact:") && app.apps[it] != null }.take(30)
        }
        override fun onDestroy() {}
        override fun getCount() = keys.size
        override fun getViewAt(i: Int): RemoteViews {
            val k = keys.getOrNull(i) ?: return loadingView
            val v = RemoteViews(packageName, R.layout.widget_app)
            app.icons.now(k)?.let { v.setImageViewBitmap(R.id.widget_icon, it) }
            v.setTextViewText(R.id.widget_label, app.apps[k]?.label ?: "")
            v.setOnClickFillInIntent(R.id.widget_cell, Intent().putExtra("app", k))
            return v
        }
        override fun getLoadingView(): RemoteViews = RemoteViews(packageName, R.layout.widget_app)
        override fun getViewTypeCount() = 1
        override fun getItemId(i: Int) = i.toLong()
        override fun hasStableIds() = false
    }
}

/** The notification history; which apps and words it shows is chosen per widget. */
class NotesWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, m: AppWidgetManager, ids: IntArray) {
        for (id in ids) update(ctx, m, id)
        m.notifyAppWidgetViewDataChanged(ids, R.id.widget_list)
    }

    override fun onDeleted(ctx: Context, ids: IntArray) {
        val e = ctx.app.prefs.sp.edit()
        for (id in ids) e.remove("widget_apps_$id").remove("widget_words_$id")
        e.apply()
    }

    companion object {
        fun update(ctx: Context, m: AppWidgetManager, id: Int) {
            val v = RemoteViews(ctx.packageName, R.layout.widget_notes)
            v.setTextViewText(R.id.widget_title, tx(R.string.notification_history))
            v.setOnClickPendingIntent(R.id.widget_title, PendingIntent.getActivity(
                ctx, 3, Intent(ctx, SettingsActivity::class.java).putExtra("screen", "history").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), FLAGS,
            ))
            v.setOnClickPendingIntent(R.id.widget_settings, PendingIntent.getActivity(
                ctx, 100_000 + id, Intent(ctx, NotesConfig::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), FLAGS,
            ))
            v.setRemoteAdapter(R.id.widget_list, Intent(ctx, NotesService::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .setData(Uri.parse("launchsoul://notes/$id")))
            v.setEmptyView(R.id.widget_list, R.id.widget_empty)
            v.setTextViewText(R.id.widget_empty, tx(if (ctx.app.prefs.historyOn) R.string.no_entries else R.string.history_off))
            v.setPendingIntentTemplate(R.id.widget_list, PendingIntent.getActivity(
                ctx, 4, Intent(ctx, TapActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            ))
            m.updateAppWidget(id, v)
        }
    }
}

class NotesService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = object : RemoteViewsFactory {
        private val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0)
        private var rows = emptyList<dev.yesil.launchsoul.notif.Entry>()
        override fun onCreate() {}
        override fun onDataSetChanged() {
            val p = app.prefs.sp
            val apps = p.getStringSet("widget_apps_$id", emptySet())!!
            val words = fold(p.getString("widget_words_$id", "") ?: "").split(' ').filter { it.isNotBlank() }
            rows = runCatching { app.history.entries("", null, false, 300) }.getOrDefault(emptyList()).filter { e ->
                (apps.isEmpty() || e.pkg in apps) && (words.isEmpty() || fold(e.title + " " + e.text).let { t -> words.any { it in t } })
            }.take(60)
        }
        override fun onDestroy() {}
        override fun getCount() = rows.size
        override fun getViewAt(i: Int): RemoteViews {
            val e = rows.getOrNull(i) ?: return loadingView
            val v = RemoteViews(packageName, R.layout.widget_note)
            app.apps.keyForPackage(e.pkg)?.let { k -> app.icons.now(k)?.let { v.setImageViewBitmap(R.id.widget_icon, it) } }
            v.setTextViewText(R.id.widget_note_title, e.title.ifEmpty { app.apps.labelOf(e.pkg) })
            v.setTextViewText(R.id.widget_note_text, e.text)
            v.setTextViewText(R.id.widget_note_time, clock(e.time))
            v.setOnClickFillInIntent(R.id.widget_cell, Intent().putExtra("note", e.key).putExtra("pkg", e.pkg))
            return v
        }
        override fun getLoadingView(): RemoteViews = RemoteViews(packageName, R.layout.widget_note)
        override fun getViewTypeCount() = 1
        override fun getItemId(i: Int) = rows.getOrNull(i)?.id ?: i.toLong()
        override fun hasStableIds() = true
    }
}

/** Opens what a widget row stands for, then goes away. */
class TapActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val a = app
        intent.getStringExtra("app")?.let { a.apps.launch(it) }
        intent.getStringExtra("note")?.let { key ->
            // The notification's own target while it is known, else the app.
            if (!Targets.open(this, key)) intent.getStringExtra("pkg")?.let { p -> a.apps.keyForPackage(p)?.let { a.apps.launch(it) } }
        }
        finish()
    }
}

/** Which apps and words a notification widget shows. */
class NotesConfig : ComponentActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return finish()
        val a = app
        val sp = a.prefs.sp
        enableEdgeToEdge()
        setContent {
            SoulTheme {
                var chosen by remember { mutableStateOf(sp.getStringSet("widget_apps_$id", emptySet())!!.toSet()) }
                var words by remember { mutableStateOf(sp.getString("widget_words_$id", "") ?: "") }
                val pkgs = remember { a.history.packages().map { it.first } }
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().padding(16.dp)) {
                    Text(tx(R.string.widget_notes_config), Modifier.padding(4.dp, 12.dp), fontSize = 24.sp, color = Color.White)
                    OutlinedTextField(words, { words = it }, Modifier.fillMaxWidth(), label = { Text(tx(R.string.widget_words)) }, singleLine = true)
                    Text(tx(if (chosen.isEmpty()) R.string.widget_all_apps else R.string.widget_some_apps), Modifier.padding(4.dp, 10.dp),
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LazyColumn(Modifier.weight(1f)) {
                        items(pkgs) { p ->
                            Row(
                                Modifier.fillMaxWidth().clickable { chosen = if (p in chosen) chosen - p else chosen + p }.padding(4.dp, 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(p in chosen, { chosen = if (it) chosen + p else chosen - p })
                                a.apps.keyForPackage(p)?.let { AppIcon(it, 32.dp) } ?: Box(Modifier.width(32.dp))
                                Spacer(Modifier.width(12.dp))
                                Text(a.apps.labelOf(p), color = Color.White)
                            }
                        }
                    }
                    Button({
                        sp.edit().putStringSet("widget_apps_$id", chosen).putString("widget_words_$id", words.trim()).apply()
                        val m = AppWidgetManager.getInstance(this@NotesConfig)
                        NotesWidget.update(this@NotesConfig, m, id)
                        m.notifyAppWidgetViewDataChanged(intArrayOf(id), R.id.widget_list)
                        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                        finish()
                    }, Modifier.fillMaxWidth().padding(top = 8.dp)) { Text(tx(R.string.save)) }
                }
            }
        }
    }
}
