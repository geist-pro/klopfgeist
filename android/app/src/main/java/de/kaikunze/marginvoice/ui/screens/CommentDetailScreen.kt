package de.kaikunze.marginvoice.ui.screens

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import kotlinx.coroutines.delay
import java.io.File
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import de.kaikunze.marginvoice.ui.components.readableWidth
import android.media.MediaPlayer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import de.kaikunze.marginvoice.core.ReviewTag
import de.kaikunze.marginvoice.core.VoiceCommand
import de.kaikunze.marginvoice.model.Settings
import de.kaikunze.marginvoice.ui.components.TagChip
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import de.kaikunze.marginvoice.core.AnnotatedTextExport
import de.kaikunze.marginvoice.core.CommentRecord
import de.kaikunze.marginvoice.core.TimeFormat
import de.kaikunze.marginvoice.model.Book
import de.kaikunze.marginvoice.model.BookContent
import de.kaikunze.marginvoice.model.BookStore
import de.kaikunze.marginvoice.model.Comment
import de.kaikunze.marginvoice.model.Library
import de.kaikunze.marginvoice.ui.Space
import de.kaikunze.marginvoice.ui.TextStyles
import de.kaikunze.marginvoice.ui.Theme
import de.kaikunze.marginvoice.voice.SessionHost

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentDetailScreen(comment: Comment, book: Book, session: SessionHost, store: BookStore, library: Library, settings: Settings, onBack: () -> Unit) {
    val p = Theme.palette
    val note = rememberVoiceNote(comment, book, store, session)
    DisposableEffect(Unit) { onDispose { library.save() } }
    val isComment = comment.kind == CommentRecord.Kind.COMMENT
    var confirmDelete by remember { mutableStateOf(false) }
    if (confirmDelete) {
        DeleteCommentDialog(comment, onDismiss = { confirmDelete = false }) {
            store.delete(comment)
            onBack()
        }
    }
    Scaffold(
        containerColor = p.background,
        topBar = {
            TopAppBar(
                title = { Text(if (isComment) "Comment" else "Bookmark") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = p.background),
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding().verticalScroll(rememberScrollState()).readableWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (isComment) {
                Card("Comment") {
                    OutlinedTextField(
                        value = comment.transcript, onValueChange = { comment.transcript = it }, label = { Text("Transcript") },
                        modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 12,
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = p.accent, unfocusedBorderColor = p.control),
                    )
                    TagPicker(comment, settings.commandLanguage)
                    if (comment.transcriptStatus == Comment.TranscriptStatus.PENDING) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.HourglassTop, null, tint = p.textSecondary, modifier = Modifier.size(16.dp))
                            Text("Transcribing…", color = p.textSecondary)
                        }
                    }
                    if (comment.transcriptStatus == Comment.TranscriptStatus.FAILED) {
                        RowButton(Icons.Rounded.Refresh, "Try Transcribing Again") { session.postProcess(comment) }
                    }
                    if (comment.audioFile != null) {
                        RowButton(
                            if (note.isPlaying) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                            if (note.isPlaying) "Stop Recording Playback" else "Play Recording (${TimeFormat.clock(comment.duration)})",
                            onClick = note::toggle,
                        )
                    }
                }
            }

            val content = session.content?.takeIf { session.book?.id == book.id }
            Card("Position · ${TimeFormat.clock(comment.mediaTime)}${comment.chapter?.let { " · $it" } ?: ""}") {
                val offset = content?.let { comment.textOffset(it.text) }
                if (content != null && offset != null) {
                    ContextText(content, offset, highlight = false)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { comment.sentenceNudge -= 1 }) {
                            Icon(Icons.Rounded.ChevronLeft, null)
                            Text("Earlier")
                        }
                        Spacer(Modifier.weight(1f))
                        Text(placement(comment), style = TextStyles.caption, color = p.textSecondary)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { comment.sentenceNudge += 1 }) {
                            Text("Later")
                            Icon(Icons.Rounded.ChevronRight, null)
                        }
                    }
                } else {
                    Text("No narration text for this book, so the comment is placed by time only.", color = p.textSecondary)
                }
                RowButton(Icons.Rounded.Headphones, "Listen From Here") { jump(session, comment) }
                Text(
                    "The comment is attached after the highlighted sentence. Use Earlier and Later to move it.",
                    style = TextStyles.footnote, color = p.textSecondary,
                )
            }

            Card(null) {
                RowButton(Icons.Rounded.Delete, "Delete", tint = p.record) { confirmDelete = true }
            }
        }
    }
}

/** None or one of the five review tags. */
@Composable
private fun TagPicker(comment: Comment, language: VoiceCommand.Language) {
    val p = Theme.palette
    var open by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.DropdownList) { open = true }, verticalAlignment = Alignment.CenterVertically) {
            Text("Tag", color = p.textPrimary, modifier = Modifier.weight(1f))
            comment.tag?.let { TagChip(it, language) } ?: Text("None", color = p.textSecondary)
            Icon(Icons.Rounded.ExpandMore, null, tint = p.textSecondary)
        }
        DropdownMenu(open, { open = false }, containerColor = p.surface) {
            DropdownMenuItem(text = { Text("None") }, onClick = { open = false; comment.tag = null })
            for (t in ReviewTag.entries) DropdownMenuItem(text = { Text(t.displayName(language)) }, onClick = { open = false; comment.tag = t })
        }
    }
}

@Composable
private fun Card(title: String?, content: @Composable () -> Unit) {
    val p = Theme.palette
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        title?.let { Text(it.uppercase(), style = TextStyles.footnote, fontWeight = FontWeight.SemiBold, color = p.textSecondary, modifier = Modifier.padding(start = 4.dp).semantics { heading() }) }
        Surface(color = p.surface, shape = Space.card) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
        }
    }
}

@Composable
private fun RowButton(icon: ImageVector, label: String, tint: androidx.compose.ui.graphics.Color = Theme.palette.accent, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Icon(icon, null, tint = tint)
        Spacer(Modifier.width(8.dp))
        Text(label, color = tint, modifier = Modifier.weight(1f))
    }
}

/** How the comment was placed in the text ("Matched to the text"). */
internal fun placement(comment: Comment): String = when {
    comment.sentenceNudge != 0 -> "Moved ${if (comment.sentenceNudge > 0) "+" else ""}${comment.sentenceNudge}"
    comment.refinedOffset != null -> "Matched to the text"
    comment.confidence >= 0.5 -> "Placed by timing"
    else -> "Estimated position"
}

/**
 * The paragraph around the anchor with the attached sentence highlighted: bold with a ◆ after it
 * (phones), or on a tinted background ([highlight], the expanded detail pane, v1).
 */
@Composable
internal fun ContextText(content: BookContent, offset: Int, highlight: Boolean, style: TextStyle = Theme.book(TextStyles.callout), highlightFill: Color = Color.Unspecified) {
    val p = Theme.palette
    val text = content.text
    val si = AnnotatedTextExport.attachSentence(offset, text)
    val lo = maxOf(0, si - 1)
    val hi = minOf(text.sentences.size - 1, si + 1)
    Text(
        buildAnnotatedString {
            for (i in lo..hi) {
                val current = i == si
                val span = when {
                    !current -> SpanStyle(color = p.textSecondary)
                    highlight -> SpanStyle(color = p.textPrimary, background = highlightFill)
                    else -> SpanStyle(color = p.textPrimary, fontWeight = FontWeight.Bold)
                }
                withStyle(span) { append(content.sentence(i) ?: "") }
                if (highlight) append(" ") else withStyle(SpanStyle(color = p.record)) { append(if (current) "  ◆ " else " ") }
            }
        },
        style = style,
    )
}

/**
 * A comment's recording, played in the app. Released when the screen or pane leaves; while it
 * plays, the session knows the sound is the app's own (not another app playing).
 */
@Stable
internal class VoiceNote(private val file: () -> File?, private val session: SessionHost) {
    private var player: MediaPlayer? = null
    var isPlaying by mutableStateOf(false)
        private set
    /** Seconds into the recording; updated while it plays. */
    var position by mutableDoubleStateOf(0.0)
        private set

    fun toggle() = if (isPlaying) stop() else play()

    fun play() {
        val f = file() ?: return
        if (session.engine.isPlaying) session.togglePlay()
        player?.release()
        player = runCatching {
            MediaPlayer().apply {
                setDataSource(f.path)
                setOnCompletionListener { stop() }
                prepare()
                start()
            }
        }.getOrNull()
        isPlaying = player != null
    }

    fun stop() {
        player?.release()
        player = null
        isPlaying = false
        position = 0.0
    }

    internal fun poll() {
        position = player?.let { runCatching { it.currentPosition / 1000.0 }.getOrNull() } ?: position
    }
}

@Composable
internal fun rememberVoiceNote(comment: Comment, book: Book, store: BookStore, session: SessionHost): VoiceNote {
    val note = remember(comment.id) { VoiceNote({ store.audioFile(comment, book) }, session) }
    LaunchedEffect(note, note.isPlaying) {
        // a recording played back here is not another app playing
        session.playingOwnAudio = note.isPlaying
        while (note.isPlaying) {
            note.poll()
            delay(100)
        }
    }
    DisposableEffect(note) {
        onDispose {
            note.stop()
            // left while playing: the flag would otherwise stay set (and the microphone take the book's own sound for it)
            session.playingOwnAudio = false
        }
    }
    return note
}
