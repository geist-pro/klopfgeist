package de.kaikunze.marginvoice

import de.kaikunze.marginvoice.model.BookStore
import de.kaikunze.marginvoice.model.ImportSource
import de.kaikunze.marginvoice.model.Library
import de.kaikunze.marginvoice.model.MediaProbe
import java.io.File
import java.nio.file.Files

/** A library and book store in a temporary folder; audio files are placeholders (duration 0). */
class TestLibrary {
    val root: File = Files.createTempDirectory("drafttalk-tests").toFile()
    val library = Library(File(root, "library.json"))
    val store = BookStore(File(root, "Books"), library, object : MediaProbe {
        override fun probe(file: File) = MediaProbe.Info(0.0, emptyList(), null)
    })

    fun source(name: String, contents: String = "audio"): ImportSource {
        val dir = File(root, "source").also { it.mkdirs() }
        val file = File(dir, name).also { it.writeText(contents) }
        return ImportSource.of(file)
    }

    fun bookFolders(): Set<String> = store.baseDir.list().orEmpty().toSet()
}
