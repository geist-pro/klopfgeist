package de.kaikunze.marginvoice.ui.screens

import androidx.compose.ui.semantics.Role
import de.kaikunze.marginvoice.ui.components.readableWidth
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.kaikunze.marginvoice.ui.TextStyles
import de.kaikunze.marginvoice.ui.Theme

/** The NOTICE and the licence texts from `assets/licenses/`; tap a licence to read it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicencesScreen(onBack: () -> Unit) {
    val p = Theme.palette
    val context = LocalContext.current
    val files = remember { context.assets.list("licenses").orEmpty().filter { it != NOTICE }.sorted() }
    val notice = remember { runCatching { context.assets.open("licenses/$NOTICE").bufferedReader().readText() }.getOrDefault("") }
    var open by remember { mutableStateOf<String?>(null) }
    Scaffold(
        containerColor = p.background,
        topBar = {
            TopAppBar(
                title = { Text(open?.removeSuffix(".txt") ?: "Licences") },
                navigationIcon = { IconButton(onClick = { if (open != null) open = null else onBack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = p.background),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).readableWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val name = open
            if (name != null) {
                val text = remember(name) { runCatching { context.assets.open("licenses/$name").bufferedReader().readText() }.getOrDefault("") }
                Text(text, style = TextStyles.footnote, fontFamily = FontFamily.Monospace, color = p.textPrimary)
            } else {
                Text(notice, style = TextStyles.footnote, color = p.textPrimary)
                HorizontalDivider(color = p.textSecondary.copy(alpha = 0.3f))
                Text("Licence texts", fontWeight = FontWeight.SemiBold, color = p.textSecondary)
                files.forEach { f ->
                    Text(
                        f.removeSuffix(".txt"), color = p.accent,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { open = f }.padding(vertical = 10.dp),
                    )
                }
            }
        }
    }
}

private const val NOTICE = "NOTICE.txt"
