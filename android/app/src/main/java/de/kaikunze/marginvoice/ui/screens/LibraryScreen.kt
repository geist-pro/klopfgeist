package de.kaikunze.marginvoice.ui.screens

import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.LaunchedEffect
import de.kaikunze.marginvoice.ui.components.rememberAnnouncer
import de.kaikunze.marginvoice.core.StatusAnnouncement
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import de.kaikunze.marginvoice.ui.components.withoutBottom
import de.kaikunze.marginvoice.ui.components.bottomOnly
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.LibraryBooks
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.kaikunze.marginvoice.conversion.ConversionCenter
import de.kaikunze.marginvoice.core.CommentRecord
import de.kaikunze.marginvoice.core.TimeFormat
import de.kaikunze.marginvoice.model.Book
import de.kaikunze.marginvoice.ui.TextStyles
import de.kaikunze.marginvoice.ui.truncates
import de.kaikunze.marginvoice.ui.Theme
import de.kaikunze.marginvoice.ui.components.EmptyState
import de.kaikunze.marginvoice.ui.components.NowPlayingBar
import de.kaikunze.marginvoice.voice.SessionHost

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    books: List<Book>,
    session: SessionHost,
    conversions: ConversionCenter,
    selected: Book?,
    showNowPlaying: Boolean,
    isImporting: Boolean,
    onOpen: (Book) -> Unit,
    onDelete: (Book) -> Unit,
    onConvert: () -> Unit,
    onImport: () -> Unit,
    onSamples: () -> Unit,
    /** Null where Settings is elsewhere (the expanded window's rail). */
    onSettings: (() -> Unit)?,
    /** Hoisted by the caller, so the scroll position outlives a change of layout (a fold, a resize). */
    listState: LazyListState = rememberLazyListState(),
) {
    val p = Theme.palette
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var addMenu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<Book?>(null) }
    // a conversion's row changes or turns into a book without a sound
    val announce = rememberAnnouncer()
    LaunchedEffect(conversions) {
        val told = HashSet<java.util.UUID>()
        snapshotFlow { conversions.jobs.map { it.id to it.phase } }.collect {
            for (job in conversions.jobs) {
                val text = when (job.phase) {
                    is ConversionCenter.Job.Phase.Finished -> StatusAnnouncement.conversionFinished(job.title)
                    is ConversionCenter.Job.Phase.Failed -> StatusAnnouncement.conversionFailed(job.title)
                    else -> continue
                }
                if (told.add(job.id)) announce(text)
            }
        }
    }
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = p.background,
        topBar = {
            LargeTopAppBar(
                title = { Text("Library") },
                navigationIcon = { if (onSettings != null) IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, "Settings") } },
                actions = {
                    Box {
                        IconButton(onClick = { addMenu = true }) { Icon(Icons.Rounded.Add, "Add") }
                        DropdownMenu(addMenu, { addMenu = false }, containerColor = p.surface) {
                            DropdownMenuItem(
                                text = { Text("Convert Document…") }, leadingIcon = { Icon(Icons.Outlined.Description, null) },
                                onClick = { addMenu = false; onConvert() },
                            )
                            DropdownMenuItem(
                                text = { Text("Import Audiobook…") }, leadingIcon = { Icon(Icons.Rounded.Download, null) },
                                onClick = { addMenu = false; onImport() },
                            )
                            DropdownMenuItem(
                                text = { Text("Sample Papers…") }, leadingIcon = { Icon(Icons.Rounded.Headphones, null) },
                                onClick = { addMenu = false; onSamples() },
                            )
                        }
                    }
                },
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = p.background, scrolledContainerColor = p.surface),
            )
        },
        bottomBar = {
            // the player is pushed away when the library shows, so keep the open book in reach
            val book = session.book
            if (showNowPlaying && book != null) NowPlayingBar(book, session, onOpen = { onOpen(book) }, modifier = Modifier.navigationBarsPadding().padding(bottom = 8.dp))
        },
    ) { padding ->
        // the list takes the bottom inset as content padding: its last rows scroll behind the navigation bar
        Box(Modifier.fillMaxSize().padding(padding.withoutBottom())) {
            val jobs = conversions.visibleJobs
            if (books.isEmpty() && jobs.isEmpty()) {
                EmptyState(
                    Icons.Rounded.LibraryBooks, "No Books Yet",
                    "Turn a PDF into an audiobook right here, or import one you already have (an .m4b, with its .txt and .timing.json when there are any). " +
                        "You can also share files to Klopfgeist from other apps.",
                    Modifier.padding(padding.bottomOnly()),
                ) {
                    Button(onClick = onConvert) { Text("Convert a Document") }
                    OutlinedButton(onClick = onImport) { Text("Import Audiobook") }
                    TextButton(onClick = onSamples) { Text("Try a Sample Paper") }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = padding.bottomOnly()) {
                    if (jobs.isNotEmpty()) {
                        item { SectionHeader("Creating") }
                        items(jobs, key = { it.id }) { job -> ConversionRow(job, conversions) }
                        item { Spacer(Modifier.padding(4.dp)) }
                    }
                    items(books, key = { it.id }) { book ->
                        BookRowSwipe(book, session, selected?.id == book.id, onOpen = { onOpen(book) }, onDelete = { confirmDelete = book })
                        HorizontalDivider(color = p.surfaceBorder, modifier = Modifier.padding(start = 20.dp))
                    }
                }
            }
            if (isImporting) {
                Box(Modifier.align(Alignment.Center).padding(padding.bottomOnly()).background(p.surface, de.kaikunze.marginvoice.ui.Space.card).padding(24.dp)) {
                    Text("Importing…", color = p.textPrimary)
                }
            }
        }
    }
    confirmDelete?.let { book ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete “${book.title}”?") },
            text = {
                val n = book.comments.size
                Text(if (n == 0) "The audiobook is removed from the library." else "The audiobook, its $n comments and their recordings are removed.")
            },
            confirmButton = { TextButton(onClick = { confirmDelete = null; onDelete(book) }) { Text("Delete", color = p.record) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
            containerColor = p.surface,
        )
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        title.uppercase(), style = TextStyles.footnote, fontWeight = FontWeight.SemiBold, color = Theme.palette.textSecondary,
        modifier = modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 6.dp).semantics { heading() },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookRowSwipe(book: Book, session: SessionHost, selected: Boolean, onOpen: () -> Unit, onDelete: () -> Unit) {
    val p = Theme.palette
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> session.setFinished(!book.isFinished, book)
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
            val color = if (toStart) p.record else if (book.isFinished) p.accent else p.success
            Row(
                Modifier.fillMaxSize().background(color).padding(horizontal = 24.dp),
                horizontalArrangement = if (toStart) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.CenterVertically,
            ) {
                val icon = if (toStart) Icons.Rounded.Delete else if (book.isFinished) Icons.AutoMirrored.Rounded.Undo else Icons.Rounded.Check
                val label = if (toStart) "Delete" else if (book.isFinished) "Mark as Unplayed" else "Mark as Finished"
                Icon(icon, null, tint = p.background)
                Spacer(Modifier.width(8.dp))
                Text(label, color = p.background, fontWeight = FontWeight.SemiBold)
            }
        },
    ) {
        // the swipe actions, for a screen reader or a switch
        val finish = if (book.isFinished) "Mark as Unplayed" else "Mark as Finished"
        val actions = listOf(
            CustomAccessibilityAction(finish) { session.setFinished(!book.isFinished, book); true },
            CustomAccessibilityAction("Delete") { onDelete(); true },
        )
        BookRow(book, session, selected, actions, onOpen)
    }
}

@Composable
private fun BookRow(book: Book, session: SessionHost, selected: Boolean, actions: List<CustomAccessibilityAction>, onOpen: () -> Unit) {
    val p = Theme.palette
    val isCurrent = session.book?.id == book.id
    val isUpNext = session.upNext?.id == book.id
    Column(
        Modifier.fillMaxWidth().background(if (selected) p.surface else p.background).clickable(role = Role.Button, onClick = onOpen)
            .semantics { customActions = actions; this.selected = selected }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (isCurrent) {
                Icon(Icons.Rounded.GraphicEq, if (session.engine.isPlaying) "Playing" else "Open", tint = p.accent, modifier = Modifier.size(18.dp))
            }
            Text(book.title, style = TextStyles.headline, color = p.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.truncates())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            val meta = TextStyles.caption
            when {
                book.isFinished -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Icon(Icons.Rounded.CheckCircle, null, tint = p.success, modifier = Modifier.size(14.dp))
                    Text("Finished", style = meta, color = p.success)
                }
                book.position > 1 -> Text("${TimeFormat.clock(maxOf(0.0, book.duration - book.position))} left", style = meta, color = p.textSecondary)
                else -> Text(TimeFormat.clock(book.duration), style = meta, color = p.textSecondary)
            }
            val comments = book.comments.count { it.kind == CommentRecord.Kind.COMMENT }
            val bookmarks = book.comments.size - comments
            if (comments > 0) Counter(Icons.Outlined.ChatBubbleOutline, comments, "$comments comments")
            if (bookmarks > 0) Counter(Icons.Outlined.BookmarkBorder, bookmarks, "$bookmarks bookmarks")
            if (book.textFile == null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Icon(Icons.Rounded.Warning, null, tint = p.warning, modifier = Modifier.size(14.dp))
                    Text("No text", style = meta, color = p.warning)
                }
            }
            Spacer(Modifier.weight(1f))
            if (isUpNext) {
                Text(
                    "Up Next", style = TextStyles.caption2, fontWeight = FontWeight.SemiBold, color = p.accent,
                    modifier = Modifier.background(p.accent.copy(alpha = 0.18f), CircleShape).padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
        if (!book.isFinished && book.position > 1) {
            LinearProgressIndicator(progress = { book.progress.toFloat() }, modifier = Modifier.fillMaxWidth(), color = p.accent, trackColor = p.control)
        }
    }
}

@Composable
private fun Counter(icon: androidx.compose.ui.graphics.vector.ImageVector, count: Int, description: String) {
    Row(
        Modifier.semantics(mergeDescendants = true) { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(icon, null, tint = Theme.palette.textSecondary, modifier = Modifier.size(14.dp))
        Text("$count", style = TextStyles.caption, color = Theme.palette.textSecondary)
    }
}

@Composable
private fun ConversionRow(job: ConversionCenter.Job, conversions: ConversionCenter) {
    val p = Theme.palette
    var confirmStop by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(job.title, style = TextStyles.headline, color = p.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            when (val phase = job.phase) {
                is ConversionCenter.Job.Phase.Failed -> Text(phase.message, style = TextStyles.caption, color = p.warning)
                ConversionCenter.Job.Phase.Reading -> Text("Reading…", style = TextStyles.caption, color = p.textSecondary)
                else -> {
                    LinearProgressIndicator(progress = { job.progress.toFloat() }, modifier = Modifier.fillMaxWidth(), color = p.accent, trackColor = p.control)
                    val what = if (phase == ConversionCenter.Job.Phase.PreparingVoice) "Downloading the voice…" else "Speaking ·"
                    Text("$what ${(job.progress * 100).toInt()} %", style = TextStyles.caption, color = p.textSecondary)
                }
            }
        }
        if (job.isActive) {
            IconButton(onClick = { confirmStop = true }, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.Stop, "Stop", tint = p.record) }
        } else {
            IconButton(onClick = { conversions.dismiss(job.id) }, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.Close, "Dismiss", tint = p.textSecondary) }
        }
    }
    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text("Stop creating “${job.title}”?") },
            text = { Text("The audio made so far is discarded.") },
            confirmButton = { TextButton(onClick = { confirmStop = false; conversions.cancel(job.id) }) { Text("Stop", color = p.record) } },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("Keep Going") } },
            containerColor = p.surface,
        )
    }
}
