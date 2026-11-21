import MarginVoiceCore
import SwiftUI

struct ExportView: View {
  let book: Book
  @Environment(BookStore.self) private var store
  @Environment(SessionHost.self) private var session
  @Environment(AppSettings.self) private var settings
  @Environment(\.dismiss) private var dismiss
  @State private var options = ExportBuilder.Options()
  @State private var files: [URL] = []
  @State private var error: String?
  @State private var isBuilding = false
  /// (done, total) while the on-device model rewords the notes.
  @State private var polishing: (Int, Int)?
  @State private var sourcePDF: URL?
  @State private var pickingPDF = false

  /// What a build depends on: the options and whether a source PDF is there.
  private struct BuildKey: Equatable {
    var options: ExportBuilder.Options
    var sourcePDF: URL?
  }

  private var shareTitle: String {
    if options.nothingChosen { return "Choose What to Export" }
    if let (done, total) = polishing { return "Polishing \(done) of \(total)…" }
    if isBuilding || files.isEmpty { return "Preparing…" }
    return "Share \(files.count) \(files.count == 1 ? "File" : "Files")"
  }

  var body: some View {
    NavigationStack {
      Form {
        Section {
          Toggle(isOn: $options.annotatedText) {
            Label {
              VStack(alignment: .leading) {
                Text("Text with comments")
                Text("Markdown with {>> comments <<} after each sentence").font(.caption).foregroundStyle(Theme.textSecondary)
              }
            } icon: { Image(systemName: "doc.richtext") }
          }
          .disabled(book.textFile == nil)
          Toggle(isOn: $options.commentList) {
            Label("Comment list (Markdown)", systemImage: "list.bullet.rectangle")
          }
          Toggle(isOn: $options.csv) { Label("Comment list (CSV)", systemImage: "tablecells") }
          Toggle(isOn: $options.json) { Label("Comment list (JSON)", systemImage: "curlybraces") }
          Toggle(isOn: $options.audio) { Label("Voice recordings", systemImage: "waveform") }
        } header: {
          let comments = book.comments.filter { $0.kind == .comment }.count
          Text("\(comments) \(comments == 1 ? "comment" : "comments"), \(book.comments.count - comments) \(book.comments.count - comments == 1 ? "bookmark" : "bookmarks")")
        } footer: {
          Group {
          if book.comments.contains(where: { $0.transcriptStatus == .pending }) {
            Text("Some comments are still being transcribed. Export again in a moment to include them.")
          }
          }
          .foregroundStyle(Theme.textSecondary)
        }
        .themedRows()

        Section {
          Toggle(isOn: $options.reviewDraft) {
            Label {
              VStack(alignment: .leading) {
                Text("Review draft")
                Text("Comments sorted by tag: summary, strengths, weaknesses, questions, minor").font(.caption).foregroundStyle(Theme.textSecondary)
              }
            } icon: { Image(systemName: "doc.text") }
          }
          if options.reviewDraft {
            Picker("Layout", selection: $options.reviewLayout) {
              Text("Generic").tag(ReviewDraftExport.Layout.generic)
              Text("IEEE VR form").tag(ReviewDraftExport.Layout.pcsForm)
            }
            .pickerStyle(.segmented)
            if ReviewPolisher.isAvailable {
              Toggle(isOn: $options.polish) {
                Label {
                  VStack(alignment: .leading) {
                    Text("Polish wording with Apple Intelligence")
                    Text("Each note becomes review prose; the spoken words stay underneath").font(.caption).foregroundStyle(Theme.textSecondary)
                  }
                } icon: { Image(systemName: "sparkles") }
              }
            }
          }
        } footer: {
          Group {
          if options.reviewDraft {
            Text("Headings in \(settings.commandLanguage == .german ? "German" : "English") (the command language). Untagged comments go under “Other notes”, bookmarks under “Passages to re-read”.")
          }
          }
          .foregroundStyle(Theme.textSecondary)
        }
        .themedRows()

        Section {
          Toggle(isOn: $options.annotatedPDF) {
            Label {
              VStack(alignment: .leading) {
                Text("Comments in the PDF")
                Text("The paper with a highlight and a note on each commented sentence").font(.caption).foregroundStyle(Theme.textSecondary)
              }
            } icon: { Image(systemName: "doc.text.magnifyingglass") }
          }
          .disabled(book.textFile == nil)
          if options.annotatedPDF {
            if let sourcePDF {
              LabeledContent("PDF", value: sourcePDF.lastPathComponent)
              Button("Choose Another PDF…") { pickingPDF = true }
            } else {
              Button("Choose the PDF…", systemImage: "doc.badge.plus") { pickingPDF = true }
            }
          }
        } footer: {
          Group {
          if options.annotatedPDF, sourcePDF == nil {
            Text("This book was not converted here, so the paper's PDF is needed once; it is kept with the book.")
          } else if options.annotatedPDF {
            Text("Sentences the narration rewrote (equations, some captions) may not be found; those comments are listed in a note on the first page.")
          }
          }
          .foregroundStyle(Theme.textSecondary)
        }
        .themedRows()

        if !files.isEmpty {
          Section("Files") {
            ForEach(files, id: \.self) { url in
              Label(url.lastPathComponent, systemImage: url.hasDirectoryPath ? "folder" : "doc")
                .font(.footnote)
                .foregroundStyle(Theme.textSecondary)
            }
          }
          .themedRows()
        }
      }
      .themedList()
      .safeAreaInset(edge: .bottom) {
        // the one action of this screen, where the thumb is
        ShareLink(items: files) {
          Label(shareTitle, systemImage: "square.and.arrow.up")
            .font(.headline)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 6)
            .foregroundStyle(Theme.onAccent)
        }
        .buttonStyle(.glassProminent)
        .controlSize(.large)
        .disabled(files.isEmpty || isBuilding)
        .padding(.horizontal, 20)
        .padding(.bottom, 8)
      }
      .navigationTitle("Export")
      .inlineNavigationTitle()
      .sheetButtons(cancel: "Close", onCancel: { dismiss() })
      .task(id: BuildKey(options: options, sourcePDF: sourcePDF)) { await build() }
      .onAppear { sourcePDF = ExportBuilder.sourcePDF(of: book, store: store) }
      .fileImporter(isPresented: $pickingPDF, allowedContentTypes: [.pdf]) { result in
        if case .success(let url) = result { keepSourcePDF(url) }
      }
      .onDisappear { replaceFiles(with: []) }
      .alert("Export Failed", isPresented: Binding(presenting: $error)) {
        Button("OK") {}
      } message: {
        Text(error ?? "")
      }
    }
  }

  /// Runs inside `.task(id: options)`, so a newer choice of options cancels it.
  private func build() async {
    guard !options.nothingChosen else { return replaceFiles(with: []) }
    isBuilding = true
    defer { isBuilding = false; polishing = nil }
    let content = await store.content(for: book)
    guard !Task.isCancelled else { return }
    let language = settings.commandLanguage
    var polished = ReviewPolisher.Result()
    if options.reviewDraft, options.polish {
      let records = book.sortedComments.map { $0.record(text: content?.text) }
      polished = await ReviewPolisher.live.run(records, title: book.title, language: language) { done, total in polishing = (done, total) }
      guard !Task.isCancelled else { return }
    }
    do {
      replaceFiles(with: try ExportBuilder.build(
        book: book, content: content, store: store, options: options, language: language,
        polished: polished.polished, summary: polished.summary, sourcePDF: sourcePDF))
    } catch {
      replaceFiles(with: [])
      self.error = error.localizedDescription
    }
  }

  /// Copies a picked PDF into the book folder as `source.pdf`, where conversions keep theirs.
  private func keepSourcePDF(_ url: URL) {
    let scoped = url.startAccessingSecurityScopedResource()
    defer { if scoped { url.stopAccessingSecurityScopedResource() } }
    let dest = store.folderURL(book).appendingPathComponent("source.pdf")
    do {
      try? FileManager.default.removeItem(at: dest)
      try FileManager.default.copyItem(at: url, to: dest)
      sourcePDF = dest
    } catch {
      self.error = error.localizedDescription
    }
  }

  /// Shows the new files and deletes the temporary folder of the ones they replace.
  private func replaceFiles(with new: [URL]) {
    let old = Set(files.map { $0.deletingLastPathComponent() })
    files = new
    // the button title changes from "Preparing…" in place
    if !new.isEmpty { Announce.say(StatusAnnouncement.exportReady(files: new.count)) }
    for folder in old { try? FileManager.default.removeItem(at: folder) }
  }
}
