import AVFAudio
import MarginVoiceCore
import SwiftData
import SwiftUI

/// Turns a PDF or text document into an audiobook on the device: shows what was
/// found (chapters, length), lets you pick title, language and voice, then starts.
struct ConvertView: View {
  let sourceURL: URL
  var onCreate: () -> Void = {}
  @Environment(ConversionCenter.self) private var conversions
  @Environment(AppSettings.self) private var settings
  @Environment(\.modelContext) private var context
  @Environment(\.dismiss) private var dismiss
  @State private var draft: ConversionCenter.Draft?
  @State private var error: String?
  @State private var voiceID: String?
  @State private var preview = AVSpeechSynthesizer()
  @State private var neuralPreview: AVAudioPlayer?
  @State private var sampleTask: Task<Void, Never>?
  @State private var preparingSample = false

  var body: some View {
    NavigationStack {
      Group {
        if let draft {
          form(draft)
        } else if let error {
          ContentUnavailableView("Can't Convert", systemImage: "doc.questionmark", description: Text(error))
        } else {
          ProgressView("Reading \(sourceURL.lastPathComponent)…")
        }
      }
      .navigationTitle("New Audiobook")
      .inlineNavigationTitle()
      .toolbar {
        ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
        ToolbarItem(placement: .confirmationAction) {
          Button("Create") { create() }
            .disabled(draft == nil || draft?.plan.utterances.isEmpty == true)
        }
      }
    }
    .task {
      do {
        let d = try await conversions.prepare(sourceURL)
        draft = d
        voiceID = conversions.defaultVoiceIdentifier(language: d.language)
        #if DEBUG
          // Simulator testing: DRAFTTALK_AUTOCREATE=1 starts the conversion right away.
          if ProcessInfo.processInfo.environment["DRAFTTALK_AUTOCREATE"] != nil { create() }
        #endif
      } catch {
        self.error = error.localizedDescription
      }
    }
    .onDisappear { stopSample() }
  }

  private func form(_ d: ConversionCenter.Draft) -> some View {
    let voices = SpeechRenderer.voices(language: d.language)
    let neural = NeuralVoiceCatalog.voices(language: d.language)
    let chapters = d.plan.chapters
    return Form {
      Section("Audiobook") {
        TextField("Title", text: binding(\.title))
        Picker("Language", selection: binding(\.language)) {
          Text("English").tag("en")
          Text("Deutsch").tag("de")
        }
      }
      .themedRows()

      Section {
        Picker("Voice", selection: $voiceID) {
          if neural.isEmpty {
            systemVoiceRows(voices)
          } else {
            Section("Natural Voices") {
              ForEach(neural) { v in
                Text("\(v.name) · \(v.accent), \(v.engineName)").tag(Optional(v.id))
              }
            }
            Section("System Voices") { systemVoiceRows(voices) }
          }
        }
        Button(preparingSample ? "Preparing the Voice…" : "Listen to a Sample", systemImage: "play.circle") { playSample(d) }
          .disabled(preparingSample)
      } header: {
        Text("Voice")
      } footer: {
        Group {
        if let v = NeuralVoiceCatalog.voice(id: voiceID) {
          neuralFooter(v)
        } else if (voices.first?.quality ?? .default) == .default {
          Text("For a more natural voice, download an Enhanced or Premium voice in Settings › Accessibility › Spoken Content › Voices.")
        } else {
          Text("Voices run on this device. Premium voices sound most natural.")
        }
        }
        .foregroundStyle(Theme.textSecondary)
      }
      .themedRows()

      Section {
        LabeledContent("Length", value: "about \(max(1, Int(d.plan.estimatedMinutes.rounded()))) min")
        LabeledContent("Words", value: d.plan.wordCount.formatted())
        if d.pageCount > 0 { LabeledContent("Pages", value: "\(d.pageCount)") }
        DisclosureGroup("\(chapters.count) Chapters") {
          ForEach(Array(chapters.enumerated()), id: \.offset) { _, title in
            Text(title).font(.callout)
          }
        }
        if d.usedOCR {
          Label("Scanned document: text was recognized from the page images.", systemImage: "text.viewfinder")
            .font(.footnote)
            .foregroundStyle(Theme.textSecondary)
        }
      } header: {
        Text("Contents")
      } footer: {
        Text("References, page headers and footnotes are left out. Figures and tables are announced by their captions. Creating the audio takes a few minutes and continues if you leave the app.").foregroundStyle(Theme.textSecondary)
      }
      .themedRows()
    }
    .themedList()
  }

  private func binding<T>(_ keyPath: WritableKeyPath<ConversionCenter.Draft, T>) -> Binding<T> {
    Binding(
      get: { draft![keyPath: keyPath] },
      set: { value in
        guard var d = draft else { return }
        let languageChanged = keyPath == \ConversionCenter.Draft.language
        d[keyPath: keyPath] = value
        // planning reads the whole document: not on every keystroke of the title
        if keyPath != \ConversionCenter.Draft.title { conversions.replan(&d) }
        draft = d
        if languageChanged {
          voiceID = conversions.defaultVoiceIdentifier(language: d.language)
        }
      })
  }

  private func qualityLabel(_ q: AVSpeechSynthesisVoiceQuality) -> String {
    switch q {
    case .premium: "Premium"
    case .enhanced: "Enhanced"
    default: "Standard"
    }
  }

  private func systemVoiceRows(_ voices: [AVSpeechSynthesisVoice]) -> some View {
    ForEach(voices, id: \.identifier) { v in
      Text("\(v.name) · \(qualityLabel(v.quality))").tag(Optional(v.identifier))
    }
  }

  @ViewBuilder private func neuralFooter(_ v: NeuralVoice) -> some View {
    switch conversions.neuralVoices.state(v.modelID) {
    case .ready:
      Text("\(v.engineName) runs on this device. Comments stay where they are if you convert the book again with another voice.")
    case .downloading(let f):
      Text("Downloading the voice… \(Int(f * 100))%")
    case .failed(let message):
      Text("The voice could not be loaded: \(message)").foregroundStyle(Theme.warning)
    case .notDownloaded:
      Text("The first book with \(v.engineName) downloads about \(v.downloadMB.formatted()) MB once; after that the voice works offline."
        + (v.engine == .qwen3 ? " Qwen3 reads German most naturally and takes a little longer than the book lasts to create." : ""))
    }
  }

  private func stopSample() {
    sampleTask?.cancel()
    preparingSample = false
    preview.stopSpeaking(at: .immediate)
    neuralPreview?.stop()
  }

  private func playSample(_ d: ConversionCenter.Draft) {
    stopSample()
    let sample = d.plan.utterances.first { $0.text.count > 60 }?.text ?? d.plan.utterances.first?.text ?? d.title
    if let neural = NeuralVoiceCatalog.voice(id: voiceID) {
      preparingSample = true
      let models = conversions.neuralVoices
      sampleTask = Task {
        defer { preparingSample = false }
        do {
          let voice = try await models.prepare(neural)
          let samples = try await voice.synthesize(sample)
          try Task.checkCancellation()
          neuralPreview = try Self.player(samples, sampleRate: voice.sampleRate)
          neuralPreview?.play()
        } catch {
          // a failed download or load shows in the footer (the model's state)
        }
      }
      return
    }
    let u = AVSpeechUtterance(string: sample)
    u.voice = voiceID.flatMap(AVSpeechSynthesisVoice.init(identifier:))
    preview.speak(u)
  }

  /// A short in-memory WAV of the sample, for `AVAudioPlayer`.
  private static func player(_ samples: [Float], sampleRate: Double) throws -> AVAudioPlayer {
    var data = Data()
    func put<T: FixedWidthInteger>(_ v: T) { withUnsafeBytes(of: v.littleEndian) { data.append(contentsOf: $0) } }
    let pcm = samples.map { Int16(max(-1, min(1, $0)) * Float(Int16.max)) }
    data.append(contentsOf: Array("RIFF".utf8)); put(UInt32(36 + pcm.count * 2))
    data.append(contentsOf: Array("WAVEfmt ".utf8)); put(UInt32(16)); put(UInt16(1)); put(UInt16(1))
    put(UInt32(sampleRate)); put(UInt32(sampleRate * 2)); put(UInt16(2)); put(UInt16(16))
    data.append(contentsOf: Array("data".utf8)); put(UInt32(pcm.count * 2))
    pcm.forEach { put($0) }
    return try AVAudioPlayer(data: data)
  }

  private func create() {
    guard var draft else { return }
    // the title is read out at the start
    conversions.replan(&draft)
    stopSample()
    if let voiceID { settings.setVoiceIdentifier(voiceID, for: draft.language) }
    conversions.start(draft, voiceIdentifier: voiceID, context: context)
    onCreate()
    dismiss()
  }
}

/// Running and failed conversions, shown at the top of the library.
struct ConversionRow: View {
  let job: ConversionCenter.Job
  @Environment(ConversionCenter.self) private var conversions
  @State private var confirmStop = false

  var body: some View {
    VStack(alignment: .leading, spacing: 8) {
      HStack {
        Image(systemName: "waveform.badge.plus").foregroundStyle(Theme.accent)
        Text(job.title).font(.headline).lineLimit(2)
        Spacer()
        if job.isActive {
          Button("Stop", systemImage: "xmark.circle.fill") { confirmStop = true }
            .labelStyle(.iconOnly)
            .foregroundStyle(Theme.textSecondary)
            .frame(minWidth: 44, minHeight: 44)
            .contentShape(Rectangle())
            .buttonStyle(.borderless)
            .confirmationDialog("Stop creating “\(job.title)”?", isPresented: $confirmStop, titleVisibility: .visible) {
              Button("Stop", role: .destructive) { conversions.cancel(job.id) }
            } message: {
              Text("The audio made so far is discarded.")
            }
        } else {
          Button("Dismiss", systemImage: "xmark") { conversions.dismiss(job.id) }
            .labelStyle(.iconOnly)
        }
      }
      if case .failed(let message) = job.phase {
        Text(message).font(.footnote).foregroundStyle(Theme.warning)
      } else {
        ProgressView(value: job.progress) {
          Text(phaseText)
            .font(.caption)
            .foregroundStyle(Theme.textSecondary)
        }
        .tint(Theme.accent)
      }
    }
    .buttonStyle(.borderless)
    .padding(.vertical, 4)
  }

  private var phaseText: String {
    switch job.phase {
    case .reading: "Reading…"
    case .preparingVoice: job.progress > 0 ? "Downloading the voice… \(Int(job.progress * 100))%" : "Preparing the voice…"
    default: "Creating audio… \(Int(job.progress * 100))%"
    }
  }
}

struct IdentifiedURL: Identifiable {
  let url: URL
  var id: URL { url }
}
