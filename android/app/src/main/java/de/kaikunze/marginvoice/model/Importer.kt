package de.kaikunze.marginvoice.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Imports audiobooks one batch at a time on the app's scope, so neither a recomposition
 * nor a rotation cancels an import halfway; files picked meanwhile wait their turn.
 * Main thread.
 */
class Importer(private val store: BookStore, private val defaultRate: () -> Double, scope: CoroutineScope) {
    /** The books of the last batch, for the UI to open; it sets this back to null. */
    class Imported(val books: List<Book>, val fromInbox: Boolean)

    private sealed interface Batch {
        class Files(val sources: List<ImportSource>) : Batch
        /** Listed when it runs, so an inbox queued twice imports its files once. */
        class Inbox(val dir: File) : Batch
    }

    private val queue = Channel<Batch>(Channel.UNLIMITED)
    private var pending by mutableIntStateOf(0)

    val isImporting: Boolean get() = pending > 0
    var error by mutableStateOf<String?>(null)
    var imported by mutableStateOf<Imported?>(null)

    init {
        scope.launch {
            for (batch in queue) {
                try {
                    val books = when (batch) {
                        is Batch.Files -> store.importFiles(batch.sources, defaultRate())
                        is Batch.Inbox -> store.importInbox(
                            batch.dir.listFiles().orEmpty().filter { it.isFile && !it.name.startsWith(".") },
                            defaultRate(),
                        )
                    }
                    if (books.isNotEmpty()) imported = Imported(books, batch is Batch.Inbox)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = e.message
                } finally {
                    pending--
                }
            }
        }
    }

    fun import(sources: List<ImportSource>) {
        if (sources.isEmpty()) return
        pending++
        queue.trySend(Batch.Files(sources))
    }

    fun importInbox(dir: File) {
        pending++
        queue.trySend(Batch.Inbox(dir))
    }
}
