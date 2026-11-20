package de.kaikunze.marginvoice.ui.screens

import de.kaikunze.marginvoice.ui.components.rememberAnnouncer
import de.kaikunze.marginvoice.core.StatusAnnouncement
import de.kaikunze.marginvoice.core.ListeningStatus
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import de.kaikunze.marginvoice.ui.truncates
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import de.kaikunze.marginvoice.core.CommentRecord
import de.kaikunze.marginvoice.core.PlayerDensity
import de.kaikunze.marginvoice.core.ReviewListing
import de.kaikunze.marginvoice.core.VoiceCommand
import de.kaikunze.marginvoice.model.Book
import de.kaikunze.marginvoice.model.BookContent
import de.kaikunze.marginvoice.model.Settings
import de.kaikunze.marginvoice.ui.TextStyles
import de.kaikunze.marginvoice.ui.Theme
import de.kaikunze.marginvoice.ui.components.BigControlButton
import de.kaikunze.marginvoice.ui.components.PillAccessory
import de.kaikunze.marginvoice.ui.components.Scrubber
import de.kaikunze.marginvoice.ui.components.StatusPill
import de.kaikunze.marginvoice.ui.components.ViewThatFits
import de.kaikunze.marginvoice.session.ReviewSession.ListenState
import de.kaikunze.marginvoice.voice.SessionHost
import java.util.Locale

/**
 * Drive mode: where you are, the sentence being read in large type, what you can
 * say right now, and big controls.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    book: Book,
    session: SessionHost,
    settings: Settings,
    large: Boolean,
    onBack: (() -> Unit)?,
    onChapters: () -> Unit,
    onExport: () -> Unit,
    onComments: () -> Unit,
) {
    val p = Theme.palette
    var showListenProblem by remember { mutableStateOf(false) }
    LaunchedEffect(book.id) { session.show(book) }
    // The position changes about five times a second. It is handed down as a lambda and read only by
    // the scrubber (while drawing) and through derived state (chapter, sentence, bookmark, up next),
    // so the screen recomposes when one of those changes, not on every tick.
    val time = remember(session) { { session.engine.currentTime } }

    Column(Modifier.fillMaxSize().background(p.background)) {
        TopAppBar(
            title = {},
            // labelled: testers missed a bare arrow as the way back
            navigationIcon = {
                if (onBack != null) TextButton(onClick = onBack, colors = ButtonDefaults.textButtonColors(contentColor = p.textPrimary)) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Library")
                }
            },
            actions = {
                IconButton(onClick = onChapters, enabled = book.chapters.isNotEmpty()) { Icon(Icons.AutoMirrored.Rounded.List, "Chapters") }
                IconButton(onClick = onExport) { Icon(Icons.Rounded.IosShare, "Export") }
                val count = book.comments.size
                IconButton(onClick = onComments) {
                    BadgedBox(badge = { if (count > 0) Badge(containerColor = p.accent, contentColor = p.background) { CappedFontScale(1.3f) { Text("$count") } } }) {
                        Icon(Icons.Outlined.ChatBubbleOutline, if (count > 0) "Comments and Bookmarks, $count" else "Comments and Bookmarks")
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = p.background, navigationIconContentColor = p.textPrimary, actionIconContentColor = p.textPrimary),
        )
        CommentsHint(session, settings, onComments)
        // The controls are pinned above the navigation bar and the sentence takes the room that
        // is left, scrolling on its own. Only when even that leaves no room (a fold's cover
        // screen, very large text) does the whole player scroll.
        BoxWithConstraints(Modifier.fillMaxSize().navigationBarsPadding(), contentAlignment = Alignment.TopCenter) {
            val column = Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 8.dp)
            val sentence = @Composable {
                SentencePanel(session.content, time, book.title, large, { isInBookmark(book, session, time()) }, Modifier)
            }
            val controls = @Composable {
                // the controls keep their size up to about accessibility type sizes, like iOS's AX2 cap
                CappedFontScale(1.5f) {
                    Column {
                        // one leading edge for text, status and controls
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Status(session) { showListenProblem = true }
                            CommandHint(session, settings.commandLanguage)
                        }
                        Spacer(Modifier.height(18.dp))
                        BookScrubber(book, session, time)
                        Spacer(Modifier.height(18.dp))
                        ControlDock(session)
                        UpNext(session, time)
                    }
                }
            }
            if (maxHeight < 440.dp) {
                Column(column.verticalScroll(rememberScrollState())) {
                    ChapterHeader(book, time)
                    Spacer(Modifier.height(18.dp))
                    sentence()
                    Spacer(Modifier.height(18.dp))
                    controls()
                }
            } else {
                Column(column.fillMaxHeight()) {
                    ChapterHeader(book, time)
                    // centered in the room between header and controls, scrolling when it is longer
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(vertical = 18.dp).clipToBounds()) {
                        Column(
                            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight),
                            verticalArrangement = Arrangement.Center,
                        ) { sentence() }
                    }
                    controls()
                }
            }
        }
    }

    if (showListenProblem) ListenProblemDialog(session) { showListenProblem = false }
}

/** Why voice commands are unavailable, with a retry. */
@Composable
internal fun ListenProblemDialog(session: SessionHost, onDismiss: () -> Unit) {
    val p = Theme.palette
    run {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Voice Commands Unavailable") },
            text = { Text((session.listenState as? ListenState.Unavailable)?.why ?: "") },
            confirmButton = { TextButton(onClick = { onDismiss(); session.startListening() }) { Text("Try Again") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("OK") } },
            containerColor = p.surface,
        )
    }
}

/**
 * The scrubber with the book's chapters, comments and bookmarks. The marks are derived from the
 * comments and the duration, so they are not rebuilt while the book plays.
 */
@Composable
internal fun BookScrubber(book: Book, session: SessionHost, time: () -> Double) {
    val markers by remember(book, session) { derivedStateOf { ReviewListing.markers(book.comments, session.engine.duration) } }
    Scrubber(time = time, duration = session.engine.duration, chapters = book.chapters, markers = markers, onSeek = session::seek)
}

/** The sentence being read belongs to a bookmark (or to the passage being marked). */
internal fun isInBookmark(book: Book, session: SessionHost, time: Double): Boolean {
    val open = book.comments.maxByOrNull { it.createdAt }
    if (session.isBookmarkOpen && open != null && open.kind == CommentRecord.Kind.BOOKMARK && time >= open.mediaTime - 1) return true
    return book.comments.any { c -> c.kind == CommentRecord.Kind.BOOKMARK && time >= c.mediaTime - 4 && time <= (c.endMediaTime ?: c.mediaTime) + 0.5 }
}

/** What the player shows around the sentence at the system's font scale (before any cap). */
@Composable
internal fun playerDensity(): PlayerDensity = PlayerDensity.of(LocalDensity.current.fontScale.toDouble())

/**
 * The book, then "Chapter 4 of 27" and the chapter title (once, when the first
 * chapter is the title itself). At large text one line, "6 OF 27 · Chapter title":
 * the room goes to the sentence being read.
 */
@Composable
internal fun ChapterHeader(book: Book, time: () -> Double) {
    val p = Theme.palette
    val chapterIndex by remember(book, time) { derivedStateOf { book.chapterIndex(time()) } }
    val index = chapterIndex
    val chapter = index?.let { book.chapters[it].title }
    if (!playerDensity().showsBookTitle) {
        val numbered = index != null && book.chapters.size > 1
        val title = chapter?.takeIf { it != book.title } ?: book.title
        val spoken = listOfNotNull(book.title, if (numbered) "Chapter ${index!! + 1} of ${book.chapters.size}" else null, title.takeIf { it != book.title }).joinToString(", ")
        CappedFontScale(1.5f) {
            Text(
                buildAnnotatedString {
                    if (numbered) {
                        withStyle(SpanStyle(color = p.accent, fontWeight = FontWeight.SemiBold)) { append("${index!! + 1} OF ${book.chapters.size}") }
                        append(" · ")
                    }
                    append(title)
                },
                style = TextStyles.footnote, color = p.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).truncates().clearAndSetSemantics { contentDescription = spoken },
            )
        }
        return
    }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).truncates().semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(book.title, style = TextStyles.subheadline, color = p.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (index != null && chapter != null && chapter != book.title) {
            if (book.chapters.size > 1) {
                Text(
                    "Chapter ${index + 1} of ${book.chapters.size}".uppercase(), style = TextStyles.caption,
                    fontWeight = FontWeight.SemiBold, color = p.accent,
                )
            }
            Text(chapter, style = Theme.book(TextStyles.title3, FontWeight.SemiBold), color = p.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The sentence being read, large, between the previous and next one. */
@Composable
internal fun SentencePanel(
    content: BookContent?, time: () -> Double, fallback: String, large: Boolean, isInBookmark: () -> Boolean, modifier: Modifier,
    /** The sentence before the current one (the medium two-pane player shows only the current and the next). */
    previous: Boolean = true,
) {
    val p = Theme.palette
    val sentenceIndex by remember(content, time) { derivedStateOf { content?.sentenceIndex(time()) } }
    val index = sentenceIndex
    val inBookmark by remember(isInBookmark) { derivedStateOf(isInBookmark) }
    // at large text only the sentence being read: its neighbours took the room it needs
    val neighbours = playerDensity().showsNeighbouringSentences
    Column(modifier.fillMaxWidth().truncates(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        val current = index?.let { content?.sentence(it) }
        if (content != null && index != null && current != null) {
            if (previous && neighbours) content.sentence(index - 1)?.let {
                // a solid colour: the secondary text at 60 % fell below 4.5:1 on the background
                Text(
                    it, style = Theme.book(if (large) TextStyles.title3 else TextStyles.body), color = p.textSecondary,
                    maxLines = if (large) 3 else 2, overflow = TextOverflow.Ellipsis,
                )
            }
            // the old sentence leaves at once, so two sentences never overlap
            AnimatedContent(
                targetState = index,
                transitionSpec = { (fadeIn(tween(350)) + slideInVertically(tween(350)) { it / 8 }) togetherWith fadeOut(tween(0)) },
                label = "sentence",
            ) { i ->
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (inBookmark) {
                        Box(Modifier.width(4.dp).fillMaxHeight().background(p.bookmark, CircleShape).semantics { contentDescription = "Bookmarked" })
                    }
                    Text(
                        content.sentence(i) ?: "", style = Theme.book(if (large) TextStyles.title else TextStyles.title2, FontWeight.Medium),
                        color = p.textPrimary, maxLines = 8, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (neighbours) content.sentence(index + 1)?.let {
                Text(it, style = Theme.book(if (large) TextStyles.title3 else TextStyles.body), color = p.textSecondary, maxLines = if (large) 5 else 3, overflow = TextOverflow.Ellipsis)
            }
            if (!content.isExact) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Info, null, tint = p.textSecondary, modifier = Modifier.size(14.dp))
                    Text("Text position is approximate", style = TextStyles.footnote, color = p.textSecondary)
                }
            }
        } else {
            Text(fallback, style = Theme.book(TextStyles.title, FontWeight.SemiBold), color = p.textPrimary)
        }
    }
}

/** The status pill. Tapping it while listening stops listening; when listening is paused, off or failed, tapping it fixes that. */
@Composable
internal fun Status(
    session: SessionHost,
    /** Names the microphone while listening ("Listening · Pixel Buds Pro"), where there is room for it. */
    withMicrophone: Boolean = false,
    onProblem: () -> Unit,
) {
    val p = Theme.palette
    val state = session.listenState
    val flash = session.flash
    // the pill changes in place: TalkBack is told, except into a comment being recorded
    val status = when {
        session.isListeningPaused -> ListeningStatus.PAUSED
        state == ListenState.Listening -> ListeningStatus.LISTENING
        state == ListenState.Starting -> ListeningStatus.STARTING
        state is ListenState.Unavailable -> ListeningStatus.UNAVAILABLE
        else -> ListeningStatus.OFF
    }
    val announce = rememberAnnouncer()
    var told by remember { mutableStateOf(status) }
    LaunchedEffect(status) {
        announce(StatusAnnouncement.listening(told, status, session.isRecording))
        told = status
    }
    // what a tap does, and how a screen reader announces it
    val tap: Pair<String, () -> Unit>? = when {
        session.isRecording -> null
        session.isListeningPaused -> "Listen again" to { session.startListening() }
        state == ListenState.Listening -> "Stop listening" to { session.pauseListening("stopped from the player") }
        state is ListenState.Off -> "Turn voice commands on" to { session.setVoiceCommands(true) }
        state is ListenState.Unavailable -> "Show the problem" to onProblem
        else -> null
    }
    // the pill is lower than a finger: its touch target is the 48 dp around it
    val modifier = if (tap != null) {
        Modifier.clickable(onClickLabel = tap.first, role = Role.Button, onClick = tap.second).minimumInteractiveComponentSize()
    } else Modifier
    when {
        session.isRecording -> StatusPill("Recording", Icons.Rounded.Mic, p.record, modifier, level = session.micLevel, pulsing = true)
        flash != null -> StatusPill(flash.label, if (flash.isBookmark) Icons.Rounded.Bookmark else Icons.Rounded.CheckCircle, if (flash.isBookmark) p.bookmark else p.success, modifier)
        session.isBookmarkOpen -> StatusPill("Marking a passage", Icons.Rounded.Bookmark, p.bookmark, modifier, shortLabel = "Passage", pulsing = true)
        session.isListeningPaused -> StatusPill("Listening paused", Icons.Rounded.Bedtime, p.textSecondary, modifier, shortLabel = "Paused", accessory = PillAccessory.Action("Listen"))
        state == ListenState.Listening -> {
            val mic = session.microphone.takeIf { withMicrophone && it.isNotBlank() }
            StatusPill(if (mic != null) "Listening · $mic" else "Listening", Icons.Rounded.GraphicEq, p.accent, modifier, shortLabel = if (mic != null) "Listening" else null, level = session.micLevel)
        }
        state == ListenState.Starting -> StatusPill("Preparing voice commands…", Icons.Rounded.HourglassTop, p.textSecondary, modifier, shortLabel = "Preparing…")
        state is ListenState.Unavailable -> StatusPill("Voice commands unavailable", Icons.Rounded.Warning, p.warning, modifier, shortLabel = "Unavailable", accessory = PillAccessory.Symbol(Icons.Outlined.Info))
        else -> StatusPill("Voice commands off", Icons.Rounded.MicOff, p.textSecondary, modifier, shortLabel = "Voice off", accessory = PillAccessory.Action("Turn On"))
    }
}

/** What can be said right now, in the command language. */
@Composable
private fun CommandHint(session: SessionHost, language: VoiceCommand.Language) {
    if (session.listenState != ListenState.Listening) return
    val p = Theme.palette
    fun say(c: VoiceCommand) = "“${VoiceCommand.phrases(language)[c]?.firstOrNull()?.let(::spelled) ?: c.name.lowercase()}”"
    val parts = when {
        session.isRecording -> listOf(say(VoiceCommand.RESUME) to "save", say(VoiceCommand.CANCEL) to "discard")
        session.isBookmarkOpen -> listOf(say(VoiceCommand.MARK) to "end the passage here")
        session.engine.isPlaying -> listOf(say(VoiceCommand.STOP) to "comment", say(VoiceCommand.MARK) to "bookmark", say(VoiceCommand.BACK) to "15 s back")
        else -> listOf(say(VoiceCommand.RESUME) to "play")
    }
    Text(
        buildAnnotatedString {
            for ((i, part) in parts.withIndex()) {
                if (i > 0) append("  ·  ")
                withStyle(SpanStyle(color = p.textPrimary, fontWeight = FontWeight.SemiBold)) { append(part.first) }
                append(" " + part.second)
            }
        },
        style = TextStyles.footnote, color = p.textSecondary,
    )
}

/** The phrase as it is written ("zurück"), not folded for matching ("zuruck"). */
private fun spelled(phrase: String) = mapOf("zuruck" to "zurück", "loschen" to "löschen")[phrase] ?: phrase

/**
 * Play under the left thumb, Comment under the right, the small actions between
 * them; on narrow screens the small actions move up a row so both stay at the edges.
 */
@Composable
private fun ControlDock(session: SessionHost) {
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(session.engine.isPlaying) { haptics.performHapticFeedback(HapticFeedbackType.ContextClick) }
    val play: @Composable () -> Unit = {
        BigControlButton(
            if (session.engine.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (session.engine.isPlaying) "Pause" else "Play",
            Theme.palette.accent, size = 80.dp, prominent = true, glyph = Theme.palette.onAccent, onClick = session::togglePlay,
        )
    }
    val comment: @Composable () -> Unit = {
        BigControlButton(
            if (session.isRecording) Icons.Rounded.Stop else Icons.Rounded.Mic, if (session.isRecording) "Save comment" else "Add comment",
            Theme.palette.record, size = 92.dp, prominent = true, onClick = session::commentButton,
        )
    }
    fun row(labels: Boolean, tight: Boolean = false): @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            play()
            Spacer(Modifier.width(16.dp))
            Spacer(Modifier.weight(1f))
            SmallActions(session, labels, tight)
            Spacer(Modifier.width(16.dp))
            Spacer(Modifier.weight(1f))
            comment()
        }
    }
    fun stacked(labels: Boolean, wrap: Boolean = false): @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SmallActions(session, labels, tight = true, wrap = wrap)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                play()
                Spacer(Modifier.weight(1f))
                comment()
            }
        }
    }
    ViewThatFits(Modifier.fillMaxWidth(), listOf(row(true), row(true, tight = true), row(false, tight = true), stacked(true), stacked(false), stacked(false, wrap = true)))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SmallActions(session: SessionHost, labels: Boolean, tight: Boolean, wrap: Boolean = false) {
    val spacing = Arrangement.spacedBy(if (tight) 8.dp else 12.dp)
    if (wrap) FlowRow(horizontalArrangement = spacing, verticalArrangement = Arrangement.spacedBy(8.dp)) { SmallActionButtons(session, labels) }
    else Row(horizontalArrangement = spacing, verticalAlignment = Alignment.CenterVertically) { SmallActionButtons(session, labels) }
}

@Composable
private fun SmallActionButtons(session: SessionHost, labels: Boolean) {
    val p = Theme.palette
    run {
        if (session.isRecording) {
            SmallAction(Icons.Rounded.Delete, "Discard", "Discard comment", labels, p.record, session::discardRecording)
        } else {
            SmallAction(Icons.Rounded.Replay10, "15 s", "Back 15 seconds", labels, p.textPrimary, session::skipBack)
            SmallAction(Icons.Rounded.Replay, "Sentence", "Repeat sentence", labels, p.textPrimary, session::repeatSentence)
            val open = session.isBookmarkOpen
            SmallAction(
                if (open) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder, if (open) "End Passage" else "Bookmark",
                if (open) "End passage" else "Bookmark", labels, if (open) p.bookmark else p.textPrimary, session::addBookmark,
            )
        }
        SpeedMenu(session.engine.rate, session::setRate)
    }
}

@Composable
internal fun SmallAction(icon: ImageVector, title: String, description: String, showsTitle: Boolean, tint: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp).semantics { contentDescription = description },
        contentPadding = ButtonDefaults.ContentPadding.let { if (showsTitle) it else androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp) },
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = Theme.palette.surface, contentColor = tint),
    ) {
        Icon(icon, null, Modifier.size(20.dp))
        if (showsTitle) {
            Spacer(Modifier.width(6.dp))
            Text(title, style = TextStyles.subheadline, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

private val speeds = listOf(0.8, 0.9, 1.0, 1.1, 1.2, 1.3, 1.4, 1.5, 1.75, 2.0, 2.5)

private fun speedLabel(r: Double) =
    if (r == Math.rint(r)) String.format(Locale.ROOT, "%.0f×", r) else (if (Math.rint(r * 10) == r * 10) String.format(Locale.ROOT, "%.1f×", r) else String.format(Locale.ROOT, "%.2f×", r))

@Composable
internal fun SpeedMenu(rate: Double, onChange: (Double) -> Unit, modifier: Modifier = Modifier, fill: Boolean = false) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        FilledTonalButton(
            onClick = { open = true },
            modifier = (if (fill) Modifier.fillMaxWidth() else Modifier).heightIn(min = 48.dp).widthIn(min = 64.dp).semantics { contentDescription = "Playback speed ${speedLabel(rate)}" },
            colors = ButtonDefaults.filledTonalButtonColors(containerColor = Theme.palette.surface, contentColor = Theme.palette.textPrimary),
        ) {
            Text(speedLabel(rate), style = TextStyles.subheadline.copy(fontFeatureSettings = "tnum"), fontWeight = FontWeight.SemiBold)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Theme.palette.surface) {
            for (s in speeds) {
                DropdownMenuItem(
                    text = { Text(speedLabel(s), fontWeight = if (s == rate) FontWeight.Bold else FontWeight.Normal) },
                    onClick = { open = false; onChange(s) },
                )
            }
        }
    }
}

/** Shown near the end, so the switch to the next book is expected. */
@Composable
internal fun UpNext(session: SessionHost, time: () -> Double) {
    val p = Theme.palette
    val next = session.upNext
    val nearEnd by remember(session, time) { derivedStateOf { session.engine.duration > 0 && session.engine.duration - time() < 120 } }
    AnimatedVisibility(next != null && nearEnd) {
        Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Rounded.PlaylistPlay, null, tint = p.accent, modifier = Modifier.size(18.dp))
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = p.textSecondary)) { append("Up next: ") }
                    withStyle(SpanStyle(color = p.textPrimary)) { append(next?.title ?: "") }
                },
                style = TextStyles.footnote, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Caps the font scale of [content], so controls keep their size at the largest text sizes. */
@Composable
internal fun CappedFontScale(max: Float, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale.coerceAtMost(max)), content = content)
}

/** Once, after the first saved comment: where comments are kept (testers looked for them). */
@Composable
private fun CommentsHint(session: SessionHost, settings: Settings, onComments: () -> Unit) {
    val p = Theme.palette
    var visible by remember { mutableStateOf(false) }
    val saved = session.flash?.takeIf { !it.isBookmark }
    LaunchedEffect(saved) {
        if (saved != null && !settings.commentsHintShown) {
            settings.commentsHintShown = true
            // shown once ever, so it stays until it is answered
            visible = true
        }
    }
    if (!visible) return
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp).background(p.surface, RoundedCornerShape(12.dp))
            .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Comment saved. All your comments and bookmarks are under the speech bubble at the top right.",
            color = p.textPrimary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { visible = false; onComments() }) { Text("Show", color = p.accent) }
        TextButton(onClick = { visible = false }) { Text("OK", color = p.accent) }
    }
}
