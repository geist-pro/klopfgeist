package de.kaikunze.marginvoice.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints

/**
 * SwiftUI's `ViewThatFits(in: .horizontal)`: the first candidate whose natural width fits, else the last.
 * The probes are composed only to be measured and are never placed; their semantics are cleared, or a
 * screen reader (and a test) would find every button once per probe.
 */
@Composable
fun ViewThatFits(modifier: Modifier = Modifier, candidates: List<@Composable () -> Unit>) {
    SubcomposeLayout(modifier) { constraints ->
        var chosen = candidates.lastIndex
        for (i in candidates.indices) {
            val natural = subcompose("probe$i") { Box(Modifier.clearAndSetSemantics {}) { candidates[i]() } }.map { it.measure(Constraints(maxHeight = constraints.maxHeight)) }
            if ((natural.maxOfOrNull { it.width } ?: 0) <= constraints.maxWidth) {
                chosen = i
                break
            }
        }
        val placeables = subcompose("chosen", candidates[chosen]).map { it.measure(constraints.copy(minHeight = 0)) }
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else placeables.maxOfOrNull { it.width } ?: 0
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(width, height) { placeables.forEach { it.place(0, 0) } }
    }
}
