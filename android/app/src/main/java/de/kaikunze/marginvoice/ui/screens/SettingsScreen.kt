package de.kaikunze.marginvoice.ui.screens

import androidx.compose.runtime.LaunchedEffect
import de.kaikunze.marginvoice.ui.components.rememberAnnouncer
import de.kaikunze.marginvoice.core.StatusAnnouncement
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import de.kaikunze.marginvoice.ui.components.readableWidth
import de.kaikunze.marginvoice.ui.components.withoutBottom
import de.kaikunze.marginvoice.ui.components.bottomOnly
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import de.kaikunze.marginvoice.BuildConfig
import de.kaikunze.marginvoice.sync.SyncCenter
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.kaikunze.marginvoice.core.VoiceCommand
import de.kaikunze.marginvoice.model.BookFont
import de.kaikunze.marginvoice.model.ColorTheme
import de.kaikunze.marginvoice.model.CommentLanguage
import de.kaikunze.marginvoice.model.Settings
import de.kaikunze.marginvoice.ui.Space
import de.kaikunze.marginvoice.ui.TextStyles
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import de.kaikunze.marginvoice.ui.Theme
import de.kaikunze.marginvoice.ui.components.ThemedSwitch
import de.kaikunze.marginvoice.ui.components.EmptyState
import de.kaikunze.marginvoice.voice.SessionHost
import java.io.File
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: Settings, session: SessionHost, onBack: () -> Unit, onCommands: () -> Unit, onLog: () -> Unit,
    sync: SyncCenter? = null, onLicences: () -> Unit = {},
) {
    val p = Theme.palette
    val context = LocalContext.current
    Scaffold(
        containerColor = p.background,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = p.background),
            )
        },
    ) { padding ->
        // the sync fields stay above the keyboard
        Column(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding().verticalScroll(rememberScrollState()).readableWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Group(
                "Voice",
                "Klopfgeist listens only while a book is open, and speech is recognized on this phone. Say a command on its own, at a normal volume, with a short pause before and after. " +
                    "A paused book stops listening after the time above${if (settings.stopListeningAfter > 0) ", after 1 minute in the background, or as soon as another app plays sound" else ""}; press Play or tap the status to listen again. Comments are transcribed in the book’s language unless you choose one, with names and terms from the text around them.",
            ) {
                ToggleRow("Voice Commands", settings.voiceCommandsEnabled) { session.setVoiceCommands(it) }
                if (settings.voiceCommandsEnabled) {
                    ChoiceRow(
                        "Stop Listening When Paused", listOf(1, 5, 15, 0), settings.stopListeningAfter.toInt(),
                        { if (it == 0) "Never" else "After $it min" },
                    ) { settings.stopListeningAfter = it.toDouble() }
                }
                ChoiceRow("Command Language", VoiceCommand.Language.entries, settings.commandLanguage, { it.displayName }) {
                    settings.commandLanguage = it
                    session.restartListening()
                }
                ChoiceRow("Comment Language", CommentLanguage.entries, settings.commentLanguage, { it.displayName }) { settings.commentLanguage = it }
                LinkRow("What You Can Say", onCommands)
            }
            Group(
                "Comments",
                "A comment ends when you say “continue” or “weiter”${if (settings.endCommentOnSilence) ", or after the pause above" else ""} (or after 5 minutes). " +
                    "The anchor moves the comment back a little, to the passage you were reacting to. After the comment, the book plays on from shortly before you said “stop”, starting at the beginning of a sentence when one is close.",
            ) {
                ToggleRow("End Comment After Silence", settings.endCommentOnSilence) { settings.endCommentOnSilence = it }
                if (settings.endCommentOnSilence) {
                    Stepper("After ${settings.silenceTimeout.toInt()} s of silence", settings.silenceTimeout, 3.0..20.0, 1.0) { settings.silenceTimeout = it }
                }
                Stepper("Anchor ${fmt1(settings.reactionOffset)} s before “stop”", settings.reactionOffset, 0.0..5.0, 0.5) { settings.reactionOffset = it }
                Stepper("Play on from ${fmt1(settings.resumeRewind)} s before “stop”", settings.resumeRewind, 0.0..10.0, 0.5) { settings.resumeRewind = it }
                Stepper("New books play at ${String.format(Locale.ROOT, "%.2g", settings.defaultRate)}×", settings.defaultRate, 0.7..2.5, 0.1) { settings.defaultRate = it }
            }
            Group("Playback", "When a book ends, Klopfgeist says its title and plays the next unfinished book in your library.") {
                ToggleRow("Continue with Next Book", settings.continueWithNextBook) {
                    settings.continueWithNextBook = it
                    session.refreshUpNext()
                }
            }
            Group(
                "Audio",
                "The phone’s microphone listens, with echo cancellation, while the book plays over the speaker, headphones or the car. " +
                    "A car or headset microphone switches Bluetooth to call quality, which makes the book sound worse.",
            ) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Microphone", color = p.textPrimary, modifier = Modifier.weight(1f))
                    Text(session.microphone, style = TextStyles.footnote, color = p.textSecondary)
                }
                ToggleRow("Use Car or Headset Microphone", settings.useHeadsetMicrophone) {
                    settings.useHeadsetMicrophone = it
                    session.restartListening()
                }
                ToggleRow("Echo Cancellation", settings.echoCancellation) {
                    settings.echoCancellation = it
                    session.echoCancellationChanged()
                }
                ToggleRow("Keep Screen On While Playing", settings.keepScreenAwake) { settings.keepScreenAwake = it }
            }
            Group("Appearance", null) {
                ChoiceRow("Color Scheme", ColorTheme.entries, settings.colorTheme, { it.displayName }) { settings.colorTheme = it }
                ChoiceRow("Book Text", BookFont.entries, settings.bookFont, { it.displayName }) { settings.bookFont = it }
            }
            if (sync != null) SyncGroup(sync)
            Group(
                "Diagnostics",
                "When a command was not heard, export the session trace right after: it holds what the recognizer heard and what the app decided " +
                    "in the last minutes (recognized words with their times and the command log, no audio), and stays on this phone until you share it.",
            ) {
                ToggleRow("Show Command Log", settings.showDebugLog) { settings.showDebugLog = it }
                if (settings.showDebugLog) LinkRow("Command Log", onLog)
                LinkRow("Export Session Trace") { shareTrace(context, session) }
            }
            Group("About", "Klopfgeist ${BuildConfig.VERSION_NAME}. Everything you say is recognized and kept on this phone, unless you turn on sync.") {
                LinkRow("Licences", onLicences)
            }
        }
    }
}

/** Writes the session's flight recorder into the cache (cleared at the next launch, like exports) and offers it to other apps. */
private fun shareTrace(context: Context, session: SessionHost) {
    val file = runCatching { session.writeTrace(File(context.cacheDir, "export-trace")) }.getOrElse {
        session.errorMessage = "Could not write the session trace: ${it.message}"
        return
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/x-ndjson"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "Klopfgeist session trace")
        clipData = ClipData.newRawUri(file.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, "Session trace")) }
        .onFailure { session.errorMessage = "No app can receive the session trace." }
}

/** Books and comments in a WebDAV folder (Nextcloud), shared with DraftTalk on other phones, the iPad and the Mac. */
@Composable
private fun SyncGroup(sync: SyncCenter) {
    val p = Theme.palette
    var server by remember { mutableStateOf(sync.server) }
    var user by remember { mutableStateOf(sync.user) }
    var password by remember { mutableStateOf("") }
    val status = when {
        sync.isSyncing -> "Syncing…"
        sync.error != null -> sync.error!!
        sync.lastSync != null -> "Last synced " + DateTimeFormatter.ofPattern("d MMM, HH:mm").withZone(ZoneId.systemDefault()).format(sync.lastSync)
        else -> null
    }
    // the status line turns into the error in place
    val announce = rememberAnnouncer()
    var wasSyncing by remember { mutableStateOf(sync.isSyncing) }
    LaunchedEffect(sync.isSyncing) {
        if (wasSyncing && !sync.isSyncing && sync.error != null) announce(StatusAnnouncement.SYNC_FAILED)
        wasSyncing = sync.isSyncing
    }
    Group(
        "Sync",
        "Books, comments and recordings are kept in the folder MarginVoice on your Nextcloud (or another WebDAV server), " +
            "so Klopfgeist on your other devices has them too. Use an app password (Nextcloud: Settings › Security). " +
            "Audio is large: the first sync of a book downloads all of it.",
    ) {
        ToggleRow("Sync with Nextcloud / WebDAV", sync.enabled) { on ->
            if (on && sync.server.isNotEmpty() && sync.hasPassword) sync.turn(true) else if (!on) sync.turn(false) else sync.configure(server, user, password)
        }
        OutlinedTextField(
            server, { server = it }, label = { Text("Server") }, placeholder = { Text("cloud.example.org") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(user, { user = it }, label = { Text("User name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            password, { password = it }, label = { Text(if (sync.hasPassword) "App password (saved)" else "App password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(status ?: "", style = TextStyles.footnote, color = if (sync.error != null) p.record else p.textSecondary, modifier = Modifier.weight(1f))
            TextButton(
                onClick = { sync.configure(server, user, password); password = "" },
                enabled = !sync.isSyncing && server.isNotBlank() && user.isNotBlank() && (password.isNotEmpty() || sync.hasPassword),
            ) { Text("Sync Now") }
        }
    }
}

private fun fmt1(x: Double) = String.format(Locale.ROOT, "%.1f", x)

@Composable
private fun Group(title: String, footer: String?, content: @Composable () -> Unit) {
    val p = Theme.palette
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title.uppercase(), style = TextStyles.footnote, fontWeight = FontWeight.SemiBold, color = p.textSecondary, modifier = Modifier.padding(start = 4.dp).semantics { heading() })
        Surface(color = p.surface, shape = Space.card) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)) { content() }
        }
        footer?.let { Text(it, style = TextStyles.footnote, color = p.textSecondary, modifier = Modifier.padding(horizontal = 4.dp)) }
    }
}

@Composable
private fun ToggleRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    // one node for a screen reader: the title, "switch", on or off (the switch alone has no name)
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).toggleable(checked, role = Role.Switch, onValueChange = onChange), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Theme.palette.textPrimary, modifier = Modifier.weight(1f))
        ThemedSwitch(checked, null)
    }
}

@Composable
private fun LinkRow(title: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button, onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Theme.palette.textPrimary, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Theme.palette.textSecondary)
    }
}

@Composable
private fun <T> ChoiceRow(title: String, options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    val p = Theme.palette
    var open by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.DropdownList) { open = true }, verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = p.textPrimary, modifier = Modifier.weight(1f))
            Text(label(selected), color = p.textSecondary)
            Icon(Icons.Rounded.ExpandMore, null, tint = p.textSecondary)
        }
        DropdownMenu(open, { open = false }, containerColor = p.surface) {
            options.forEach { o -> DropdownMenuItem(text = { Text(label(o)) }, onClick = { open = false; onSelect(o) }) }
        }
    }
}

@Composable
private fun Stepper(label: String, value: Double, range: ClosedFloatingPointRange<Double>, step: Double, onChange: (Double) -> Unit) {
    val p = Theme.palette
    fun round(x: Double) = Math.round(x / step) * step
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = p.textPrimary, modifier = Modifier.weight(1f))
        // named after the setting: "Less" alone says nothing when a screen reader lands on the button
        IconButton(onClick = { onChange(round(value - step).coerceIn(range)) }, enabled = value > range.start + 1e-9) { Icon(Icons.Rounded.Remove, "Less: $label") }
        IconButton(onClick = { onChange(round(value + step).coerceIn(range)) }, enabled = value < range.endInclusive - 1e-9) { Icon(Icons.Rounded.Add, "More: $label") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommandListScreen(onBack: () -> Unit) {
    val p = Theme.palette
    val rows = listOf(
        "stop / Stopp" to "Pause, beep, and record a comment",
        "continue / weiter" to "Save the comment and play on",
        "cancel / verwerfen" to "Discard the comment you're recording",
        "back / zurück" to "Back 15 s (say twice for 30 s)",
        "again / nochmal" to "Repeat the current sentence",
        "bookmark / Lesezeichen" to "Mark the sentence you just heard. Say it again within two minutes to mark the whole passage.",
        "faster, slower / schneller, langsamer" to "Change speed",
        "weakness / Schwäche, strength / Stärke, question / Frage, minor / Kleinigkeit, summary / Zusammenfassung" to
            "Say one as the first word of a comment to sort it into the review draft (Export › Review draft).",
        "Next-track button" to "Start a comment (headset, steering wheel)",
    )
    Scaffold(
        containerColor = p.background,
        topBar = {
            TopAppBar(
                title = { Text("What You Can Say") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = p.background),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).readableWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Surface(color = p.surface, shape = Space.card) {
                Column(Modifier.padding(horizontal = 14.dp)) {
                    rows.forEachIndexed { i, (say, does) ->
                        Column(Modifier.padding(vertical = 10.dp)) {
                            Text(say, fontWeight = FontWeight.SemiBold, color = p.textPrimary)
                            Text(does, style = TextStyles.footnote, color = p.textSecondary)
                        }
                        if (i < rows.lastIndex) HorizontalDivider(color = p.surfaceBorder)
                    }
                }
            }
            Text(
                "Say a command on its own, with a short pause before and after. English and German both work.",
                style = TextStyles.footnote, color = p.textSecondary, modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommandLogScreen(session: SessionHost, onBack: () -> Unit) {
    val p = Theme.palette
    val time = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
    Scaffold(
        containerColor = p.background,
        topBar = {
            TopAppBar(
                title = { Text("Command Log") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = p.background),
            )
        },
    ) { padding ->
        if (session.events.isEmpty()) {
            EmptyState(Icons.Rounded.GraphicEq, "Nothing Yet", "Recognized and ignored commands appear here.", Modifier.padding(padding))
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding.withoutBottom()), contentPadding = padding.bottomOnly()) {
                items(session.events, key = { it.id }) { entry ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(
                            if (entry.accepted) Icons.Rounded.CheckCircle else Icons.Outlined.Cancel, null,
                            tint = if (entry.accepted) p.success else p.textSecondary, modifier = Modifier.size(20.dp),
                        )
                        Column {
                            Text(entry.text, color = p.textPrimary)
                            Text(time.format(entry.date), style = TextStyles.caption, color = p.textSecondary)
                        }
                    }
                    HorizontalDivider(color = p.surfaceBorder, modifier = Modifier.padding(start = 50.dp))
                }
            }
        }
    }
}
