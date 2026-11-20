package de.kaikunze.marginvoice.sync

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URLDecoder
import java.nio.file.Files

/** A tiny WebDAV server in memory: enough of Nextcloud for the sync folder. */
private class FakeDav(private val password: String = "secret") : Dispatcher() {
    val files = sortedMapOf<String, ByteArray>()
    val folders = sortedSetOf("/dav")
    private var etag = 0
    private val etags = HashMap<String, Int>()

    override fun dispatch(request: RecordedRequest): MockResponse {
        if (request.getHeader("Authorization") != okhttp3.Credentials.basic("kai", password)) return MockResponse().setResponseCode(401)
        val path = URLDecoder.decode(request.requestUrl!!.encodedPath, "UTF-8").trimEnd('/')
        return when (request.method) {
            "PUT" -> {
                if (path.substringBeforeLast('/') !in folders) return MockResponse().setResponseCode(409)
                files[path] = request.body.readByteArray(); etags[path] = ++etag
                MockResponse().setResponseCode(201)
            }
            "GET" -> files[path]?.let { MockResponse().setBody(okio.Buffer().write(it)) } ?: MockResponse().setResponseCode(404)
            "MKCOL" -> if (path in folders) MockResponse().setResponseCode(405) else { folders.add(path); MockResponse().setResponseCode(201) }
            "DELETE" -> {
                val gone = files.keys.filter { it == path || it.startsWith("$path/") } + folders.filter { it == path || it.startsWith("$path/") }
                files.keys.removeAll(gone.toSet()); folders.removeAll(gone.toSet())
                MockResponse().setResponseCode(if (gone.isEmpty()) 404 else 204)
            }
            "PROPFIND" -> {
                if (path !in folders) return MockResponse().setResponseCode(404)
                val children = folders.filter { it.substringBeforeLast('/') == path }.map { "$it/" to null } +
                    files.keys.filter { it.substringBeforeLast('/') == path }.map { it to etags[it] }
                val body = buildString {
                    append("""<?xml version="1.0"?><d:multistatus xmlns:d="DAV:">""")
                    append("""<d:response><d:href>${enc(path)}/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>""")
                    for ((p, tag) in children) {
                        val type = if (tag == null) "<d:collection/>" else ""
                        val et = tag?.let { "<d:getetag>\"$it\"</d:getetag>" } ?: ""
                        append("""<d:response><d:href>${enc(p)}</d:href><d:propstat><d:prop><d:resourcetype>$type</d:resourcetype>$et</d:prop></d:propstat></d:response>""")
                    }
                    append("</d:multistatus>")
                }
                MockResponse().setResponseCode(207).setBody(body)
            }
            else -> MockResponse().setResponseCode(405)
        }
    }

    private fun enc(p: String) = p.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
}

class WebDavFolderTests {
    private val server = MockWebServer()
    private val dav = FakeDav()

    init {
        server.dispatcher = dav
        server.start()
    }

    @After fun stop() = server.shutdown()

    private fun folder(password: String = "secret") = WebDavFolder(server.url("/dav").toString(), "kai", password)

    @Test fun writesListsReadsAndDeletes() = runTest {
        val f = folder()
        assertNull(f.list("Books"))
        val bytes = "hello".toByteArray()
        f.write("Books/ABC/comments/X Y.json", bytes.size.toLong()) { bytes.inputStream() }
        assertTrue("/dav/Books/ABC/comments" in dav.folders)
        assertEquals(listOf("ABC"), f.list("Books")!!.map { it.name })
        assertTrue(f.list("Books")!!.single().isFolder)
        val items = f.list("Books/ABC/comments")!!
        assertEquals("X Y.json", items.single().name)
        val tag = items.single().tag
        assertEquals("hello", f.read("Books/ABC/comments/X Y.json").decodeToString())
        val dest = File(Files.createTempDirectory("dav").toFile(), "x.json")
        f.download("Books/ABC/comments/X Y.json", dest)
        assertEquals("hello", dest.readText())

        f.write("Books/ABC/comments/X Y.json", 2) { "hi".byteInputStream() }
        assertTrue(f.list("Books/ABC/comments")!!.single().tag != tag)

        f.delete("Books/ABC")
        f.delete("Books/ABC") // already gone is fine
        assertEquals(emptyList<String>(), f.list("Books")!!.map { it.name })
    }

    @Test fun wrongPasswordSaysSo() = runTest {
        val e = assertThrows(WebDavFolder.Error::class.java) { kotlinx.coroutines.runBlocking { folder("nope").list("Books") } }
        assertTrue(e.message!!.contains("app password"))
    }

    @Test fun nextcloudAddressBecomesItsDavFolder() {
        assertEquals("https://cloud.example.org/remote.php/dav/files/kai/MarginVoice", SyncCenter.davUrl("cloud.example.org/", "kai"))
        assertEquals("https://x.org/remote.php/dav/files/kai/Other", SyncCenter.davUrl("https://x.org/remote.php/dav/files/kai/Other", "kai"))
    }
}
