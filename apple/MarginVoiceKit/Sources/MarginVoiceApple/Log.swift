import os

extension Logger {
  static func marginVoice(_ category: String) -> Logger { Logger(subsystem: "de.kaikunze.marginvoice", category: category) }
}
