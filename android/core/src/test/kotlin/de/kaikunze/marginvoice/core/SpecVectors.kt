package de.kaikunze.marginvoice.core

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import java.io.File

/** The language-neutral conformance cases in `spec/vectors`, shared with the Swift core. */
object SpecVectors {
    /** Like Swift's `JSONDecoder`: unknown keys ("why", "description") are skipped, a missing optional is null. */
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    /** `spec/vectors` of the repository: the nearest one above the working directory (Gradle runs tests in `android/core`). */
    val directory: File
        get() {
            val start = File(System.getProperty("user.dir")).absoluteFile
            var dir: File? = start
            while (dir != null) {
                val candidate = File(dir, "spec/vectors")
                if (candidate.isDirectory) return candidate
                dir = dir.parentFile
            }
            throw IllegalStateException("no spec/vectors directory at or above $start")
        }

    fun file(name: String): File {
        val file = File(directory, "$name.json")
        check(file.isFile) { "missing vector file $file" }
        return file
    }

    fun <T> load(name: String, type: DeserializationStrategy<T>): T = json.decodeFromString(type, file(name).readText())

    /** Fails with every collected message, each naming its case. */
    fun report(vectors: String, ran: Int, failures: List<String>) {
        println("spec/vectors/$vectors.json: ran $ran cases, ${failures.size} mismatches")
        if (failures.isNotEmpty()) throw AssertionError("$vectors.json: ${failures.size} mismatches\n" + failures.joinToString("\n"))
    }
}
