import Foundation

/// Three-way merge of one kind of item (books, or the comments of a book) between this
/// device and the sync store. `base` is what both sides held after the last sync (the
/// content hash per id), so a missing item tells a deletion from a new item without
/// tombstones:
///
/// - changed on one side only: that side wins;
/// - changed on both: the newer `updatedAt` wins (ties: the larger hash, so every device picks the same);
/// - deleted on one side, unchanged on the other: deleted;
/// - deleted on one side, edited on the other: the edit survives (no comment is lost).
public enum SyncMerge {
  public struct Item<T: Sendable>: Sendable {
    public let value: T
    public let hash: String
    public let updatedAt: Date

    public init(value: T, hash: String, updatedAt: Date) {
      self.value = value
      self.hash = hash
      self.updatedAt = updatedAt
    }
  }

  public enum Action<T: Sendable>: Sendable {
    case putLocal(String, T)
    case deleteLocal(String)
    case putRemote(String, T)
    case deleteRemote(String)
  }

  enum Side { case none, local, remote }

  public struct Result<T: Sendable>: Sendable {
    public let actions: [Action<T>]
    public let base: [String: String]
  }

  public static func merge<T>(base: [String: String], local: [String: Item<T>], remote: [String: Item<T>]) -> Result<T> {
    var actions: [Action<T>] = []
    var next: [String: String] = [:]
    for id in Set(base.keys).union(local.keys).union(remote.keys).sorted() {
      let b = base[id]
      switch (local[id], remote[id]) {
      case (nil, nil):
        break
      case (let l?, let r?):
        let winner: Side =
          if l.hash == r.hash { .none }
          else if l.hash == b { .remote }
          else if r.hash == b { .local }
          else if r.updatedAt != l.updatedAt { r.updatedAt > l.updatedAt ? .remote : .local }
          else { r.hash > l.hash ? .remote : .local }
        switch winner {
        case .remote:
          actions.append(.putLocal(id, r.value))
          next[id] = r.hash
        case .local:
          actions.append(.putRemote(id, l.value))
          next[id] = l.hash
        case .none:
          next[id] = l.hash
        }
      case (let l?, nil):
        if let b, b == l.hash {
          actions.append(.deleteLocal(id))
        } else {
          actions.append(.putRemote(id, l.value))
          next[id] = l.hash
        }
      case (nil, let r?):
        if let b, b == r.hash {
          actions.append(.deleteRemote(id))
        } else {
          actions.append(.putLocal(id, r.value))
          next[id] = r.hash
        }
      }
    }
    return Result(actions: actions, base: next)
  }
}
