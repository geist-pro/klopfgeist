import Testing
@testable import MarginVoiceCore

struct TimeTextMapTests {
  @Test(.enabled(if: Fixture.hasPrivate)) func matchesAllEnglishChapters() throws {
    let text = try Fixture.text("icra")
    let ch = try Fixture.chapters("icra")
    let matches = ChapterMatcher.match(chapters: ch.chapters, text: text)
    #expect(matches.count == ch.chapters.count)
    let intro = try #require(ch.chapters.firstIndex { $0.title == "Introduction" })
    let m = try #require(matches.first { $0.chapterIndex == intro })
    #expect(text.substring(m.textOffset..<(m.textOffset + 22)) == "Section: Introduction.")
  }

  @Test(.enabled(if: Fixture.hasPrivate)) func matchesGermanChapters() throws {
    let text = try Fixture.text("hace_de")
    let ch = try Fixture.chapters("hace_de")
    let matches = ChapterMatcher.match(chapters: ch.chapters, text: text)
    #expect(matches.count >= ch.chapters.count - 1)
  }

  @Test(.enabled(if: Fixture.hasPrivate)) func mapIsMonotonicAndInvertible() throws {
    let text = try Fixture.text("icra")
    let ch = try Fixture.chapters("icra")
    let map = TimeTextMap.fromChapters(ch.chapters, text: text, duration: ch.duration)
    var last = -1
    for t in stride(from: 0.0, through: ch.duration, by: 30) {
      let o = map.offset(at: t)
      #expect(o >= last)
      last = o
      #expect(abs(map.time(at: o) - t) < 1)
    }
    #expect(map.offset(at: ch.duration) == text.count)
  }

  @Test func sidecarIsExact() throws {
    let json = #"{"version":1,"chunks":[{"start":0,"end":2,"char_start":0,"char_end":10},{"start":2.5,"end":5,"char_start":11,"char_end":30}]}"#
    let sidecar = try TimingSidecar.decode(Data(json.utf8))
    let map = TimeTextMap.fromSidecar(sidecar, textLength: 30, duration: 5)
    #expect(map.isExact)
    #expect(map.offset(at: 1) == 5)
    #expect(map.offset(at: 2.5) == 11)
  }

  @Test func sidecarConvertsCodePoints() {
    // "é" written as e + combining accent is two code points, one Character
    let text = NarrationText("Cafe\u{301} ok")
    let s = TimingSidecar(chunks: [.init(start: 0, end: 1, charStart: 6, charEnd: 8)])
    let c = s.convertedToCharacterOffsets(in: text).chunks[0]
    #expect(c.charStart == 5 && c.charEnd == 7)
  }
}

import Foundation
