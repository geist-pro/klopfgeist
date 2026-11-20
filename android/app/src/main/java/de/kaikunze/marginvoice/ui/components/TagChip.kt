package de.kaikunze.marginvoice.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.kaikunze.marginvoice.core.ReviewTag
import de.kaikunze.marginvoice.core.VoiceCommand
import de.kaikunze.marginvoice.ui.TextStyles
import de.kaikunze.marginvoice.ui.Theme
import de.kaikunze.marginvoice.ui.tagTint

/**
 * A comment's review tag ("Weakness") in an outline of the tag's colour. The word is in the
 * primary text colour: the tints on their own tinted fill fall below 4.5:1.
 */
@Composable
fun TagChip(tag: ReviewTag, language: VoiceCommand.Language, modifier: Modifier = Modifier) {
    val tint = Theme.palette.tagTint(tag)
    Text(
        tag.displayName(language), style = TextStyles.footnote, fontWeight = FontWeight.SemiBold, color = Theme.palette.textPrimary, maxLines = 1,
        modifier = modifier.background(tint.copy(alpha = 0.14f), CircleShape).border(1.dp, tint, CircleShape)
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}
