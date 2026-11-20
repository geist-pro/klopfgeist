import Foundation

/// One word the recognizer heard, on the microphone timeline (seconds of microphone
/// audio since capture started).
public struct SpokenWord: Sendable, Equatable, Codable {
  public var text: String
  public var start: Double
  public var end: Double

  public init(_ text: String, start: Double, end: Double) {
    self.text = text
    self.start = start
    self.end = end
  }
}

/// A recognizer result. A volatile hypothesis replaces the previous volatile one; a
/// final one is appended to what is known for good.
public struct Hypothesis: Sendable, Equatable, Codable {
  public var words: [SpokenWord]
  public var isFinal: Bool
  /// The word times are only roughly right: SpeechAnalyzer times a volatile guess over
  /// the whole run heard since the last final result, silence included. A command in
  /// such a guess may fire, but is not rejected on its times until final words arrive.
  public var timesApproximate: Bool

  public init(words: [SpokenWord], isFinal: Bool, timesApproximate: Bool = false) {
    self.words = words
    self.isFinal = isFinal
    self.timesApproximate = timesApproximate
  }

  private enum CodingKeys: String, CodingKey { case words, isFinal, timesApproximate }

  /// Traces written before `timesApproximate` existed still load.
  public init(from decoder: any Decoder) throws {
    let c = try decoder.container(keyedBy: CodingKeys.self)
    words = try c.decode([SpokenWord].self, forKey: .words)
    isFinal = try c.decode(Bool.self, forKey: .isFinal)
    timesApproximate = try c.decodeIfPresent(Bool.self, forKey: .timesApproximate) ?? false
  }

  /// For recognizers that time a result but not its words: the words spread evenly
  /// over the range.
  public init(text: String, start: Double, end: Double, isFinal: Bool) {
    let parts = text.split(whereSeparator: \.isWhitespace).map(String.init)
    let step = parts.isEmpty ? 0 : max(0, end - start) / Double(parts.count)
    words = parts.enumerated().map { i, w in
      SpokenWord(w, start: start + Double(i) * step, end: start + Double(i + 1) * step)
    }
    self.isFinal = isFinal
    timesApproximate = false
  }
}

/// Turns what the recognizer hears into voice commands.
///
/// A command is a command phrase at the end of what was said, with a pause before it
/// and nothing after it. Both are read from the recognizer's word times, so it works
/// the same on a microphone the energy detector can't segment (AirPods gate and
/// level their signal). The energy detector only delays a decision while someone is
/// audibly still speaking, and never for long.
///
/// Every command phrase that stays at the end of what was said ends in exactly one
/// outcome: fired, or rejected with a reason the command log can show.
public struct CommandDetector: Sendable {
  public struct Policy: Sendable, Equatable, Codable {
    /// Pause needed between other speech and the command.
    public var pauseBefore: Double
    /// How long the command has to stay the last thing heard. Counted from when the
    /// recognizer first reported it, not from when it was said: a recognizer that
    /// runs behind would otherwise deliver "stop" already "settled", a moment
    /// before it delivers the words that followed.
    public var settle: Double
    /// Longest wait for audible speech to end before deciding anyway.
    public var maximumHold: Double
    /// A command phrase stretched over more than this is speech.
    public var maximumDuration: Double
    /// The same command again within this time is the same command.
    public var refractory: Double
    /// A command known only from a guess (not a final result) that speech audibly went on
    /// after waits for the final words. For comments: a recognizer that reports the start of
    /// a sentence first ("we should continue…") would otherwise end the comment inside it.
    public var waitsForFinalWords: Bool

    private enum CodingKeys: String, CodingKey { case pauseBefore, settle, maximumHold, maximumDuration, refractory, waitsForFinalWords }

    /// Policies written before `waitsForFinalWords` existed still load.
    public init(from decoder: Decoder) throws {
      let c = try decoder.container(keyedBy: CodingKeys.self)
      pauseBefore = try c.decode(Double.self, forKey: .pauseBefore)
      settle = try c.decode(Double.self, forKey: .settle)
      maximumHold = try c.decode(Double.self, forKey: .maximumHold)
      maximumDuration = try c.decode(Double.self, forKey: .maximumDuration)
      refractory = try c.decode(Double.self, forKey: .refractory)
      waitsForFinalWords = try c.decodeIfPresent(Bool.self, forKey: .waitsForFinalWords) ?? false
    }

    public init(
      pauseBefore: Double, settle: Double, maximumHold: Double = 1.2,
      maximumDuration: Double = 2, refractory: Double = 1, waitsForFinalWords: Bool = false
    ) {
      self.pauseBefore = pauseBefore
      self.settle = settle
      self.maximumHold = maximumHold
      self.maximumDuration = maximumDuration
      self.refractory = refractory
      self.waitsForFinalWords = waitsForFinalWords
    }

    /// The book plays or is paused: quick, since "stop" should land on the sentence.
    public static let listening = Policy(pauseBefore: 0.3, settle: 0.3)
    /// A comment is being spoken: "continue" in the middle of a sentence is not a
    /// command, so the silence after it is longer. The pause before it stays short:
    /// people close a comment with a natural sentence break ("This works. Continue."),
    /// about a third of a second in the field.
    public static let recording = Policy(pauseBefore: 0.3, settle: 0.6, waitsForFinalWords: true)
    /// The book is on a loudspeaker next to the microphone: what remains of it keeps
    /// the energy detector busy, so it is waited for only briefly.
    public static let listeningOverSpeaker = Policy(pauseBefore: 0.3, settle: 0.3, maximumHold: 0.6)
  }

  public struct Context: Sendable, Equatable {
    public var policy: Policy
    /// Commands that mean something right now; anything else is speech.
    public var allowed: Set<VoiceCommand>
    /// What the book narrated in the last few seconds, when the microphone can hear it.
    public var recentBookText: String?

    public init(policy: Policy, allowed: Set<VoiceCommand> = Set(VoiceCommand.allCases), recentBookText: String? = nil) {
      self.policy = policy
      self.allowed = allowed
      self.recentBookText = recentBookText
    }

    /// While a comment records, only these end it.
    public static let recordingCommands: Set<VoiceCommand> = [.resume, .cancel, .mark]
  }

  public struct Candidate: Sendable, Equatable {
    public let command: VoiceCommand
    public let text: String
    /// Where the command phrase starts on the microphone timeline.
    public let start: Double
    public let end: Double

    public init(command: VoiceCommand, text: String, start: Double, end: Double) {
      self.command = command
      self.text = text
      self.start = start
      self.end = end
    }
  }

  public enum Rejection: String, Sendable, Equatable, Codable {
    /// Said right after other words, without a pause.
    case notIsolated
    /// More words followed it.
    case speechContinued
    /// The book itself just said it.
    case bookEcho
    /// The same command was acted on a moment ago.
    case duplicate
    /// The phrase was drawn out like ordinary speech.
    case tooLong
  }

  public enum Outcome: Sendable, Equatable {
    case fire(Candidate)
    case rejected(String, Rejection)
  }

  public var languages: Set<VoiceCommand.Language>

  /// Words known for good, oldest first.
  private var finalWords: [SpokenWord] = []
  /// The recognizer's current guess at what follows them.
  private var volatileWords: [SpokenWord] = []
  /// The guess is timed over the whole run, not word by word.
  private var volatileApproximate = false
  /// Words that start before this have been decided.
  private var decidedUntil = -Double.infinity
  private var pending: Pending?
  private var speechActive = false
  /// When the energy detector last stopped hearing speech (microphone time).
  private var speechEndedAt = -Double.infinity
  private var lastFire: (command: VoiceCommand, at: Double)?

  private struct Pending: Sendable {
    var candidate: Candidate
    var pauseBefore: Double
    /// Some of its words are timed only approximately (a volatile guess timed over the
    /// whole run): rejections that depend on its times wait for final words.
    var isVolatile: Bool
    /// How long the command phrase itself took (without the repeats before it).
    var duration: Double
    /// When the recognizer first reported the phrase as it stands.
    var seen: Double
    /// Some of its words are still the recognizer's guess: more words may follow in the final result.
    var isGuess = false
  }

  private struct Token {
    let text: String
    let start: Double
    let end: Double
    let isVolatile: Bool
    let isGuess: Bool
  }

  /// Speech heard this long past a command's end means more was said (the energy detector's release is shorter).
  private static let speechTail: Double = 0.4
  /// How long heard words are kept for the pause before a command.
  private static let memory: Double = 12
  /// How long a command timed only approximately waits for final words before a
  /// rejection that depends on its times (too long, not isolated) is made anyway.
  private static let finalWait: Double = 2.5
  /// A command word said again within this gap is the same command repeated.
  private static let repeatGap: Double = 1

  public init(languages: Set<VoiceCommand.Language> = Set(VoiceCommand.Language.allCases)) {
    self.languages = languages
  }

  /// The microphone restarted (its timeline starts at zero again) or another book opened.
  public mutating func reset() {
    finalWords.removeAll()
    volatileWords.removeAll()
    volatileApproximate = false
    decidedUntil = -.infinity
    pending = nil
    speechActive = false
    speechEndedAt = -.infinity
    lastFire = nil
  }

  /// Nothing said before `time` can become a command any more (a comment begins here).
  public mutating func forget(before time: Double) {
    decidedUntil = max(decidedUntil, time)
    if let p = pending, p.candidate.start < time { pending = nil }
  }

  /// The energy detector hears speech (or stopped hearing it) at microphone time `time`.
  public mutating func speech(active: Bool, at time: Double? = nil) {
    speechActive = active
    if !active, let time { speechEndedAt = time }
  }

  /// A recognizer result arrived at microphone time `now`.
  public mutating func hypothesis(_ h: Hypothesis, now: Double, context: Context) -> [Outcome] {
    if h.isFinal {
      finalWords.append(contentsOf: h.words)
      finalWords.sort { $0.start < $1.start }
      // the guess it replaces: everything it covers
      let covered = h.words.map(\.end).max() ?? -.infinity
      volatileWords.removeAll { $0.start < covered }
      finalWords.removeAll { $0.end < now - Self.memory }
    } else {
      volatileWords = h.words
      volatileApproximate = h.timesApproximate
    }
    return evaluate(now: now, context: context)
  }

  /// Time passed; call a few times a second.
  public mutating func tick(now: Double, context: Context) -> [Outcome] {
    evaluate(now: now, context: context)
  }

  private mutating func evaluate(now: Double, context: Context) -> [Outcome] {
    var outcomes: [Outcome] = []
    let found = trailingCommand(allowed: context.allowed)

    // a command that was waiting and is no longer the end of what was said
    if let p = pending, found.map({ !isSame($0.candidate, p.candidate) }) ?? true {
      if stillHeard(after: p.candidate) {
        outcomes.append(.rejected(p.candidate.text, .speechContinued))
        decidedUntil = max(decidedUntil, p.candidate.end)
      }
      pending = nil
    }
    guard let found else { return outcomes }

    if var p = pending {
      // the same command in a newer result: re-timed, or grown ("mark" → "mark this")
      if found.candidate.end - p.candidate.end > 0.05 || found.candidate.text != p.candidate.text { p.seen = now }
      p.candidate = found.candidate
      p.pauseBefore = found.pauseBefore
      p.isVolatile = found.isVolatile
      p.duration = found.duration
      p.isGuess = found.isGuess
      pending = p
    } else {
      pending = Pending(
        candidate: found.candidate, pauseBefore: found.pauseBefore, isVolatile: found.isVolatile, duration: found.duration, seen: now,
        isGuess: found.isGuess)
    }
    guard let p = pending, isSettled(p, now: now, policy: context.policy) else { return outcomes }

    let c = p.candidate
    // a guess that speech went on after: its final words decide whether it was the end of what was said
    let spokenOn = speechActive || speechEndedAt > c.end + Self.speechTail
    if context.policy.waitsForFinalWords, p.isGuess, spokenOn, now - p.seen < Self.finalWait { return outcomes }
    // the command just acted on, said again: whatever its timing, it is that one
    let isDuplicate = lastFire.map { $0.command == c.command && c.start - $0.at < context.policy.refractory } ?? false
    let tooLong = p.duration > context.policy.maximumDuration
    let notIsolated = p.pauseBefore < context.policy.pauseBefore
    // an approximately timed guess: its final words decide
    if !isDuplicate, p.isVolatile, tooLong || notIsolated, now - p.seen < Self.finalWait { return outcomes }

    pending = nil
    decidedUntil = max(decidedUntil, c.end)
    if isDuplicate {
      outcomes.append(.rejected(c.text, .duplicate))
    } else if tooLong {
      outcomes.append(.rejected(c.text, .tooLong))
    } else if notIsolated {
      outcomes.append(.rejected(c.text, .notIsolated))
    } else if let book = context.recentBookText, Self.isBookEcho(utterance: c.text, recentBookText: book) {
      outcomes.append(.rejected(c.text, .bookEcho))
    } else {
      lastFire = (c.command, c.end)
      outcomes.append(.fire(c))
    }
    return outcomes
  }

  private func isSettled(_ p: Pending, now: Double, policy: Policy) -> Bool {
    guard now - p.seen >= policy.settle, now - p.candidate.end >= policy.settle else { return false }
    // someone is audibly still speaking: wait, but not for long (noise looks like speech too)
    if speechActive, now - p.seen < policy.maximumHold { return false }
    return true
  }

  private func isSame(_ a: Candidate, _ b: Candidate) -> Bool {
    a.command == b.command && abs(a.start - b.start) < 0.5
  }

  /// Whether the recognizer still reports words where the candidate was (as opposed
  /// to having withdrawn the whole guess, which is not worth a log line).
  private func stillHeard(after c: Candidate) -> Bool {
    (finalWords + volatileWords).contains { $0.end > c.end + 0.01 }
  }

  /// The command phrase at the very end of what was heard, if there is one.
  private func trailingCommand(allowed: Set<VoiceCommand>) -> (candidate: Candidate, pauseBefore: Double, isVolatile: Bool, duration: Double, isGuess: Bool)? {
    var spoken: [Token] = []
    let heard = finalWords.map { ($0, false, false) } + volatileWords.map { ($0, volatileApproximate, true) }
    for (word, isVolatile, isGuess) in heard.sorted(by: { $0.0.start < $1.0.start }) {
      let parts = TextSegmentation.tokens(word.text)
      // a recognizer run of several words: spread them over the run
      let step = (word.end - word.start) / Double(max(1, parts.count))
      for (i, part) in parts.enumerated() where !VoiceCommand.fillers.contains(part) {
        spoken.append(
          Token(text: part, start: word.start + Double(i) * step, end: word.start + Double(i + 1) * step, isVolatile: isVolatile, isGuess: isGuess))
      }
    }
    for n in [2, 1] where spoken.count >= n {
      let first = spoken.count - n
      // every word of the phrase is still undecided (a final result may re-time a word a little)
      guard spoken[first].start >= decidedUntil - 0.05 else { continue }
      let text = spoken[first...].map(\.text).joined(separator: " ")
      guard let command = VoiceCommand.parse(text, languages: languages) else { continue }
      guard allowed.contains(command) else { return nil }
      // "stop, stop": a word repeated because nothing happened yet is one command, timed
      // from the first (a late recognizer delivers both at once)
      var head = first
      while n == 1, head > 0, spoken[head - 1].start >= decidedUntil - 0.05,
        spoken[head].start - spoken[head - 1].end < Self.repeatGap,
        VoiceCommand.parse(spoken[head - 1].text, languages: languages) == command
      {
        head -= 1
      }
      let start = spoken[head].start
      let pause = head > 0 ? start - spoken[head - 1].end : .infinity
      let isVolatile = spoken[head...].contains(where: \.isVolatile)
      let isGuess = spoken[head...].contains(where: \.isGuess)
      let end = spoken[spoken.count - 1].end
      return (Candidate(command: command, text: text, start: start, end: end), pause, isVolatile, end - spoken[first].start, isGuess)
    }
    return nil
  }

  /// True when every word of the utterance was just narrated by the book.
  public static func isBookEcho(utterance: String, recentBookText: String) -> Bool {
    let spoken = Set(TextSegmentation.tokens(recentBookText))
    let said = TextSegmentation.tokens(utterance).filter { !VoiceCommand.fillers.contains($0) }
    return !said.isEmpty && said.allSatisfy(spoken.contains)
  }
}
