import AVFAudio
#if os(iOS)
  import BackgroundTasks
#endif
import MarginVoiceCore
import Foundation
import Observation
import SwiftData
import os

/// Converts documents to audiobooks on device: extract → narrate → speak → book.
///
/// Conversions keep running when the app is backgrounded through a continued
/// processing task (iOS 26), which shows its progress in a Live Activity.
@MainActor
@Observable
final class ConversionCenter {
  struct Job: Identifiable {
    enum Phase: Equatable {
      case reading
      /// A neural voice's model loads (downloading it the first time).
      case preparingVoice
      case speaking
      case finished(UUID)
      case failed(String)
      case cancelled
    }

    let id = UUID()
    var title: String
    var phase: Phase = .reading
    var progress: Double = 0
    var isActive: Bool { phase == .reading || phase == .preparingVoice || phase == .speaking }
  }

  /// A document read and narrated, waiting for the user to confirm voice and title.
  struct Draft: Identifiable {
    let id = UUID()
    let sourceURL: URL
    var title: String
    var language: String
    let document: SourceDocument
    let pageCount: Int
    let usedOCR: Bool
    var plan: Narrator.Plan
  }

  nonisolated static let taskPrefix = "de.kaikunze.drafttalk.convert"
  private static let log = Logger.drafttalk("convert")

  private(set) var jobs: [Job] = []
  @ObservationIgnored private var tasks: [UUID: Task<Void, Never>] = [:]
  @ObservationIgnored private var renderers: [UUID: SpeechRenderer] = [:]
  #if os(iOS)
    @ObservationIgnored private var backgroundTasks: [String: BGContinuedProcessingTask] = [:]
  #else
    /// Keeps the Mac from napping while audio renders.
    @ObservationIgnored private var activities: [String: NSObjectProtocol] = [:]
  #endif
  @ObservationIgnored private var jobForTaskID: [String: UUID] = [:]
  @ObservationIgnored private let store: BookStore
  @ObservationIgnored private let settings: AppSettings
  /// Neural voices on this device (Kokoro, Qwen3) and their downloads.
  let neuralVoices = NeuralVoiceModels()

  init(store: BookStore, settings: AppSettings) {
    self.store = store
    self.settings = settings
  }

  /// Running and failed conversions (finished ones show up as books instead).
  var visibleJobs: [Job] {
    jobs.filter {
      if case .failed = $0.phase { return true }
      return $0.isActive
    }
  }

  // MARK: Reading

  /// Reads the document and prepares the narration (fast; no audio yet).
  func prepare(_ url: URL) async throws -> Draft {
    let local = try copyToTemporary(url)
    let result = try await Task.detached(priority: .userInitiated) {
      try await DocumentExtractor.extract(local) { _ in }
    }.value
    let plan = Narrator.plan(result.document, language: result.language)
    return Draft(
      sourceURL: local, title: result.document.title ?? url.deletingPathExtension().lastPathComponent,
      language: result.language, document: result.document, pageCount: result.pageCount, usedOCR: result.usedOCR, plan: plan)
  }

  func replan(_ draft: inout Draft) {
    var doc = draft.document
    doc.title = draft.title
    draft.plan = Narrator.plan(doc, language: draft.language)
  }

  private func copyToTemporary(_ url: URL) throws -> URL {
    let ok = url.startAccessingSecurityScopedResource()
    defer { if ok { url.stopAccessingSecurityScopedResource() } }
    let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
    try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    let dest = dir.appendingPathComponent(url.lastPathComponent)
    try FileManager.default.copyItem(at: url, to: dest)
    return dest
  }

  // MARK: Speaking

  /// The voice a new book in `language` starts with: the last one picked, else
  /// a neural voice where this build has them, else the best system voice.
  func defaultVoiceIdentifier(language: String) -> String? {
    if let saved = settings.voiceIdentifier(for: language),
      NeuralVoiceCatalog.voice(id: saved) != nil || AVSpeechSynthesisVoice(identifier: saved) != nil
    {
      return saved
    }
    return NeuralVoiceCatalog.defaultVoice(language: language, ready: neuralVoices.isReady)?.id
      ?? SpeechRenderer.bestVoice(language: language)?.identifier
  }

  func start(_ draft: Draft, voiceIdentifier: String?, context: ModelContext) {
    var job = Job(title: draft.title)
    let neural = NeuralVoiceCatalog.voice(id: voiceIdentifier)
    job.phase = neural == nil ? .speaking : .preparingVoice
    jobs.insert(job, at: 0)
    let jobID = job.id
    let systemVoice = neural != nil ? nil : voiceIdentifier.flatMap(AVSpeechSynthesisVoice.init(identifier:)) ?? SpeechRenderer.bestVoice(language: draft.language)
    let taskID = submitBackgroundTask(for: jobID, title: draft.title)

    let reportProgress: @Sendable (Double) -> Void = { [weak self] fraction in
      Task { @MainActor in self?.update(jobID, progress: fraction, taskID: taskID) }
    }
    tasks[jobID] = Task { [weak self] in
      guard let self else { return }
      var bookFolder: URL?
      var inserted: Book?
      // a failed or cancelled conversion leaves no half-rendered audio behind
      func discardFolder() { if let bookFolder { try? FileManager.default.removeItem(at: bookFolder) } }
      do {
        let renderer: SpeechRenderer
        if let neural {
          let watch = Task { @MainActor [weak self] in
            // the model download is the first part of the job's progress
            while let self, !Task.isCancelled {
              if case .downloading(let f) = self.neuralVoices.state(neural.modelID) { self.update(jobID, progress: f, taskID: taskID) }
              try? await Task.sleep(for: .milliseconds(250))
            }
          }
          defer { watch.cancel() }
          renderer = SpeechRenderer(sampleVoice: try await self.neuralVoices.prepare(neural))
          try Task.checkCancellation()
          self.setPhase(jobID, .speaking)
        } else {
          renderer = SpeechRenderer(voice: systemVoice)
        }
        self.renderers[jobID] = renderer
        let (folderName, folder) = try self.store.makeBookFolder()
        bookFolder = folder
        try draft.plan.text.write(to: folder.appendingPathComponent("narration.txt"), atomically: true, encoding: .utf8)
        try? FileManager.default.copyItem(at: draft.sourceURL, to: folder.appendingPathComponent("source." + draft.sourceURL.pathExtension.lowercased()))
        let output = try await renderer.render(draft.plan, to: folder.appendingPathComponent("book.m4a"), progress: reportProgress)
        let encoder = JSONEncoder()
        try encoder.encode(output.timing).write(to: folder.appendingPathComponent("timing.json"))
        let book = Book(
          title: draft.title, folder: folderName, audioFile: "book.m4a", textFile: "narration.txt", timingFile: "timing.json",
          language: draft.language, duration: output.duration, chapters: output.chapters, rate: self.settings.defaultRate)
        book.voiceName = renderer.voiceName
        context.insert(book)
        inserted = book
        try context.save()
        self.finish(jobID, phase: .finished(book.id), taskID: taskID, success: true)
      } catch is CancellationError {
        if let inserted { context.delete(inserted) }
        discardFolder()
        self.finish(jobID, phase: .cancelled, taskID: taskID, success: false)
      } catch {
        Self.log.error("conversion: \(error.localizedDescription)")
        // a book that could not be saved must not be saved later without its files
        if let inserted { context.delete(inserted) }
        discardFolder()
        self.finish(jobID, phase: .failed(error.localizedDescription), taskID: taskID, success: false)
      }
    }
  }

  func cancel(_ id: UUID) {
    tasks[id]?.cancel()
    renderers[id]?.cancel()
  }

  func dismiss(_ id: UUID) {
    jobs.removeAll { $0.id == id }
  }

  private func setPhase(_ id: UUID, _ phase: Job.Phase) {
    guard let i = jobs.firstIndex(where: { $0.id == id }) else { return }
    jobs[i].phase = phase
    jobs[i].progress = 0
  }

  private func update(_ id: UUID, progress: Double, taskID: String?) {
    guard let i = jobs.firstIndex(where: { $0.id == id }) else { return }
    jobs[i].progress = progress
    #if os(iOS)
      if let taskID, let bg = backgroundTasks[taskID] {
        bg.progress.completedUnitCount = Int64(progress * 1000)
      }
    #endif
  }

  private func finish(_ id: UUID, phase: Job.Phase, taskID: String?, success: Bool) {
    if let i = jobs.firstIndex(where: { $0.id == id }) {
      jobs[i].phase = phase
      if success { jobs[i].progress = 1 }
      // the row changes or turns into a book without a sound
      switch phase {
      case .finished: Announce.say(StatusAnnouncement.conversionFinished(title: jobs[i].title))
      case .failed: Announce.say(StatusAnnouncement.conversionFailed(title: jobs[i].title))
      default: break
      }
    }
    tasks[id] = nil
    renderers[id] = nil
    // a neural model holds hundreds of MB (Qwen3: ~3 GB); free it when nothing renders
    if tasks.isEmpty { Task { await NeuralSpeechHost.shared.unload() } }
    if let taskID {
      #if os(iOS)
        backgroundTasks[taskID]?.setTaskCompleted(success: success)
        backgroundTasks[taskID] = nil
      #else
        activities[taskID].map(ProcessInfo.processInfo.endActivity)
        activities[taskID] = nil
      #endif
      jobForTaskID[taskID] = nil
    }
  }

  // MARK: Background continuation

  #if os(macOS)
    private func submitBackgroundTask(for jobID: UUID, title: String) -> String? {
      let identifier = "\(Self.taskPrefix).\(jobID.uuidString.prefix(8))"
      activities[identifier] = ProcessInfo.processInfo.beginActivity(options: [.userInitiated, .idleSystemSleepDisabled], reason: "Creating audiobook")
      jobForTaskID[identifier] = jobID
      return identifier
    }
  #else

  private func attach(_ task: BGContinuedProcessingTask) {
    guard let jobID = jobForTaskID[task.identifier], tasks[jobID] != nil else {
      task.setTaskCompleted(success: true)
      return
    }
    backgroundTasks[task.identifier] = task
    task.progress.totalUnitCount = 1000
    task.expirationHandler = { [weak self] in
      MainActor.assumeIsolated { self?.cancel(jobID) }
    }
  }

  private func submitBackgroundTask(for jobID: UUID, title: String) -> String? {
    let identifier = "\(Self.taskPrefix).\(jobID.uuidString.prefix(8))"
    // continued-processing tasks register each concrete identifier (the wildcard is in Info.plist)
    let registered = BGTaskScheduler.shared.register(forTaskWithIdentifier: identifier, using: .main) { [weak self] task in
      nonisolated(unsafe) let continued = task as? BGContinuedProcessingTask
      MainActor.assumeIsolated {
        guard let continued else { return }
        self?.attach(continued)
      }
    }
    guard registered else { return nil }
    let request = BGContinuedProcessingTaskRequest(
      identifier: identifier, title: "Creating audiobook", subtitle: title)
    request.strategy = .queue
    do {
      try BGTaskScheduler.shared.submit(request)
      jobForTaskID[identifier] = jobID
      return identifier
    } catch {
      Self.log.info("background continuation unavailable: \(error.localizedDescription)")
      return nil
    }
  }
  #endif
}
