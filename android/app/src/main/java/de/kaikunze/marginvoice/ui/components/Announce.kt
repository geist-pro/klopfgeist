package de.kaikunze.marginvoice.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * Tells TalkBack about a change that only shows on screen. What is said, and when nothing
 * is, is decided by `StatusAnnouncement` in the core; a null or empty text is silence.
 */
@Composable
fun rememberAnnouncer(): (String?) -> Unit {
    val view = LocalView.current
    return remember(view) { { text: String? -> if (!text.isNullOrEmpty()) view.announceForAccessibility(text) } }
}
