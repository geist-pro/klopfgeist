package de.kaikunze.marginvoice.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp

/**
 * Forms and running text keep a readable width on tablets and unfolded phones, centred
 * (Material: content is not stretched over a wide window). Put it after `verticalScroll`,
 * so the margins still scroll. No effect below 840 dp.
 */
fun Modifier.readableWidth(): Modifier = wrapContentWidth().widthIn(max = 840.dp).fillMaxWidth()

/**
 * A scaffold's padding without the bottom, for the parent of a list. The list takes the bottom
 * as `contentPadding` ([bottomOnly]), so its last rows scroll behind the navigation bar
 * instead of being cut off above it.
 */
@Composable
fun PaddingValues.withoutBottom(): PaddingValues {
    val direction = LocalLayoutDirection.current
    return PaddingValues(start = calculateStartPadding(direction), top = calculateTopPadding(), end = calculateEndPadding(direction))
}

fun PaddingValues.bottomOnly(): PaddingValues = PaddingValues(bottom = calculateBottomPadding())
