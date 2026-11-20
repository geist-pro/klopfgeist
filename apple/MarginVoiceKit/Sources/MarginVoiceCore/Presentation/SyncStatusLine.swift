import Foundation

/// The sync footer of the Mac library: "Synced with iCloud 2 min ago". Nil when no sync is on.
public enum SyncStatusLine {
  public static func text(iCloud: Bool, webDAV: Bool, isSyncing: Bool, lastSync: Date?, error: String?, now: Date) -> String? {
    let place: String
    switch (iCloud, webDAV) {
    case (true, true): place = "iCloud and WebDAV"
    case (true, false): place = "iCloud"
    case (false, true): place = "WebDAV"
    case (false, false): return nil
    }
    if isSyncing { return "Syncing with \(place)…" }
    if error != nil { return "\(place) sync failed" }
    guard let lastSync else { return "Not synced with \(place) yet" }
    return "Synced with \(place) \(ago(now.timeIntervalSince(lastSync)))"
  }

  static func ago(_ seconds: Double) -> String {
    let s = max(0, Int(seconds))
    if s < 60 { return "just now" }
    if s < 3600 { return "\(s / 60) min ago" }
    if s < 86_400 { return "\(s / 3600) h ago" }
    return "\(s / 86_400) d ago"
  }
}
