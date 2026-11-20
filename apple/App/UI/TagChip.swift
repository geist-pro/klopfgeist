import MarginVoiceCore
import SwiftUI

/// A comment's review tag ("Weakness"), in the tag's colour.
struct TagChip: View {
  let tag: ReviewTag
  var language: VoiceCommand.Language = .english

  var body: some View {
    TagOutline(text: tag.displayName(language), tint: Theme.tagTint(tag))
  }
}

/// A word in a thin outline of its colour (review tags, "Bookmark").
struct TagOutline: View {
  let text: String
  let tint: Color

  var body: some View {
    Text(text)
      .font(.caption.weight(.bold))
      .lineLimit(1)
      .padding(.horizontal, 6)
      .padding(.vertical, 1)
      .overlay { RoundedRectangle(cornerRadius: Theme.controlRadius).strokeBorder(tint, lineWidth: 1) }
      .foregroundStyle(tint)
  }
}

/// Which comments a list shows: all, the untagged ones, or one tag.
enum TagFilter: Hashable {
  case all, untagged, tag(ReviewTag)

  func includes(_ comment: Comment) -> Bool {
    switch self {
    case .all: true
    case .untagged: comment.tag == nil
    case .tag(let t): comment.tag == t
    }
  }

  func label(_ language: VoiceCommand.Language) -> String {
    switch self {
    case .all: language == .german ? "Alle Tags" : "All tags"
    case .untagged: language == .german ? "Ohne Tag" : "Untagged"
    case .tag(let t): t.displayName(language)
    }
  }

  static var all_: [TagFilter] { [.all, .untagged] + ReviewTag.allCases.map { .tag($0) } }
}
