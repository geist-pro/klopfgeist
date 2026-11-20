package de.kaikunze.marginvoice.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.kaikunze.marginvoice.ui.Theme

/** A large round transport control that is easy to hit without looking. */
@Composable
fun BigControlButton(
    icon: ImageVector,
    label: String,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 76.dp,
    prominent: Boolean = false,
    /**
     * The glyph on a prominent (filled) button. By default the scheme's colour on the record red
     * (the Comment button); Play passes the palette's `onAccent`, since white is too faint on a light tint (Gruvbox teal).
     */
    glyph: Color = MaterialTheme.colorScheme.onError,
    onClick: () -> Unit,
) {
    if (prominent) {
        FilledIconButton(
            onClick = onClick, modifier = modifier.size(size), shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = tint, contentColor = glyph),
        ) { Icon(icon, label, Modifier.size(size * 0.38f)) }
    } else {
        FilledTonalIconButton(
            onClick = onClick, modifier = modifier.size(size), shape = CircleShape,
            colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Theme.palette.surface, contentColor = tint),
        ) { Icon(icon, label, Modifier.size(size * 0.38f)) }
    }
}
