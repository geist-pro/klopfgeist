package de.kaikunze.marginvoice

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import de.kaikunze.marginvoice.audio.PlaybackService
import de.kaikunze.marginvoice.conversion.DocumentExtractor
import de.kaikunze.marginvoice.model.ImportSource
import de.kaikunze.marginvoice.ui.AppRequests
import de.kaikunze.marginvoice.ui.AppRoot
import de.kaikunze.marginvoice.ui.MarginVoiceTheme
import de.kaikunze.marginvoice.ui.Theme
import kotlinx.coroutines.CompletableDeferred
import java.io.File

class MainActivity : ComponentActivity() {
    private val requests = AppRequests()
    private var controller: ListenableFuture<MediaController>? = null
    private var resumed by mutableIntStateOf(0)
    private var permission: CompletableDeferred<Boolean>? = null

    private val askPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        permission?.complete(result[Manifest.permission.RECORD_AUDIO] == true)
    }
    private val pickAudiobooks = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) requests.imports = requests.imports + uris.map { ImportSource.of(this, it) }
    }
    private val pickDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { requests.conversions.add(ImportSource.of(this, it)) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        val graph = (application as MarginVoiceApplication).graph
        graph.capture.requestPermission = {
            CompletableDeferred<Boolean>().also {
                permission = it
                askPermission.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
            }.await()
        }
        val inbox = File(getExternalFilesDir(null), "Inbox").also { it.mkdirs() }
        if (savedInstanceState == null) handle(intent)
        setContent {
            MarginVoiceTheme(graph.settings.colorTheme, graph.settings.bookFont) {
                // status and navigation bar icons follow the palette: dark icons on a light page
                val dark = Theme.palette.isDark
                LaunchedEffect(dark) {
                    val bars = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                    enableEdgeToEdge(bars, bars)
                }
                LaunchedEffect(graph.session.keepScreenOn) {
                    if (graph.session.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                AppRoot(
                    graph, requests, inbox,
                    onPickAudiobooks = { pickAudiobooks.launch(arrayOf("audio/*", "video/mp4", "text/plain", "application/json", "application/zip", "application/octet-stream")) },
                    onPickDocument = { pickDocument.launch(DocumentExtractor.mimeTypes) },
                    resumed = resumed,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onStart() {
        super.onStart()
        (application as MarginVoiceApplication).graph.session.setInForeground(true)
        // a controller keeps the media session (notification, lock screen, headset buttons) connected;
        // Robolectric (the JVM UI tests) can't bind services
        if (Build.FINGERPRINT == "robolectric") return
        controller = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java))).buildAsync()
    }

    override fun onResume() {
        super.onResume()
        resumed++
    }

    override fun onStop() {
        (application as MarginVoiceApplication).graph.session.setInForeground(false)
        // leaving the app: hand this device's comments over now
        (application as MarginVoiceApplication).graph.sync.syncSoon()
        controller?.let { MediaController.releaseFuture(it) }
        controller = null
        super.onStop()
    }

    /** "Open with" and "Share" from other apps, and the debug extras used for screenshots and device tests. */
    private fun handle(intent: Intent?) {
        intent ?: return
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) requests.receive(uris.map { ImportSource.of(this, it) })
        if (BuildConfig.DEBUG) {
            // adb shell am start -n pro.geist.klopfgeist/de.kaikunze.marginvoice.MainActivity --ei open 0 --ez play true --es show notes|chapters|export|settings|nowplaying
            // --ez clearLibrary true deletes every book (the settings stay), before anything else
            if (intent.getBooleanExtra("clearLibrary", false)) {
                val graph = (application as MarginVoiceApplication).graph
                graph.session.close()
                graph.library.books.toList().forEach { graph.store.delete(it) }
                graph.session.refreshUpNext()
            }
            // --ez trace true writes the session trace to Android/data/<id>/files/session.trace.jsonl (adb pull)
            if (intent.getBooleanExtra("trace", false)) {
                val graph = (application as MarginVoiceApplication).graph
                getExternalFilesDir(null)?.let { runCatching { graph.session.writeTrace(it) } }
            }
            // --es commandLanguage en|de: the language of the voice commands (tools/voice_suite.py)
            intent.getStringExtra("commandLanguage")?.let { code ->
                (application as MarginVoiceApplication).graph.settings.commandLanguage = de.kaikunze.marginvoice.core.VoiceCommand.Language.fromCode(code)
            }
            // --es theme gruvbox|geistDark|…: the colour theme (screenshots)
            intent.getStringExtra("theme")?.let { name ->
                val graph = (application as MarginVoiceApplication).graph
                de.kaikunze.marginvoice.model.ColorTheme.entries.firstOrNull { it.raw == name }?.let { graph.settings.colorTheme = it }
            }
            if (intent.hasExtra("open")) requests.open = intent.getIntExtra("open", 0)
            requests.play = intent.getBooleanExtra("play", false)
            intent.getStringExtra("show")?.let { requests.show = it }
            intent.getStringExtra("font")?.let { name ->
                val graph = (application as MarginVoiceApplication).graph
                de.kaikunze.marginvoice.model.BookFont.entries.firstOrNull { it.raw == name }?.let { graph.settings.bookFont = it }
            }
        }
    }
}
