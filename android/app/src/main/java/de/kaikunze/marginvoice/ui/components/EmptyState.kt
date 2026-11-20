package de.kaikunze.marginvoice.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.kaikunze.marginvoice.ui.TextStyles
import de.kaikunze.marginvoice.ui.Theme

/** iOS `ContentUnavailableView`: a symbol, a title, an explanation and optional actions. */
@Composable
fun EmptyState(icon: ImageVector, title: String, description: String, modifier: Modifier = Modifier, actions: @Composable () -> Unit = {}) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = Theme.palette.textSecondary, modifier = Modifier.size(48.dp))
        Text(title, style = TextStyles.title2, fontWeight = FontWeight.Bold, color = Theme.palette.textPrimary, textAlign = TextAlign.Center)
        Text(description, style = TextStyles.subheadline, color = Theme.palette.textSecondary, textAlign = TextAlign.Center)
        actions()
    }
}
