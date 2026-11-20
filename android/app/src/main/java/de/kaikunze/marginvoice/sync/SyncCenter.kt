package de.kaikunze.marginvoice.sync

import android.content.SharedPreferences
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import de.kaikunze.marginvoice.core.SyncEngine
import de.kaikunze.marginvoice.core.SyncFolder
import de.kaikunze.marginvoice.core.SyncLocal
import de.kaikunze.marginvoice.core.SyncState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant

/**
 * Sync with a WebDAV folder (Nextcloud and the like), which the Apple apps can sync
 * with too. Runs at launch, when the app goes to the background, and half a minute
 * after the last change; one sync at a time.
 */
class SyncCenter(
    private val prefs: SharedPreferences,
    private val secrets: SecretStore?,
    private val stateFile: File,
    private val local: SyncLocal,
    private val scope: CoroutineScope,
    private val folder: (server: String, user: String, password: String) -> SyncFolder = { s, u, p -> WebDavFolder(davUrl(s, u), u, p) },
) {
    var enabled by mutableStateOf(prefs.getBoolean(ENABLED, false))
        private set
    var server by mutableStateOf(prefs.getString(SERVER, "") ?: "")
        private set
    var user by mutableStateOf(prefs.getString(USER, "") ?: "")
        private set
    val hasPassword: Boolean get() = !secrets?.get(PASSWORD).isNullOrEmpty()

    var isSyncing by mutableStateOf(false)
        private set
    var lastSync by mutableStateOf(prefs.getLong(LAST, 0).takeIf { it > 0 }?.let(Instant::ofEpochMilli))
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var lastReport by mutableStateOf<SyncEngine.Report?>(null)
        private set

    private val mutex = Mutex()
    private var pending: Job? = null

    /** Sets the account; an empty password keeps the stored one. Turns sync on and runs it. */
    fun configure(server: String, user: String, password: String) {
        val changed = server.trim() != this.server || user.trim() != this.user
        this.server = server.trim()
        this.user = user.trim()
        prefs.edit().putString(SERVER, this.server).putString(USER, this.user).apply()
        if (password.isNotEmpty()) secrets?.put(PASSWORD, password)
        // another account or folder: what this device knew about the old one doesn't apply
        if (changed) stateFile.delete()
        turn(true)
    }

    fun turn(on: Boolean) {
        enabled = on
        prefs.edit().putBoolean(ENABLED, on).apply()
        error = null
        if (on) syncSoon(0)
    }

    /** A library change: sync once things are quiet (playback saves the position often). */
    fun libraryChanged() {
        if (!isSyncing) syncSoon(QUIET_MS)
    }

    fun syncSoon(delayMs: Long = 0) {
        if (!enabled) return
        pending?.cancel()
        pending = scope.launch {
            delay(delayMs)
            sync()
        }
    }

    suspend fun sync() {
        if (!enabled) return
        val password = secrets?.get(PASSWORD)
        if (server.isEmpty() || user.isEmpty() || password.isNullOrEmpty()) {
            error = "Enter the server, user name and app password."
            return
        }
        mutex.withLock {
            isSyncing = true
            try {
                val state = withContext(Dispatchers.IO) {
                    runCatching { json.decodeFromString(SyncState.serializer(), stateFile.readText()) }.getOrDefault(SyncState())
                }
                val report = SyncEngine(folder(server, user, password), local) { Log.w(TAG, it) }.sync(state)
                withContext(Dispatchers.IO) {
                    val tmp = File(stateFile.parentFile, stateFile.name + ".tmp")
                    tmp.writeText(json.encodeToString(SyncState.serializer(), report.state))
                    tmp.renameTo(stateFile)
                }
                lastReport = report
                lastSync = Instant.now()
                prefs.edit().putLong(LAST, lastSync!!.toEpochMilli()).apply()
                error = null
                Log.i(TAG, "synced: ${report.uploaded} up, ${report.downloaded} down, ${report.deleted} deleted")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "sync failed", e)
                error = e.message ?: e.toString()
            } finally {
                isSyncing = false
            }
        }
    }

    companion object {
        private const val TAG = "Sync"
        private const val ENABLED = "sync.enabled"
        private const val SERVER = "sync.server"
        private const val USER = "sync.user"
        private const val PASSWORD = "sync.password"
        private const val LAST = "sync.last"
        private const val QUIET_MS = 30_000L
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * A Nextcloud address (`https://cloud.example.org`) becomes its WebDAV folder
         * `…/remote.php/dav/files/<user>/DraftTalk`; a full WebDAV URL is used as given.
         */
        fun davUrl(server: String, user: String): String {
            val s = server.trim().trimEnd('/').let { if ("://" in it) it else "https://$it" }
            return if ("/remote.php/" in s || "/dav" in s.substringAfter("://").substringAfter('/', "")) s
            else "$s/remote.php/dav/files/${user.trim()}/MarginVoice"
        }
    }
}
