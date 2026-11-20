package de.kaikunze.marginvoice.audio

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import de.kaikunze.marginvoice.MarginVoiceApplication
import de.kaikunze.marginvoice.MainActivity
import de.kaikunze.marginvoice.core.ReviewMachine.RemoteButton

/**
 * Lock screen, notification, steering-wheel and headset controls for the open book.
 *
 * As on iOS, next track starts a comment and previous track discards one while
 * recording (otherwise goes back), so a comment can be made with a single button
 * press when voice is unreliable. Seeking from the notification is off.
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val app = application as MarginVoiceApplication
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, RemotePlayer(app.graph.engine.player) { app.graph.remote.press(it) })
            .setSessionActivity(open)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady) stopSelf()
    }

    override fun onDestroy() {
        // the player belongs to the app graph and outlives the session
        session?.release()
        session = null
        super.onDestroy()
    }

    /** Routes the media session's transport commands to [MediaSessionRemote] (and so to the review session) instead of the player. */
    private class RemotePlayer(player: Player, private val handle: (RemoteButton) -> Unit) : ForwardingPlayer(player) {
        override fun getAvailableCommands(): Player.Commands = super.getAvailableCommands().buildUpon()
            .add(Player.COMMAND_SEEK_TO_NEXT).add(Player.COMMAND_SEEK_TO_PREVIOUS)
            .remove(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM).remove(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .remove(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM).remove(Player.COMMAND_SEEK_BACK).remove(Player.COMMAND_SEEK_FORWARD)
            .build()

        override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)

        override fun play() = handle(RemoteButton.PLAY)
        override fun pause() = handle(RemoteButton.PAUSE)
        override fun setPlayWhenReady(playWhenReady: Boolean) = handle(if (playWhenReady) RemoteButton.PLAY else RemoteButton.PAUSE)
        override fun seekToNext() = handle(RemoteButton.NEXT)
        override fun seekToNextMediaItem() = handle(RemoteButton.NEXT)
        override fun seekToPrevious() = handle(RemoteButton.PREVIOUS)
        override fun seekToPreviousMediaItem() = handle(RemoteButton.PREVIOUS)
    }
}
