package de.kaikunze.marginvoice.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import de.kaikunze.marginvoice.ui.TextStyles
import de.kaikunze.marginvoice.ui.Theme

/** Shows that tapping the pill does something. */
sealed interface PillAccessory {
    data class Symbol(val icon: ImageVector) : PillAccessory
    /** A short action word in a filled chip ("Turn On"). */
    data class Action(val title: String) : PillAccessory
}

/** Shows what the app is doing right now: listening, recording, saved, paused. */
@Composable
fun StatusPill(
    label: String,
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    /** Shown when the label doesn't fit on one line; the full label stays the spoken one. */
    shortLabel: String? = null,
    level: Float = 0f,
    pulsing: Boolean = false,
    accessory: PillAccessory? = null,
) {
    val pulse = if (pulsing) {
        val transition = rememberInfiniteTransition(label = "pulse")
        val a by transition.animateFloat(1f, 0.35f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "alpha")
        a
    } else 1f
    Row(
        modifier
            .background(tint.copy(alpha = 0.14f), CircleShape)
            .border(1.dp, tint.copy(alpha = 0.45f), CircleShape)
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .clearAndSetSemantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp).alpha(pulse))
        val text = @Composable { t: String ->
            Text(t, style = TextStyles.subheadline, fontWeight = FontWeight.SemiBold, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (shortLabel != null) {
            // the full label when it fits on one line, else the short one, at its own width
            SubcomposeLayout(Modifier.weight(1f, fill = false)) { c ->
                val full = subcompose("full") { text(label) }.first().measure(Constraints(maxHeight = c.maxHeight))
                val shown = if (full.width <= c.maxWidth) full else subcompose("short") { text(shortLabel) }.first().measure(c.copy(minWidth = 0))
                layout(shown.width, shown.height) { shown.place(0, 0) }
            }
        } else {
            Box(Modifier.weight(1f, fill = false)) { text(label) }
        }
        if (level > 0.02f) LevelBars(level, tint)
        when (accessory) {
            is PillAccessory.Symbol -> Icon(accessory.icon, null, tint = tint, modifier = Modifier.size(18.dp))
            is PillAccessory.Action -> Text(
                accessory.title, style = TextStyles.subheadline, fontWeight = FontWeight.SemiBold, color = Theme.palette.onAccent,
                modifier = Modifier.background(Theme.palette.accent, CircleShape).padding(horizontal = 8.dp, vertical = 2.dp),
            )
            null -> {}
        }
    }
}

@Composable
private fun LevelBars(level: Float, tint: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(14.dp)) {
        for (i in 0 until 4) {
            val h by animateFloatAsState(4 + minOf(1f, level * (4 - i) / 2) * 10, tween(100), label = "bar")
            Box(Modifier.width(3.dp).height(h.dp).background(tint, CircleShape))
        }
    }
}
