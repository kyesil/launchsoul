package dev.yesil.launchsoul.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

data class ParsedReminder(val at: LocalDateTime, val text: String)

/**
 * Understands "yarın 9 süt al", "20 dk sonra çayı kapat", "cuma 14:30 toplantı", "akşam 8'de ara" and in English
 * "tomorrow 9 buy milk", "in 20 min turn off the tea", "friday 14:30 meeting", "at 8pm call mum".
 */
object ReminderParser {
    private val days = mapOf(
        "pazartesi" to DayOfWeek.MONDAY, "sali" to DayOfWeek.TUESDAY, "carsamba" to DayOfWeek.WEDNESDAY,
        "persembe" to DayOfWeek.THURSDAY, "cuma" to DayOfWeek.FRIDAY, "cumartesi" to DayOfWeek.SATURDAY,
        "pazar" to DayOfWeek.SUNDAY,
        "monday" to DayOfWeek.MONDAY, "tuesday" to DayOfWeek.TUESDAY, "wednesday" to DayOfWeek.WEDNESDAY,
        "thursday" to DayOfWeek.THURSDAY, "friday" to DayOfWeek.FRIDAY, "saturday" to DayOfWeek.SATURDAY,
        "sunday" to DayOfWeek.SUNDAY,
    )

    /** Parts of the day, folded, to the part they stand for. */
    private val parts = mapOf(
        "sabah" to "sabah", "ogle" to "ogle", "ogleden" to "ogleden", "aksam" to "aksam", "gece" to "gece",
        "morning" to "sabah", "noon" to "ogle", "afternoon" to "ogleden", "evening" to "aksam", "tonight" to "gece", "night" to "gece",
    )
    private val minutes = setOf("dk", "dakika", "min", "mins", "minute", "minutes")
    private val hours = setOf("sa", "saat", "h", "hr", "hrs", "hour", "hours")
    private val days2 = setOf("gun", "day", "days")
    private val ampm = Regex("""^(\d{1,2})(?::(\d{2}))?(am|pm)$""")
    private val clock = Regex("""^(\d{1,2})(?:[:.](\d{2}))?(?:['’]?(?:de|da|te|ta|ye|ya|e|a))?$""")
    private val amount = Regex("""^\d{1,3}$""")

    fun parse(input: String, now: LocalDateTime): ParsedReminder? {
        val words = input.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.size < 2) return null
        val f = words.map { fold(it) }
        val used = BooleanArray(words.size)

        // "in 20 min"
        for (i in 0 until words.size - 2) {
            if (f[i] != "in") continue
            val n = f[i + 1].takeIf { amount.matches(it) }?.toLong() ?: continue
            val at = when (f[i + 2]) {
                in minutes -> now.plusMinutes(n)
                in hours -> now.plusHours(n)
                in days2 -> now.plusDays(n)
                else -> continue
            }
            for (k in i..i + 2) used[k] = true
            return finish(words, used, at)
        }

        // "20 dk sonra"
        for (i in 0 until words.size - 2) {
            if (f[i + 2] != "sonra") continue
            val n = if (f[i] == "yarim") null else f[i].takeIf { amount.matches(it) }?.toLong() ?: continue
            val unit = f[i + 1]
            val at = when {
                n == null -> if (unit == "saat") now.plusMinutes(30) else continue
                unit in minutes -> now.plusMinutes(n)
                unit in hours -> now.plusHours(n)
                unit in days2 -> now.plusDays(n)
                else -> continue
            }
            for (k in i..i + 2) used[k] = true
            return finish(words, used, at)
        }

        var date: LocalDate? = null
        var time: LocalTime? = null
        var part: String? = null
        var pm: Boolean? = null
        for (i in words.indices) {
            val w = f[i]
            when {
                w == "bugun" || w == "today" -> date = now.toLocalDate()
                w == "yarin" || w == "tomorrow" -> date = now.toLocalDate().plusDays(1)
                w in days && date == null -> {
                    var d = now.toLocalDate().plusDays(1)
                    while (d.dayOfWeek != days[w]) d = d.plusDays(1)
                    date = d
                }
                w in parts && part == null -> part = parts[w]
                w == "saat" || w == "at" -> {}
                (w == "am" || w == "pm") && time != null && i > 0 && used[i - 1] -> pm = w == "pm"
                time == null && ampm.matches(w) -> {
                    val m = ampm.find(w)!!
                    val h = m.groupValues[1].toInt()
                    val min = m.groupValues[2].ifEmpty { "0" }.toInt()
                    if (h !in 1..12 || min > 59) return null
                    time = LocalTime.of(h % 12, min)
                    pm = m.groupValues[3] == "pm"
                }
                time == null && clock.matches(w) -> {
                    val m = clock.find(w)!!
                    val h = m.groupValues[1].toInt()
                    val min = m.groupValues[2].ifEmpty { "0" }.toInt()
                    if (h > 23 || min > 59) return null
                    time = LocalTime.of(h, min)
                }
                else -> continue
            }
            used[i] = true
        }
        // A bare number is too weak a sign ("iphone 15"): it needs a day, a part of the day or a minute.
        val explicit = pm != null || words.indices.any {
            used[it] && (f[it] == "saat" || f[it] == "at" || f[it].contains(':') || f[it].contains('\'') || f[it].contains('’'))
        }
        if (date == null && part == null && !explicit) return null
        if (time == null) {
            time = when (part) {
                "sabah" -> LocalTime.of(9, 0)
                "ogle", "ogleden" -> LocalTime.of(13, 0)
                "aksam" -> LocalTime.of(19, 0)
                "gece" -> LocalTime.of(22, 0)
                else -> if (date != null) LocalTime.of(9, 0) else return null
            }
        } else if (pm != null) {
            time = LocalTime.of(time.hour % 12 + if (pm == true) 12 else 0, time.minute)
        } else if (time.hour in 1..11) {
            if (part == "aksam" || part == "ogleden" || (part == "ogle" && time.hour < 6)) time = time.plusHours(12)
            else if (part == "gece" && time.hour >= 8) time = time.plusHours(12)
        }
        var at = LocalDateTime.of(date ?: now.toLocalDate(), time)
        if (!at.isAfter(now)) at = when {
            date == null -> {
                // "8'de" said at 10 means 20:00 when that is still ahead, otherwise tomorrow.
                val later = at.plusHours(12)
                if (part == null && pm == null && time.hour in 1..11 && later.isAfter(now)) later else at.plusDays(1)
            }
            else -> return null
        }
        return finish(words, used, at)
    }

    private fun finish(words: List<String>, used: BooleanArray, at: LocalDateTime): ParsedReminder? {
        val text = words.filterIndexed { i, _ -> !used[i] }.joinToString(" ").trim()
        if (text.isEmpty()) return null
        return ParsedReminder(at.withSecond(if (used.size > 2 && at.second != 0) at.second else 0).withNano(0), text)
    }
}
