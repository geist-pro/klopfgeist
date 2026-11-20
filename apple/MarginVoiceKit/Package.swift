// swift-tools-version: 6.2
import PackageDescription

// Layering (each target may only depend on the ones above it):
//   MarginVoiceCore     pure Swift, no Apple UI/audio frameworks: runs under `swift test`
//   MarginVoiceSession  the review session against ports (protocols), driven by a virtual clock in tests
//   MarginVoiceApple    the session's ports on AVFoundation, Speech, MediaPlayer and CoreAudio
//                       (no SwiftData, no SwiftUI: persistence and screens live in the app targets)
let package = Package(
  name: "MarginVoiceKit",
  platforms: [.iOS(.v26), .macOS(.v26)],
  products: [
    .library(name: "MarginVoiceCore", targets: ["MarginVoiceCore"]),
    .library(name: "MarginVoiceSession", targets: ["MarginVoiceSession"]),
    .library(name: "MarginVoiceApple", targets: ["MarginVoiceApple"]),
  ],
  targets: [
    .target(name: "MarginVoiceCore"),
    .target(name: "MarginVoiceSession", dependencies: ["MarginVoiceCore"]),
    .target(name: "MarginVoiceApple", dependencies: ["MarginVoiceSession", "MarginVoiceCore"]),
    .testTarget(name: "MarginVoiceSessionTests", dependencies: ["MarginVoiceSession"]),
    .testTarget(name: "MarginVoiceAppleTests", dependencies: ["MarginVoiceApple"]),
    .testTarget(
      name: "MarginVoiceCoreTests",
      dependencies: ["MarginVoiceCore"],
      resources: [.copy("Fixtures")]
    ),
  ],
  swiftLanguageModes: [.v6]
)
