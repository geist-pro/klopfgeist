package de.kaikunze.marginvoice.ui.screens

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.kaikunze.marginvoice.core.CommentRecord
import de.kaikunze.marginvoice.core.TimeFormat
import de.kaikunze.marginvoice.model.Book
import de.kaikunze.marginvoice.ui.TextStyles
import de.kaikunze.marginvoice.ui.Theme
import de.kaikunze.marginvoice.voice.SessionHost

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChaptersSheet(book: Book, session: SessionHost, onDismiss: () -> Unit) {
    val p = Theme.palette
    val current by remember(book, session) { derivedStateOf { book.chapterIndex(session.engine.currentTime) } }
    val list = rememberLazyListState()
    LaunchedEffect(Unit) { current?.let { list.scrollToItem(maxOf(0, it - 3)) } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = p.surface) {
        Text("Chapters", style = TextStyles.headline, color = p.textPrimary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp).semantics { heading() })
        LazyColumn(state = list) {
            itemsIndexed(book.chapters) { i, chapter ->
                Row(
                    Modifier.fillMaxWidth().clickable(role = Role.Button) { session.seek(chapter.start); onDismiss() }.heightIn(min = 48.dp).semantics { selected = current == i }.padding(horizontal = 20.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.AutoMirrored.Rounded.VolumeUp, if (current == i) "Now playing" else null, tint = p.accent, modifier = Modifier.size(18.dp).alpha(if (current == i) 1f else 0f))
                    Text(
                        chapter.title, color = if (current == i) p.accent else p.textPrimary,
                        fontWeight = if (current == i) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f),
                    )
                    val count = book.comments.count { it.kind == CommentRecord.Kind.COMMENT && it.mediaTime >= chapter.start && it.mediaTime < chapter.end }
                    if (count > 0) {
                        Icon(Icons.Outlined.ChatBubbleOutline, null, tint = p.record, modifier = Modifier.size(14.dp))
                        Text("$count", style = TextStyles.caption, color = p.record)
                    }
                    Text(TimeFormat.clock(chapter.start), style = TextStyles.caption.copy(fontFeatureSettings = "tnum"), color = p.textSecondary)
                }
            }
            item { Spacer(Modifier.padding(16.dp)) }
        }
    }
}
