package de.kaikunze.marginvoice.audio

import de.kaikunze.marginvoice.core.ReviewMachine.RemoteButton
import de.kaikunze.marginvoice.session.NowPlaying
import de.kaikunze.marginvoice.session.RemoteControlling

/**
 * The session's [RemoteControlling] on Android. The buttons arrive in the media
 * session of [PlaybackService] (lock screen, notification, headset, steering wheel),
 * which hands them to [press]; position, duration, rate and the play state reach the
 * system from ExoPlayer itself, so `update` only has the titles to set.
 */
class MediaSessionRemote(private val player: ExoBookPlayer) : RemoteControlling {
    private var handler: ((RemoteButton) -> Unit)? = null

    override fun activate(handler: (RemoteButton) -> Unit) {
        this.handler = handler
    }

    override fun deactivate() {
        handler = null
    }

    /** Title (the chapter) and artist (the book) on the lock screen and notification. */
    override fun update(info: NowPlaying) = player.setNowPlaying(info.chapter ?: info.title, info.title)

    /** A transport button, from the media session. Nothing happens while no book is open. */
    fun press(button: RemoteButton) {
        handler?.invoke(button)
    }
}
