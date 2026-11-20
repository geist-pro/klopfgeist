package de.kaikunze.marginvoice.voice

import android.content.Context
import android.content.res.AssetManager
import de.kaikunze.marginvoice.core.VoiceCommand
import org.vosk.Model
import java.io.File

/**
 * The small Vosk models (from `tools/fetch_models.sh`), copied from the APK's
 * assets into app storage on first use, where Vosk can open them.
 */
class VoskModels(private val context: Context) {
    private val models = HashMap<VoiceCommand.Language, Model>()

    /** Loads the model of a language (seconds on first use); call off the main thread. */
    @Synchronized
    fun model(language: VoiceCommand.Language): Model {
        models[language]?.let { return it }
        val dir = unpack(language.code)
        return Model(dir.path).also { models[language] = it }
    }

    private fun unpack(lang: String): File {
        val assets = context.assets
        val target = File(context.filesDir, "vosk/$lang")
        val version = runCatching { assets.open("vosk/$lang/VERSION").bufferedReader().readText() }
            .getOrElse { throw IllegalStateException("The $lang voice command model is missing from this build (run tools/fetch_models.sh).") }
        val marker = File(target, "VERSION")
        if (marker.exists() && marker.readText() == version) return target
        target.deleteRecursively()
        copy(assets, "vosk/$lang", target)
        return target
    }

    private fun copy(assets: AssetManager, path: String, target: File) {
        val children = assets.list(path).orEmpty()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            assets.open(path).use { input -> target.outputStream().use { input.copyTo(it) } }
            return
        }
        target.mkdirs()
        // the version marker last, so an interrupted copy is redone
        for (child in children.sortedBy { it == "VERSION" }) copy(assets, "$path/$child", File(target, child))
    }
}
