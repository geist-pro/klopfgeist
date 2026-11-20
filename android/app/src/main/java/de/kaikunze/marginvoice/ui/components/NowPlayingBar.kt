package de.kaikunze.marginvoice.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.kaikunze.marginvoice.core.TimeFormat
import de.kaikunze.marginvoice.model.Book
import de.kaikunze.marginvoice.ui.TextStyles
import de.kaikunze.marginvoice.ui.truncates
import de.kaikunze.marginvoice.ui.Theme
import de.kaikunze.marginvoice.voice.SessionHost

/**
 * The open book at the bottom of the library on a phone: back to the player with
 * one tap, and play/pause and comment without opening it.
 */
@Composable
fun NowPlayingBar(book: Book, session: SessionHost, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val p = Theme.palette
    // the text changes once a second, the position several times
    val subtitle by remember(book, session) {
        derivedStateOf {
            val time = session.engine.currentTime
            val left = TimeFormat.clock(maxOf(0.0, session.engine.duration - time)) + " left"
            book.chapterTitle(time)?.let { "$it · $left" } ?: left
        }
    }
    Surface(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = CircleShape, color = p.surface, tonalElevation = 3.dp, shadowElevation = 6.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp)) {
            Row(
                Modifier.weight(1f).truncates().clickable(role = Role.Button, onClick = onOpen).heightIn(min = 48.dp).semantics { contentDescription = "Now playing: ${book.title}. Opens the player" },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Rounded.GraphicEq, null, tint = p.accent, modifier = Modifier.size(28.dp))
                Column {
                    Text(book.title, style = TextStyles.subheadline, fontWeight = FontWeight.SemiBold, color = p.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(subtitle, style = TextStyles.caption, color = p.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            IconButton(onClick = session::togglePlay, modifier = Modifier.size(48.dp)) {
                Icon(if (session.engine.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (session.engine.isPlaying) "Pause" else "Play", tint = p.textPrimary)
            }
            IconButton(onClick = session::commentButton, modifier = Modifier.size(48.dp)) {
                Icon(if (session.isRecording) Icons.Rounded.Stop else Icons.Rounded.Mic, if (session.isRecording) "Save comment" else "Add comment", tint = p.record)
            }
        }
    }
}
