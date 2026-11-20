import Foundation
import Testing
@testable import MarginVoiceCore

struct BookFilesTests {
  func urls(_ names: String...) -> [URL] { names.map { URL(fileURLWithPath: "/inbox/\($0)") } }

  @Test func stemsPairSidecars() {
    #expect(BookFiles.stem(of: URL(fileURLWithPath: "/a/My Book.m4b")) == "My Book")
    #expect(BookFiles.stem(of: URL(fileURLWithPath: "/a/My Book.narration.txt")) == "My Book")
    #expect(BookFiles.stem(of: URL(fileURLWithPath: "/a/My Book.timing.json")) == "My Book")
    #expect(BookFiles.stem(of: URL(fileURLWithPath: "/a/v1.2.txt")) == "v1.2")
  }

  @Test func groupsOneBookPerAudioFile() {
    let (books, orphans) = BookFiles.group(urls("b.m4b", "b.narration.txt", "b.timing.json", "a.MP3", "c.txt"))
    #expect(books.map(\.stem) == ["a", "b"])
    #expect(books[0].text == nil && books[0].timing == nil)
    #expect(books[1].text?.lastPathComponent == "b.narration.txt")
    #expect(books[1].timing?.lastPathComponent == "b.timing.json")
    #expect(orphans == ["c"])
    // doc2audio's narration wins over a plain text of the same name
    #expect(BookFiles.group(urls("d.txt", "d.m4b", "d.narration.txt")).books[0].text?.lastPathComponent == "d.narration.txt")
  }

  @Test func inboxSeparatesAudiobooksFromDocuments() {
    let (audiobook, documents) = BookFiles.sortInbox(urls("x.m4b", "x.txt", "paper.pdf", "notes.md", "song.flac", "a.txt"))
    #expect(Set(audiobook.map(\.lastPathComponent)) == ["x.m4b", "x.txt"])
    // a .txt next to an audiobook is its narration, not a document to convert
    #expect(documents.map(\.lastPathComponent) == ["a.txt", "notes.md", "paper.pdf"])
  }

  @Test func safeExportNames() {
    #expect(BookFiles.safeName("Projektskizze: WallBOT / v2") == "Projektskizze--WallBOT---v2")
    #expect(BookFiles.safeName("  Ä Ö ü  ") == "Ä-Ö-ü")
    #expect(BookFiles.safeName(String(repeating: "x", count: 80)).count == 60)
  }

  @Test func clockSurvivesInvalidTimes() {
    #expect(TimeFormat.clock(.nan) == "0:00")
    #expect(TimeFormat.clock(.infinity) == "0:00")
    #expect(TimeFormat.clock(3723) == "1:02:03")
  }

  @Test func csvQuotesLineBreaksAndDefusesFormulas() {
    #expect(CommentExport.quote("a\r\nb") == "\"a\r\nb\"")
    #expect(CommentExport.quote("=SUM(A1)") == "'=SUM(A1)")
    #expect(CommentExport.quote("plain") == "plain")
  }

  @Test func languageCodes() {
    #expect(VoiceCommand.Language(code: "de") == .german)
    #expect(VoiceCommand.Language(code: "de-AT") == .german)
    #expect(VoiceCommand.Language(code: "fr") == .english)
    #expect(VoiceCommand.Language(code: nil) == .english)
    #expect(VoiceCommand.Language.german.regionIdentifier == "de-DE")
  }

  @Test func detectsGermanAndEnglish() {
    #expect(VoiceCommand.Language.detect("Die Ergebnisse zeigen, dass der Roboter die Wand schneller reinigt als bisher.") == .german)
    #expect(VoiceCommand.Language.detect("The results show that the robot cleans the wall faster than before.") == .english)
  }

  @Test func chapterAtTime() {
    let chapters = [ChapterMark(title: "A", start: 0, end: 10), ChapterMark(title: "B", start: 10, end: 20)]
    #expect(chapters.index(at: 5) == 0)
    #expect(chapters.index(at: 9.96) == 1)
    #expect(chapters.index(at: 25) == 1)
    #expect([ChapterMark]().index(at: 3) == nil)
  }

  @Test func sentenceNudges() {
    let text = NarrationText("One a. Two b. Three c.\n")
    let afterOne = text.sentences[0].range.upperBound
    #expect(CommentRecord.nudgedOffset(afterOne, by: 0, in: text) == afterOne)
    #expect(CommentRecord.nudgedOffset(afterOne, by: 1, in: text) == text.sentences[1].range.upperBound)
    // clamped at both ends
    #expect(CommentRecord.nudgedOffset(afterOne, by: 9, in: text) == text.sentences[2].range.upperBound)
    #expect(CommentRecord.nudgedOffset(afterOne, by: -9, in: text) == text.sentences[0].range.upperBound)
    #expect(CommentRecord.nudgedOffset(42, by: 1, in: nil) == 42)
  }

  @Test func passageEndFollowsRefinedStart() {
    #expect(CommentRecord.passageEnd(estimatedEnd: 100, estimatedStart: 40, refinedStart: nil) == 100)
    #expect(CommentRecord.passageEnd(estimatedEnd: 100, estimatedStart: 40, refinedStart: 50) == 110)
    // never ends before it starts
    #expect(CommentRecord.passageEnd(estimatedEnd: 30, estimatedStart: 40, refinedStart: 50) == 50)
  }
}
