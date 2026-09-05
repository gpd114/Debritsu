package com.debritsu.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.debritsu.app.data.Settings
import kotlinx.coroutines.launch

private val WarnPlate = Color(0x3FE29075)
private val WarnInk = Color(0xFFE29075)
private val BannerMuted = Color(0xFF948CAB)

/**
 * Says mpv is missing, and offers to fetch it.
 *
 * Shown before anything is played rather than when playback fails. Finding out
 * that the player is absent at the moment you press play on an episode is a
 * worse way to learn it, and the message there cannot do anything about it.
 *
 * Absent entirely once mpv is present, which is the usual case — a permanent
 * strip about a solved problem is noise.
 */
@Composable
fun VlcBanner(onInstalled: () -> Unit) {
    var found by remember { mutableStateOf(Vlc.directory(Settings.store.getString("vlc_path", ""))) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val clipboard: ClipboardManager = LocalClipboardManager.current

    // Rechecked when the window regains attention, so installing mpv by hand in
    // another window makes this go away without restarting anything.
    LaunchedEffect(Unit) {
        while (found == null) {
            kotlinx.coroutines.delay(3000)
            if (busy) continue
            found = Vlc.directory(Settings.store.getString("vlc_path", ""))
            if (found != null) onInstalled()
        }
    }

    if (found != null) return

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(10.dp)).background(WarnPlate).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("VLC is not installed", style = MaterialTheme.typography.titleSmall, color = WarnInk)
        Text(
            "Debritsu decodes through libVLC, which ships with VLC. " +
                "It is fetched from the VideoLAN project rather than shipped here.",
            style = MaterialTheme.typography.bodySmall,
            color = BannerMuted
        )

        if (message.isNotEmpty()) {
            Text(message, style = MaterialTheme.typography.bodySmall, color = BannerMuted)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                enabled = !busy && VlcInstall.available(),
                onClick = {
                    busy = true
                    message = ""
                    scope.launch {
                        val result = VlcInstall.install { message = it }
                        busy = false
                        when (result) {
                            is VlcInstall.Result.Installed -> {
                                found = result.dir
                                message = ""
                                onInstalled()
                            }
                            is VlcInstall.Result.Failed -> message = result.why
                        }
                    }
                }
            ) {
                Text(
                    when {
                        busy -> "Installing…"
                        !VlcInstall.available() -> "winget not available"
                        else -> "Install VLC"
                    }
                )
            }

            // The command itself, for anyone who would rather run it themselves
            // or is on a machine where winget needs coaxing.
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(VlcInstall.command))
                message = "Command copied. Run it in a terminal, then come back."
            }) {
                Text("Copy the command", color = BannerMuted, style = MaterialTheme.typography.bodySmall)
            }
        }

        Text(
            VlcInstall.command,
            style = MaterialTheme.typography.bodySmall,
            color = BannerMuted
        )
    }
}
