package dev.yesil.launchsoul.notif

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dev.yesil.launchsoul.App
import dev.yesil.launchsoul.core.fold

class Entry(
    val id: Long, val pkg: String, val title: String, val text: String, val time: Long,
    /** 0 still there, otherwise the NotificationListenerService.REASON_* it went away with. */
    val reason: Int, val removedAt: Long,
)

/** How a notification ended, as far as Android tells us. */
enum class Ending(val label: String) {
    OPEN("Duruyor"), SEEN("Açıldı ya da silindi"), APP("Uygulama kaldırdı"), MISSED("Kaçırılmış olabilir")
}

fun Entry.ending(): Ending = when (reason) {
    0 -> Ending.OPEN
    1, 2, 3 -> Ending.SEEN // click, cancel, cancel all
    8, 9, 12 -> Ending.APP // app cancel, app cancel all, group summary
    else -> Ending.MISSED // timeout, package changed, snoozed, channel banned...
}

class Rule(
    val id: Long, val pkg: String, val contains: String, val action: String, val arg: String,
    val dryRun: Boolean, val on: Boolean,
)

object RuleAction {
    const val DISMISS = "dismiss"
    const val SNOOZE = "snooze"
    const val SPEAK = "speak"
    const val VIBRATE = "vibrate"
    const val OPEN_LATER = "remind"

    val names = linkedMapOf(
        DISMISS to "Bildirimi sil",
        SNOOZE to "Ertele (dakika)",
        SPEAK to "Sesli oku",
        VIBRATE to "Uzun titret",
        OPEN_LATER to "Sonra yeniden hatırlat (dakika)",
    )
}

class History(private val app: App) : SQLiteOpenHelper(app, "notifications.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE n(id INTEGER PRIMARY KEY AUTOINCREMENT, nkey TEXT NOT NULL, pkg TEXT NOT NULL, title TEXT NOT NULL," +
                "body TEXT NOT NULL, folded TEXT NOT NULL, time INTEGER NOT NULL, reason INTEGER NOT NULL DEFAULT 0," +
                "removed INTEGER NOT NULL DEFAULT 0)",
        )
        db.execSQL("CREATE INDEX n_time ON n(time)")
        db.execSQL("CREATE INDEX n_key ON n(nkey, reason)")
        db.execSQL("CREATE TABLE gap(id INTEGER PRIMARY KEY AUTOINCREMENT, since INTEGER NOT NULL, until INTEGER NOT NULL)")
        db.execSQL(
            "CREATE TABLE rule(id INTEGER PRIMARY KEY AUTOINCREMENT, pkg TEXT NOT NULL, contains TEXT NOT NULL," +
                "action TEXT NOT NULL, arg TEXT NOT NULL, dry INTEGER NOT NULL, enabled INTEGER NOT NULL)",
        )
        db.execSQL("CREATE TABLE log(id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, msg TEXT NOT NULL)")
        db.execSQL(
            "CREATE TABLE reminder(id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, body TEXT NOT NULL," +
                "fired INTEGER NOT NULL DEFAULT 0)",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {}

    // All functions below block; call them off the main thread.

    /** Adds a row, or leaves the last one when the same notification came again with the same words. */
    fun posted(key: String, pkg: String, title: String, text: String, time: Long) {
        val db = writableDatabase
        db.rawQuery("SELECT title, body FROM n WHERE nkey=? AND reason=0 ORDER BY id DESC LIMIT 1", arrayOf(key)).use {
            if (it.moveToFirst() && it.getString(0) == title && it.getString(1) == text) return
        }
        db.insert("n", null, ContentValues().apply {
            put("nkey", key)
            put("pkg", pkg)
            put("title", title)
            put("body", text)
            put("folded", fold("$title $text"))
            put("time", time)
        })
    }

    fun removed(key: String, reason: Int) {
        writableDatabase.execSQL(
            "UPDATE n SET reason=?, removed=? WHERE nkey=? AND reason=0",
            arrayOf<Any>(if (reason == 0) -1 else reason, System.currentTimeMillis(), key),
        )
    }

    /** After a pause: rows whose notification is no longer there ended while nobody was listening. */
    fun closeMissing(active: Set<String>) {
        val db = writableDatabase
        val open = db.rawQuery("SELECT DISTINCT nkey FROM n WHERE reason=0", null).use { c -> c.map { it.getString(0) } }
        for (k in open) if (k !in active) removed(k, -1)
    }

    fun addGap(since: Long, until: Long) {
        writableDatabase.insert("gap", null, ContentValues().apply {
            put("since", since)
            put("until", until)
        })
    }

    fun gaps(): List<Pair<Long, Long>> =
        readableDatabase.rawQuery("SELECT since, until FROM gap ORDER BY id DESC LIMIT 20", null)
            .use { c -> c.map { it.getLong(0) to it.getLong(1) } }

    fun entries(query: String, pkg: String?, missedOnly: Boolean, limit: Int = 400): List<Entry> {
        val where = mutableListOf<String>()
        val args = mutableListOf<String>()
        for (w in fold(query).split(' ').filter { it.isNotEmpty() }) {
            where += "folded LIKE ? ESCAPE '\\'"
            args += "%" + w.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        }
        if (pkg != null) {
            where += "pkg=?"
            args += pkg
        }
        if (missedOnly) where += "reason NOT IN (0,1,2,3,8,9,12)"
        val sql = "SELECT id,pkg,title,body,time,reason,removed FROM n" +
            (if (where.isEmpty()) "" else where.joinToString(" AND ", " WHERE ")) + " ORDER BY time DESC LIMIT $limit"
        return readableDatabase.rawQuery(sql, args.toTypedArray()).use { c ->
            c.map {
                Entry(
                    it.getLong(0), it.getString(1), it.getString(2), it.getString(3), it.getLong(4), it.getInt(5),
                    it.getLong(6),
                )
            }
        }
    }

    fun packages(): List<Pair<String, Int>> =
        readableDatabase.rawQuery("SELECT pkg, COUNT(*) FROM n GROUP BY pkg ORDER BY 2 DESC", null)
            .use { c -> c.map { it.getString(0) to it.getInt(1) } }

    fun count(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM n", null).use { it.moveToFirst(); it.getInt(0) }

    fun delete(id: Long) {
        writableDatabase.delete("n", "id=?", arrayOf("$id"))
    }

    fun deleteAll() {
        writableDatabase.delete("n", null, null)
        writableDatabase.delete("gap", null, null)
        writableDatabase.execSQL("VACUUM")
    }

    fun prune(days: Int) {
        val limit = System.currentTimeMillis() - days * 86_400_000L
        writableDatabase.delete("n", "time<?", arrayOf("$limit"))
        writableDatabase.delete("gap", "until<?", arrayOf("$limit"))
        writableDatabase.delete("log", "time<?", arrayOf("${System.currentTimeMillis() - 30 * 86_400_000L}"))
    }

    fun rules(): List<Rule> =
        readableDatabase.rawQuery("SELECT id,pkg,contains,action,arg,dry,enabled FROM rule ORDER BY id", null).use { c ->
            c.map {
                Rule(
                    it.getLong(0), it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getInt(5) != 0,
                    it.getInt(6) != 0,
                )
            }
        }

    fun saveRule(r: Rule) {
        val v = ContentValues().apply {
            put("pkg", r.pkg)
            put("contains", r.contains)
            put("action", r.action)
            put("arg", r.arg)
            put("dry", if (r.dryRun) 1 else 0)
            put("enabled", if (r.on) 1 else 0)
        }
        if (r.id == 0L) writableDatabase.insert("rule", null, v)
        else writableDatabase.update("rule", v, "id=?", arrayOf("${r.id}"))
    }

    fun deleteRule(id: Long) {
        writableDatabase.delete("rule", "id=?", arrayOf("$id"))
    }

    fun log(msg: String) {
        app.db.execute {
            runCatching {
                writableDatabase.insert("log", null, ContentValues().apply {
                    put("time", System.currentTimeMillis())
                    put("msg", msg)
                })
            }
        }
    }

    fun logs(): List<Pair<Long, String>> =
        readableDatabase.rawQuery("SELECT time, msg FROM log ORDER BY id DESC LIMIT 200", null)
            .use { c -> c.map { it.getLong(0) to it.getString(1) } }

    class Reminder(val id: Long, val at: Long, val text: String)

    fun addReminder(at: Long, text: String): Long = writableDatabase.insert("reminder", null, ContentValues().apply {
        put("at", at)
        put("body", text)
    })

    fun reminders(): List<Reminder> =
        readableDatabase.rawQuery("SELECT id, at, body FROM reminder WHERE fired=0 ORDER BY at", null)
            .use { c -> c.map { Reminder(it.getLong(0), it.getLong(1), it.getString(2)) } }

    fun reminder(id: Long): Reminder? =
        readableDatabase.rawQuery("SELECT id, at, body FROM reminder WHERE id=?", arrayOf("$id"))
            .use { c -> c.map { Reminder(it.getLong(0), it.getLong(1), it.getString(2)) }.firstOrNull() }

    fun markFired(id: Long) {
        writableDatabase.execSQL("UPDATE reminder SET fired=1 WHERE id=?", arrayOf<Any>(id))
    }

    private fun <T> Cursor.map(f: (Cursor) -> T): List<T> {
        val out = ArrayList<T>(count)
        while (moveToNext()) out += f(this)
        return out
    }
}
