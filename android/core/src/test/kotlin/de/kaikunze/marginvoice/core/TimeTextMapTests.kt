package de.kaikunze.marginvoice.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class TimeTextMapTests {
    @Test fun matchesAllEnglishChapters() {
        val text = Fixture.text("icra")
        val ch = Fixture.chapters("icra")
        val matches = ChapterMatcher.match(ch.chapters, text)
        assertEquals(ch.chapters.size, matches.size)
        val intro = ch.chapters.indexOfFirst { it.title == "Introduction" }
        val m = matches.first { it.chapterIndex == intro }
        assertEquals("Section: Introduction.", text.substring(m.textOffset, m.textOffset + 22))
    }

    @Test fun matchesGermanChapters() {
        val text = Fixture.text("hace_de")
        val ch = Fixture.chapters("hace_de")
        val matches = ChapterMatcher.match(ch.chapters, text)
        assertTrue(matches.size >= ch.chapters.size - 1)
    }

    @Test fun mapIsMonotonicAndInvertible() {
        val text = Fixture.text("icra")
        val ch = Fixture.chapters("icra")
        val map = TimeTextMap.fromChapters(ch.chapters, text, ch.duration)
        var last = -1
        var t = 0.0
        while (t <= ch.duration) {
            val o = map.offset(at = t)
            assertTrue(o >= last)
            last = o
            assertTrue(abs(map.time(at = o) - t) < 1)
            t += 30
        }
        assertEquals(text.count, map.offset(at = ch.duration))
    }

    @Test fun sidecarIsExact() {
        val json = """{"version":1,"chunks":[{"start":0,"end":2,"char_start":0,"char_end":10},{"start":2.5,"end":5,"char_start":11,"char_end":30}]}"""
        val sidecar = TimingSidecar.decode(json)
        val map = TimeTextMap.fromSidecar(sidecar, textLength = 30, duration = 5.0)
        assertTrue(map.isExact)
        assertEquals(5, map.offset(at = 1.0))
        assertEquals(11, map.offset(at = 2.5))
    }

    @Test fun sidecarConvertsCodePoints() {
        // "😀" is two UTF-16 units but one code point (Swift: "é" decomposed is one Character, two code points)
        val text = NarrationText("Caf😀 ok")
        val s = TimingSidecar(chunks = listOf(TimingSidecar.Chunk(0.0, 1.0, charStart = 5, charEnd = 7)))
        val c = s.convertedToCharacterOffsets(text).chunks[0]
        assertTrue(c.charStart == 6 && c.charEnd == 8)
    }
}
