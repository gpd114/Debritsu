package com.debritsu.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.debritsu.app.data.AniList
import com.debritsu.app.data.BuildInfo
import com.debritsu.app.data.DebridProvider
import com.debritsu.app.data.Settings
import java.awt.Desktop
import java.net.URI

private val SetViolet = Color(0xFF8B5CF6)
private val SetPanel = Color(0xFF1E1830)
private val SetMuted = Color(0xFF948CAB)

/**
 * Settings, in the sections the phone uses: AniList, addons, debrid, playback.
 *
 * Subtitle and audio appearance are absent on purpose. On Android those exist
 * because ExoPlayer has to be told; here mpv owns rendering and has far better
 * controls for it than this panel could offer, so duplicating them would only
 * create two places to set one thing.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsPane(modifier: Modifier = Modifier, onChanged: () -> Unit) {
    Column(
        modifier.fillMaxSize().background(SetPanel).verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.titleLarge)

        SectionHeading("AniList")
        AniListSection(onChanged)

        SectionHeading("Stremio addons")
        AddonsSection()

        SectionHeading("Debrid")
        DebridSection()

        SectionHeading("Playback")
        PlaybackSection()

        SectionHeading("Files")
        FilesSection()
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text,
        fontWeight = FontWeight.SemiBold,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 8.dp)
    )
}

/**
 * A field for something that should not be readable over a shoulder.
 *
 * Tokens were plain text here, which is worse on a desktop than on a phone: the
 * panel is wide enough to show a whole token, and a desktop screen is more
 * likely to be shared, projected or screenshotted. Hidden by default with a
 * deliberate reveal, and the reveal is per field rather than global.
 */
@Composable
private fun SecretField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    help: String? = null
) {
    var revealed by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            singleLine = true,
            visualTransformation =
                if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                TextButton(onClick = { revealed = !revealed }) {
                    Text(if (revealed) "Hide" else "Show", style = MaterialTheme.typography.bodySmall)
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                // Says whether it is set without showing what it is, which is
                // the question actually being asked when you open this panel.
                if (value.isEmpty()) "Not set" else "Set — ${value.length} characters",
                color = SetMuted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            if (value.isNotEmpty()) {
                TextButton(onClick = { onValueChange("") }) {
                    Text("Clear", color = SetMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        help?.let { Text(it, color = SetMuted, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun AniListSection(onChanged: () -> Unit) {
    var token by remember { mutableStateOf(Settings.aniListToken) }
    var clientId by remember { mutableStateOf(Settings.store.getString("anilist_client_id", "")) }
    val signedIn = token.isNotEmpty()

    Text(
        if (signedIn) "Signed in. Progress syncs after each episode."
        else "Not signed in. Browsing and playback still work without an account.",
        color = SetMuted,
        style = MaterialTheme.typography.bodySmall
    )

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(
            enabled = Settings.aniListClientId.isNotBlank(),
            onClick = {
                runCatching {
                    Desktop.getDesktop().browse(URI(AniList.authUrl(Settings.aniListClientId)))
                }
            }
        ) {
            Text(
                when {
                    Settings.aniListClientId.isBlank() -> "No client id"
                    signedIn -> "Re-authorise"
                    else -> "Sign in"
                }
            )
        }
        if (signedIn) {
            TextButton(onClick = {
                token = ""
                Settings.aniListToken = ""
                onChanged()
            }) { Text("Sign out", color = SetMuted) }
        }
    }

    Text(
        "Sign in opens AniList in your browser and shows a token to paste below. " +
            "This build needs its own client, with the redirect URL set to " +
            "https://anilist.co/api/v2/oauth/pin",
        color = SetMuted,
        style = MaterialTheme.typography.bodySmall
    )

    SecretField(
        value = token,
        onValueChange = { token = it; Settings.aniListToken = it.trim(); onChanged() },
        label = "AniList token"
    )

    OutlinedTextField(
        value = clientId,
        onValueChange = { clientId = it; Settings.aniListClientId = it.trim() },
        label = { Text("Client ID (blank uses the built-in one)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun AddonsSection() {
    var addons by remember { mutableStateOf(Settings.addons) }
    var entry by remember { mutableStateOf("") }

    // A list rather than the single field this had. More than one addon is the
    // normal case — one for releases, one for subtitles — and the phone has
    // allowed several all along.
    addons.forEach { url ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                url,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = {
                Settings.removeAddon(url)
                addons = Settings.addons
            }) { Text("Remove", color = SetMuted, style = MaterialTheme.typography.bodySmall) }
        }
    }
    if (addons.isEmpty()) {
        Text("No addons. Nothing will resolve without one.", color = SetMuted,
            style = MaterialTheme.typography.bodySmall)
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = entry,
            onValueChange = { entry = it },
            label = { Text("Addon URL") },
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
        Button(
            enabled = entry.isNotBlank(),
            onClick = {
                Settings.addAddon(entry)
                addons = Settings.addons
                entry = ""
            }
        ) { Text("Add") }
    }
    Text(
        "A stremio:// link or a manifest.json URL; both are accepted.",
        color = SetMuted,
        style = MaterialTheme.typography.bodySmall
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DebridSection() {
    var provider by remember { mutableStateOf(Settings.debridProvider) }
    var token by remember { mutableStateOf(Settings.debridToken) }

    // Tokens are stored per provider, so picking the wrong one does not merely
    // mislabel the field — it files the key under another service's name and
    // then calls that service's API with it.
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        DebridProvider.entries.forEach { p ->
            TextButton(onClick = {
                provider = p
                Settings.debridProvider = p
                token = Settings.debridToken
            }) {
                Text(
                    p.label,
                    color = if (p == provider) SetViolet else SetMuted,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }

    SecretField(
        value = token,
        onValueChange = { token = it; Settings.debridToken = it.trim() },
        label = "${provider.label} API token",
        help = "From ${provider.tokenHint}"
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlaybackSection() {
    var autoPlay by remember { mutableStateOf(Settings.autoPlay) }
    var maxRes by remember { mutableStateOf(Settings.maxResolution) }
    var maxSize by remember { mutableStateOf(Settings.maxSizeMb) }
    var english by remember { mutableStateOf(Settings.preferEnglish) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Play the best match automatically", Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium)
        Switch(checked = autoPlay, onCheckedChange = { autoPlay = it; Settings.autoPlay = it })
    }

    Text(
        "Highest quality — ${if (maxRes == 0) "any" else "${maxRes}p"}",
        style = MaterialTheme.typography.bodySmall
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(0, 480, 720, 1080, 2160).forEach { r ->
            TextButton(onClick = { maxRes = r; Settings.maxResolution = r }) {
                Text(
                    if (r == 0) "Any" else "${r}p",
                    color = if (r == maxRes) SetViolet else SetMuted,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }

    Text(
        "Largest file — ${if (maxSize == 0) "no cap" else "$maxSize MB"}",
        style = MaterialTheme.typography.bodySmall
    )
    Slider(
        value = maxSize.toFloat(),
        onValueChange = { maxSize = it.toInt() },
        onValueChangeFinished = { Settings.maxSizeMb = maxSize },
        valueRange = 0f..8000f,
        steps = 39
    )
    Text(
        "A source that never says how big it is fails this cap, because the " +
            "point of it is not spending data you did not agree to spend.",
        color = SetMuted,
        style = MaterialTheme.typography.bodySmall
    )

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Skip releases with no English", Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium)
        Switch(checked = english, onCheckedChange = { english = it; Settings.preferEnglish = it })
    }

    LanguageSection()
}

/**
 * Which track mpv should start on.
 *
 * Left to itself mpv follows the system language, which on an English machine
 * quietly picks the dub — the same fault ExoPlayer has on an English phone, and
 * for the same reason. Both spellings of each code are sent, because releases
 * tag tracks jpn or ja inconsistently.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LanguageSection() {
    var audio by remember { mutableStateOf(Settings.preferredAudioLanguage) }
    var subs by remember { mutableStateOf(Settings.store.getString("sub_lang", "eng")) }

    Text("Audio track", style = MaterialTheme.typography.bodySmall, color = SetMuted)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf("ja" to "Japanese", "en" to "English", "" to "File default").forEach { (code, label) ->
            TextButton(onClick = { audio = code; Settings.preferredAudioLanguage = code }) {
                Text(
                    label,
                    color = if (code == audio) SetViolet else SetMuted,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }

    Text("Subtitles", style = MaterialTheme.typography.bodySmall, color = SetMuted)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf("eng" to "English", "" to "File default").forEach { (code, label) ->
            TextButton(onClick = { subs = code; Settings.store.putString("sub_lang", code) }) {
                Text(
                    label,
                    color = if (code == subs) SetViolet else SetMuted,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
    Text(
        "mpv still lists every track, so a release that labels them oddly can " +
            "be corrected while it plays.",
        color = SetMuted,
        style = MaterialTheme.typography.bodySmall
    )
}

@Composable
private fun FilesSection() {
    var mpvPath by remember { mutableStateOf(Settings.store.getString("mpv_path", "")) }
    var downloadDir by remember { mutableStateOf(Settings.store.getString("download_dir", "")) }
    val found = remember(mpvPath) { Mpv.locate(mpvPath) }

    OutlinedTextField(
        value = mpvPath,
        onValueChange = { mpvPath = it; Settings.store.putString("mpv_path", it.trim()) },
        label = { Text("mpv.exe (blank to search)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Text(
        found?.let { "Found: ${it.absolutePath}" }
            ?: "Not found — winget install shinchiro.mpv",
        color = if (found == null) Color(0xFFE29075) else SetMuted,
        style = MaterialTheme.typography.bodySmall
    )

    OutlinedTextField(
        value = downloadDir,
        onValueChange = { downloadDir = it; Settings.store.putString("download_dir", it.trim()) },
        label = { Text("Download folder (blank for the default)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Text(
        Downloader.directory().absolutePath,
        color = SetMuted,
        style = MaterialTheme.typography.bodySmall
    )
    Box(Modifier.height(12.dp))
}
