package de.kaikunze.marginvoice.model

import androidx.compose.runtime.mutableStateMapOf
import de.kaikunze.marginvoice.core.SampleBooks
import de.kaikunze.marginvoice.sync.WebDavFolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Downloads the sample papers into the inbox, which imports them like any `.drafttalk`
 * file. Runs on the app's scope, so leaving the sheet keeps the download going. Main thread.
 */
class SampleDownloads(
    private val importer: Importer,
    private val scope: CoroutineScope,
    private val client: OkHttpClient = WebDavFolder.defaultClient,
) {
    /** Progress 0…1 per sample file while it downloads. */
    val progress = mutableStateMapOf<String, Float>()
    /** The last failure per sample file. */
    val errors = mutableStateMapOf<String, String>()
    private val jobs = HashMap<String, Job>()

    fun download(sample: SampleBooks.Sample, inbox: File) {
        if (sample.file in progress) return
        errors.remove(sample.file)
        progress[sample.file] = 0f
        jobs[sample.file] = scope.launch {
            try {
                val file = withContext(Dispatchers.IO) { fetch(sample, inbox) { progress[sample.file] = it } }
                if (!file.renameTo(File(inbox, sample.file))) throw IOException("Couldn't move ${sample.file} into the library.")
                importer.importInbox(inbox)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errors[sample.file] = e.message ?: "The download failed."
            } finally {
                progress.remove(sample.file)
                jobs.remove(sample.file)
                part(sample, inbox).delete()
            }
        }
    }

    fun cancel(sample: SampleBooks.Sample) {
        jobs[sample.file]?.cancel()
    }

    /** Hidden, so the inbox import skips it; in the inbox itself, so the finished file is renamed, not copied. */
    private fun part(sample: SampleBooks.Sample, inbox: File) = File(inbox, ".${sample.file}.part")

    /** Writes the hidden part file in the inbox, reporting progress on the main thread. */
    private suspend fun fetch(sample: SampleBooks.Sample, inbox: File, onProgress: (Float) -> Unit): File {
        val part = part(sample, inbox).apply { parentFile?.mkdirs() }
        client.newCall(Request.Builder().url(sample.url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("The download failed (HTTP ${response.code}).")
            val body = response.body ?: throw IOException("The download failed (empty response).")
            val total = body.contentLength().takeIf { it > 0 } ?: sample.bytes
            var done = 0L
            var reported = 0f
            body.byteStream().use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        val f = (done.toFloat() / total).coerceAtMost(1f)
                        if (f - reported >= 0.01f) {
                            reported = f
                            withContext(Dispatchers.Main) { onProgress(f) }
                        }
                        kotlinx.coroutines.yield()
                    }
                }
            }
        }
        return part
    }
}
