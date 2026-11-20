package de.kaikunze.marginvoice.core

import kotlinx.serialization.Serializable
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs `spec/vectors/player-density.json`; the Swift core runs the same file. */
class PlayerDensityVectorTests {
    @Serializable
    data class VectorFile(val cases: List<Case>)

    @Serializable
    data class Case(val name: String, val textScale: Double, val density: String)

    @Test fun conformsTo() {
        val cases = SpecVectors.load("player-density", VectorFile.serializer()).cases
        assertTrue("player-density.json has only ${cases.size} cases", cases.size >= 9)
        val failures = ArrayList<String>()
        for (c in cases) {
            val actual = PlayerDensity.of(c.textScale).raw
            if (actual != c.density) failures.add("${c.name}: expected ${c.density}, got $actual")
        }
        SpecVectors.report("player-density", cases.size, failures)
    }

    @Test fun focusedDropsEverythingAroundTheSentence() {
        assertTrue(PlayerDensity.FULL.showsNeighbouringSentences)
        assertTrue(PlayerDensity.FULL.showsBookTitle)
        assertFalse(PlayerDensity.FOCUSED.showsNeighbouringSentences)
        assertFalse(PlayerDensity.FOCUSED.showsBookTitle)
    }
}
