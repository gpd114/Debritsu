package com.debritsu.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.debritsu.app.data.DownloadIndex
import com.debritsu.app.data.Downloaded

private val DlPanel = Color(0xFF1E1830)
private val DlPaper = Color(0xFFF1EEF8)
private val DlMuted = Color(0xFF948CAB)
private val DlKeep = Color(0xFF6FC79B)

/**
 * What is on disk.
 *
 * This screen has to work with no network at all, which is the whole reason
 * downloads exist — and until it existed there was no way to reach a downloaded
 * episode offline, because the shelves come from AniList and AniList is exactly
 * what is missing on a plane. So everything drawn here comes from the index and
 * the filesystem: the title, the episode number and the poster were all stored
 * when the download was made, precisely so that nothing has to be looked up
 * now.
 */
@Composable
fun DownloadsScreen(
    onBack: () -> Unit,
    onPlay: (Downloaded) -> Unit,
    onChanged: () -> Unit
) {
    val items = DownloadIndex.all().sortedWith(compareBy({ it.title }, { it.episode }))

    Column(Modifier.fillMaxSize().padding(28.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Downloads", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onBack) { Text("← Back", color = DlMuted) }
        }

        if (items.isEmpty()) {
            Text(
                "Nothing downloaded yet. Open a show and use the ↓ beside an episode.",
                color = DlMuted,
                modifier = Modifier.padding(top = 16.dp)
            )
            return@Column
        }

        Text(
            "${items.size} episode${if (items.size == 1) "" else "s"} in ${Downloader.directory()}",
            color = DlMuted,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp)
        )

        LazyColumn(
            Modifier.fillMaxSize().padding(top = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(items) { item ->
                DownloadRow(item, onPlay) { Downloader.remove(item); onChanged() }
            }
        }
    }
}

@Composable
private fun DownloadRow(
    item: Downloaded,
    onPlay: (Downloaded) -> Unit,
    onDelete: () -> Unit
) {
    val file = Downloader.fileFor(item)
    val complete = Downloader.isComplete(item)
    val running = Downloader.isRunning(item)
    val megabytes = if (file.exists()) file.length() / 1_048_576 else 0

    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(DlPanel).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RemoteImage(
            url = item.coverPath,
            modifier = Modifier.width(40.dp).height(56.dp),
            fallback = item.title
        )
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(item.title, style = MaterialTheme.typography.titleSmall)
            Text(
                buildString {
                    append("Episode ${item.episode}")
                    if (megabytes > 0) append("  ·  $megabytes MB")
                    when {
                        running -> append("  ·  downloading")
                        !complete -> append("  ·  incomplete")
                    }
                    if (item.sourceName.isNotBlank()) {
                        append("  ·  ${item.sourceName.lineSequence().first().take(48)}")
                    }
                },
                color = if (complete) DlMuted else Color(0xFFE29075),
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (complete) {
            Text("✓", color = DlKeep, modifier = Modifier.padding(end = 12.dp))
            Button(onClick = { onPlay(item) }) { Text("Play") }
        }
        TextButton(onClick = onDelete) { Text("Delete", color = DlMuted) }
    }
}
