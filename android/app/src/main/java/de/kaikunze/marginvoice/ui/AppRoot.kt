package de.kaikunze.marginvoice.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.rounded.LibraryBooks
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.runtime.saveable.rememberSaveable
import de.kaikunze.marginvoice.ui.screens.CommentsListDetail
import de.kaikunze.marginvoice.ui.screens.MediumPlayerScreen
import de.kaikunze.marginvoice.ui.screens.rememberCommentsViewState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.kaikunze.marginvoice.AppGraph
import de.kaikunze.marginvoice.core.BookFiles
import de.kaikunze.marginvoice.core.BookPackage
import de.kaikunze.marginvoice.model.Book
import de.kaikunze.marginvoice.model.ImportSource
import de.kaikunze.marginvoice.ui.components.EmptyState
import de.kaikunze.marginvoice.ui.screens.ChaptersSheet
import de.kaikunze.marginvoice.ui.screens.CommandListScreen
import de.kaikunze.marginvoice.ui.screens.CommandLogScreen
import de.kaikunze.marginvoice.ui.screens.LicencesScreen
import de.kaikunze.marginvoice.ui.screens.CommentDetailScreen
import de.kaikunze.marginvoice.ui.screens.CommentsScreen
import de.kaikunze.marginvoice.ui.screens.ConvertSheet
import de.kaikunze.marginvoice.ui.screens.ExportSheet
import de.kaikunze.marginvoice.ui.screens.LibraryScreen
import de.kaikunze.marginvoice.ui.screens.PlayerScreen
import de.kaikunze.marginvoice.ui.screens.SamplesSheet
import de.kaikunze.marginvoice.ui.screens.SettingsScreen
import java.io.File

/** Requests from outside the UI: picked files, shared files, debug launch extras. */
class AppRequests {
    /** Documents to convert, one sheet at a time (documents shared together wait their turn). */
    val conversions = mutableStateListOf<ImportSource>()
    /** Files to import as audiobooks. */
    var imports by mutableStateOf<List<ImportSource>>(emptyList())
    /** Debug: open the n-th book, play it, show a sheet. */
    var open by mutableStateOf<Int?>(null)
    var play by mutableStateOf(false)
    var show by mutableStateOf<String?>(null)

    /** Sorts files the way the iOS inbox does: documents to convert, the rest as audiobook parts. */
    fun receive(sources: List<ImportSource>) {
        val sorted = BookFiles.sortInbox(sources) { it.name }
        conversions.addAll(sorted.documents.filter { d -> conversions.none { it.name == d.name } })
        val audio = sources.filter { it !in sorted.documents }
        if (audio.isNotEmpty()) imports = imports + audio
    }
}

@Composable
fun AppRoot(graph: AppGraph, requests: AppRequests, inbox: File, onPickAudiobooks: () -> Unit, onPickDocument: () -> Unit, resumed: Int) {
    // saved: a rotation, a fold or a resize recreates the activity, and the open book, the comment shown and the place in the stack stay
    val nav = rememberSaveable(saver = Navigator.Saver) { Navigator() }
    val session = graph.session
    val importer = graph.importer
    var loadError by remember { mutableStateOf<String?>(graph.library.loadError) }
    var showChapters by rememberSaveable { mutableStateOf(false) }
    var showExport by rememberSaveable { mutableStateOf(false) }
    var showSamples by rememberSaveable { mutableStateOf(false) }
    val offered = remember { HashSet<String>() }
    val books = graph.library.sorted

    fun openBook(book: Book) {
        nav.popToLibrary()
        nav.push(Route.Player(book.id))
    }

    // picked and shared files join the import queue (it runs on the app's scope)
    LaunchedEffect(requests.imports) {
        if (requests.imports.isEmpty()) return@LaunchedEffect
        importer.import(requests.imports)
        requests.imports = emptyList()
    }

    LaunchedEffect(importer.imported) {
        val imported = importer.imported ?: return@LaunchedEffect
        importer.imported = null
        // an inbox import only opens its book from the library
        if (!imported.fromInbox || nav.top == Route.Library) imported.books.firstOrNull()?.let(::openBook)
    }

    // The inbox (Android/data/pro.geist.klopfgeist/files/Inbox, for `adb push`): audiobooks are
    // imported and removed (a PDF under the same name becomes the book's source.pdf),
    // documents offered for conversion once.
    LaunchedEffect(resumed) {
        val files = inbox.listFiles().orEmpty().filter { it.isFile && !it.name.startsWith(".") }
        val sorted = BookFiles.sortInbox(files) { it.name }
        val packages = files.any { BookFiles.extension(it.name) == BookPackage.EXTENSION }
        if (sorted.audiobookFiles.isNotEmpty() || packages) importer.importInbox(inbox)
        for (doc in sorted.documents) if (offered.add(doc.path)) requests.conversions.add(ImportSource.of(doc))
    }

    // the session moves on to the next book by itself when one ends
    LaunchedEffect(session.book?.id) {
        val id = session.book?.id ?: return@LaunchedEffect
        val top = nav.top
        if (top is Route.Player && top.book != id) {
            nav.stack[nav.stack.size - 1] = Route.Player(id)
        }
    }

    // debug launch extras (screenshots, device tests)
    LaunchedEffect(requests.open, books.size) {
        val i = requests.open ?: return@LaunchedEffect
        val book = books.getOrNull(i) ?: books.firstOrNull() ?: return@LaunchedEffect
        requests.open = null
        openBook(book)
        when (requests.show) {
            "notes" -> nav.push(Route.Comments(book.id))
            "chapters" -> showChapters = true
            "export" -> showExport = true
            "nowplaying" -> { kotlinx.coroutines.delay(2000); nav.popToLibrary() }
        }
        if (requests.play) {
            kotlinx.coroutines.delay(3000)
            session.togglePlay()
        }
    }
    LaunchedEffect(requests.show) {
        when (requests.show) {
            "settings" -> nav.push(Route.Settings)
            "log" -> nav.push(Route.Log)
        }
    }

    BackHandler(enabled = nav.stack.size > 1) { nav.pop() }

    // the window's own width, before the cutout is taken off: the size class is the window's
    BoxWithConstraints(Modifier.fillMaxSize().background(Theme.palette.background)) {
        val width = WidthClass.of(maxWidth.value)
        val expanded = width == WidthClass.EXPANDED
        val top = nav.top
        val playerBook = (top as? Route.Player)?.let { graph.library.book(it.book) }
        // the book whose comments a list shows; its filters and scroll position are kept here, above the
        // layouts, so they survive a change of size class (each layout puts the list somewhere else)
        val commentsBookId = when (top) {
            is Route.Comments -> top.book
            is Route.CommentDetail -> top.book
            else -> playerBook?.id ?: session.book?.id
        }
        val commentsState = rememberCommentsViewState(commentsBookId)
        val libraryList = rememberLazyListState()

        @Composable
        fun library(selected: Book?) = LibraryScreen(
            books = books, session = session, conversions = graph.conversions, selected = selected,
            showNowPlaying = !expanded && top == Route.Library, isImporting = importer.isImporting,
            onOpen = ::openBook,
            onDelete = { book ->
                if (session.book?.id == book.id) session.close()
                graph.store.delete(book)
                session.refreshUpNext()
            },
            onConvert = onPickDocument, onImport = onPickAudiobooks, onSamples = { showSamples = true }, onSettings = if (expanded) null else ({ nav.push(Route.Settings) }),
            listState = libraryList,
        )

        @Composable
        fun player(book: Book) = PlayerScreen(
            book = book, session = session, settings = graph.settings, large = expanded,
            onBack = if (expanded) null else ({ nav.pop() }),
            onChapters = { showChapters = true }, onExport = { showExport = true }, onComments = { nav.push(Route.Comments(book.id)) },
        )

        Row(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))) {
            // the rail on every top-level destination of an expanded window; its book is the one on screen, else the open one
            val railBook = commentsBookId?.let { graph.library.book(it) }
            if (expanded) AppRail(top, railBook, session) { destination ->
                when (destination) {
                    RailDestination.LIBRARY -> nav.goTo(Route.Library)
                    RailDestination.PLAYER -> railBook?.let { nav.goTo(Route.Player(it.id)) }
                    RailDestination.COMMENTS -> railBook?.let { nav.goTo(Route.Comments(it.id)) }
                    RailDestination.SETTINGS -> nav.goTo(Route.Settings)
                }
            }
            Box(
                Modifier.weight(1f).fillMaxHeight()
                    // the rail takes the start inset (a navigation bar on the left in landscape)
                    .then(if (expanded) Modifier.consumeWindowInsets(WindowInsets.systemBars.only(WindowInsetsSides.Start)) else Modifier),
            ) {
                when {
                    (top == Route.Library || top is Route.Player) && expanded -> Row(Modifier.fillMaxSize()) {
                        Box(Modifier.width(380.dp).fillMaxHeight()) { library(playerBook) }
                        VerticalDivider(color = Theme.palette.surfaceBorder)
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            if (playerBook != null) player(playerBook)
                            else EmptyState(Icons.Rounded.Headphones, "No Book Open", "Choose a book from the library, or import an .m4b and its .txt.")
                        }
                    }
                    top == Route.Library -> library(null)
                    top is Route.Player && playerBook == null -> LaunchedEffect(Unit) { nav.pop() }
                    top is Route.Player && width == WidthClass.MEDIUM -> MediumPlayerScreen(
                        playerBook!!, session, graph.store, graph.settings, commentsState,
                        onBack = { nav.pop() }, onChapters = { showChapters = true }, onExport = { showExport = true },
                        onOpenComment = { nav.push(Route.CommentDetail(playerBook.id, it.id)) },
                    )
                    top is Route.Player -> player(playerBook!!)
                    // one call site for the list with or without a selection, so the list keeps its state when one is chosen
                    expanded && (top is Route.Comments || top is Route.CommentDetail) -> {
                        val book = commentsBookId?.let { graph.library.book(it) }
                        if (book != null) {
                            CommentsListDetail(
                                book, session, graph.store, graph.library, graph.settings, commentsState,
                                selected = (top as? Route.CommentDetail)?.comment,
                                onSelect = { nav.select(Route.CommentDetail(book.id, it.id)) },
                                onDeleted = { if (nav.top is Route.CommentDetail) nav.pop() },
                            )
                        } else LaunchedEffect(Unit) { nav.pop() }
                    }
                    top is Route.Comments -> graph.library.book(top.book)?.let { book ->
                        CommentsScreen(book, session, graph.store, graph.settings, commentsState, onBack = { nav.pop() }, onOpen = { nav.push(Route.CommentDetail(book.id, it.id)) })
                    } ?: LaunchedEffect(Unit) { nav.pop() }
                    top is Route.CommentDetail -> {
                        val book = graph.library.book(top.book)
                        val comment = book?.comments?.firstOrNull { it.id == top.comment }
                        if (book != null && comment != null) {
                            CommentDetailScreen(comment, book, session, graph.store, graph.library, graph.settings, onBack = { nav.pop() })
                        } else LaunchedEffect(Unit) { nav.pop() }
                    }
                    top == Route.Settings -> SettingsScreen(
                        graph.settings, session, onBack = { nav.pop() }, onCommands = { nav.push(Route.Commands) }, onLog = { nav.push(Route.Log) },
                        sync = graph.sync, onLicences = { nav.push(Route.Licences) },
                    )
                    top == Route.Commands -> CommandListScreen(onBack = { nav.pop() })
                    top == Route.Log -> CommandLogScreen(session, onBack = { nav.pop() })
                    top == Route.Licences -> LicencesScreen(onBack = { nav.pop() })
                }
            }
        }
    }

    val sheetBook = session.book
    if (showChapters && sheetBook != null) ChaptersSheet(sheetBook, session) { showChapters = false }
    if (showExport && sheetBook != null) ExportSheet(sheetBook, graph.store, graph.settings, graph.cacheDir) { showExport = false }
    if (showSamples) SamplesSheet(books, graph.samples, inbox, onOpen = ::openBook) { showSamples = false }
    requests.conversions.firstOrNull()?.let { source ->
        ConvertSheet(
            source, graph.conversions, graph.settings,
            onDismiss = { requests.conversions.remove(source) },
            onStarted = {
                requests.conversions.remove(source)
                // the new book keeps its own copy of the source
                File(inbox, source.name).takeIf { it.exists() }?.delete()
                nav.popToLibrary()
            },
        )
    }
    // here, not on the player: errors also come from the library's Now Playing bar
    val error = session.errorMessage ?: importer.error ?: loadError
    if (error != null) {
        AlertDialog(
            onDismissRequest = { session.errorMessage = null; importer.error = null; loadError = null },
            title = { Text(if (session.errorMessage != null) "Something Went Wrong" else "Import Failed") },
            text = { Text(error) },
            confirmButton = { TextButton(onClick = { session.errorMessage = null; importer.error = null; loadError = null }) { Text("OK") } },
            containerColor = Theme.palette.surface,
        )
    }
}

/** The expanded window's navigation rail: Comment as its action, then Library, Player, Comments and Settings. */
@Composable
private fun AppRail(top: Route, book: Book?, session: de.kaikunze.marginvoice.voice.SessionHost, onDestination: (RailDestination) -> Unit) {
    val p = Theme.palette
    NavigationRail(
        containerColor = p.background, contentColor = p.textPrimary,
        header = {
            if (book != null) {
                FloatingActionButton(
                    onClick = session::commentButton, modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
                    containerColor = p.record, contentColor = MaterialTheme.colorScheme.onError,
                ) {
                    Icon(if (session.isRecording) Icons.Rounded.Stop else Icons.Rounded.Mic, if (session.isRecording) "Save comment" else "Add comment")
                }
            }
        },
    ) {
        val current = RailDestination.of(top)
        val colors = NavigationRailItemDefaults.colors(
            selectedIconColor = p.textPrimary, selectedTextColor = p.textPrimary, indicatorColor = p.surfaceBorder,
            unselectedIconColor = p.textSecondary, unselectedTextColor = p.textSecondary,
        )
        for (d in RailDestination.entries) {
            val (icon, label) = when (d) {
                RailDestination.LIBRARY -> Icons.Rounded.LibraryBooks to "Library"
                RailDestination.PLAYER -> Icons.Rounded.Headphones to "Player"
                RailDestination.COMMENTS -> Icons.Outlined.ChatBubbleOutline to "Comments"
                RailDestination.SETTINGS -> Icons.Outlined.Settings to "Settings"
            }
            NavigationRailItem(
                selected = current == d, onClick = { onDestination(d) },
                icon = { Icon(icon, null) }, label = { Text(label) },
                // the player and the comments need a book
                enabled = book != null || d == RailDestination.LIBRARY || d == RailDestination.SETTINGS,
                colors = colors,
            )
        }
    }
}
