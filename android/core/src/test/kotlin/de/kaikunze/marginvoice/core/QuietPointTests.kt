package de.kaikunze.marginvoice.core

import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Runs `spec/vectors/quiet-point.json`; the Swift core runs the same file. */
class QuietPointVectorTests {
    @Serializable
    data class VectorFile(val cases: List<Case>)

    @Serializable
    data class Run(val level: Double, val seconds: Double)

    @Serializable
    data class Case(val name: String, val start: Double, val target: Double, val runs: List<Run>, val resume: Double? = null)

    @Test fun conformsTo() {
        val cases = SpecVectors.load("quiet-point", VectorFile.serializer()).cases
        assertTrue("quiet-point.json has only ${cases.size} cases", cases.size >= 11)
        val failures = ArrayList<String>()
        for (c in cases) {
            val levels = c.runs.flatMap { run -> List(Math.round(run.seconds / QuietPoint.FRAME).toInt()) { run.level } }.toDoubleArray()
            val actual = QuietPoint.find(levels, c.start, c.target)
            val same = if (actual == null || c.resume == null) actual == c.resume else abs(actual - c.resume) < 0.005
            if (!same) failures.add("${c.name}: expected ${c.resume}, got $actual")
        }
        SpecVectors.report("quiet-point", cases.size, failures)
    }

    @Test fun levelsAreRmsPerFrame() {
        val samples = ShortArray(320 * 2) { if (it < 320) 1000 else 0 }
        val levels = QuietPoint.levels(samples, 16_000)
        assertEquals(2, levels.size)
        assertEquals(1000.0, levels[0], 0.001)
        assertEquals(0.0, levels[1], 0.001)
    }
}
