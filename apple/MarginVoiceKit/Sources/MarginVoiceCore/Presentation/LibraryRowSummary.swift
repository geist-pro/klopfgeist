import Foundation

/// The second line of a book in the Mac library: "7 comments · 31 min left",
/// "Not started · 54 min", "Finished · 12 comments". Bookmarks are not counted.
public enum LibraryRowSummary {
  public static func line(comments: Int, position: Double, duration: Double, isFinished: Bool) -> String {
    let count = comments == 1 ? "1 comment" : "\(comments) comments"
    if isFinished { return comments > 0 ? "Finished · \(count)" : "Finished" }
    if position <= 1 { return "Not started · \(length(duration))" }
    let left = "\(length(max(0, duration - position))) left"
    return comments > 0 ? "\(count) · \(left)" : left
  }

  /// "54 min", "1 h 10 min"; at least a minute.
  static func length(_ seconds: Double) -> String {
    let minutes = max(1, Int((seconds / 60).rounded()))
    if minutes < 60 { return "\(minutes) min" }
    let rest = minutes % 60
    return rest == 0 ? "\(minutes / 60) h" : "\(minutes / 60) h \(rest) min"
  }
}
