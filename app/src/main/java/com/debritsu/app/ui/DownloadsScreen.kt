package com.debritsu.app.ui

import android.app.DownloadManager
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.debritsu.app.data.Downloaded
import com.debritsu.app.cast.ExternalPlayer
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
        containerColor = Color.Transparent,
        topBar = { GlossyTopBar("Downloads", onBack) }
    ) { pad ->
        Column(Modifier.padding(pad)) {

            syncNote?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = Ink.Candy,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp)
                )
            }

            if (items.isEmpty()) {
                GlossyCard(Modifier.padding(18.dp), spacing = 6.dp) {
                    Text("Nothing downloaded yet", style = MaterialTheme.typography.titleMedium)
                    Hint(
                        "Open a show, pick an episode, then tap the download button " +
                            "beside Play to bring up its sources — each one has a download " +
                            "icon of its own. Downloaded episodes play with no connection " +
                            "at all, and your progress syncs the next time you're online."
                    )
                }
                return@Column
            }

            LazyColumn(
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(items, key = { it.key }) { d ->
                    val complete = remember(tick, d.key) { Downloads.isComplete(d) }
                    val progress = remember(tick, d.key) { Downloads.progressOf(d.downloadId) }
                    val failed = remember(tick, d.key) {
                        Downloads.statusOf(d.downloadId) == DownloadManager.STATUS_FAILED && !complete
                    }

                    val card = RoundedCornerShape(20.dp)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .raised(card)
                            .clip(card)
                            .background(Gloss.Chip)
                            .border(1.dp, Gloss.TopLight, card)
                            .clickable(enabled = complete) { play(context, d) }
                            .padding(10.dp)
                    ) {
                        val poster = RoundedCornerShape(12.dp)
                        AsyncImage(
                            model = d.coverPath?.let { File(it) },
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .width(54.dp)
                                .height(80.dp)
                                .clip(poster)
                                .background(Ink.Veil)
                                .gloss()
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                d.title,
                                style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp),
                                color = Ink.Bone,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "Episode ${d.episode}  ·  ${d.sourceName.take(28)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Ink.Mist,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(8.dp))
                            when {
                                complete -> Pill(
                                    "Ready  ·  ${Downloads.fileFor(d).length() / 1_000_000} MB",
                                    brush = Gloss.Violet
                                )
                                failed -> Pill("Failed", brush = Gloss.Pink)
                                progress >= 0f -> JellyBar(progress, Modifier.fillMaxWidth())
                                else -> LinearProgressIndicator(
                                    color = Ink.Candy,
                                    trackColor = Color(0x1AFFFFFF),
                                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                                )
                            }
                        }
                        if (complete) {
                            IconButton(onClick = {
                                // Sharing the file can fail — nothing installed
                                // that plays video, or a path FileProvider
                                // doesn't cover — and this is a plain click
                                // handler, so an escaping throw takes the app down.
                                runCatching {
                                    ExternalPlayer.launch(
                                        context,
                                        android.net.Uri.fromFile(Downloads.fileFor(d)).toString(),
                                        "${d.title} — EP ${d.episode}"
                                    )
                                }.onFailure {
                                    android.widget.Toast.makeText(
                                        context,
                                        "No app on this device can open that episode",
                                        android.widget.Toast.LENGTH_LONG
                                    ).show()
                                }
                            }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.OpenInNew,
                                    contentDescription = "Open in another app",
                                    tint = Ink.Mist
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
            .putExtra(PlayerActivity.EXTRA_SERIES_TITLE, d.title)
            .putExtra(PlayerActivity.EXTRA_ANILIST_ID, d.anilistId)
            .putExtra(PlayerActivity.EXTRA_EPISODE, d.episode)
            // How many episodes the show has, which the record kept when it was
            // downloaded. Without it the player treats the count as unknown and
            // shows the next button on the last episode too, where there is
            // nowhere for it to go. Zero still means unknown, so a record from
            // before this was stored behaves exactly as it always did.
            .putExtra(PlayerActivity.EXTRA_EPISODE_COUNT, d.totalEpisodes ?: 0)
    )
}
