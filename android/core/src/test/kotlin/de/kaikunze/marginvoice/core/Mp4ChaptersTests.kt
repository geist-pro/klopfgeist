package de.kaikunze.marginvoice.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** Fixtures made with ffmpeg (`-f ipod` and `-f mp4`), which write both a chapter track and `chpl`. */
class Mp4ChaptersTests {
    private val expected = listOf(
        ChapterMark("Opening", 0.0, 2.0), ChapterMark("Zweites Kapitel: Ärger", 2.0, 4.5), ChapterMark("Results", 4.5, 6.0),
    )

    @Test fun readsTheChapterTrack() {
        val track = Mp4Chapters.readTrack(Fixture.file("chapters.m4b"))!!
        assertEquals(expected.map { it.title to it.start }, track)
    }

    @Test fun readsNeroChapters() {
        val chpl = Mp4Chapters.readChpl(Fixture.file("chapters_mp4.m4a"))!!
        assertEquals(expected.map { it.title }, chpl.map { it.first })
        assertEquals(expected.map { it.start }, chpl.map { Math.round(it.second * 1000) / 1000.0 })
    }

    @Test fun chaptersEndWhereTheNextBegins() {
        assertEquals(expected, Mp4Chapters.read(Fixture.file("chapters.m4b"), duration = 6.0))
    }

    @Test fun notAnMp4GivesNoChapters() {
        assertEquals(emptyList<ChapterMark>(), Mp4Chapters.read(Fixture.file("icra.txt"), duration = 1.0))
    }
}
