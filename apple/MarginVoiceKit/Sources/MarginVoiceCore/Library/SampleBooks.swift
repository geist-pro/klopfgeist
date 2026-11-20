import Foundation

/// Narrated papers to try the app with, as `.drafttalk` files on the site's "samples" release.
/// The ids are the books' ids, so a downloaded sample shows as already in the library.
public enum SampleBooks {
  public struct Sample: Identifiable, Sendable {
    public let id: UUID
    public let title: String
    public let credit: String
    public let duration: Double
    public let bytes: Int64
    public let file: String
    public var url: URL { URL(string: "\(SampleBooks.base)/\(file)")! }
  }

  public static let base = "https://github.com/kkai/marginvoice/releases/download/samples"

  public static let all: [Sample] = [
    Sample(
      id: UUID(uuidString: "5917169A-29A3-4208-84D2-2F9B303288F5")!, title: "Cuddle-Fish: Exploring a Soft Floating Robot with Flapping Wings for Physical Interactions",
      credit: "Xu et al., Augmented Humans 2025", duration: 3331, bytes: 51_850_196, file: "cuddle-fish.drafttalk"),
    Sample(
      id: UUID(uuidString: "4EF2CA9C-2240-4BDA-9F71-5135C549E9A0")!, title: "Human–Computer Co-Regulation: A Review of Biofeedback for Meditative Practice",
      credit: "Liu, Peng et al., IMWUT 2026", duration: 3721, bytes: 48_722_457, file: "co-regulation.drafttalk"),
    Sample(
      id: UUID(uuidString: "BABAFA6B-2813-44DB-9259-418526B704E6")!, title: "NASA-Task Load Index in CHI: A Comprehensive Review and Subscale Meta-Analysis",
      credit: "Lee, Starner et al., TOCHI 2026", duration: 6816, bytes: 88_156_758, file: "nasa-tlx.drafttalk"),
  ]

  /// "52 MB"
  public static func size(_ bytes: Int64) -> String { "\((bytes + 500_000) / 1_000_000) MB" }
}
