package de.kaikunze.marginvoice.ui.components

import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import de.kaikunze.marginvoice.ui.Theme

/**
 * A switch whose thumb shows when it is off (Material's default paints it in the track's colour here),
 * with an outline that shows on the surface of a card or sheet.
 */
@Composable
fun ThemedSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, enabled: Boolean = true) {
    val p = Theme.palette
    Switch(
        checked, onCheckedChange, enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = p.onAccent, checkedTrackColor = p.accent, checkedBorderColor = p.accent,
            uncheckedThumbColor = p.textSecondary, uncheckedTrackColor = p.surface, uncheckedBorderColor = p.control,
            disabledUncheckedThumbColor = p.surfaceBorder, disabledUncheckedTrackColor = p.surface, disabledUncheckedBorderColor = p.surfaceBorder,
        ),
    )
}
