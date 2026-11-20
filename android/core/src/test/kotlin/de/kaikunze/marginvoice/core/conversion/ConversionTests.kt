package de.kaikunze.marginvoice.core.conversion

import de.kaikunze.marginvoice.core.Fixture
import de.kaikunze.marginvoice.core.TextSegmentation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import kotlin.math.sin
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private fun dump(doc: SourceDocument, limit: Int = 400) {
    println("TITLE: ${doc.title ?: "-"}")
    for (b in doc.blocks.take(limit)) when (b) {
        is SourceDocument.Block.Heading -> println("H${b.level}: ${b.text}")
        is SourceDocument.Block.Figure -> println("FIG ${b.number ?: ""}: ${b.caption.take(70)}")
        is SourceDocument.Block.Table -> println("TAB ${b.number ?: ""}: ${b.caption.take(70)}")
        is SourceDocument.Block.Paragraph -> println("  P: ${b.text.take(110)}")
        is SourceDocument.Block.ListItem -> println("  - ${b.text.take(80)}")
        is SourceDocument.Block.Equation -> println("EQ ${b.number ?: "-"}")
    }
}

private fun pdfDocument(name: String, fallbackTitle: String?): SourceDocument {
    val pdf = PdfBoxLineReader.read(Fixture.file("$name.pdf"))
    return PdfLayoutAnalyzer.document(pdf.lines, pdf.pageCount, fallbackTitle)
}

class ConversionTests {
    @Test fun extractsPaperStructure() {
        val doc = pdfDocument("icra", "icra")
        dump(doc)
        assertTrue(doc.title, doc.title?.startsWith(Fixture.file("icra.txt").readLines().first().split(" ").take(3).joinToString(" ")) == true)
    }
}

class NarrationComparisonTests {
    /** The on-device pipeline should say (nearly) the same words as doc2audio. */
    @Test fun matchesDoc2audioNarration() {
        val doc = pdfDocument("icra", null)
        val plan = Narrator.plan(doc, "en")
        val ours = TextSegmentation.tokens(plan.text)
        val theirs = TextSegmentation.tokens(Fixture.text("icra").string)
        val counts = HashMap<String, Int>()
        for (t in theirs) counts[t] = (counts[t] ?: 0) + 1
        var shared = 0
        for (t in ours) {
            val c = counts[t] ?: 0
            if (c > 0) { counts[t] = c - 1; shared++ }
        }
        val recall = shared.toDouble() / theirs.size
        val precision = shared.toDouble() / ours.size
        println("words ours ${ours.size} doc2audio ${theirs.size} recall $recall precision $precision")
        assertTrue("recall $recall", recall > 0.8) // doc2audio additionally speaks display math from font information
        assertTrue("precision $precision", precision > 0.85)
        // every utterance is located exactly in the narration text
        for (u in plan.utterances) assertEquals(u.text, plan.text.substring(u.range.first, u.range.last + 1))
        assertTrue(plan.chapters.contains("Introduction"))
    }
}

/**
 * ACM journal layout (IMWUT, acmart): uppercase author block, contact
 * information, CCS concepts, running headers and a long reference list.
 */
class ACMPaperTests {
    private fun document() = pdfDocument("acm_imwut", null)

    @Test fun cutsFrontMatterReferencesAndTableCells() {
        val doc = document()
        val text = Narrator.plan(doc, "en").text
        assertTrue(doc.title, doc.title?.startsWith("E-Tactile Flow") == true)
        // authors, affiliations, contact information, keywords, citation format
        for (gone in listOf("SHILONG", "Shilong Liu", "Shandong University", "@", "CCS Concepts", "Key Words", "ACM Reference Format", "Creative Commons")) {
            assertFalse("still reads $gone", text.contains(gone))
        }
        // references, acknowledgments, appendix, table cells, figure labels
        for (gone in listOf("Ashburn", "Natural Science Foundation", "Flow Short Scale used", "The pulp of the left thumb", "Working Memory Set Load")) {
            assertFalse("still reads $gone", text.contains(gone))
        }
        // body text, list items, figure and table captions are kept
        for (kept in listOf(
            "Chronic or recurrent pain significantly impairs", "Attentional capture is often imposed", "Top-down tasks exhibit superior analgesic",
            "Figure 6c evaluates", "Schematic Diagram of the Positions", "This study provides initial empirical support",
        )) {
            assertTrue("lost $kept", text.contains(kept))
        }
        val headings = doc.blocks.mapNotNull { (it as? SourceDocument.Block.Heading)?.text }
        for (h in listOf("Abstract", "Introduction", "Common Avenues for Pain Relief", "Pain Relief Based on Visual Distraction", "Results", "Conclusion")) {
            assertTrue("missing heading $h", headings.contains(h))
        }
    }

    @Test fun dumpStructure() = dump(document(), Int.MAX_VALUE)
}

class PlainTextDocumentTests {
    @Test fun headingsNeedNoBlankLine() {
        val doc = SourceDocument.fromPlainText("# Title\nIntro line.\n## Methods\nWe did it\nlike this.\n- one\n  more\n* two", "x", markdown = true)
        assertEquals("Title", doc.title)
        assertEquals(
            listOf(
                SourceDocument.Block.Paragraph("Intro line."), SourceDocument.Block.Heading("Methods", 1),
                SourceDocument.Block.Paragraph("We did it like this."), SourceDocument.Block.ListItem("one more"),
                SourceDocument.Block.ListItem("two"),
            ),
            doc.blocks,
        )
    }

    @Test fun plainTextKeepsWrappedLinesInTheirParagraph() {
        val doc = SourceDocument.fromPlainText("Er sagte\n- und dann ging er\n#1 war das.\n\n- point one\n\n    The next paragraph\nstarts here.", null)
        assertEquals(
            listOf(
                SourceDocument.Block.Paragraph("Er sagte - und dann ging er #1 war das."), SourceDocument.Block.ListItem("point one"),
                SourceDocument.Block.Paragraph("The next paragraph starts here."),
            ),
            doc.blocks,
        )
    }

    @Test fun hashWithoutSpaceIsNoHeading() {
        val doc = SourceDocument.fromPlainText("Intro.\n#1 is first.", null, markdown = true)
        assertEquals(listOf(SourceDocument.Block.Paragraph("Intro. #1 is first.")), doc.blocks)
    }

    @Test fun decomposedUmlautsSurvive() {
        val doc = SourceDocument.fromPlainText("Müller", null)
        assertEquals(listOf(SourceDocument.Block.Paragraph("Müller")), doc.blocks)
        assertTrue(Narrator.plan(doc, "de").text.contains("Müller"))
    }
}

class RtfTextTests {
    @Test fun readsParagraphsAndSpecialCharacters() {
        val rtf = """{\rtf1\ansi\ansicpg1252{\fonttbl\f0\fswiss Helvetica;}{\colortbl;\red255\green255\blue255;}
{\*\expandedcolortbl;;}\f0\fs24 Gr\'fc\'dfe aus M\u252?nchen\par
Second \b bold\b0  paragraph \endash  with \{braces\}.\par
}"""
        assertEquals("Grüße aus München\nSecond bold paragraph – with {braces}.\n", RtfText.plain(rtf))
    }
}

/** The render loop with a stand-in neural voice (iOS: SpeechRendererTests). */
class SpeechRendererTests {
    private class CountingSink(override val sampleRate: Int) : PcmSink {
        override var frames = 0L
        override fun write(samples: ShortArray) { frames += samples.size }
    }

    /**
     * A neural voice hands back samples per sentence: each sentence's chunk lasts
     * exactly as long as its samples, pauses go between, and the file matches.
     */
    @Test fun rendersSampleVoiceWithExactTiming() = runTest {
        val doc = SourceDocument("Neural", listOf(
            SourceDocument.Block.Heading("Introduction", 1),
            SourceDocument.Block.Paragraph("One short sentence. And a second, somewhat longer sentence follows here."),
        ))
        val plan = Narrator.plan(doc, language = "en")
        // 10 ms of a quiet tone per character, at Kokoro's rate
        val voice = SampleVoice("Tone", 24_000) { text -> FloatArray(text.length * 240) { (sin(it * 0.05) * 0.1).toFloat() } }
        var sink: CountingSink? = null
        val out = PlanRenderer.render(plan, voice, { rate -> CountingSink(rate).also { sink = it } })
        assertEquals(plan.utterances.size, out.timing.chunks.size)
        for ((chunk, utterance) in out.timing.chunks.zip(plan.utterances)) {
            assertEquals(utterance.text.length * 0.01, chunk.end - chunk.start, 0.001)
        }
        val pauses = plan.utterances.dropLast(1).sumOf { it.pauseAfter }
        val spoken = plan.utterances.sumOf { it.text.length * 0.01 }
        assertEquals(spoken + pauses + (plan.utterances.lastOrNull()?.pauseAfter ?: 0.0), out.duration, 0.01)
        assertEquals(out.duration, sink!!.frames.toDouble() / sink!!.sampleRate, 0.2)
    }

    @Test fun sampleVoiceCancels() = runBlocking {
        val plan = Narrator.plan(SourceDocument("T", listOf(SourceDocument.Block.Paragraph("A. B. C. D."))), language = "en")
        val voice = SampleVoice("Slow", 24_000) {
            delay(5_000)
            FloatArray(1)
        }
        val task = async(Dispatchers.Default) { PlanRenderer.render(plan, voice, { CountingSink(it) }) }
        delay(100)
        task.cancel()
        assertThrows(CancellationException::class.java) { runBlocking { task.await() } }
        Unit
    }
}
