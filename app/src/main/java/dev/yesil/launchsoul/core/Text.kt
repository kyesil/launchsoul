package dev.yesil.launchsoul.core

import java.text.Normalizer
import kotlin.math.abs

/** Lower case without Turkish or other accents, so "Işık", "ISIK" and "isik" are equal. */
fun fold(s: String): String {
    val sb = StringBuilder(s.length)
    for (c in Normalizer.normalize(s, Normalizer.Form.NFD)) {
        if (Character.getType(c) == Character.NON_SPACING_MARK.toInt()) continue
        when (c) {
            'İ', 'I', 'ı' -> sb.append('i')
            else -> sb.append(c.lowercaseChar())
        }
    }
    return sb.toString()
}

/** The parts of [label] that made it match [query] (folded), as index ranges of the unfolded text. */
fun matchRanges(query: String, label: String): List<IntRange> {
    if (query.isEmpty() || label.isEmpty()) return emptyList()
    // The folded text with, for each of its letters, the place it came from.
    val sb = StringBuilder()
    val at = ArrayList<Int>()
    label.forEachIndexed { i, c ->
        for (x in fold(c.toString())) {
            sb.append(x)
            at += i
        }
    }
    val t = sb.toString()
    fun range(from: Int, n: Int) = at[from]..at[from + n - 1]
    val sep = " -._&"
    val starts = t.indices.filter { t[it] !in sep && (it == 0 || t[it - 1] in sep) }
    starts.firstOrNull { t.startsWith(query, it) }?.let { return listOf(range(it, query.length)) }
    if (starts.size > 1 && starts.map { t[it] }.joinToString("").startsWith(query)) {
        return starts.take(query.length).map { range(it, 1) }
    }
    t.indexOf(query).takeIf { it >= 0 }?.let { return listOf(range(it, query.length)) }
    for (s in starts) {
        val end = (s until t.length).firstOrNull { t[it] in sep } ?: t.length
        for (n in listOf(query.length, query.length - 1, query.length + 1)) {
            if (n in 1..end - s && withinOneEdit(query, t.substring(s, s + n))) return listOf(range(s, n))
        }
    }
    // Several words in any order.
    val words = query.split(' ').filter { it.isNotEmpty() }
    return if (words.size < 2) emptyList() else words.flatMap { matchRanges(it, label) }
}

/** The digits of [query] in a phone number, over spaces and signs. */
fun numberRange(query: String, number: String): IntRange? {
    val q = query.filter { it.isDigit() }
    if (q.length < 2 || query.any { it.isLetter() }) return null
    val at = number.indices.filter { number[it].isDigit() }
    val d = at.map { number[it] }.joinToString("")
    val i = d.indexOf(q).takeIf { it >= 0 } ?: d.indexOf(q.trimStart('0')).takeIf { it >= 0 && q.trimStart('0').length >= 2 } ?: return null
    val n = if (d.startsWith(q, i)) q.length else q.trimStart('0').length
    return at[i]..at[i + n - 1]
}

/** How well [query] (folded) matches [label] (folded); 0 is no match. */
fun matchScore(query: String, label: String): Int {
    if (query.isEmpty()) return 0
    if (label == query) return 100
    if (label.startsWith(query)) return 90
    val words = label.split(' ', '-', '.', '_', '&').filter { it.isNotEmpty() }
    if (words.any { it.startsWith(query) }) return 80
    if (words.size > 1 && words.joinToString("") { it.take(1) }.startsWith(query)) return 70
    if (query.length >= 2 && label.contains(query)) return 60
    // One wrong, missing or extra letter at the start of a word.
    if (query.length >= 4 && words.any { w ->
            (query.length - 1..query.length + 1).any { n -> n in 1..w.length && withinOneEdit(query, w.take(n)) }
        }
    ) return 40
    return 0
}

fun withinOneEdit(a: String, b: String): Boolean {
    if (abs(a.length - b.length) > 1) return false
    var i = 0
    while (i < a.length && i < b.length && a[i] == b[i]) i++
    if (i == a.length && i == b.length) return true
    if (a.length == b.length) {
        if (a.substring(i + 1) == b.substring(i + 1)) return true
        return i + 1 < a.length && a[i] == b[i + 1] && a[i + 1] == b[i] && a.substring(i + 2) == b.substring(i + 2)
    }
    return if (a.length > b.length) a.substring(i + 1) == b.substring(i) else a.substring(i) == b.substring(i + 1)
}

/** Evaluates "12,5*4+%..." style input; null when it is not a calculation. */
fun calculate(input: String): Double? {
    val s = input.replace(" ", "").replace(',', '.').replace('×', '*').replace('x', '*').replace('÷', '/').trimEnd('=')
    if (s.length < 3 || s.none { it.isDigit() } || s.drop(1).none { it in "+-*/^%" }) return null
    if (s.any { !it.isDigit() && it !in "+-*/^%.()" }) return null
    var p = 0
    fun expr(): Double {
        fun factor(): Double {
            if (p >= s.length) throw NumberFormatException()
            if (s[p] == '-') {
                p++
                return -factor()
            }
            if (s[p] == '+') {
                p++
                return factor()
            }
            var v: Double
            if (s[p] == '(') {
                p++
                v = expr()
                if (p >= s.length || s[p] != ')') throw NumberFormatException()
                p++
            } else {
                val st = p
                while (p < s.length && (s[p].isDigit() || s[p] == '.')) p++
                v = s.substring(st, p).toDouble()
            }
            if (p < s.length && s[p] == '%') {
                p++
                v /= 100
            }
            if (p < s.length && s[p] == '^') {
                p++
                v = Math.pow(v, factor())
            }
            return v
        }
        fun term(): Double {
            var v = factor()
            while (p < s.length && s[p] in "*/") v = if (s[p++] == '*') v * factor() else v / factor()
            return v
        }
        var v = term()
        while (p < s.length && s[p] in "+-") v = if (s[p++] == '+') v + term() else v - term()
        return v
    }
    return try {
        val v = expr()
        if (p == s.length && v.isFinite()) v else null
    } catch (_: Exception) {
        null
    }
}
