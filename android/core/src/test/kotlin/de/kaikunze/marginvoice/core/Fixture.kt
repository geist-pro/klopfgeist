package de.kaikunze.marginvoice.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

object Fixture {
    @Serializable
    data class Chapters(val duration: Double, val chapters: List<ChapterMark>)

    /**
     * The paper and the proposal some tests read (`icra.*`, `hace_de.*`) are unpublished and not in the
     * public repository (`fixtures/README.md`): a test that asks for one is skipped where they are missing.
     */
    fun file(name: String): File {
        val resource = javaClass.getResource("/fixtures/$name")
        if (name.startsWith("icra") || name.startsWith("hace_de")) {
            org.junit.Assume.assumeTrue("private fixture $name is not in this checkout", resource != null)
        }
        return File(requireNotNull(resource) { "missing fixture $name" }.toURI())
    }

    fun text(name: String): NarrationText = NarrationText(file("$name.txt").readText())

    fun chapters(name: String): Chapters =
        Json { ignoreUnknownKeys = true }.decodeFromString(Chapters.serializer(), file("${name}_chapters.json").readText())
}
