package de.kaikunze.marginvoice.ui.screens

import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.kaikunze.marginvoice.core.SampleBooks
import de.kaikunze.marginvoice.core.TimeFormat
import de.kaikunze.marginvoice.model.Book
import de.kaikunze.marginvoice.model.SampleDownloads
import de.kaikunze.marginvoice.ui.TextStyles
import de.kaikunze.marginvoice.ui.Theme
import java.io.File

/** The narrated sample papers: download one into the library, or open it once it is there. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SamplesSheet(books: List<Book>, downloads: SampleDownloads, inbox: File, onOpen: (Book) -> Unit, onDismiss: () -> Unit) {
    val p = Theme.palette
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = p.surface) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            Text("Sample Papers", style = TextStyles.headline, color = p.textPrimary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp).semantics { heading() })
            Text(
                "Narrated papers with their PDF, to try the voice commands on. Each opens in the library like any other book.",
                style = TextStyles.subheadline, color = p.textSecondary, modifier = Modifier.padding(horizontal = 20.dp),
            )
            for (sample in SampleBooks.all) {
                val book = books.firstOrNull { it.id.toString().equals(sample.id, ignoreCase = true) }
                val progress = downloads.progress[sample.file]
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(sample.title, style = TextStyles.headline, color = p.textPrimary)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${sample.credit} · ${TimeFormat.clock(sample.duration)} · ${SampleBooks.size(sample.bytes)}",
                            style = TextStyles.caption, color = p.textSecondary, modifier = Modifier.weight(1f),
                        )
                        when {
                            book != null -> TextButton(onClick = { onDismiss(); onOpen(book) }) {
                                Icon(Icons.Rounded.CheckCircle, null, tint = p.success, modifier = Modifier.size(16.dp))
                                Text("Open", modifier = Modifier.padding(start = 6.dp))
                            }
                            progress != null -> IconButton(onClick = { downloads.cancel(sample) }) { Icon(Icons.Rounded.Close, "Stop download", tint = p.textSecondary) }
                            else -> OutlinedButton(onClick = { downloads.download(sample, inbox) }) { Text("Download") }
                        }
                    }
                    if (progress != null) {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), color = p.accent, trackColor = p.control)
                    }
                    downloads.errors[sample.file]?.let { Text(it, style = TextStyles.caption, color = p.warning) }
                }
                HorizontalDivider(color = p.surfaceBorder, modifier = Modifier.padding(start = 20.dp))
            }
            Text(
                "Downloaded from github.com/kkai/marginvoice, narrated with the Kokoro voice.",
                style = TextStyles.caption, color = p.textSecondary, modifier = Modifier.padding(20.dp),
            )
        }
    }
}
