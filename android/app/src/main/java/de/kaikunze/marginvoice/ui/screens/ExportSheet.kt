package de.kaikunze.marginvoice.ui.screens

import de.kaikunze.marginvoice.ui.components.rememberAnnouncer
import de.kaikunze.marginvoice.core.StatusAnnouncement
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import android.content.ClipData
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.FindInPage
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.RateReview
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import de.kaikunze.marginvoice.core.BookFiles
import de.kaikunze.marginvoice.core.BookPackage
import de.kaikunze.marginvoice.core.CommentRecord
import de.kaikunze.marginvoice.core.ReviewDraftExport
import de.kaikunze.marginvoice.core.VoiceCommand
import de.kaikunze.marginvoice.model.Book
import de.kaikunze.marginvoice.model.BookStore
import de.kaikunze.marginvoice.model.Comment
import de.kaikunze.marginvoice.model.ExportBuilder
import de.kaikunze.marginvoice.model.Settings
import de.kaikunze.marginvoice.ui.TextStyles
import de.kaikunze.marginvoice.ui.Theme
import de.kaikunze.marginvoice.ui.components.ThemedSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(book: Book, store: BookStore, settings: Settings, cacheDir: File, onDismiss: () -> Unit) {
    val p = Theme.palette
    val context = LocalContext.current
    var options by remember { mutableStateOf(ExportBuilder.Options()) }
    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    var building by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var sourcePdf by remember { mutableStateOf(ExportBuilder.sourcePdf(book, store)) }
    // a picked PDF is kept with the book as source.pdf, where conversions keep theirs
    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val dest = File(store.folder(book), "source.pdf")
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> dest.outputStream().use { input.copyTo(it) } }
            sourcePdf = dest
        } catch (e: Exception) {
            error = e.message
        }
    }

    /** Shows the new files and deletes the folder of the ones they replace. */
    fun replace(new: List<File>) {
        val old = files.mapNotNull { f -> generateSequence(f) { it.parentFile }.firstOrNull { it.parentFile == cacheDir } }.toSet()
        files = new
        old.forEach { it.deleteRecursively() }
    }
    // the button title changes from "Preparing…" in place
    val announce = rememberAnnouncer()
    LaunchedEffect(files, building) {
        if (!building && files.isNotEmpty()) announce(StatusAnnouncement.exportReady(files.size))
    }
    LaunchedEffect(options, sourcePdf) {
        if (options.nothingChosen) { replace(emptyList()); return@LaunchedEffect }
        building = true
        val content = store.content(book)
        try {
            val built = withContext(Dispatchers.IO) { ExportBuilder.build(book, content, store, options, cacheDir, settings.commandLanguage, sourcePdf) }
            val pkg = if (options.bookPackage) {
                val dir = File(cacheDir, "export-${UUID.randomUUID()}").also { it.mkdirs() }
                File(dir, "${BookFiles.safeName(book.title).ifEmpty { "book" }}.${BookPackage.EXTENSION}").also { store.exportPackage(book, it) }
            } else null
            replace(built + listOfNotNull(pkg))
            error = null
        } catch (e: Exception) {
            replace(emptyList())
            error = e.message
        }
        building = false
    }
    DisposableEffect(Unit) { onDispose { replace(emptyList()) } }

    val comments = book.comments.count { it.kind == CommentRecord.Kind.COMMENT }
    val bookmarks = book.comments.size - comments
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = p.surface) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Export", style = TextStyles.headline, color = p.textPrimary, modifier = Modifier.semantics { heading() })
            Text(
                "$comments ${if (comments == 1) "comment" else "comments"}, $bookmarks ${if (bookmarks == 1) "bookmark" else "bookmarks"}",
                style = TextStyles.footnote, color = p.textSecondary, modifier = Modifier.padding(bottom = 8.dp),
            )
            Toggle(Icons.Outlined.Description, "Text with comments", "Markdown with {>> comments <<} after each sentence", options.annotatedText, enabled = book.textFile != null) {
                options = options.copy(annotatedText = it)
            }
            Toggle(Icons.AutoMirrored.Outlined.ListAlt, "Comment list (Markdown)", null, options.commentList) { options = options.copy(commentList = it) }
            Toggle(Icons.Outlined.TableChart, "Comment list (CSV)", null, options.csv) { options = options.copy(csv = it) }
            Toggle(Icons.Outlined.DataObject, "Comment list (JSON)", null, options.json) { options = options.copy(json = it) }
            Toggle(Icons.Rounded.GraphicEq, "Voice recordings", null, options.audio) { options = options.copy(audio = it) }
            Toggle(Icons.Outlined.RateReview, "Review draft", "Comments sorted by tag: summary, strengths, weaknesses, questions, minor", options.reviewDraft) {
                options = options.copy(reviewDraft = it)
            }
            if (options.reviewDraft) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    ReviewDraftExport.Layout.entries.forEachIndexed { i, layout ->
                        SegmentedButton(
                            selected = options.reviewLayout == layout, shape = SegmentedButtonDefaults.itemShape(i, 2),
                            onClick = { options = options.copy(reviewLayout = layout) },
                        ) { Text(if (layout == ReviewDraftExport.Layout.GENERIC) "Generic" else "IEEE VR form") }
                    }
                }
                Text(
                    "Headings in ${if (settings.commandLanguage == VoiceCommand.Language.GERMAN) "German" else "English"} (the command language). " +
                        "Untagged comments go under “Other notes”, bookmarks under “Passages to re-read”.",
                    style = TextStyles.footnote, color = p.textSecondary,
                )
            }
            Toggle(Icons.Outlined.Inventory2, "Whole book (.drafttalk)", "Audio, text, comments and recordings, to open in Klopfgeist on another device", options.bookPackage) {
                options = options.copy(bookPackage = it)
            }
            Toggle(Icons.Outlined.FindInPage, "Comments in the PDF", "The paper with a highlight and a note on each commented sentence", options.annotatedPdf, enabled = book.textFile != null) {
                options = options.copy(annotatedPdf = it)
            }
            if (options.annotatedPdf) {
                val pdf = sourcePdf
                if (pdf == null) {
                    Text("This book was not converted here, so the paper's PDF is needed once; it is kept with the book.", style = TextStyles.footnote, color = p.textSecondary)
                } else {
                    Text("Sentences the narration rewrote (equations, some captions) may not be found; those comments are listed in a note on the first page.", style = TextStyles.footnote, color = p.textSecondary)
                }
                TextButton(onClick = { pickPdf.launch(arrayOf("application/pdf")) }) {
                    Icon(Icons.Outlined.PictureAsPdf, null)
                    Text(if (pdf == null) "Choose the PDF…" else "Choose Another PDF…", modifier = Modifier.padding(start = 8.dp))
                }
            }
            if (book.comments.any { it.transcriptStatus == Comment.TranscriptStatus.PENDING }) {
                Text("Some comments are still being transcribed. Export again in a moment to include them.", style = TextStyles.footnote, color = p.textSecondary)
            }
            error?.let { Text("Export failed: $it", style = TextStyles.footnote, color = p.record) }
            files.forEach { Text(it.name, style = TextStyles.footnote, color = p.textSecondary) }
            // the one action of this sheet, where the thumb is
            Button(
                onClick = {
                    val uris = files.map { FileProvider.getUriForFile(context, "${context.packageName}.files", it) }
                    val intent = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
                        type = "*/*"
                        if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris[0]) else putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                        putExtra(Intent.EXTRA_SUBJECT, book.title)
                        clipData = ClipData.newRawUri(book.title, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, book.title))
                },
                enabled = files.isNotEmpty() && !building,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = p.accent, contentColor = p.onAccent),
            ) {
                Icon(Icons.Rounded.IosShare, null)
                Text(
                    when {
                        options.nothingChosen -> "Choose What to Export"
                        building || files.isEmpty() -> "Preparing…"
                        else -> "Share ${files.size} ${if (files.size == 1) "File" else "Files"}"
                    },
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun Toggle(icon: ImageVector, title: String, subtitle: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val p = Theme.palette
    // one node for a screen reader, and the whole row toggles: the title, "switch", on or off
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(checked && enabled, enabled = enabled, role = Role.Switch, onValueChange = onChange), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, tint = p.accent, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) p.textPrimary else p.textSecondary)
            subtitle?.let { Text(it, style = TextStyles.caption, color = p.textSecondary) }
        }
        ThemedSwitch(checked = checked && enabled, onCheckedChange = null, enabled = enabled)
    }
}
