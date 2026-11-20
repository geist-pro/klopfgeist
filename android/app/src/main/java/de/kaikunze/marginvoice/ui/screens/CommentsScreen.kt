package de.kaikunze.marginvoice.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import de.kaikunze.marginvoice.core.KindFilter
import de.kaikunze.marginvoice.core.ReviewListing
import de.kaikunze.marginvoice.core.TagFilter
import kotlinx.coroutines.flow.distinctUntilChanged
import java.util.UUID
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import de.kaikunze.marginvoice.ui.components.withoutBottom
import de.kaikunze.marginvoice.ui.components.bottomOnly
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.kaikunze.marginvoice.core.CommentRecord
import de.kaikunze.marginvoice.core.TimeFormat
import de.kaikunze.marginvoice.model.Book
import de.kaikunze.marginvoice.model.BookStore
import de.kaikunze.marginvoice.model.Comment
import de.kaikunze.marginvoice.model.deleteMessage
import de.kaikunze.marginvoice.model.Settings
import de.kaikunze.marginvoice.ui.TextStyles
import de.kaikunze.marginvoice.ui.truncates
import de.kaikunze.marginvoice.ui.Theme
import de.kaikunze.marginvoice.core.VoiceCommand
import de.kaikunze.marginvoice.ui.components.EmptyState
import de.kaikunze.marginvoice.ui.components.TagChip
import de.kaikunze.marginvoice.voice.SessionHost

/**
 * What a comment list remembers: the filters and the scroll position. Hoisted to [AppRoot] and
 * saved, so they survive recreation and a change of window size class (a fold or unfold moves
 * the list between the full-screen, medium and expanded layouts).
 */
@Stable
class CommentsViewState(filter: KindFilter, tags: TagFilter, val list: LazyListState) {
    var filter by mutableStateOf(filter)
    var tags by mutableStateOf(tags)

    companion object {
        val Saver: Saver<CommentsViewState, Any> = listSaver(
            save = { listOf(it.filter.name, it.tags.key, it.list.firstVisibleItemIndex, it.list.firstVisibleItemScrollOffset) },
            restore = { CommentsViewState(KindFilter.valueOf(it[0] as String), TagFilter.fromKey(it[1] as String), LazyListState(it[2] as Int, it[3] as Int)) },
        )
    }
}

/** One per book: another book's list starts at the top with all comments. */
@Composable
fun rememberCommentsViewState(book: UUID?): CommentsViewState =
    rememberSaveable(book, saver = CommentsViewState.Saver) { CommentsViewState(KindFilter.ALL, TagFilter.All, LazyListState()) }

/** The comments that [state]'s filters let through, in reading order; recomputed only when the comments or filters change. */
@Composable
internal fun rememberVisibleComments(book: Book, state: CommentsViewState): State<List<Comment>> =
    remember(book, state) { derivedStateOf { ReviewListing.visible(book.comments, state.filter, state.tags) } }

/** Comments and bookmarks of a book, in reading order (phones, full screen). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentsScreen(book: Book, session: SessionHost, store: BookStore, settings: Settings, state: CommentsViewState, onBack: () -> Unit, onOpen: (Comment) -> Unit) {
    val p = Theme.palette
    // a note swiped away waits for confirmation: a spoken comment cannot be recorded again
    var confirmDelete by remember { mutableStateOf<Comment?>(null) }
    val notes by rememberVisibleComments(book, state)
    Scaffold(
        containerColor = p.background,
        topBar = {
            TopAppBar(
                title = { Text("Comments & Bookmarks") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = { CommentsMenu(book, session, settings, state) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = p.background),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding.withoutBottom())) {
            KindFilterRow(state, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp))
            if (notes.isEmpty()) {
                CommentsEmpty(state.filter, Modifier.padding(padding.bottomOnly()))
            } else {
                LazyColumn(Modifier.fillMaxSize(), state = state.list, contentPadding = padding.bottomOnly()) {
                    items(notes, key = { it.id }, contentType = { it.kind }) { comment ->
                        CommentRowSwipe(comment, quote(book, session, comment), settings.commandLanguage, onOpen = { onOpen(comment) },
                            onPlay = { jump(session, comment) }, onDelete = { confirmDelete = comment })
                        HorizontalDivider(color = p.surfaceBorder, modifier = Modifier.padding(start = 20.dp))
                    }
                }
            }
        }
    }
    confirmDelete?.let { comment ->
        DeleteCommentDialog(comment, onDismiss = { confirmDelete = null }) { store.delete(comment) }
    }
}

/** All / Comments / Bookmarks. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun KindFilterRow(
    state: CommentsViewState, modifier: Modifier = Modifier, inactive: Color = Theme.palette.background,
    /** Narrow panes: the labels shrink to fit (down to 10 sp) instead of ending in "…". */
    shrink: Boolean = false,
) {
    val p = Theme.palette
    SingleChoiceSegmentedButtonRow(modifier) {
        val labels = mapOf(KindFilter.ALL to "All", KindFilter.COMMENTS to "Comments", KindFilter.BOOKMARKS to "Bookmarks")
        KindFilter.entries.forEachIndexed { i, f ->
            SegmentedButton(
                selected = state.filter == f, onClick = { state.filter = f }, shape = SegmentedButtonDefaults.itemShape(i, KindFilter.entries.size),
                // narrow panes: the fill alone marks the choice, the check's room goes to the label
                icon = { if (!shrink) SegmentedButtonDefaults.Icon(state.filter == f) },
                colors = SegmentedButtonDefaults.colors(activeContainerColor = p.surfaceBorder, activeContentColor = p.textPrimary, inactiveContainerColor = inactive, inactiveContentColor = p.textSecondary),
            ) {
                if (shrink) {
                    val color = if (state.filter == f) p.textPrimary else p.textSecondary
                    BasicText(
                        labels.getValue(f), maxLines = 1, style = TextStyles.subheadline.copy(color = color, fontWeight = FontWeight.SemiBold),
                        autoSize = TextAutoSize.StepBased(minFontSize = 13.sp, maxFontSize = 15.sp), overflow = TextOverflow.Ellipsis, modifier = Modifier.truncates(),
                    )
                } else Text(labels.getValue(f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.truncates())
            }
        }
    }
}

/** The list's menu: the tag filter and transcribing every comment again. */
@Composable
internal fun CommentsMenu(book: Book, session: SessionHost, settings: Settings, state: CommentsViewState) {
    val p = Theme.palette
    var more by remember { mutableStateOf(false) }
    var confirmTranscribe by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { more = true }) { Icon(if (state.tags == TagFilter.All) Icons.Rounded.MoreVert else Icons.Rounded.FilterList, "More") }
        DropdownMenu(more, { more = false }, containerColor = p.surface) {
            for (f in TagFilter.all) {
                DropdownMenuItem(
                    text = { Text(f.label(settings.commandLanguage), fontWeight = if (f == state.tags) FontWeight.Bold else FontWeight.Normal) },
                    onClick = { more = false; state.tags = f },
                )
            }
            HorizontalDivider(color = p.surfaceBorder)
            DropdownMenuItem(
                text = { Text("Transcribe Comments Again") }, leadingIcon = { Icon(Icons.Rounded.GraphicEq, null) },
                enabled = book.comments.any { it.audioFile != null },
                onClick = { more = false; confirmTranscribe = true },
            )
        }
    }
    if (confirmTranscribe) {
        val language = settings.commentLanguage.resolved(book.language)
        AlertDialog(
            onDismissRequest = { confirmTranscribe = false },
            title = { Text("Transcribe all comments again in ${language.displayName}?") },
            text = { Text("This replaces the current transcripts, including any you edited. Change the language in Settings › Comment Language.") },
            confirmButton = { TextButton(onClick = { confirmTranscribe = false; session.transcribeAgain(book) }) { Text("Transcribe Again") } },
            dismissButton = { TextButton(onClick = { confirmTranscribe = false }) { Text("Cancel") } },
            containerColor = p.surface,
        )
    }
}

/** What to do to get a first comment or bookmark. */
@Composable
internal fun CommentsEmpty(filter: KindFilter, modifier: Modifier = Modifier) {
    if (filter == KindFilter.BOOKMARKS) {
        EmptyState(
            Icons.Outlined.BookmarkBorder, "No Bookmarks",
            "Say “bookmark” to mark the sentence you just heard. Say it again within two minutes to mark the whole passage in between.",
            modifier,
        )
    } else {
        EmptyState(
            Icons.Outlined.ChatBubbleOutline, "No Comments Yet",
            "While the book plays, say “stop”, wait for the beep, and speak. Say “continue” when you’re done.",
            modifier,
        )
    }
}

/**
 * The comment list of the wide layouts (v1): rounded rows on [container].
 * With [time], the row at the playhead (`ReviewListing.current`) is outlined and kept in view
 * (medium window, beside the player); with [selected], that row is filled (expanded, beside the detail).
 */
@Composable
internal fun CommentsPane(
    book: Book,
    session: SessionHost,
    store: BookStore,
    settings: Settings,
    state: CommentsViewState,
    time: (() -> Double)?,
    selected: UUID?,
    onOpen: (Comment) -> Unit,
    modifier: Modifier = Modifier,
    container: Color = Theme.palette.surface,
    header: @Composable () -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val p = Theme.palette
    var confirmDelete by remember { mutableStateOf<Comment?>(null) }
    val notes by rememberVisibleComments(book, state)
    // changes when the playhead reaches another row, not on every tick
    val current by remember(time) { derivedStateOf { time?.let { ReviewListing.current(notes, it())?.id } } }
    if (time != null) {
        LaunchedEffect(state.list) {
            snapshotFlow { ReviewListing.focusIndex(notes, time()) }.distinctUntilChanged().collect { i ->
                if (i != null && state.list.layoutInfo.visibleItemsInfo.none { it.index == i && it.offset >= 0 }) state.list.animateScrollToItem(i)
            }
        }
    }
    Column(modifier.background(container)) {
        header()
        if (notes.isEmpty()) {
            CommentsEmpty(state.filter, Modifier.padding(contentPadding))
        } else {
            LazyColumn(
                Modifier.fillMaxSize(), state = state.list,
                contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 8.dp + contentPadding.calculateBottomPadding()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(notes, key = { it.id }, contentType = { it.kind }) { comment ->
                    // the rows recompose when the playhead reaches another one: keep their actions and quotes
                    val actions = remember(comment, session) {
                        listOf(
                            CustomAccessibilityAction("Play") { jump(session, comment); true },
                            CustomAccessibilityAction("Delete") { confirmDelete = comment; true },
                        )
                    }
                    val quoted by remember(comment, book, session) { derivedStateOf { quote(book, session, comment) } }
                    CommentCard(
                        comment, quoted, settings.commandLanguage,
                        atPlayhead = comment.id == current, selected = comment.id == selected, base = container, actions = actions,
                        onOpen = { onOpen(comment) },
                    )
                }
            }
        }
    }
    confirmDelete?.let { comment ->
        DeleteCommentDialog(comment, onDismiss = { confirmDelete = null }) { store.delete(comment) }
    }
}

/** Asks before a comment or bookmark is deleted. */
@Composable
internal fun DeleteCommentDialog(comment: Comment, onDismiss: () -> Unit, onDelete: () -> Unit) {
    val p = Theme.palette
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (comment.kind == CommentRecord.Kind.BOOKMARK) "Delete Bookmark?" else "Delete Comment?") },
        text = { Text(comment.deleteMessage) },
        confirmButton = { TextButton(onClick = { onDismiss(); onDelete() }) { Text("Delete", color = p.record) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = p.surface,
    )
}

private fun quote(book: Book, session: SessionHost, comment: Comment): String? {
    val content = session.content ?: return null
    if (book.id != session.book?.id) return null
    return comment.record(content.text).quotedText(content.text).ifEmpty { null }
}

/** Plays from shortly before the comment. */
fun jump(session: SessionHost, comment: Comment) {
    session.seek(maxOf(0.0, comment.mediaTime - 8))
    if (!session.engine.isPlaying) session.togglePlay()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommentRowSwipe(comment: Comment, quote: String?, language: VoiceCommand.Language, onOpen: () -> Unit, onPlay: () -> Unit, onDelete: () -> Unit) {
    val p = Theme.palette
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = {
            when (it) {
                SwipeToDismissBoxValue.StartToEnd -> onPlay()
                SwipeToDismissBoxValue.EndToStart -> onDelete()
                SwipeToDismissBoxValue.Settled -> {}
            }
            false
        },
    )
    SwipeToDismissBox(
        state = state,
        backgroundContent = {
            val toStart = state.dismissDirection == SwipeToDismissBoxValue.EndToStart
            Row(
                Modifier.fillMaxSize().background(if (toStart) p.record else p.accent).padding(horizontal = 24.dp),
                horizontalArrangement = if (toStart) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (toStart) Icons.Rounded.Delete else Icons.Rounded.PlayArrow, null, tint = p.background)
                Spacer(Modifier.width(8.dp))
                Text(if (toStart) "Delete" else "Play", color = p.background, fontWeight = FontWeight.SemiBold)
            }
        },
    ) {
        // the swipe actions, for a screen reader or a switch
        val actions = listOf(
            CustomAccessibilityAction("Play") { onPlay(); true },
            CustomAccessibilityAction("Delete") { onDelete(); true },
        )
        CommentRow(comment, quote, language, actions, onOpen)
    }
}

@Composable
private fun CommentRow(comment: Comment, quote: String?, language: VoiceCommand.Language, actions: List<CustomAccessibilityAction>, onOpen: () -> Unit) {
    val p = Theme.palette
    val bookmark = comment.kind == CommentRecord.Kind.BOOKMARK
    Row(
        Modifier.fillMaxWidth().background(p.background).clickable(role = Role.Button, onClick = onOpen).semantics { customActions = actions }.truncates().padding(horizontal = 20.dp, vertical = 12.dp)
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(if (bookmark) p.bookmark else p.record, CircleShape))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (bookmark) Icons.Rounded.Bookmark else Icons.Rounded.ChatBubble, if (bookmark) "Bookmark" else "Comment", tint = if (bookmark) p.bookmark else p.record, modifier = Modifier.size(14.dp))
                // the place gives way, so a tag is never pushed out of the row
                Text(
                    commentPlace(comment), style = TextStyles.footnote.copy(fontFeatureSettings = "tnum"), color = p.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                comment.tag?.let { TagChip(it, language) }
            }
            if (bookmark) {
                Text(quote ?: "Bookmark", style = Theme.book(TextStyles.body), color = p.textPrimary, maxLines = if (comment.isPassage) 5 else 3, overflow = TextOverflow.Ellipsis)
            } else {
                CommentTranscript(comment, TextStyles.subheadline, TextStyles.body)
                quote?.let { Text(it, style = Theme.book(TextStyles.footnote), color = p.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

/** "2:40 · Introduction", or "13:02–14:10 · Introduction" for a passage. */
internal fun commentPlace(comment: Comment): String {
    val end = comment.endMediaTime
    val time = if (end != null) "${TimeFormat.clock(comment.mediaTime)}–${TimeFormat.clock(end)}" else TimeFormat.clock(comment.mediaTime)
    return time + (comment.chapter?.let { " · $it" } ?: "")
}

/** The transcript, or that it is still being made or failed. */
@Composable
internal fun CommentTranscript(
    comment: Comment, statusStyle: TextStyle, style: TextStyle, color: Color = Theme.palette.textPrimary,
    /** Phones keep "Transcribing…" quiet; the wide lists show it in the warning colour (v1). */
    pending: Color = Theme.palette.textSecondary,
) {
    val p = Theme.palette
    when (comment.transcriptStatus) {
        Comment.TranscriptStatus.PENDING -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.HourglassTop, null, tint = pending, modifier = Modifier.size(16.dp))
            Text("Transcribing…", style = statusStyle, color = pending)
        }
        Comment.TranscriptStatus.FAILED -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.GraphicEq, null, tint = p.warning, modifier = Modifier.size(16.dp))
            Text("Not transcribed. Tap to listen.", style = statusStyle, color = p.warning)
        }
        else -> Text(comment.transcript.ifEmpty { "(empty)" }, style = style, color = color)
    }
}

/** A fill a little lighter than [base], for the selected or current row (text keeps its contrast on it). */
@Composable
internal fun raisedFill(base: Color): Color = Theme.palette.textPrimary.copy(alpha = 0.08f).compositeOver(base)

/**
 * A row of the wide lists (v1): "time · chapter" with the tag, then the transcript; a bookmark
 * shows "Bookmark · time" or "Passage · start–end" and the quoted text in the book's font.
 */
@Composable
private fun CommentCard(
    comment: Comment, quote: String?, language: VoiceCommand.Language, atPlayhead: Boolean, selected: Boolean, base: Color,
    actions: List<CustomAccessibilityAction>, onOpen: () -> Unit,
) {
    val p = Theme.palette
    val bookmark = comment.kind == CommentRecord.Kind.BOOKMARK
    val shape = RoundedCornerShape(16.dp)
    val fill = when {
        atPlayhead -> p.record.copy(alpha = 0.10f).compositeOver(base)
        selected -> raisedFill(base)
        else -> base
    }
    Column(
        Modifier.fillMaxWidth().clip(shape).background(fill, shape)
            .then(if (atPlayhead) Modifier.border(1.dp, p.record, shape) else Modifier)
            .selectable(selected = selected, role = Role.Button, onClick = onOpen)
            .semantics {
                customActions = actions
                if (atPlayhead) stateDescription = "At the playhead"
            }
            .heightIn(min = 48.dp).truncates().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val meta = TextStyles.footnote.copy(fontFeatureSettings = "tnum")
            when {
                !bookmark -> Text(commentPlace(comment), style = meta, color = if (atPlayhead || selected) p.textPrimary else p.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                comment.isPassage -> Text("Passage · ${TimeFormat.clock(comment.mediaTime)}–${TimeFormat.clock(comment.endMediaTime ?: comment.mediaTime)}", style = meta, color = p.bookmark, maxLines = 1)
                else -> Text("Bookmark · ${TimeFormat.clock(comment.mediaTime)}", style = meta, color = p.bookmark, maxLines = 1)
            }
            comment.tag?.let { TagChip(it, language) }
        }
        if (bookmark) {
            Text(quote?.let { "“$it”" } ?: "Bookmark", style = Theme.book(TextStyles.subheadline), color = p.textSecondary, maxLines = if (comment.isPassage) 4 else 2, overflow = TextOverflow.Ellipsis)
        } else {
            CommentTranscript(comment, TextStyles.callout, TextStyles.callout, pending = p.warning)
        }
    }
}
