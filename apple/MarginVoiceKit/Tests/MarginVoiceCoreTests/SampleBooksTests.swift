import Foundation
import Testing
@testable import MarginVoiceCore

struct SampleBooksTests {
  @Test func samplesAreBookPackagesWithUppercaseIds() {
    #expect(Set(SampleBooks.all.map(\.id)).count == 3)
    for s in SampleBooks.all {
      #expect(s.file.hasSuffix(".\(BookPackage.fileExtension)"))
      #expect(s.url.absoluteString == "\(SampleBooks.base)/\(s.file)")
    }
    #expect(SampleBooks.size(51_850_196) == "52 MB")
  }
}
