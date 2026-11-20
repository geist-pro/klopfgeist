import Foundation
import MarginVoiceCore
import MarginVoiceSession
import SwiftData

/// The open book as the review session sees it: where it plays from, what it just
/// narrated, and where comments and bookmarks go (the SwiftData library).
@MainActor
final class SessionBook: ReviewBook {
  let book: Book
  /// The narration text; loaded after the audio, so the book can play at once.
  var content: BookContent?
  private let context: ModelContext
  private let store: BookStore
  private let processor: CommentProcessor
  /// The bookmark a second "bookmark" would extend to a passage.
  private var openBookmark: Comment?

  init(book: Book, content: BookContent? = nil, context: ModelContext, store: BookStore, processor: CommentProcessor) {
    self.book = book
    self.content = content
    self.context = context
    self.store = store
    self.processor = processor
  }

  var title: String { book.title }
  var audioURL: URL { store.audioURL(book) }
  var position: Double { book.position }
  var rate: Double { book.rate }

  func chapterTitle(at time: Double) -> String? { book.chapterTitle(at: time) }

  func recentText(before time: Double, seconds: Double) -> String? {
    content?.recentText(before: time, seconds: seconds)
  }

  func sentenceStart(at time: Double) -> Double? {
    content?.sentenceStartTime(at: time)
  }

  // MARK: Recordings

  func newRecordingURL() -> URL? {
    let dir = store.commentsURL(book)
    do {
      try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    } catch {
      return nil
    }
    return dir.appendingPathComponent("comment-\(Int(Date().timeIntervalSince1970 * 1000)).m4a")
  }

  func discardRecording(_ url: URL) {
    try? FileManager.default.removeItem(at: url)
  }

  // MARK: Comments and bookmarks

  func addComment(at time: Double, audio: URL, duration: Double) -> Bool {
    add(kind: .comment, at: time, audioFile: audio.lastPathComponent, duration: duration).saved
  }

  func addBookmark(at time: Double) -> Bool {
    let (bookmark, saved) = add(kind: .bookmark, at: time, audioFile: nil, duration: 0)
    openBookmark = bookmark
    return saved
  }

  func closeBookmark(at time: Double) -> BookmarkClose {
    // deleted meanwhile: nothing to extend, and nothing that failed to save
    guard let bookmark = openBookmark, CommentProcessor.exists(bookmark) else { return .missing }
    bookmark.endMediaTime = time
    bookmark.endEstimatedOffset = content?.offset(at: time)
    openBookmark = nil
    return context.saveLogged("passage end") ? .closed : .notSaved
  }

  private func add(kind: CommentRecord.Kind, at time: Double, audioFile: String?, duration: Double) -> (comment: Comment, saved: Bool) {
    let offset = content?.offset(at: time)
    let confidence: Double = content.map { $0.isExact ? 0.8 : 0.3 } ?? 0
    let comment = Comment(
      kind: kind, mediaTime: time, estimatedOffset: offset, confidence: confidence, chapter: book.chapterTitle(at: time),
      audioFile: audioFile, duration: duration)
    context.insert(comment)
    comment.book = book
    let saved = context.saveLogged(kind == .comment ? "comment" : "bookmark")
    // transcribes the recording and pins the comment to the text
    let processor = processor
    Task { await processor.process(comment) }
    return (comment, saved)
  }

  // MARK: Position

  func savePosition(_ time: Double) -> Bool {
    book.position = time
    return context.saveLogged("position")
  }

  func saveRate(_ rate: Double) {
    book.rate = rate
  }
}
