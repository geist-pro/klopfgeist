package de.kaikunze.marginvoice.sync

import de.kaikunze.marginvoice.core.SyncFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source
import org.w3c.dom.Element
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The sync store on a WebDAV server (Nextcloud: `https://<host>/remote.php/dav/files/<user>/DraftTalk`).
 * Basic auth with an app password, over HTTPS only (plain HTTP just for a local test server).
 */
class WebDavFolder(root: String, user: String, password: String, private val client: OkHttpClient = defaultClient) : SyncFolder {
    private val base: HttpUrl = root.trimEnd('/').toHttpUrl()
    private val auth = Credentials.basic(user, password)
    /** Folders known to exist, so each is created once. */
    private val made = HashSet<String>()

    class Error(message: String) : IOException(message)

    private fun url(path: String, folder: Boolean = false): HttpUrl {
        val b = base.newBuilder()
        path.split('/').filter { it.isNotEmpty() }.forEach { b.addPathSegment(it) }
        if (folder) b.addPathSegment("")
        return b.build()
    }

    private suspend fun call(request: Request.Builder): Response = withContext(Dispatchers.IO) {
        client.newCall(request.header("Authorization", auth).build()).execute()
    }

    private fun Response.check(vararg ok: Int): Response {
        if (code in ok || (ok.isEmpty() && isSuccessful)) return this
        val message = when (code) {
            401 -> "the server refused the user name or app password"
            403 -> "the server does not allow this"
            507 -> "the server is full"
            else -> "HTTP $code ${message}"
        }
        close()
        throw Error("Sync: $message (${request.method} ${request.url.encodedPath})")
    }

    override suspend fun list(path: String): List<SyncFolder.Item>? {
        val request = Request.Builder().url(url(path, folder = true))
            .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML)).header("Depth", "1")
        call(request).use { response ->
            if (response.code == 404) return null
            response.check(207)
            val body = response.body?.byteStream() ?: return emptyList()
            return withContext(Dispatchers.IO) { parse(body, url(path, folder = true).encodedPath) }
        }
    }

    override suspend fun read(path: String): ByteArray = call(Request.Builder().url(url(path)).get()).use {
        it.check()
        withContext(Dispatchers.IO) { it.body!!.bytes() }
    }

    override suspend fun download(path: String, dest: File) {
        call(Request.Builder().url(url(path)).get()).use { response ->
            response.check()
            withContext(Dispatchers.IO) {
                dest.parentFile?.mkdirs()
                val tmp = File(dest.parentFile, dest.name + ".download")
                tmp.outputStream().use { out -> response.body!!.byteStream().copyTo(out) }
                if (!tmp.renameTo(dest)) { tmp.delete(); throw Error("Sync: could not save ${dest.name}") }
            }
        }
    }

    override suspend fun write(path: String, size: Long, open: () -> InputStream) {
        makeFolders(path.substringBeforeLast('/', ""))
        val body = object : RequestBody() {
            override fun contentType() = OCTET
            override fun contentLength() = size
            override fun writeTo(sink: BufferedSink) { open().source().use { sink.writeAll(it) } }
        }
        call(Request.Builder().url(url(path)).put(body)).use { it.check(200, 201, 204) }
    }

    override suspend fun delete(path: String) {
        call(Request.Builder().url(url(path)).delete()).use { it.check(200, 204, 404) }
        made.removeAll { it == path || it.startsWith("$path/") }
    }

    private suspend fun makeFolders(path: String) {
        var current = ""
        for (part in path.split('/').filter { it.isNotEmpty() }) {
            current = if (current.isEmpty()) part else "$current/$part"
            if (current in made) continue
            // 405: it is there already
            call(Request.Builder().url(url(current, folder = true)).method("MKCOL", null)).use { it.check(201, 405) }
            made.add(current)
        }
    }

    /** The children in a PROPFIND multistatus (without the folder itself). */
    private fun parse(input: InputStream, selfPath: String): List<SyncFolder.Item> {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = factory.newDocumentBuilder().parse(input)
        val responses = doc.getElementsByTagNameNS(DAV, "response")
        val items = ArrayList<SyncFolder.Item>()
        val self = URLDecoder.decode(selfPath, "UTF-8").trimEnd('/')
        for (i in 0 until responses.length) {
            val r = responses.item(i) as Element
            val href = URLDecoder.decode(text(r, "href") ?: continue, "UTF-8").let { h ->
                if (h.startsWith("http")) h.toHttpUrl().encodedPath.let { URLDecoder.decode(it, "UTF-8") } else h
            }.trimEnd('/')
            if (href == self) continue
            val isFolder = r.getElementsByTagNameNS(DAV, "collection").length > 0
            val tag = text(r, "getetag") ?: listOfNotNull(text(r, "getcontentlength"), text(r, "getlastmodified")).joinToString("/")
            items.add(SyncFolder.Item(href.substringAfterLast('/'), isFolder, tag))
        }
        return items
    }

    private fun text(e: Element, name: String): String? =
        e.getElementsByTagNameNS(DAV, name).item(0)?.textContent?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        private const val DAV = "DAV:"
        private val XML = "application/xml; charset=utf-8".toMediaType()
        private val OCTET = "application/octet-stream".toMediaType()
        private const val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getetag/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>"""

        val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS).build()
        }
    }
}
