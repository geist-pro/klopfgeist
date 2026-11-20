import Foundation

/// The listening pill: "Listening · AirPods Pro" (iPad), or with the full microphone
/// description and the echo-cancellation state (Mac toolbar).
public enum ListeningLabel {
  /// - Parameter microphone: the capture adapter's description ("AirPods Pro, high quality",
  ///   "Built-in microphone (book on Speaker)").
  public static func text(microphone: String, echoCancellation: Bool, detailed: Bool) -> String {
    var name = microphone.trimmingCharacters(in: .whitespaces)
    if ["", "Not listening", "No microphone"].contains(name) { return "Listening" }
    if !detailed {
      // the device alone: no quality note, no "(book on …)"
      if let cut = name.firstIndex(where: { $0 == "," || $0 == "(" }) { name = String(name[..<cut]) }
      return "Listening · \(name.trimmingCharacters(in: .whitespaces))"
    }
    return "Listening · \(name)\(echoCancellation ? ", echo cancellation on" : "")"
  }
}
