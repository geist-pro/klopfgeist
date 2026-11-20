import Foundation
import PDFKit
import Testing
@testable import MarginVoiceCore

struct ConversionTests {
  func icraDocument() throws -> SourceDocument {
    let url = try #require(Bundle.module.url(forResource: "icra", withExtension: "pdf", subdirectory: "Fixtures"))
    let pdf = try #require(PDFDocument(url: url))
    let lines = PDFLineReader.lines(of: pdf)
    return PDFLayoutAnalyzer.document(from: lines, pageCount: pdf.pageCount, fallbackTitle: "icra")
  }

  @Test(.enabled(if: Fixture.hasPrivate)) func extractsPaperStructure() throws {
    let doc = try icraDocument()
    print("TITLE:", doc.title ?? "-")
    for b in doc.blocks.prefix(400) {
      switch b {
      case .heading(let t, let l): print("H\(l):", t)
      case .figure(let n, let c): print("FIG \(n ?? ""):", c.prefix(60))
      case .table(let n, let c): print("TAB \(n ?? ""):", c.prefix(60))
      case .paragraph(let t): print("  P:", t.prefix(90))
      case .listItem(let t): print("  -", t.prefix(80))
      case .equation(let n): print("EQ", n ?? "-")
      }
    }
    #expect(doc.title?.hasPrefix(try Fixture.text("icra").string.split(separator: " ").prefix(3).joined(separator: " ")) == true)
  }
}

struct NarrationComparisonTests {
  /// The on-device pipeline should say (nearly) the same words as doc2audio.
  @Test(.enabled(if: Fixture.hasPrivate)) func matchesDoc2audioNarration() throws {
    let url = try #require(Bundle.module.url(forResource: "icra", withExtension: "pdf", subdirectory: "Fixtures"))
    let pdf = try #require(PDFDocument(url: url))
    let doc = PDFLayoutAnalyzer.document(from: PDFLineReader.lines(of: pdf), pageCount: pdf.pageCount, fallbackTitle: nil)
    let plan = Narrator.plan(doc, language: "en")
    let ours = TextSegmentation.tokens(plan.text)
    let theirs = TextSegmentation.tokens(try Fixture.text("icra").string)
    var counts: [String: Int] = [:]
    for t in theirs { counts[t, default: 0] += 1 }
    var shared = 0
    for t in ours where (counts[t] ?? 0) > 0 {
      counts[t]! -= 1
      shared += 1
    }
    let recall = Double(shared) / Double(theirs.count), precision = Double(shared) / Double(ours.count)
    print("words ours \(ours.count) doc2audio \(theirs.count) recall \(recall) precision \(precision)")
    #expect(recall > 0.8)  // doc2audio additionally speaks display math from font information
    #expect(precision > 0.85)
    // every utterance is located exactly in the narration text
    let chars = Array(plan.text)
    for u in plan.utterances { #expect(String(chars[u.range]) == u.text) }
    #expect(plan.chapters.contains("Introduction"))
  }
}

import AVFAudio

struct SpeechRendererTests {
  @Test func rendersWithExactTiming() async throws {
    let doc = SourceDocument(title: "A Short Test", blocks: [
      .heading("Introduction", level: 1),
      .paragraph("This is the first sentence. Here is a second one, e.g. with an abbreviation."),
      .heading("Results", level: 1),
      .paragraph("The effect was large (p < .001). We are done."),
    ])
    let plan = Narrator.plan(doc, language: "en")
    let voice = try #require(SpeechRenderer.bestVoice(language: "en"))
    print("voice:", voice.name, voice.quality.rawValue)
    let url = FileManager.default.temporaryDirectory.appendingPathComponent("render-test.m4a")
    let out = try await SpeechRenderer(voice: voice).render(plan, to: url)
    print("duration", out.duration, "chunks", out.timing.chunks.count, "chapters", out.chapters.map(\.title))
    #expect(out.timing.chunks.count == plan.utterances.count)
    #expect(out.chapters.map(\.title) == ["A Short Test", "Introduction", "Results"])
    let file = try AVAudioFile(forReading: url)
    let fileDuration = Double(file.length) / file.processingFormat.sampleRate
    #expect(abs(fileDuration - out.duration) < 0.2)
    // the sidecar maps back onto the narration text exactly
    let text = NarrationText(plan.text)
    let map = TimeTextMap.fromSidecar(out.timing.convertedToCharacterOffsets(in: text), textLength: text.count, duration: out.duration)
    let results = try #require(plan.utterances.first { $0.text.hasPrefix("The effect") })
    let chunk = try #require(out.timing.chunks.first { abs($0.start - map.time(at: results.range.lowerBound)) < 0.01 })
    #expect(chunk.end > chunk.start)
    print(plan.text)
  }

  /// A neural voice hands back samples per sentence: each sentence's chunk lasts
  /// exactly as long as its samples, pauses go between, and the file matches.
  @Test func rendersSampleVoiceWithExactTiming() async throws {
    let doc = SourceDocument(title: "Neural", blocks: [
      .heading("Introduction", level: 1),
      .paragraph("One short sentence. And a second, somewhat longer sentence follows here."),
    ])
    let plan = Narrator.plan(doc, language: "en")
    // 10 ms of a quiet tone per character, at Kokoro's rate
    let voice = SpeechRenderer.SampleVoice(name: "Tone", sampleRate: 24000) { text in
      (0..<(text.count * 240)).map { Float(sin(Double($0) * 0.05) * 0.1) }
    }
    let url = FileManager.default.temporaryDirectory.appendingPathComponent("render-sample-test.m4a")
    let renderer = SpeechRenderer(sampleVoice: voice)
    #expect(renderer.voiceName == "Tone")
    let out = try await renderer.render(plan, to: url)
    #expect(out.timing.chunks.count == plan.utterances.count)
    for (chunk, utterance) in zip(out.timing.chunks, plan.utterances) {
      #expect(abs((chunk.end - chunk.start) - Double(utterance.text.count) * 0.01) < 0.001)
    }
    let pauses = plan.utterances.dropLast().map(\.pauseAfter).reduce(0, +)
    let spoken = plan.utterances.map { Double($0.text.count) * 0.01 }.reduce(0, +)
    #expect(abs(out.duration - (spoken + pauses + (plan.utterances.last?.pauseAfter ?? 0))) < 0.01)
    let file = try AVAudioFile(forReading: url)
    #expect(abs(Double(file.length) / file.processingFormat.sampleRate - out.duration) < 0.2)
  }

  @Test func sampleVoiceCancels() async throws {
    let plan = Narrator.plan(SourceDocument(title: "T", blocks: [.paragraph("A. B. C. D.")]), language: "en")
    let voice = SpeechRenderer.SampleVoice(name: "Slow", sampleRate: 24000) { _ in
      try await Task.sleep(for: .seconds(5))
      return [0]
    }
    let url = FileManager.default.temporaryDirectory.appendingPathComponent("render-cancel-test.m4a")
    let task = Task { try await SpeechRenderer(sampleVoice: voice).render(plan, to: url) }
    try await Task.sleep(for: .milliseconds(100))
    task.cancel()
    await #expect(throws: CancellationError.self) { try await task.value }
  }
}

/// ACM journal layout (IMWUT, acmart): uppercase author block, contact
/// information, CCS concepts, running headers and a long reference list.
struct ACMPaperTests {
  static func document() throws -> SourceDocument {
    let url = try #require(Bundle.module.url(forResource: "acm_imwut", withExtension: "pdf", subdirectory: "Fixtures"))
    let pdf = try #require(PDFDocument(url: url))
    return PDFLayoutAnalyzer.document(from: PDFLineReader.lines(of: pdf), pageCount: pdf.pageCount, fallbackTitle: nil)
  }

  @Test func cutsFrontMatterReferencesAndTableCells() throws {
    let doc = try Self.document()
    let plan = Narrator.plan(doc, language: "en")
    let text = plan.text
    #expect(doc.title?.hasPrefix("E-Tactile Flow") == true)
    // authors, affiliations, contact information, keywords, citation format
    for gone in ["SHILONG", "Shilong Liu", "Shandong University", "@", "CCS Concepts", "Key Words", "ACM Reference Format", "Creative Commons"] {
      #expect(!text.contains(gone), "still reads \(gone)")
    }
    // references, acknowledgments, appendix, table cells, figure labels
    for gone in ["Ashburn", "Natural Science Foundation", "Flow Short Scale used", "The pulp of the left thumb", "Working Memory Set Load"] {
      #expect(!text.contains(gone), "still reads \(gone)")
    }
    // body text, list items, figure and table captions are kept
    for kept in ["Chronic or recurrent pain significantly impairs", "Attentional capture is often imposed", "Top-down tasks exhibit superior analgesic",
      "Figure 6c evaluates", "Schematic Diagram of the Positions", "This study provides initial empirical support"]
    {
      #expect(text.contains(kept), "lost \(kept)")
    }
    let headings = doc.blocks.compactMap { if case .heading(let t, _) = $0 { t } else { nil } }
    for h in ["Abstract", "Introduction", "Common Avenues for Pain Relief", "Pain Relief Based on Visual Distraction", "Results", "Conclusion"] {
      #expect(headings.contains(h), "missing heading \(h)")
    }
  }

  @Test func dumpStructure() throws {
    let doc = try Self.document()
    print("TITLE:", doc.title ?? "-")
    for b in doc.blocks {
      switch b {
      case .heading(let t, let l): print("H\(l):", t)
      case .figure(let n, let c): print("FIG \(n ?? ""):", c.prefix(70))
      case .table(let n, let c): print("TAB \(n ?? ""):", c.prefix(70))
      case .paragraph(let t): print("  P:", t.prefix(110))
      case .listItem(let t): print("  -", t.prefix(80))
      case .equation(let n): print("EQ", n ?? "-")
      }
    }
  }
}


struct PlainTextDocumentTests {
  @Test func headingsNeedNoBlankLine() {
    let doc = SourceDocument.fromPlainText("# Title\nIntro line.\n## Methods\nWe did it\nlike this.\n- one\n  more\n* two", fallbackTitle: "x", markdown: true)
    #expect(doc.title == "Title")
    #expect(doc.blocks == [
      .paragraph("Intro line."), .heading("Methods", level: 1), .paragraph("We did it like this."),
      .listItem("one more"), .listItem("two"),
    ])
  }

  @Test func plainTextKeepsWrappedLinesInTheirParagraph() {
    let doc = SourceDocument.fromPlainText("Er sagte\n- und dann ging er\n#1 war das.\n\n- point one\n\n    The next paragraph\nstarts here.", fallbackTitle: nil)
    #expect(doc.blocks == [
      .paragraph("Er sagte - und dann ging er #1 war das."), .listItem("point one"),
      .paragraph("The next paragraph starts here."),
    ])
  }

  @Test func hashWithoutSpaceIsNoHeading() {
    let doc = SourceDocument.fromPlainText("Intro.\n#1 is first.", fallbackTitle: nil, markdown: true)
    #expect(doc.blocks == [.paragraph("Intro. #1 is first.")])
  }

  @Test func decomposedUmlautsSurvive() {
    let doc = SourceDocument.fromPlainText("Mu\u{0308}ller", fallbackTitle: nil)
    #expect(doc.blocks == [.paragraph("Müller")])
    #expect(Narrator.plan(doc, language: "de").text.contains("Müller"))
  }
}
