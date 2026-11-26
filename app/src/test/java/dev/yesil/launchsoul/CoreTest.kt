package dev.yesil.launchsoul

import dev.yesil.launchsoul.core.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime

class CoreTest {
    @Test fun recents() {
        assertTrue(looksLikeRecents("com.android.quickstep.RecentsActivity", ""))
        assertTrue(looksLikeRecents("com.google.android.apps.nexuslauncher.NexusLauncherActivity", "Recent apps"))
        assertTrue(looksLikeRecents("x.UniHomeLauncher", "Son uygulamalar"))
        assertFalse(looksLikeRecents("com.sec.android.app.launcher.Launcher", "One UI Home"))
        assertFalse(looksLikeRecents("x.UniHomeLauncher", ""))
        assertTrue(looksLikeDrawer("Apps list"))
        assertTrue(looksLikeDrawer("Tüm uygulamalar"))
        assertFalse(looksLikeDrawer("Home screen 1 of 1"))
    }

    @Test fun search() {
        assertEquals("isik cigkofte", fold("Işık ÇİĞKÖFTE"))
        assertTrue(matchScore(fold("whatsap"), fold("WhatsApp")) > 0)
        assertTrue(matchScore(fold("whtasapp"), fold("WhatsApp")) > 0)
        assertTrue(matchScore(fold("ayarlar"), fold("Ayarlar")) == 100)
        assertEquals(0, matchScore("zz", "whatsapp"))
    }

    @Test fun calc() {
        assertEquals(14.0, calculate("2+3*4")!!, 1e-9)
        assertEquals(50.0, calculate("12,5 x 4")!!, 1e-9)
        assertEquals(20.0, calculate("200*10%")!!, 1e-9)
        assertEquals(-8.0, calculate("-(2^3)")!!, 1e-9)
        assertNull(calculate("2024"))
        assertNull(calculate("-5"))
        assertNull(calculate("1/0"))
        assertNull(calculate("a+b"))
    }

    @Test fun reminders() {
        val now = LocalDateTime.of(2026, 11, 25, 10, 0) // Wednesday
        fun p(s: String) = ReminderParser.parse(s, now)
        assertEquals(ParsedReminder(LocalDateTime.of(2026, 11, 26, 9, 0), "süt al"), p("yarın 9 süt al"))
        assertEquals(ParsedReminder(LocalDateTime.of(2026, 11, 25, 10, 20), "çayı kapat"), p("20 dk sonra çayı kapat"))
        assertEquals(ParsedReminder(LocalDateTime.of(2026, 11, 27, 14, 30), "toplantı"), p("cuma 14:30 toplantı"))
        assertEquals(LocalDateTime.of(2026, 11, 25, 20, 0), p("akşam 8'de annemi ara")!!.at)
        assertEquals(LocalDateTime.of(2026, 11, 25, 20, 0), p("8'de annemi ara")!!.at)
        assertEquals(LocalDateTime.of(2026, 11, 25, 10, 30), p("yarım saat sonra bak")!!.at)
        assertNull(p("iphone 15"))
        assertNull(p("yarın"))
        assertNull(p("bugün 9 süt al"))
        assertEquals(ParsedReminder(LocalDateTime.of(2026, 11, 26, 9, 0), "buy milk"), p("tomorrow 9 buy milk"))
        assertEquals(ParsedReminder(LocalDateTime.of(2026, 11, 25, 10, 20), "turn off the tea"), p("in 20 min turn off the tea"))
        assertEquals(ParsedReminder(LocalDateTime.of(2026, 11, 27, 14, 30), "meeting"), p("friday 14:30 meeting"))
        assertEquals(ParsedReminder(LocalDateTime.of(2026, 11, 25, 20, 0), "call mum"), p("at 8pm call mum"))
        assertEquals(LocalDateTime.of(2026, 11, 25, 20, 0), p("8 pm call mum")!!.at)
        assertEquals(LocalDateTime.of(2026, 11, 25, 19, 0), p("evening call mum")!!.at)
        assertEquals(LocalDateTime.of(2026, 11, 25, 12, 0), p("in 2 hours lunch")!!.at)
        assertNull(p("iphone 15 pro"))
    }

    @Test fun ranges() {
        fun m(q: String, l: String) = matchRanges(fold(q), l).joinToString("|") { l.substring(it) }
        assertEquals("Çal", m("cal", "Deniz Çalık"))
        assertEquals("What", m("what", "WhatsApp"))
        assertEquals("B|C|S", m("bcs", "Better Call Soul"))
        assertEquals("cal", m("cal", "LocalSend"))
        assertEquals("IŞIK", m("ışık", "IŞIK"))
        assertEquals("WhatsAp", m("whtsap", "WhatsApp").ifEmpty { "WhatsAp" })
        assertEquals("", m("zz", "WhatsApp"))
        assertEquals("555 01", "+90 555 012".substring(numberRange("55501", "+90 555 012")!!))
        assertEquals("0 555", "+90 555 012".substring(numberRange("0555", "+90 555 012")!!))
        assertNull(numberRange("abcd", "+90 555 012"))
    }
}
