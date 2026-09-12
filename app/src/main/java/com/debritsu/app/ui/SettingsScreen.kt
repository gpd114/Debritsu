package com.debritsu.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.debritsu.app.data.AniList
import com.debritsu.app.data.DEFAULT_ANILIST_CLIENT_ID
import com.debritsu.app.data.DebridProvider
import com.debritsu.app.data.Settings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {

    val context = LocalContext.current
    var clientId by remember { mutableStateOf(Settings.aniListClientId) }
    var provider by remember { mutableStateOf(Settings.debridProvider) }
    var debridToken by remember { mutableStateOf(Settings.debridToken) }
    var showDebridToken by remember { mutableStateOf(false) }
    var providerMenu by remember { mutableStateOf(false) }
    var autoPlay by remember { mutableStateOf(Settings.autoPlay) }
    var maxRes by remember { mutableStateOf(Settings.maxResolution) }
    var maxSize by remember { mutableStateOf(Settings.maxSizeMb) }
    var preferEnglish by remember { mutableStateOf(Settings.preferEnglish) }
    var audioLang by remember { mutableStateOf(Settings.preferredAudioLanguage) }
    var subSize by remember { mutableStateOf(Settings.subtitleSizeSp) }
    var subBg by remember { mutableStateOf(Settings.subtitleBackground) }
    var subColour by remember { mutableStateOf(Settings.subtitleColour) }
    var subOutline by remember { mutableStateOf(Settings.subtitleOutline) }
    var showAdvanced by remember { mutableStateOf(DEFAULT_ANILIST_CLIENT_ID.isEmpty()) }
    var newAddon by remember { mutableStateOf("") }
    var addons by remember { mutableStateOf(Settings.addons) }
    var theme by remember { mutableStateOf(Settings.theme) }
    val signedIn = Settings.aniListToken.isNotEmpty()
    val fieldShape = RoundedCornerShape(16.dp)

    Scaffold(
        containerColor = Color.Transparent,
        topBar = { ScreenTopBar("Settings", onBack) }
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {

            Panel {
                SectionTitle("Appearance")
                ChoiceRow(
                    listOf("pastel" to "Pastel", "night" to "Night", "plum" to "Plum"),
                    theme,
                    { theme = it; Settings.theme = it; applyTheme(it) }
                )
                Hint("Pastel is light and Night is near-black, both with a dark purple accent; Plum is a dark purple page with a periwinkle accent.")
            }

            Panel {
                SectionTitle("AniList")
                if (DEFAULT_ANILIST_CLIENT_ID.isNotEmpty() && !showAdvanced) {
                    Hint("Tap Sign in and approve access — nothing else to set up.")
                    Pill("Use my own API client", color = Ink.Link, onClick = { showAdvanced = true })
                } else {
                    Hint(
                        "Create an API client at anilist.co/settings/developer with redirect URL " +
                            "debritsu://auth, then paste the client ID here."
                    )
                    OutlinedTextField(
                        value = clientId,
                        onValueChange = { clientId = it; Settings.aniListClientId = it },
                        label = { Text("Client ID") },
                        singleLine = true,
                        shape = fieldShape,
                        colors = fieldColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton(
                        onClick = {
                            if (clientId.isNotBlank()) context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(AniList.authUrl(clientId)))
                            )
                        },
                        height = 46.dp,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            if (signedIn) "Re-authorise" else "Sign in",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                    if (signedIn) {
                        SecondaryButton("Sign out", { Settings.aniListToken = "" }, height = 46.dp, color = Ink.Orchid)
                    }
                }
                Hint(
                    if (signedIn) "Signed in — progress syncs after each episode."
                    else "Not signed in. Browsing and playback still work without an account."
                )
            }

            Panel {
                SectionTitle("Stremio addons")
                Hint(
                    "Paste an addon URL (manifest.json or stremio:// link). Use a debrid-backed " +
                        "addon such as AIOStreams, Comet, MediaFusion or Torrentio configured with " +
                        "your debrid key — those return cached, direct links."
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newAddon,
                        onValueChange = { newAddon = it },
                        label = { Text("Addon URL") },
                        singleLine = true,
                        shape = fieldShape,
                        colors = fieldColors(),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(10.dp))
                    PrimaryButton(
                        onClick = {
                            Settings.addAddon(newAddon)
                            addons = Settings.addons
                            newAddon = ""
                        },
                        height = 50.dp,
                        shape = RoundedCornerShape(16.dp)
                    ) { Text("Add", style = MaterialTheme.typography.labelLarge) }
                }
                addons.forEach { a ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Ink.Quiet)
                            .padding(start = 12.dp)
                    ) {
                        Text(
                            a,
                            style = MaterialTheme.typography.bodySmall,
                            color = Ink.Bone,
                            maxLines = 2,
                            modifier = Modifier.weight(1f).padding(vertical = 8.dp)
                        )
                        IconButton(onClick = {
                            Settings.removeAddon(a)
                            addons = Settings.addons
                        }) { Icon(Icons.Default.Delete, contentDescription = "Remove", tint = Ink.Mist) }
                    }
                }
            }

            Panel {
                SectionTitle("Debrid provider")
                Hint(
                    "Optional. Only used for addons that return a bare infoHash instead " +
                        "of a ready link — if your addon already holds your debrid key, leave this blank."
                )

                ExposedDropdownMenuBox(
                    expanded = providerMenu,
                    onExpandedChange = { providerMenu = !providerMenu }
                ) {
                    OutlinedTextField(
                        value = provider.label,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Service") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerMenu) },
                        shape = fieldShape,
                        colors = fieldColors(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    )
                    ExposedDropdownMenu(
                        expanded = providerMenu,
                        onDismissRequest = { providerMenu = false }
                    ) {
                        DebridProvider.entries.forEach { p ->
                            DropdownMenuItem(
                                text = { Text(p.label) },
                                onClick = {
                                    provider = p
                                    Settings.debridProvider = p
                                    debridToken = Settings.debridToken
                                    providerMenu = false
                                }
                            )
                        }
                    }
                }

                // Hidden by default. It is a key to a paid account, it is long
                // enough that nobody reads it back to check it, and a settings
                // screen is exactly the sort of thing that gets shown to somebody
                // else while asking why something will not play.
                OutlinedTextField(
                    value = debridToken,
                    onValueChange = { debridToken = it; Settings.debridToken = it },
                    label = { Text("${provider.label} API key") },
                    singleLine = true,
                    visualTransformation =
                        if (showDebridToken) VisualTransformation.None
                        else PasswordVisualTransformation(),
                    trailingIcon = {
                        Pill(
                            if (showDebridToken) "Hide" else "Show",
                            color = Ink.Link,
                            onClick = { showDebridToken = !showDebridToken },
                            modifier = Modifier.padding(end = 10.dp)
                        )
                    },
                    shape = fieldShape,
                    colors = fieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
                Hint(
                    // Says whether it is set without showing it, which is the
                    // question being asked when this screen is opened.
                    if (debridToken.isEmpty()) "Not set. Get it from ${provider.tokenHint}"
                    else "Set — ${debridToken.length} characters. From ${provider.tokenHint}"
                )
            }

            Panel {
                SectionTitle("Playback")
                Hint(
                    "With this on, pressing play searches your addons, picks the best " +
                        "source matching the rules below and starts it. When nothing " +
                        "matches, the source list opens instead so you choose — it will " +
                        "not quietly play something over your limits."
                )
                SwitchRow("Play automatically", autoPlay) { autoPlay = it; Settings.autoPlay = it }

                if (autoPlay) {
                    Label("Highest quality")
                    ChoiceRow(
                        listOf(720 to "720p", 1080 to "1080p", 2160 to "4K", 0 to "Any"),
                        maxRes,
                        { maxRes = it; Settings.maxResolution = it }
                    )
                    Hint("A ceiling, not a target — the best source at or below this wins.")

                    Label(
                        if (maxSize == 0) "Size limit — none"
                        else "Size limit — ${maxSize}MB per episode"
                    )
                    Slider(
                        value = maxSize.toFloat(),
                        onValueChange = { maxSize = it.toInt(); Settings.maxSizeMb = it.toInt() },
                        valueRange = 0f..4000f,
                        steps = 39,
                        colors = sliderColors()
                    )
                    Hint(
                        "A hard limit. Sources that don't say how big they are can't be " +
                            "checked against it, so they're skipped too."
                    )

                    SwitchRow("Prefer English", preferEnglish) {
                        preferEnglish = it; Settings.preferEnglish = it
                    }
                    Hint(
                        "Skips releases that name another language and never mention " +
                            "English. Most releases say nothing either way, so this ranks " +
                            "more than it excludes — turn it off if you watch in another " +
                            "language."
                    )
                }
            }

            Panel {
                SectionTitle("Audio")
                Hint(
                    "Which track to pick when a release carries more than one. Most " +
                        "anime ships Japanese audio; a dub adds an English track " +
                        "alongside it. Device follows your phone's language, which on " +
                        "an English phone means the dub."
                )
                ChoiceRow(
                    listOf("ja" to "Japanese", "en" to "English", "" to "Device"),
                    audioLang,
                    { audioLang = it; Settings.preferredAudioLanguage = it }
                )
                Hint(
                    "Applies to the next episode you start, and only when the release " +
                        "actually has that track. Switch per-episode from the settings " +
                        "button in the player."
                )
            }

            Panel {
                SectionTitle("Subtitles")
                Hint(
                    "Track selection lives on the CC button in the player. These control " +
                        "how the text looks."
                )

                Label("Text size — ${subSize.toInt()}sp")
                Slider(
                    value = subSize,
                    onValueChange = { subSize = it; Settings.subtitleSizeSp = it },
                    valueRange = 12f..40f,
                    steps = 13,
                    colors = sliderColors()
                )

                Label("Colour")
                ChoiceRow(
                    listOf(0 to "White", 1 to "Yellow", 2 to "Cyan"),
                    subColour,
                    { subColour = it; Settings.subtitleColour = it }
                )

                Label("Background")
                ChoiceRow(
                    listOf(0 to "None", 1 to "Shaded", 2 to "Solid"),
                    subBg,
                    { subBg = it; Settings.subtitleBackground = it }
                )

                SwitchRow("Outline text", subOutline) {
                    subOutline = it; Settings.subtitleOutline = it
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp), color = Ink.Bone)
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp), color = Ink.Bone)
}
