package de.kaikunze.marginvoice.core

import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs `spec/vectors/status-announcement.json`; the Swift core runs the same file. */
class StatusAnnouncementVectorTests {
    @Serializable
    data class VectorFile(val cases: List<Case>)

    @Serializable
    data class Case(val name: String, val from: String, val to: String, val recording: Boolean, val say: String? = null)

    @Test fun conformsTo() {
        val cases = SpecVectors.load("status-announcement", VectorFile.serializer()).cases
        assertTrue("status-announcement.json has only ${cases.size} cases", cases.size >= 13)
        val failures = ArrayList<String>()
        for (c in cases) {
            val from = ListeningStatus.entries.first { it.raw == c.from }
            val to = ListeningStatus.entries.first { it.raw == c.to }
            val actual = StatusAnnouncement.listening(from, to, c.recording)
            if (actual != c.say) failures.add("${c.name}: expected ${c.say}, got $actual")
        }
        SpecVectors.report("status-announcement", cases.size, failures)
    }

    @Test fun resultsOfWorkInTheBackgroundNameTheBook() {
        assertEquals("Cuddle-fish is ready to play", StatusAnnouncement.conversionFinished("Cuddle-fish"))
        assertEquals("Creating Cuddle-fish failed", StatusAnnouncement.conversionFailed("Cuddle-fish"))
        assertEquals("Sync failed", StatusAnnouncement.SYNC_FAILED)
        assertEquals("1 file ready to share", StatusAnnouncement.exportReady(1))
        assertEquals("3 files ready to share", StatusAnnouncement.exportReady(3))
    }
}
