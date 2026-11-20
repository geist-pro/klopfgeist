package de.kaikunze.marginvoice.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import de.kaikunze.marginvoice.core.ChapterMark
import de.kaikunze.marginvoice.core.ScrubberMarker
import de.kaikunze.marginvoice.core.TimeFormat
import de.kaikunze.marginvoice.ui.TextStyles
import de.kaikunze.marginvoice.ui.Theme

/**
 * Playback position with chapter ticks, comment dots above the track and
 * bookmarks on it (a tick, or a bar for a bookmarked passage).
 */
@Composable
fun Scrubber(
    /** Read while drawing and in the two time labels only, so the caller does not recompose as the book plays. */
    time: () -> Double,
    duration: Double,
    chapters: List<ChapterMark>,
    /** Comments, bookmarks and passages (`ReviewListing.markers`), drawn in this order. */
    markers: List<ScrubberMarker>,
    onSeek: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragTime by remember { mutableStateOf<Double?>(null) }
    val shown = { dragTime ?: time() }
    val thumb by animateDpAsState(if (dragTime == null) 16.dp else 22.dp, label = "thumb")
    val p = Theme.palette
    fun seekTime(x: Float, width: Float) = (x / maxOf(width, 1f) * duration).coerceIn(0.0, maxOf(0.0, duration))

    Column(
        modifier.semantics {
            contentDescription = "Position"
            val now = time()
            stateDescription = "${TimeFormat.clock(now)} of ${TimeFormat.clock(duration)}"
            progressBarRangeInfo = ProgressBarRangeInfo(now.toFloat(), 0f..maxOf(0.001f, duration.toFloat()))
            setProgress { onSeek(it.toDouble().coerceIn(0.0, duration)); true }
        },
    ) {
        Canvas(
            Modifier.fillMaxWidth().height(48.dp)
                .pointerInput(duration) {
                    detectTapGestures { onSeek(seekTime(it.x, size.width.toFloat())) }
                }
                .pointerInput(duration) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragTime = seekTime(it.x, size.width.toFloat()) },
                        onDragEnd = { dragTime?.let(onSeek); dragTime = null },
                        // resets when the system cancels the drag
                        onDragCancel = { dragTime = null },
                    ) { change, _ -> dragTime = seekTime(change.position.x, size.width.toFloat()) }
                },
        ) {
            val w = size.width
            val mid = size.height / 2
            val at = shown()
            fun x(t: Double) = if (duration > 0) (t / duration).toFloat() * w else 0f
            val track = 6.dp.toPx()
            // the control colour: the border colour is too close to the background to see the track
            drawRoundRect(p.control, Offset(0f, mid - track / 2), Size(w, track), CornerRadius(track / 2))
            drawRoundRect(p.accent, Offset(0f, mid - track / 2), Size(x(at), track), CornerRadius(track / 2))
            for (c in chapters.drop(1)) {
                drawRect(p.textPrimary, Offset(x(c.start) - 0.75.dp.toPx(), mid - 5.dp.toPx()), Size(1.5.dp.toPx(), 10.dp.toPx()))
            }
            for (m in markers) {
                val x = m.start * w
                when (m.kind) {
                    ScrubberMarker.Kind.PASSAGE -> {
                        val width = maxOf(4.dp.toPx(), ((m.end ?: m.start) - m.start) * w)
                        drawRoundRect(p.bookmark, Offset(x, mid - track / 2), Size(width, track), CornerRadius(track / 2))
                    }
                    ScrubberMarker.Kind.BOOKMARK ->
                        drawRoundRect(p.bookmark, Offset(x - 1.5.dp.toPx(), mid - 7.dp.toPx()), Size(3.dp.toPx(), 14.dp.toPx()), CornerRadius(1.5.dp.toPx()))
                    ScrubberMarker.Kind.COMMENT -> drawCircle(p.record, 3.5.dp.toPx(), Offset(x, mid - 11.dp.toPx()))
                }
            }
            drawCircle(p.textPrimary, thumb.toPx() / 2, Offset(x(at), mid))
        }
        TimeLabels(shown, duration)
    }
}

/** Elapsed and remaining time. The labels change once a second, the position several times: only the text is derived. */
@Composable
private fun TimeLabels(shown: () -> Double, duration: Double) {
    val elapsed by remember(shown) { derivedStateOf { TimeFormat.clock(shown()) } }
    val remaining by remember(shown, duration) { derivedStateOf { "-" + TimeFormat.clock(maxOf(0.0, duration - shown())) } }
    Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        val digits = TextStyles.footnote.copy(fontFeatureSettings = "tnum")
        Text(elapsed, style = digits, color = Theme.palette.textSecondary)
        Spacer(Modifier.weight(1f))
        Text(remaining, style = digits, color = Theme.palette.textSecondary)
    }
}

