package com.debritsu.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.debritsu.app.data.BuildInfo
import com.debritsu.app.data.Settings

/**
 * The violet the phone and television builds use, so the three look like one
 * application rather than three that happen to share a name.
 */
private val Violet = Color(0xFF8B5CF6)
private val Ink = Color(0xFF16121F)
private val Paper = Color(0xFFF1EEF8)

fun main() {
    // Before any screen reads a setting. The shared module answers with
    // defaults until it has a store, so a window built ahead of this would
    // quietly show a signed-out app with no addons.
    Settings.store = FileStore.default()
    BuildInfo.anilistClientId = ANILIST_CLIENT_ID
    BuildInfo.debug = System.getenv("DEBRITSU_DEBUG") != null
    BuildInfo.log = { tag, message -> println("$tag  $message") }

    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Debritsu",
            state = rememberWindowState(width = 1100.dp, height = 720.dp)
        ) {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Violet,
                    background = Ink,
                    surface = Ink,
                    onBackground = Paper,
                    onSurface = Paper
                )
            ) {
                Surface(Modifier.fillMaxSize()) { Home() }
            }
        }
    }
}

@Composable
private fun Home() {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Debritsu", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Signed in: ${Settings.aniListToken.isNotEmpty()}",
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            "Addons configured: ${Settings.addons.size}",
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            "Debrid provider: ${Settings.debridProvider.label}",
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            "Source filter: ${Settings.sourceFilter}",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
