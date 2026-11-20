package de.kaikunze.marginvoice.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class SampleBooksTests {
    @Test fun samplesAreBookPackagesWithUppercaseIds() {
        assertEquals(3, SampleBooks.all.map { it.id }.toSet().size)
        for (s in SampleBooks.all) {
            assertEquals(s.id, UUID.fromString(s.id).toString().uppercase())
            assertTrue(s.file.endsWith(".${BookPackage.EXTENSION}"))
            assertEquals("${SampleBooks.BASE}/${s.file}", s.url)
        }
        assertEquals("52 MB", SampleBooks.size(51_850_196))
    }
}
