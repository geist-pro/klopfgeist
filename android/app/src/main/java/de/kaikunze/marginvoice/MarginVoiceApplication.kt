package de.kaikunze.marginvoice

import android.app.Application
import android.content.Context
import de.kaikunze.marginvoice.audio.ExoBookPlayer
import de.kaikunze.marginvoice.audio.MediaSessionRemote
import de.kaikunze.marginvoice.conversion.ConversionCenter
import de.kaikunze.marginvoice.core.VoiceCommand
import de.kaikunze.marginvoice.model.BookContent
import de.kaikunze.marginvoice.model.BookStore
import de.kaikunze.marginvoice.model.Importer
import de.kaikunze.marginvoice.model.SampleDownloads
import de.kaikunze.marginvoice.model.ExportBuilder
import de.kaikunze.marginvoice.model.Library
import de.kaikunze.marginvoice.model.PrefsStore
import de.kaikunze.marginvoice.model.Settings
import de.kaikunze.marginvoice.sync.LibrarySyncLocal
import de.kaikunze.marginvoice.sync.SecretStore
import de.kaikunze.marginvoice.sync.SyncCenter
import de.kaikunze.marginvoice.voice.AnchorRefiner
import de.kaikunze.marginvoice.voice.CommentProcessor
import de.kaikunze.marginvoice.voice.FileTranscriber
import de.kaikunze.marginvoice.voice.AndroidAudioCapture
import de.kaikunze.marginvoice.voice.SessionHost
import de.kaikunze.marginvoice.voice.VoskModels
import kotlinx.coroutines.MainScope
import java.io.File

class MarginVoiceApplication : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}

/**
 * Everything the app shares, created once at launch: the library, and the review
 * session with its adapters (player, microphone, media session) behind [SessionHost].
 */
class AppGraph(context: Context) {
    val scope = MainScope()
    val settings = Settings(PrefsStore(context.getSharedPreferences("settings", Context.MODE_PRIVATE)))
    val library = Library(File(context.filesDir, "library.json")).apply { load() }
    val store = BookStore(File(context.filesDir, "Books"), library)
    /** `MainScope()` is `Dispatchers.Main`, which queues: the review session relies on that. */
    val engine = ExoBookPlayer(context, ownCommunicationMode = { settings.useHeadsetMicrophone })
    val remote = MediaSessionRemote(engine)
    val models = VoskModels(context)
    private val transcriber = FileTranscriber(context, models)
    private val refiner = AnchorRefiner(transcriber)
    val capture = AndroidAudioCapture(context, models, scope, echoCancellationEnabled = { settings.echoCancellation })
    val processor = CommentProcessor(store, library, settings, scope, object : CommentProcessor.Engines {
        override suspend fun transcribe(file: File, language: VoiceCommand.Language, vocabulary: List<String>): String {
            // a comment just recorded is still being finished on the recorder's thread
            capture.awaitRecording(file)
            return transcriber.transcribe(file, language, vocabulary)
        }

        override suspend fun refine(bookAudio: File, anchor: Double, content: BookContent, language: VoiceCommand.Language) =
            refiner.refine(bookAudio, anchor, content, language)
    })
    val session = SessionHost(engine, capture, remote, processor, store, library, settings, scope)
    val importer = Importer(store, { settings.defaultRate }, scope)
    val samples = SampleDownloads(importer, scope)
    val conversions = ConversionCenter(context, store, library, settings, scope)
    val cacheDir: File = context.cacheDir
    val sync = SyncCenter(
        context.getSharedPreferences("sync", Context.MODE_PRIVATE),
        runCatching { SecretStore(context.getSharedPreferences("sync", Context.MODE_PRIVATE)) }.getOrNull(),
        File(context.filesDir, "sync-state.json"),
        LibrarySyncLocal(library, store, willDelete = { if (session.book?.id == it.id) session.close() }),
        scope,
    )

    init {
        library.onSaved = { sync.libraryChanged() }
        if (library.loadError == null) sync.syncSoon()
        if (library.loadError == null) store.removeLeftovers()
        ExportBuilder.removeTemporaryFiles(context.cacheDir)
    }
}
