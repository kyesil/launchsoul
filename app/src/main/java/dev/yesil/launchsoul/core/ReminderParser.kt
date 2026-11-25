package dev.yesil.launchsoul.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

data class ParsedReminder(val at: LocalDateTime, val text: String)

/** Understands "yarın 9 süt al", "20 dk sonra çayı kapat", "cuma 14:30 toplantı", "akşam 8'de ara". */
object ReminderParser {
    private val days = mapOf(
        "pazartesi" to DayOfWeek.MONDAY, "sali" to DayOfWeek.TUESDAY, "carsamba" to DayOfWeek.WEDNESDAY,
        "persembe" to DayOfWeek.THURSDAY, "cuma" to DayOfWeek.FRIDAY, "cumartesi" to DayOfWeek.SATURDAY,
        "pazar" to DayOfWeek.SUNDAY,
    )
    private val clock = Regex("""^(\d{1,2})(?:[:.](\d{2}))?(?:['’]?(?:de|da|te|ta|ye|ya|e|a))?$""")
    private val amount = Regex("""^\d{1,3}$""")

    fun parse(input: String, now: LocalDateTime): ParsedReminder? {
        val words = input.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.size < 2) return null
        val f = words.map { fold(it) }
        val used = BooleanArray(words.size)

        // "20 dk sonra"
        for (i in 0 until words.size - 2) {
            if (f[i + 2] != "sonra") continue
            val n = if (f[i] == "yarim") null else f[i].takeIf { amount.matches(it) }?.toLong() ?: continue
            val unit = f[i + 1]
            val at = when {
                n == null -> if (unit == "saat") now.plusMinutes(30) else continue
                unit in setOf("dk", "dakika") -> now.plusMinutes(n)
                unit in setOf("sa", "saat") -> now.plusHours(n)
                unit == "gun" -> now.plusDays(n)
                else -> continue
            }
            for (k in i..i + 2) used[k] = true
            return finish(words, used, at)
        }

        var date: LocalDate? = null
        var time: LocalTime? = null
        var part: String? = null
        for (i in words.indices) {
            val w = f[i]
            when {
                w == "bugun" -> date = now.toLocalDate()
                w == "yarin" -> date = now.toLocalDate().plusDays(1)
                w in days && date == null -> {
                    var d = now.toLocalDate().plusDays(1)
                    while (d.dayOfWeek != days[w]) d = d.plusDays(1)
                    date = d
                }
                w in setOf("sabah", "ogle", "ogleden", "aksam", "gece") && part == null -> part = w
                w == "saat" -> {}
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
        val explicit = words.indices.any { used[it] && (f[it] == "saat" || f[it].contains(':') || f[it].contains('\'') || f[it].contains('’')) }
        if (date == null && part == null && !explicit) return null
        if (time == null) {
            time = when (part) {
                "sabah" -> LocalTime.of(9, 0)
                "ogle", "ogleden" -> LocalTime.of(13, 0)
                "aksam" -> LocalTime.of(19, 0)
                "gece" -> LocalTime.of(22, 0)
                else -> if (date != null) LocalTime.of(9, 0) else return null
            }
        } else if (time.hour in 1..11) {
            if (part == "aksam" || part == "ogleden" || (part == "ogle" && time.hour < 6)) time = time.plusHours(12)
            else if (part == "gece" && time.hour >= 8) time = time.plusHours(12)
        }
        var at = LocalDateTime.of(date ?: now.toLocalDate(), time)
        if (!at.isAfter(now)) at = when {
            date == null -> {
                // "8'de" said at 10 means 20:00 when that is still ahead, otherwise tomorrow.
                val pm = at.plusHours(12)
                if (part == null && time.hour in 1..11 && pm.isAfter(now)) pm else at.plusDays(1)
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
