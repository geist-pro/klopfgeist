import Foundation
import Testing
@testable import MarginVoiceCore

/// Runs `spec/vectors/audio-plan.json`; the Kotlin core runs the same file.
struct AudioPlanVectorTests {
  struct File: Decodable { let cases: [Case] }

  struct Case: Decodable, Sendable, CustomTestStringConvertible {
    let name: String
    let needs: AudioNeeds
    let route: AudioRoute
    let echoCancellationAllowed: Bool?
    let plan: AudioPlan
    var testDescription: String { name }
  }

  static let cases = (try? SpecVectors.load("audio-plan", as: File.self).cases) ?? []

  @Test func theVectorFileLoads() throws {
    #expect(try SpecVectors.load("audio-plan", as: File.self).cases.count >= 16)
  }

  @Test(arguments: cases)
  func conformsTo(_ c: Case) {
    #expect(AudioPlan.make(needs: c.needs, route: c.route, echoCancellationAllowed: c.echoCancellationAllowed ?? true) == c.plan, "\(c.name)")
  }
}

struct AudioCoordinatorTests {
  static let speaker = AudioRoute.builtIn
  static let airPods = AudioRoute(outputs: [.bluetoothA2DP], input: .bluetoothLE, headsetMicrophoneAvailable: true)

  static func needs(played: Bool = true, recording: Bool = false) -> AudioNeeds {
    var n = AudioNeeds()
    n.bookOpen = true
    n.voiceCommands = true
    n.microphonePermission = true
    n.hasPlayed = played
    n.recording = recording
    return n
  }

  /// Applies whatever the coordinator asks for, like the adapter does.
  @discardableResult
  static func apply(_ action: AudioCoordinator.Action?, to c: inout AudioCoordinator, now: Double) -> AudioCoordinator.Action? {
    if let action { c.didApply(action.plan, now: now) }
    return action
  }

  @Test func openingABookBuildsTheMicrophoneOnce() {
    var c = AudioCoordinator()
    let first = Self.apply(c.needsChanged(Self.needs(played: false), now: 0), to: &c, now: 0)
    #expect(first?.change == .capture)
    #expect(first?.plan.echoCancellation == false)
    // nothing changed: nothing to do, however often it is asked
    #expect(c.needsChanged(Self.needs(played: false), now: 1) == nil)
    #expect(c.tick(route: Self.speaker, now: 2) == nil)
  }

  @Test func firstPlayOnTheSpeakerTurnsEchoCancellationOn() {
    var c = AudioCoordinator()
    Self.apply(c.needsChanged(Self.needs(played: false), now: 0), to: &c, now: 0)
    let action = c.needsChanged(Self.needs(), now: 5)
    #expect(action?.plan.echoCancellation == true)
    #expect(action?.change == .capture)
  }

  @Test func playingOnAirPodsRebuildsNothing() {
    var c = AudioCoordinator()
    _ = c.routeChanged(Self.airPods, reason: .other, now: 0)
    Self.apply(c.needsChanged(Self.needs(played: false), now: 1), to: &c, now: 1)
    // the old app rebuilt the microphone here, and a "stop" said right after Play was lost
    let action = c.needsChanged(Self.needs(), now: 5)
    #expect(action?.change == .session)
    #expect(action?.plan.capture == true)
  }

  @Test func aRouteThatKeepsMovingIsActedOnOnce() {
    var c = AudioCoordinator()
    Self.apply(c.needsChanged(Self.needs(), now: 0), to: &c, now: 0)
    #expect(c.applied.echoCancellation)
    let half = AudioRoute(outputs: [.bluetoothA2DP], input: .builtInMic, headsetMicrophoneAvailable: true)
    // AirPods arrive: echo cancellation goes at once, so they can take the book
    let uncover = Self.apply(c.routeChanged(half, reason: .deviceArrived, now: 10), to: &c, now: 10)
    #expect(uncover?.plan.echoCancellation == false)
    #expect(uncover?.plan.capture == true)
    // …and the rest of the switching is waited out
    #expect(c.routeChanged(Self.airPods, reason: .other, now: 10.2) == nil)
    #expect(c.routeChanged(Self.airPods, reason: .other, now: 10.5) == nil)
    #expect(c.tick(route: Self.airPods, now: 10.9) == nil)
    let settled = c.tick(route: Self.airPods, now: 11.2)
    // the plan for AirPods is what is already applied apart from what the recognizer may hear
    #expect(settled?.change ?? AudioPlan.Change.none == AudioPlan.Change.none)
    #expect(settled?.plan.bookAudible ?? false == false)
  }

  @Test func headphonesLeavingBringsEchoCancellationBackAfterTheRouteSettles() {
    var c = AudioCoordinator()
    _ = c.routeChanged(Self.airPods, reason: .other, now: 0)
    Self.apply(c.needsChanged(Self.needs(), now: 1), to: &c, now: 1)
    #expect(!c.applied.echoCancellation)
    #expect(c.routeChanged(Self.speaker, reason: .deviceLeft, now: 20) == nil)
    #expect(c.tick(route: Self.speaker, now: 20.3) == nil)
    let action = c.tick(route: Self.speaker, now: 20.7)
    #expect(action?.plan.echoCancellation == true)
    #expect(action?.change == .capture)
  }

  @Test func aCommentKeepsItsMicrophone() {
    var c = AudioCoordinator()
    Self.apply(c.needsChanged(Self.needs(), now: 0), to: &c, now: 0)
    Self.apply(c.needsChanged(Self.needs(recording: true), now: 5), to: &c, now: 5)
    // headphones connect in the middle of the comment
    #expect(c.routeChanged(Self.airPods, reason: .deviceArrived, now: 6) == nil)
    let during = Self.apply(c.tick(route: Self.airPods, now: 7), to: &c, now: 7)
    #expect(during?.change ?? AudioPlan.Change.none < AudioPlan.Change.capture)
    #expect(c.applied.echoCancellation)
    #expect(c.tick(route: Self.airPods, now: 9) == nil)
    // the comment is saved: now the microphone is chosen again
    let after = c.needsChanged(Self.needs(), now: 12)
    #expect(after?.change == .capture)
    #expect(after?.plan.echoCancellation == false)
  }

  @Test func aFlappingRouteStopsTogglingEchoCancellation() {
    var c = AudioCoordinator()
    var now = 0.0
    Self.apply(c.needsChanged(Self.needs(), now: now), to: &c, now: now)
    for _ in 0..<5 {
      now += 1
      Self.apply(c.routeChanged(Self.airPods, reason: .deviceArrived, now: now), to: &c, now: now)
      now += 1
      Self.apply(c.tick(route: Self.airPods, now: now), to: &c, now: now)
      now += 1
      _ = c.routeChanged(Self.speaker, reason: .deviceLeft, now: now)
      now += 1
      Self.apply(c.tick(route: Self.speaker, now: now), to: &c, now: now)
    }
    #expect(!c.echoCancellationAllowed)
    #expect(!c.applied.echoCancellation)
    #expect(c.applied.capture)
    // a new book starts with a clean slate
    var closed = AudioNeeds()
    closed.bookOpen = false
    Self.apply(c.needsChanged(closed, now: now + 1), to: &c, now: now + 1)
    #expect(c.echoCancellationAllowed)
  }

  @Test func aRouteThatMovedWithoutNotificationIsNoticed() {
    var c = AudioCoordinator()
    Self.apply(c.needsChanged(Self.needs(), now: 0), to: &c, now: 0)
    #expect(c.tick(route: Self.airPods, now: 5) == nil)
    let action = c.tick(route: Self.airPods, now: 5.7)
    #expect(action?.plan.echoCancellation == false)
  }
}
