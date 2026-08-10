package com.debritsu.app.ui

import android.app.DownloadManager
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.debritsu.app.data.Downloaded
import com.debritsu.app.data.Downloads
import com.debritsu.app.data.SyncQueue
import com.debritsu.app.player.PlayerActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(onBack: () -> Unit) {

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf(Downloads.all()) }
    var tick by remember { mutableStateOf(0) }
    var syncNote by remember { mutableStateOf<String?>(null) }

    // DownloadManager has no callback we can observe from Compose, so poll
    // while the screen is open. It stops as soon as you leave.
    LaunchedEffect(Unit) {
        while (true) {
            delay(1200)
            tick++
            items = Downloads.all()
        }
    }

    // Anything finished offline gets pushed the moment we have a connection.
    LaunchedEffect(Unit) {
        val waiting = SyncQueue.count
        if (waiting > 0) {
            SyncQueue.flush()
            val left = SyncQueue.count
            syncNote = if (left == 0) "Synced $waiting update(s) to AniList"
            else "$left update(s) still waiting for a connection"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Downloads") },
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
        Column(Modifier.padding(pad)) {

            syncNote?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = Ink.Orchid,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }

            if (items.isEmpty()) {
                Column(Modifier.padding(24.dp)) {
                    Text("Nothing downloaded yet", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Open an episode, then tap the download icon beside a source. " +
                            "Downloaded episodes play with no connection at all, and your " +
                            "progress syncs the next time you're online.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Ink.Mist,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                return@Column
            }

            LazyColumn(contentPadding = PaddingValues(16.dp)) {
                items(items, key = { it.key }) { d ->
                    val complete = remember(tick, d.key) { Downloads.isComplete(d) }
                    val progress = remember(tick, d.key) { Downloads.progressOf(d.downloadId) }
                    val failed = remember(tick, d.key) {
                        Downloads.statusOf(d.downloadId) == DownloadManager.STATUS_FAILED && !complete
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = complete) { play(context, d) }
                            .padding(vertical = 10.dp)
                    ) {
                        AsyncImage(
                            model = d.coverPath?.let { File(it) },
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .width(52.dp)
                                .height(78.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Ink.Veil)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                d.title,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "EP ${d.episode.toString().padStart(2, '0')}  ·  ${d.sourceName.take(28)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Ink.Mist
                            )
                            Spacer(Modifier.height(6.dp))
                            when {
                                complete -> Text(
                                    "READY  ·  ${Downloads.fileFor(d).length() / 1_000_000} MB",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Ink.Iris
                                )
                                failed -> Text(
                                    "FAILED",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Ink.Orchid
                                )
                                progress >= 0f -> LinearProgressIndicator(
                                    progress = { progress },
                                    color = Ink.Iris,
                                    trackColor = Ink.Edge,
                                    modifier = Modifier.fillMaxWidth().height(3.dp)
                                )
                                else -> LinearProgressIndicator(
                                    color = Ink.Iris,
                                    trackColor = Ink.Edge,
                                    modifier = Modifier.fillMaxWidth().height(3.dp)
                                )
                            }
                        }
                        IconButton(onClick = {
                            scope.launch {
                                Downloads.remove(d)
                                items = Downloads.all()
                            }
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Ink.Mist)
                        }
                    }
                    HorizontalDivider(color = Ink.Edge)
                }
            }
        }
    }
}

private fun play(context: android.content.Context, d: Downloaded) {
    context.startActivity(
        Intent(context, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_URL, android.net.Uri.fromFile(Downloads.fileFor(d)).toString())
            .putExtra(PlayerActivity.EXTRA_TITLE, "${d.title} — EP ${d.episode}")
            .putExtra(PlayerActivity.EXTRA_ANILIST_ID, d.anilistId)
            .putExtra(PlayerActivity.EXTRA_EPISODE, d.episode)
    )
}
