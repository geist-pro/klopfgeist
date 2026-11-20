package de.kaikunze.marginvoice.ui.screens

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import android.speech.tts.Voice
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.kaikunze.marginvoice.conversion.ConversionCenter
import de.kaikunze.marginvoice.conversion.SpeechRenderer
import de.kaikunze.marginvoice.model.ImportSource
import de.kaikunze.marginvoice.model.Settings
import de.kaikunze.marginvoice.ui.TextStyles
import de.kaikunze.marginvoice.ui.Theme
import java.util.Locale

/** Reads a picked document, shows what will be read, and starts the conversion with the chosen voice. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConvertSheet(source: ImportSource, conversions: ConversionCenter, settings: Settings, onDismiss: () -> Unit, onStarted: () -> Unit) {
    val p = Theme.palette
    var draft by remember { mutableStateOf<ConversionCenter.Draft?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var renderer by remember { mutableStateOf<SpeechRenderer?>(null) }
    var title by remember { mutableStateOf("") }
    var voice by remember { mutableStateOf<Voice?>(null) }
    var showChapters by remember { mutableStateOf(false) }
    LaunchedEffect(source) {
        try {
            val d = conversions.prepare(source)
            draft = d
            title = d.title
        } catch (e: Exception) {
            error = e.message ?: "The document could not be read."
        }
        renderer = runCatching { conversions.renderer() }.getOrNull()
    }
    val d = draft
    val voices = remember(d?.language, renderer) { d?.let { renderer?.voices(it.language) }.orEmpty() }
    LaunchedEffect(voices) {
        val lang = d?.language ?: return@LaunchedEffect
        voice = voices.firstOrNull { it.name == settings.voiceIdentifier(lang) } ?: voices.firstOrNull()
    }

    ModalBottomSheet(onDismissRequest = { renderer?.stopPreview(); onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = p.surface) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("New Audiobook", style = TextStyles.headline, color = p.textPrimary, modifier = Modifier.semantics { heading() })
            when {
                error != null -> Text(error!!, color = p.warning)
                d == null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(color = p.accent)
                    Text("Reading ${source.name}…", color = p.textSecondary)
                }
                else -> {
                    OutlinedTextField(
                        title, { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    )
                    Text("Language", style = TextStyles.footnote, color = p.textSecondary)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        listOf("en" to "English", "de" to "Deutsch").forEachIndexed { i, (code, name) ->
                            SegmentedButton(
                                selected = d.language == code, shape = SegmentedButtonDefaults.itemShape(i, 2),
                                onClick = { draft = conversions.replan(d, title, code) },
                            ) { Text(name) }
                        }
                    }
                    VoicePicker(voices, voice) { voice = it }
                    TextButton(onClick = { renderer?.preview(d.plan.utterances.drop(1).firstOrNull()?.text ?: d.plan.text.take(200), voice) }) {
                        Icon(Icons.Rounded.VolumeUp, null)
                        Text("Listen to a Sample", modifier = Modifier.padding(start = 8.dp))
                    }
                    val minutes = d.plan.estimatedMinutes
                    Text(
                        "About ${if (minutes < 1) "1" else String.format(Locale.ROOT, "%.0f", minutes)} min · ${d.plan.wordCount} words" +
                            (if (d.pageCount > 0) " · ${d.pageCount} pages" else ""),
                        style = TextStyles.footnote, color = p.textSecondary,
                    )
                    val chapters = d.plan.chapters
                    if (chapters.isNotEmpty()) {
                        Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { showChapters = !showChapters }.heightIn(min = 48.dp).semantics { stateDescription = if (showChapters) "Expanded" else "Collapsed" }, verticalAlignment = Alignment.CenterVertically) {
                            Text("${chapters.size} chapters", color = p.textPrimary, modifier = Modifier.weight(1f))
                            Icon(if (showChapters) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = p.textSecondary)
                        }
                        if (showChapters) chapters.forEach { Text(it, style = TextStyles.footnote, color = p.textSecondary) }
                    }
                    Button(
                        onClick = {
                            renderer?.stopPreview()
                            val final = if (title != d.title) conversions.replan(d, title, d.language) else d
                            voice?.let { settings.setVoiceIdentifier(it.name, final.language) }
                            conversions.start(final, voice)
                            onStarted()
                        },
                        enabled = title.isNotBlank() && renderer != null,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = p.accent, contentColor = p.onAccent),
                    ) { Text("Create Audiobook", fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    }
}

@Composable
private fun VoicePicker(voices: List<Voice>, selected: Voice?, onSelect: (Voice) -> Unit) {
    val p = Theme.palette
    var open by remember { mutableStateOf(false) }
    fun label(v: Voice) = "${v.name} · ${quality(v.quality)}"
    Box {
        Row(Modifier.fillMaxWidth().clickable(enabled = voices.isNotEmpty(), role = Role.DropdownList) { open = true }.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Voice", color = p.textPrimary, modifier = Modifier.weight(1f))
            Text(selected?.let(::label) ?: "No voice installed", style = TextStyles.footnote, color = p.textSecondary)
            Icon(Icons.Rounded.ExpandMore, null, tint = p.textSecondary)
        }
        DropdownMenu(open, { open = false }, containerColor = p.surface) {
            voices.forEach { v -> DropdownMenuItem(text = { Text(label(v)) }, onClick = { open = false; onSelect(v) }) }
        }
    }
}

private fun quality(q: Int) = when {
    q >= Voice.QUALITY_VERY_HIGH -> "Premium"
    q >= Voice.QUALITY_HIGH -> "Enhanced"
    else -> "Default"
}
