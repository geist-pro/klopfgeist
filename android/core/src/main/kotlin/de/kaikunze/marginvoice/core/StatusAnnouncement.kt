package de.kaikunze.marginvoice.core

/** The listening status as the player shows it. */
enum class ListeningStatus(val raw: String) {
    OFF("off"), STARTING("starting"), LISTENING("listening"), PAUSED("paused"), UNAVAILABLE("unavailable"),
}

/**
 * What a screen reader is told when something changes that has no tone of its own.
 * Recording, saving and bookmarks are not here: they have tones, and a spoken
 * announcement would land in the comment being recorded or over the book.
 */
object StatusAnnouncement {
    /** The sentence for a change of the listening status, or null for silence. */
    fun listening(from: ListeningStatus, to: ListeningStatus, recording: Boolean): String? {
        // the microphone is recording a comment: the screen reader stays out of it
        if (from == to || recording) return null
        return when (to) {
            ListeningStatus.STARTING -> null
            ListeningStatus.LISTENING -> "Listening for voice commands"
            ListeningStatus.PAUSED -> "Listening paused"
            ListeningStatus.OFF -> "Voice commands off"
            ListeningStatus.UNAVAILABLE -> "Voice commands unavailable"
        }
    }

    fun conversionFinished(title: String) = "$title is ready to play"
    fun conversionFailed(title: String) = "Creating $title failed"
    const val SYNC_FAILED = "Sync failed"
    fun exportReady(files: Int) = if (files == 1) "1 file ready to share" else "$files files ready to share"
}
