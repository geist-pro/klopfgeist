package de.kaikunze.marginvoice.conversion

import android.content.Context
import android.speech.tts.Voice
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import de.kaikunze.marginvoice.core.conversion.Narrator
import de.kaikunze.marginvoice.core.conversion.SourceDocument
import de.kaikunze.marginvoice.model.Book
import de.kaikunze.marginvoice.model.BookStore
import de.kaikunze.marginvoice.model.ImportSource
import de.kaikunze.marginvoice.model.Library
import de.kaikunze.marginvoice.model.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Converts documents to audiobooks on device: extract → narrate → speak → book. Main thread. */
class ConversionCenter(
    private val context: Context,
    private val store: BookStore,
    private val library: Library,
    private val settings: Settings,
    private val scope: CoroutineScope,
) {
    class Job(val title: String, val id: UUID = UUID.randomUUID()) {
        sealed interface Phase {
            data object Reading : Phase
            /** A neural voice downloading or loading before the first sentence (`progress` is the download's). */
            data object PreparingVoice : Phase
            data object Speaking : Phase
            data class Finished(val bookId: UUID) : Phase
            data class Failed(val message: String) : Phase
            data object Cancelled : Phase
        }

        var phase by mutableStateOf<Phase>(Phase.Reading)
        var progress by mutableDoubleStateOf(0.0)
        val isActive: Boolean get() = phase == Phase.Reading || phase == Phase.PreparingVoice || phase == Phase.Speaking
    }

    /** A document read and narrated, waiting for the user to confirm voice and title. */
    data class Draft(
        val source: File,
        val title: String,
        val language: String,
        val document: SourceDocument,
        val pageCount: Int,
        val usedOCR: Boolean,
        val plan: Narrator.Plan,
        val id: UUID = UUID.randomUUID(),
    )

    val jobs = mutableStateListOf<Job>()
    private val tasks = HashMap<UUID, kotlinx.coroutines.Job>()
    private var renderer: SpeechRenderer? = null

    /** Running and failed conversions (finished ones show up as books instead). */
    val visibleJobs: List<Job> get() = jobs.filter { it.isActive || it.phase is Job.Phase.Failed }

    /** The TTS engine, created once. */
    suspend fun renderer(): SpeechRenderer = renderer ?: SpeechRenderer.create(context).also { renderer = it }

    // Reading

    /** Reads the document and prepares the narration (fast; no audio yet). */
    suspend fun prepare(source: ImportSource): Draft = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "convert-${UUID.randomUUID()}").also { it.mkdirs() }
        val local = File(dir, source.name.substringAfterLast('/'))
        source.open().use { input -> local.outputStream().use { input.copyTo(it) } }
        val result = DocumentExtractor.extract(context, local)
        val plan = Narrator.plan(result.document, result.language)
        Draft(local, result.document.title ?: local.nameWithoutExtension, result.language, result.document, result.pageCount, result.usedOCR, plan)
    }

    /** The draft narrated again after its title or language changed. */
    fun replan(draft: Draft, title: String, language: String): Draft =
        draft.copy(title = title, language = language, plan = Narrator.plan(draft.document.copy(title = title), language))

    // Speaking

    fun start(draft: Draft, voice: Voice?) {
        val job = Job(draft.title).apply { phase = Job.Phase.Speaking }
        jobs.add(0, job)
        ConversionService.start(context, draft.title)
        tasks[job.id] = scope.launch {
            var folder: File? = null
            try {
                val (folderName, dir) = store.makeBookFolder()
                folder = dir
                val renderer = renderer()
                val output = withContext(Dispatchers.Default) {
                    File(dir, "narration.txt").writeText(draft.plan.text)
                    runCatching { draft.source.copyTo(File(dir, "source." + draft.source.extension.lowercase())) }
                    renderer.render(draft.plan, voice, File(dir, "book.m4a"), File(context.cacheDir, "render-${job.id}")) { fraction ->
                        scope.launch {
                            job.progress = fraction
                            ConversionService.update(context, draft.title, fraction)
                        }
                    }
                }
                File(dir, "timing.json").writeText(output.timing.encode())
                val book = Book(
                    title = draft.title, folder = folderName, audioFile = "book.m4a", textFile = "narration.txt",
                    timingFile = "timing.json", language = draft.language, voiceName = renderer.voiceName ?: voice?.name, duration = output.duration,
                    chapters = output.chapters, rate = settings.defaultRate,
                )
                library.books.add(book)
                if (!library.save()) {
                    // a book that could not be saved must not be saved later without its files
                    library.books.remove(book)
                    error("The library could not be saved.")
                }
                job.phase = Job.Phase.Finished(book.id)
                job.progress = 1.0
                draft.source.parentFile?.deleteRecursively()
            } catch (e: CancellationException) {
                folder?.deleteRecursively()
                job.phase = Job.Phase.Cancelled
            } catch (e: Exception) {
                Log.e(TAG, "conversion", e)
                // a failed conversion leaves no half-rendered audio behind
                folder?.deleteRecursively()
                job.phase = Job.Phase.Failed(e.message ?: "The conversion failed.")
            } finally {
                tasks.remove(job.id)
                if (tasks.isEmpty()) ConversionService.stop(context)
            }
        }
    }

    fun cancel(id: UUID) {
        tasks[id]?.cancel()
        renderer?.cancel()
    }

    fun dismiss(id: UUID) {
        jobs.removeAll { it.id == id }
    }

    companion object {
        private const val TAG = "ConversionCenter"
    }
}
