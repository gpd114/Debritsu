package com.debritsu.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
    var providerMenu by remember { mutableStateOf(false) }
    var subSize by remember { mutableStateOf(Settings.subtitleSizeSp) }
    var subBg by remember { mutableStateOf(Settings.subtitleBackground) }
    var subColour by remember { mutableStateOf(Settings.subtitleColour) }
    var subOutline by remember { mutableStateOf(Settings.subtitleOutline) }
    var showAdvanced by remember { mutableStateOf(DEFAULT_ANILIST_CLIENT_ID.isEmpty()) }
    var newAddon by remember { mutableStateOf("") }
    var addons by remember { mutableStateOf(Settings.addons) }
    val signedIn = Settings.aniListToken.isNotEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            Text("Stremio addons", fontWeight = FontWeight.SemiBold)
            Text(
                "Paste an addon URL (manifest.json or stremio:// link). Use a debrid-backed " +
                    "addon such as AIOStreams, Comet, MediaFusion or Torrentio configured with " +
                    "your Real-Debrid key — those return cached, direct links.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newAddon,
                    onValueChange = { newAddon = it },
                    label = { Text("Addon URL") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    Settings.addAddon(newAddon)
                    addons = Settings.addons
                    newAddon = ""
                }) { Text("Add") }
            }
            addons.forEach { a ->
                ListItem(
                    headlineContent = { Text(a, fontSize = 12.sp) },
                    trailingContent = {
                        IconButton(onClick = {
                            Settings.removeAddon(a)
                            addons = Settings.addons
                        }) { Icon(Icons.Default.Delete, contentDescription = "Remove") }
                    }
                )
            }

            HorizontalDivider()

            Text("Debrid provider", fontWeight = FontWeight.SemiBold)
            Text(
                "Optional. Only used for addons that return a bare infoHash instead " +
                    "of a ready link — if your addon already holds your debrid key, leave this blank.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
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

            OutlinedTextField(
                value = debridToken,
                onValueChange = { debridToken = it; Settings.debridToken = it },
                label = { Text("${provider.label} API key") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "Get it from ${provider.tokenHint}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider()

            Text("Subtitles", fontWeight = FontWeight.SemiBold)
            Text(
                "Track selection lives on the CC button in the player. These control " +
                    "how the text looks.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text("Text size — ${subSize.toInt()}sp", fontSize = 13.sp)
            Slider(
                value = subSize,
                onValueChange = { subSize = it; Settings.subtitleSizeSp = it },
                valueRange = 12f..40f,
                steps = 13
            )

            Text("Colour", fontSize = 13.sp)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("White", "Yellow", "Cyan").forEachIndexed { i, label ->
                    SegmentedButton(
                        selected = subColour == i,
                        onClick = { subColour = i; Settings.subtitleColour = i },
                        shape = SegmentedButtonDefaults.itemShape(i, 3)
                    ) { Text(label, fontSize = 12.sp) }
                }
            }

            Text("Background", fontSize = 13.sp)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("None", "Shaded", "Solid").forEachIndexed { i, label ->
                    SegmentedButton(
                        selected = subBg == i,
                        onClick = { subBg = i; Settings.subtitleBackground = i },
                        shape = SegmentedButtonDefaults.itemShape(i, 3)
                    ) { Text(label, fontSize = 12.sp) }
                }
            }

            Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Outline text", Modifier.weight(1f), fontSize = 14.sp)
                Switch(
                    checked = subOutline,
                    onCheckedChange = { subOutline = it; Settings.subtitleOutline = it }
                )
            }

            HorizontalDivider()

            Text("AniList", fontWeight = FontWeight.SemiBold)
            if (DEFAULT_ANILIST_CLIENT_ID.isNotEmpty() && !showAdvanced) {
                Text(
                    "Tap Sign in and approve Debritsu in your browser — nothing else to set up.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = { showAdvanced = true }) {
                    Text("Use my own API client", fontSize = 12.sp)
                }
            } else {
                Text(
                    "Create an API client at anilist.co/settings/developer with redirect URL " +
                        "debritsu://auth, then paste the client ID here.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = clientId,
                    onValueChange = { clientId = it; Settings.aniListClientId = it },
                    label = { Text("Client ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = clientId.isNotBlank(),
                    onClick = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(AniList.authUrl(clientId)))
                        )
                    }
                ) { Text(if (signedIn) "Re-authorise" else "Sign in") }

                if (signedIn) {
                    OutlinedButton(onClick = { Settings.aniListToken = "" }) { Text("Sign out") }
                }
            }
            Text(
                if (signedIn) "Signed in — progress syncs after each episode."
                else "Not signed in. Browsing and playback still work without an account.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
