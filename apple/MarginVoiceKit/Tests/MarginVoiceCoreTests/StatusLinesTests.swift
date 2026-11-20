import Foundation
import Testing
@testable import MarginVoiceCore

/// The short status lines of the wide layouts: library rows, sync footer, listening pill.
struct StatusLinesTests {
  // MARK: Library rows (Mac sidebar)

  @Test func aBookInProgressShowsCommentsAndTimeLeft() {
    #expect(LibraryRowSummary.line(comments: 7, position: 1122, duration: 2987, isFinished: false) == "7 comments · 31 min left")
    #expect(LibraryRowSummary.line(comments: 1, position: 600, duration: 700, isFinished: false) == "1 comment · 2 min left")
    #expect(LibraryRowSummary.line(comments: 0, position: 600, duration: 4800, isFinished: false) == "1 h 10 min left")
  }

  @Test func anUnstartedBookShowsItsLength() {
    #expect(LibraryRowSummary.line(comments: 0, position: 0, duration: 3240, isFinished: false) == "Not started · 54 min")
    #expect(LibraryRowSummary.line(comments: 0, position: 0, duration: 20, isFinished: false) == "Not started · 1 min")
  }

  @Test func aFinishedBookShowsItsComments() {
    #expect(LibraryRowSummary.line(comments: 12, position: 3000, duration: 3000, isFinished: true) == "Finished · 12 comments")
    #expect(LibraryRowSummary.line(comments: 0, position: 3000, duration: 3000, isFinished: true) == "Finished")
  }

  // MARK: Sync footer

  private static let now = Date(timeIntervalSinceReferenceDate: 800_000_000)

  @Test func syncedSaysWhereAndHowLongAgo() {
    let line = SyncStatusLine.text(iCloud: true, webDAV: false, isSyncing: false, lastSync: Self.now.addingTimeInterval(-130), error: nil, now: Self.now)
    #expect(line == "Synced with iCloud 2 min ago")
    let both = SyncStatusLine.text(iCloud: true, webDAV: true, isSyncing: false, lastSync: Self.now.addingTimeInterval(-20), error: nil, now: Self.now)
    #expect(both == "Synced with iCloud and WebDAV just now")
    let old = SyncStatusLine.text(iCloud: false, webDAV: true, isSyncing: false, lastSync: Self.now.addingTimeInterval(-3 * 3600 - 5), error: nil, now: Self.now)
    #expect(old == "Synced with WebDAV 3 h ago")
    let days = SyncStatusLine.text(iCloud: true, webDAV: false, isSyncing: false, lastSync: Self.now.addingTimeInterval(-50 * 3600), error: nil, now: Self.now)
    #expect(days == "Synced with iCloud 2 d ago")
  }

  @Test func syncingAndFailuresComeFirst() {
    #expect(SyncStatusLine.text(iCloud: true, webDAV: false, isSyncing: true, lastSync: nil, error: "x", now: Self.now) == "Syncing with iCloud…")
    #expect(SyncStatusLine.text(iCloud: true, webDAV: false, isSyncing: false, lastSync: Self.now, error: "offline", now: Self.now) == "iCloud sync failed")
    #expect(SyncStatusLine.text(iCloud: true, webDAV: false, isSyncing: false, lastSync: nil, error: nil, now: Self.now) == "Not synced with iCloud yet")
  }

  @Test func noSyncNoLine() {
    #expect(SyncStatusLine.text(iCloud: false, webDAV: false, isSyncing: false, lastSync: Self.now, error: nil, now: Self.now) == nil)
  }

  // MARK: Listening pill

  @Test func thePillNamesTheMicrophoneWithoutTheQualityNote() {
    #expect(ListeningLabel.text(microphone: "AirPods Pro, high quality", echoCancellation: false, detailed: false) == "Listening · AirPods Pro")
    #expect(ListeningLabel.text(microphone: "Built-in microphone (book on Speaker)", echoCancellation: true, detailed: false) == "Listening · Built-in microphone")
  }

  @Test func theDetailedPillAddsEchoCancellation() {
    #expect(ListeningLabel.text(microphone: "MacBook Pro Microphone", echoCancellation: true, detailed: true) == "Listening · MacBook Pro Microphone, echo cancellation on")
    #expect(ListeningLabel.text(microphone: "AirPods Pro, call quality", echoCancellation: false, detailed: true) == "Listening · AirPods Pro, call quality")
  }

  @Test func noKnownMicrophoneIsJustListening() {
    #expect(ListeningLabel.text(microphone: "Not listening", echoCancellation: false, detailed: true) == "Listening")
    #expect(ListeningLabel.text(microphone: "", echoCancellation: false, detailed: false) == "Listening")
    #expect(ListeningLabel.text(microphone: "No microphone", echoCancellation: false, detailed: false) == "Listening")
  }
}
