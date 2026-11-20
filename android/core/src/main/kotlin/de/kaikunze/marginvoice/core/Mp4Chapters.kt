package de.kaikunze.marginvoice.core

import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset

/**
 * Chapter marks of an `.m4b` / `.m4a`, which Android's media APIs don't expose
 * (iOS reads them with `loadChapterMetadataGroups`). Reads the QuickTime chapter
 * text track (`tref/chap`, what Apple and ffmpeg's ipod muxer write) and falls back
 * to Nero's `moov/udta/chpl` list.
 */
object Mp4Chapters {
    fun read(file: File, duration: Double): List<ChapterMark> = runCatching {
        RandomAccessFile(file, "r").use { f ->
            val moov = children(f, 0, f.length()).firstOrNull { it.type == "moov" } ?: return emptyList()
            val starts = fromTrack(f, moov) ?: fromChpl(f, moov) ?: return emptyList()
            starts.mapIndexed { i, (title, start) ->
                ChapterMark(title.ifEmpty { "Chapter ${i + 1}" }, start, starts.getOrNull(i + 1)?.second ?: duration)
            }
        }
    }.getOrDefault(emptyList())

    /** Chapters from one source only (tests). */
    internal fun readTrack(file: File) = RandomAccessFile(file, "r").use { f -> fromTrack(f, children(f, 0, f.length()).first { it.type == "moov" }) }
    internal fun readChpl(file: File) = RandomAccessFile(file, "r").use { f -> fromChpl(f, children(f, 0, f.length()).first { it.type == "moov" }) }

    private class Box(val type: String, val start: Long, val bodyStart: Long, val end: Long)

    private fun children(f: RandomAccessFile, from: Long, to: Long): List<Box> {
        val result = ArrayList<Box>()
        var off = from
        while (off + 8 <= to) {
            f.seek(off)
            var size = f.readInt().toLong() and 0xFFFFFFFFL
            val type = String(ByteArray(4).also { f.readFully(it) }, Charsets.ISO_8859_1)
            var header = 8L
            if (size == 1L) { size = f.readLong(); header = 16 }
            if (size == 0L) size = to - off
            if (size < header || off + size > to) break
            result.add(Box(type, off, off + header, off + size))
            off += size
        }
        return result
    }

    private fun child(f: RandomAccessFile, box: Box?, vararg path: String): Box? {
        var b = box ?: return null
        for (p in path) b = children(f, b.bodyStart, b.end).firstOrNull { it.type == p } ?: return null
        return b
    }

    private fun trackId(f: RandomAccessFile, trak: Box): Int? {
        val tkhd = child(f, trak, "tkhd") ?: return null
        f.seek(tkhd.bodyStart)
        val version = f.readUnsignedByte()
        f.skipBytes(3 + if (version == 1) 16 else 8)
        return f.readInt()
    }

    private fun fromTrack(f: RandomAccessFile, moov: Box): List<Pair<String, Double>>? {
        val traks = children(f, moov.bodyStart, moov.end).filter { it.type == "trak" }
        val chapterIds = HashSet<Int>()
        for (t in traks) {
            val chap = child(f, t, "tref", "chap") ?: continue
            f.seek(chap.bodyStart)
            repeat(((chap.end - chap.bodyStart) / 4).toInt()) { chapterIds.add(f.readInt()) }
        }
        val track = traks.firstOrNull { trackId(f, it) in chapterIds } ?: return null
        val mdhd = child(f, track, "mdia", "mdhd") ?: return null
        f.seek(mdhd.bodyStart)
        val version = f.readUnsignedByte()
        f.skipBytes(3 + if (version == 1) 16 else 8)
        val timescale = (f.readInt().toLong() and 0xFFFFFFFFL).toDouble().takeIf { it > 0 } ?: return null
        val stbl = child(f, track, "mdia", "minf", "stbl") ?: return null

        // sample times
        val times = ArrayList<Long>()
        child(f, stbl, "stts")?.let { b ->
            f.seek(b.bodyStart + 4)
            var t = 0L
            repeat(f.readInt()) {
                val count = f.readInt()
                val delta = f.readInt().toLong() and 0xFFFFFFFFL
                repeat(count) { times.add(t); t += delta }
            }
        } ?: return null
        // sample sizes
        val sizes = ArrayList<Int>()
        child(f, stbl, "stsz")?.let { b ->
            f.seek(b.bodyStart + 4)
            val fixed = f.readInt()
            val count = f.readInt()
            repeat(count) { sizes.add(if (fixed != 0) fixed else f.readInt()) }
        } ?: return null
        // chunk offsets and samples per chunk
        val chunkOffsets = ArrayList<Long>()
        val co = child(f, stbl, "stco") ?: child(f, stbl, "co64") ?: return null
        f.seek(co.bodyStart + 4)
        repeat(f.readInt()) { chunkOffsets.add(if (co.type == "co64") f.readLong() else f.readInt().toLong() and 0xFFFFFFFFL) }
        val stsc = ArrayList<Pair<Int, Int>>() // first chunk (1-based), samples per chunk
        child(f, stbl, "stsc")?.let { b ->
            f.seek(b.bodyStart + 4)
            repeat(f.readInt()) {
                val first = f.readInt()
                val per = f.readInt()
                f.readInt()
                stsc.add(first to per)
            }
        } ?: return null

        val offsets = ArrayList<Long>()
        for ((ci, chunkOffset) in chunkOffsets.withIndex()) {
            val per = stsc.lastOrNull { it.first <= ci + 1 }?.second ?: 1
            var off = chunkOffset
            repeat(per) {
                if (offsets.size < sizes.size) {
                    offsets.add(off)
                    off += sizes[offsets.size - 1]
                }
            }
        }
        val result = ArrayList<Pair<String, Double>>()
        for (i in 0 until minOf(times.size, offsets.size)) {
            f.seek(offsets[i])
            val len = f.readUnsignedShort().coerceAtMost(maxOf(0, sizes[i] - 2))
            val bytes = ByteArray(len).also { f.readFully(it) }
            result.add(decodeText(bytes) to times[i] / timescale)
        }
        return result.takeIf { it.isNotEmpty() }
    }

    private fun decodeText(bytes: ByteArray): String {
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        return String(bytes, Charsets.UTF_8).trim()
    }

    private fun fromChpl(f: RandomAccessFile, moov: Box): List<Pair<String, Double>>? {
        val chpl = child(f, moov, "udta", "chpl") ?: return null
        f.seek(chpl.bodyStart)
        val version = f.readUnsignedByte()
        f.skipBytes(3)
        if (version != 0) f.skipBytes(4)
        val count = f.readUnsignedByte()
        val result = ArrayList<Pair<String, Double>>()
        repeat(count) {
            if (f.filePointer >= chpl.end) return@repeat
            val start = f.readLong() / 10_000_000.0
            val len = f.readUnsignedByte()
            val bytes = ByteArray(len).also { f.readFully(it) }
            result.add(String(bytes, Charset.forName("UTF-8")) to start)
        }
        return result.takeIf { it.isNotEmpty() }
    }
}
