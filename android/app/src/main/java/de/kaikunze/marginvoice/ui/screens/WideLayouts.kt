package de.kaikunze.marginvoice.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.kaikunze.marginvoice.core.CommentRecord
import de.kaikunze.marginvoice.core.ReviewListing
import de.kaikunze.marginvoice.core.ReviewTag
import de.kaikunze.marginvoice.core.TimeFormat
import de.kaikunze.marginvoice.core.VoiceCommand
import de.kaikunze.marginvoice.model.Book
import de.kaikunze.marginvoice.model.BookStore
import de.kaikunze.marginvoice.model.Comment
import de.kaikunze.marginvoice.model.Library
import de.kaikunze.marginvoice.model.Settings
import de.kaikunze.marginvoice.ui.TextStyles
import de.kaikunze.marginvoice.ui.Theme
import de.kaikunze.marginvoice.ui.components.EmptyState
import de.kaikunze.marginvoice.ui.components.ViewThatFits
import de.kaikunze.marginvoice.ui.tagTint
import de.kaikunze.marginvoice.ui.truncates
import de.kaikunze.marginvoice.voice.SessionHost
import java.util.UUID

// The wide-window layouts of the v1 design: the medium window's player beside its comments,
// and the expanded window's comments as list and detail. They are made of the phone screens'
// pieces (PlayerScreen, CommentsScreen, CommentDetailScreen).

/**
 * Medium window (600–839 dp): the player and the book's comments side by side, two equal
 * columns with 24 dp margins and gap. The comment at the playhead is outlined and kept in view.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediumPlayerScreen(
    book: Book,
    session: SessionHost,
    store: BookStore,
    settings: Settings,
    comments: CommentsViewState,
    onBack: () -> Unit,
    onChapters: () -> Unit,
    onExport: () -> Unit,
    onOpenComment: (Comment) -> Unit,
) {
    val p = Theme.palette
    LaunchedEffect(book.id) { session.show(book) }
    // read only while drawing and in derived state, as on the phone player
    val time = remember(session) { { session.engine.currentTime } }
    // the comment list follows the playhead only for the book that is playing
    val playing = session.book?.id == book.id
    Column(Modifier.fillMaxSize().background(p.background)) {
        TopAppBar(
            title = {},
            navigationIcon = {
                TextButton(onClick = onBack, colors = ButtonDefaults.textButtonColors(contentColor = p.textPrimary)) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Library")
                }
            },
            actions = {
                IconButton(onClick = onChapters, enabled = book.chapters.isNotEmpty()) { Icon(Icons.AutoMirrored.Rounded.List, "Chapters") }
                IconButton(onClick = onExport) { Icon(Icons.Rounded.IosShare, "Export") }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = p.background, navigationIconContentColor = p.textPrimary, actionIconContentColor = p.textPrimary),
        )
        Row(
            Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            PlayerPane(book, session, time, Modifier.weight(1f).fillMaxHeight().semantics { contentDescription = "Player" })
            CommentsPane(
                book, session, store, settings, comments, time = if (playing) time else null, selected = null, onOpen = onOpenComment,
                modifier = Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(24.dp)),
                header = {
                    Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Comments & Bookmarks", style = TextStyles.title2, color = p.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).truncates().semantics { heading() },
                            )
                            CommentsMenu(book, session, settings, comments)
                        }
                        KindFilterRow(comments, Modifier.fillMaxWidth().padding(end = 12.dp), inactive = p.surface, shrink = true)
                    }
                },
            )
        }
    }
}

/** The medium window's player (v1): where you are, the sentence and the next, status, scrubber, and wide controls. */
@Composable
private fun PlayerPane(book: Book, session: SessionHost, time: () -> Double, modifier: Modifier) {
    val p = Theme.palette
    var showListenProblem by remember { mutableStateOf(false) }
    BoxWithConstraints(modifier) {
        val controls = @Composable {
            CappedFontScale(1.5f) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Status(session, withMicrophone = true) { showListenProblem = true }
                    BookScrubber(book, session, time)
                    PaneSmallActions(session)
                    PaneTransport(session)
                    UpNext(session, time)
                }
            }
        }
        val sentence = @Composable {
            SentencePanel(session.content, time, book.title, large = false, isInBookmark = { isInBookmark(book, session, time()) }, modifier = Modifier, previous = false)
        }
        // as on the phone: the controls are pinned and the sentence scrolls in the room left;
        // a short window (a phone in landscape) scrolls the whole pane
        if (maxHeight < 480.dp) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                ChapterHeader(book, time)
                sentence()
                controls()
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                ChapterHeader(book, time)
                Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 14.dp).clipToBounds()) {
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) { sentence() }
                }
                controls()
            }
        }
    }
    if (showListenProblem) ListenProblemDialog(session) { showListenProblem = false }
}

/** Back 15 s, bookmark and speed, three equal buttons (Discard instead of the first two while recording). */
@Composable
private fun PaneSmallActions(session: SessionHost) {
    val p = Theme.palette
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val cell = Modifier.weight(1f)
        if (session.isRecording) {
            SmallAction(Icons.Rounded.Delete, "Discard", "Discard comment", showsTitle = true, tint = p.record, onClick = session::discardRecording, modifier = Modifier.weight(2f))
        } else {
            SmallAction(Icons.Rounded.Replay10, "15 s", "Back 15 seconds", showsTitle = false, tint = p.textPrimary, onClick = session::skipBack, modifier = cell)
            val open = session.isBookmarkOpen
            SmallAction(
                if (open) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder, "End Passage", if (open) "End passage" else "Bookmark",
                showsTitle = false, tint = if (open) p.bookmark else p.textPrimary, onClick = session::addBookmark, modifier = cell,
            )
        }
        SpeedMenu(session.engine.rate, session::setRate, cell, fill = true)
    }
}

/** Play and Comment as two wide 80 dp buttons. */
@Composable
private fun PaneTransport(session: SessionHost) {
    val p = Theme.palette
    val playing = session.engine.isPlaying
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        WideControlButton(
            if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play",
            container = p.accent, glyph = p.onAccent, onClick = session::togglePlay, modifier = Modifier.weight(1f),
        )
        WideControlButton(
            if (session.isRecording) Icons.Rounded.Stop else Icons.Rounded.Mic, if (session.isRecording) "Save comment" else "Add comment",
            container = p.record, glyph = MaterialTheme.colorScheme.onError, onClick = session::commentButton, modifier = Modifier.weight(1f),
        )
    }
}

/** A wide transport button (v1 medium): 80 dp tall, rounded, named after what a tap does. */
@Composable
internal fun WideControlButton(icon: ImageVector, label: String, container: Color, glyph: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FilledIconButton(
        onClick = onClick, modifier = modifier.height(80.dp), shape = RoundedCornerShape(28.dp),
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = container, contentColor = glyph),
    ) { Icon(icon, label, Modifier.size(30.dp)) }
}

/**
 * Expanded window (≥ 840 dp): the comments as list and detail. The detail has no back arrow:
 * it sits beside its list, and system back clears the selection first.
 */
@Composable
fun CommentsListDetail(
    book: Book,
    session: SessionHost,
    store: BookStore,
    library: Library,
    settings: Settings,
    comments: CommentsViewState,
    selected: UUID?,
    onSelect: (Comment) -> Unit,
    onDeleted: () -> Unit,
) {
    val p = Theme.palette
    val comment = selected?.let { id -> book.comments.firstOrNull { it.id == id } }
    BoxWithConstraints(Modifier.fillMaxSize().background(p.background).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.End))) {
        val listWidth = minOf(412.dp, maxWidth * 0.42f)
        Row(Modifier.fillMaxSize()) {
            CommentsPane(
                book, session, store, settings, comments, time = null, selected = comment?.id, onOpen = onSelect,
                modifier = Modifier.width(listWidth).fillMaxHeight().semantics { contentDescription = "Comment list" },
                container = p.background,
                header = {
                    Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Comments & Bookmarks", style = TextStyles.title, color = p.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).truncates().semantics { heading() },
                            )
                            CommentsMenu(book, session, settings, comments)
                        }
                        val summary by remember(book) { derivedStateOf { ReviewListing.summary(book.comments) } }
                        Text("${book.title} · $summary", style = TextStyles.subheadline, color = p.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.truncates())
                        KindFilterRow(comments, Modifier.fillMaxWidth().padding(end = 12.dp), shrink = true)
                    }
                },
            )
            Box(
                Modifier.weight(1f).fillMaxHeight().padding(start = 8.dp, end = 16.dp, top = 16.dp, bottom = 16.dp)
                    .clip(RoundedCornerShape(24.dp)).background(p.surface)
                    .semantics { contentDescription = "Comment detail" },
            ) {
                if (comment != null) {
                    // a new selection starts at the top, with the editor closed
                    key(comment.id) { CommentDetailPane(comment, book, session, store, library, settings, onDeleted) }
                } else {
                    EmptyState(Icons.Outlined.ChatBubbleOutline, "No Comment Selected", "Choose a comment or bookmark to read it, tag it, or listen from there.")
                }
            }
        }
    }
}

/** The expanded detail (v1): transcript, tag, voice note, the matched passage and the actions. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CommentDetailPane(comment: Comment, book: Book, session: SessionHost, store: BookStore, library: Library, settings: Settings, onDeleted: () -> Unit) {
    val p = Theme.palette
    val isComment = comment.kind == CommentRecord.Kind.COMMENT
    val note = rememberVoiceNote(comment, book, store, session)
    var editing by rememberSaveable(comment.id) { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    // edits and tags are written to the library when another comment is chosen or the pane leaves
    DisposableEffect(comment.id) { onDispose { library.save() } }
    val content = session.content?.takeIf { session.book?.id == book.id }
    Column(Modifier.fillMaxSize().imePadding().padding(horizontal = 32.dp, vertical = 24.dp)) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val kind = when {
                        isComment -> "Comment"
                        comment.isPassage -> "Passage"
                        else -> "Bookmark"
                    }
                    Text("$kind · ${commentPlace(comment)}", style = TextStyles.subheadline.copy(fontFeatureSettings = "tnum"), color = p.textSecondary)
                    when {
                        isComment && editing -> {
                            val focus = remember { FocusRequester() }
                            OutlinedTextField(
                                value = comment.transcript, onValueChange = { comment.transcript = it }, label = { Text("Transcript") },
                                modifier = Modifier.fillMaxWidth().focusRequester(focus), minLines = 2, maxLines = 10,
                                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = p.accent, unfocusedBorderColor = p.control),
                            )
                            LaunchedEffect(Unit) { focus.requestFocus() }
                        }
                        isComment -> Box(Modifier.semantics { heading() }) {
                            CommentTranscript(comment, TextStyles.title3, TextStyles.title2.copy(fontSize = 24.sp, lineHeight = 31.sp), pending = p.warning)
                        }
                        else -> {
                            val quoted = content?.let { comment.record(it.text).quotedText(it.text) }?.ifEmpty { null }
                            Text(quoted?.let { "“$it”" } ?: kind, style = Theme.book(TextStyles.title2), color = p.textPrimary, modifier = Modifier.semantics { heading() })
                        }
                    }
                }
                DetailMenu(comment, session)
            }
            if (isComment) TagChooser(comment, settings.commandLanguage)
            if (comment.audioFile != null) VoiceNoteBar(comment, note)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(placement(comment).uppercase(), style = TextStyles.footnote, fontWeight = FontWeight.Bold, color = p.textSecondary, modifier = Modifier.semantics { heading() })
                val offset = content?.let { comment.textOffset(it.text) }
                if (content != null && offset != null) {
                    ContextText(
                        content, offset, highlight = true, style = Theme.book(TextStyles.title3).copy(fontSize = 19.sp, lineHeight = 30.sp),
                        highlightFill = p.record.copy(alpha = 0.22f).compositeOver(p.surface),
                    )
                } else {
                    Text(
                        if (session.book?.id == book.id) "No narration text for this book, so the comment is placed by time only." else "Open the book to see the passage.",
                        style = TextStyles.callout, color = p.textSecondary,
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        DetailActions(isComment, editing, onPlay = { jump(session, comment) }, onEdit = { editing = !editing }, onDelete = { confirmDelete = true })
    }
    if (confirmDelete) {
        DeleteCommentDialog(comment, onDismiss = { confirmDelete = false }) {
            store.delete(comment)
            onDeleted()
        }
    }
}

/** Play from here, Edit transcript, and Delete at the far end; wrapped when the pane is narrow. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailActions(isComment: Boolean, editing: Boolean, onPlay: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    val p = Theme.palette
    val play = @Composable {
        Button(onClick = onPlay, modifier = Modifier.heightIn(min = 48.dp), colors = ButtonDefaults.buttonColors(containerColor = p.accent, contentColor = p.onAccent)) {
            Text("Play from here", fontWeight = FontWeight.SemiBold)
        }
    }
    val edit = @Composable {
        if (isComment) {
            OutlinedButton(onClick = onEdit, modifier = Modifier.heightIn(min = 48.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = p.textPrimary)) {
                Text(if (editing) "Done" else "Edit transcript")
            }
        }
    }
    val delete = @Composable {
        TextButton(onClick = onDelete, modifier = Modifier.heightIn(min = 48.dp), colors = ButtonDefaults.textButtonColors(contentColor = p.record)) { Text("Delete") }
    }
    ViewThatFits(
        Modifier.fillMaxWidth(),
        listOf(
            {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    play(); edit(); Spacer(Modifier.weight(1f)); delete()
                }
            },
            { FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { play(); edit(); delete() } },
        ),
    )
}

/** Moving the comment by a sentence, and transcribing again after a failure (the phone detail's other actions). */
@Composable
private fun DetailMenu(comment: Comment, session: SessionHost) {
    val p = Theme.palette
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Rounded.MoreVert, "More") }
        DropdownMenu(open, { open = false }, containerColor = p.surface) {
            DropdownMenuItem(text = { Text("Move a Sentence Earlier") }, leadingIcon = { Icon(Icons.Rounded.ChevronLeft, null) }, onClick = { open = false; comment.sentenceNudge -= 1 })
            DropdownMenuItem(text = { Text("Move a Sentence Later") }, leadingIcon = { Icon(Icons.Rounded.ChevronRight, null) }, onClick = { open = false; comment.sentenceNudge += 1 })
            if (comment.transcriptStatus == Comment.TranscriptStatus.FAILED) {
                DropdownMenuItem(text = { Text("Try Transcribing Again") }, leadingIcon = { Icon(Icons.Rounded.Refresh, null) }, onClick = { open = false; session.postProcess(comment) })
            }
        }
    }
}

/** The five review tags as single-select chips; tapping the chosen one clears it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagChooser(comment: Comment, language: VoiceCommand.Language) {
    val p = Theme.palette
    FlowRow(
        Modifier.selectableGroup().semantics { contentDescription = "Tag" },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (tag in ReviewTag.entries) {
            val chosen = comment.tag == tag
            val tint = p.tagTint(tag)
            val shape = RoundedCornerShape(8.dp)
            Row(
                Modifier.minimumInteractiveComponentSize()
                    .selectable(selected = chosen, role = Role.RadioButton, onClick = { comment.tag = if (chosen) null else tag })
                    .background(if (chosen) tint.copy(alpha = 0.18f).compositeOver(p.surface) else p.surface, shape)
                    .border(1.dp, if (chosen) tint else p.control, shape)
                    .heightIn(min = 36.dp).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (chosen) Icon(Icons.Rounded.Check, null, tint = tint, modifier = Modifier.size(16.dp))
                // text in the primary colour: the tag tints on their own tinted fill fall below 4.5:1
                Text(tag.displayName(language), style = TextStyles.subheadline, fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal, color = p.textPrimary)
            }
        }
    }
}

/** The recording: play/stop, a waveform that fills as it plays, and the time. */
@Composable
private fun VoiceNoteBar(comment: Comment, note: VoiceNote) {
    val p = Theme.palette
    val progress = { if (comment.duration > 0) (note.position / comment.duration).toFloat().coerceIn(0f, 1f) else 0f }
    Row(
        Modifier.fillMaxWidth().background(p.background, RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        FilledIconButton(
            onClick = note::toggle, modifier = Modifier.size(48.dp), shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = p.accent, contentColor = p.onAccent),
        ) { Icon(if (note.isPlaying) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, if (note.isPlaying) "Stop voice note" else "Play voice note") }
        Waveform(comment.id, progress, p.accent, p.control, Modifier.weight(1f).height(32.dp))
        val label by remember(comment, note) { derivedStateOf { "${TimeFormat.clock(note.position)} / ${TimeFormat.clock(comment.duration)}" } }
        Text(label, style = TextStyles.subheadline.copy(fontFeatureSettings = "tnum"), color = p.textSecondary)
    }
}

/**
 * Decorative bars (the recording's levels are not stored): heights from the comment's id, so a
 * comment keeps its shape. The played part is read while drawing, so playback does not recompose.
 */
@Composable
private fun Waveform(id: UUID, progress: () -> Float, played: Color, rest: Color, modifier: Modifier) {
    Canvas(modifier.clearAndSetSemantics {}) {
        val bar = 3.dp.toPx()
        val gap = 3.dp.toPx()
        val count = ((size.width + gap) / (bar + gap)).toInt().coerceAtLeast(1)
        val seed = id.leastSignificantBits xor id.mostSignificantBits
        val split = progress() * count
        for (i in 0 until count) {
            val h = (0.25f + 0.75f * (((seed ushr ((i * 7) % 57)) and 0xFF).toFloat() / 255f)) * size.height
            drawRoundRect(
                if (i < split) played else rest, Offset(i * (bar + gap), (size.height - h) / 2), Size(bar, h), CornerRadius(bar / 2),
            )
        }
    }
}
