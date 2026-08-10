package com.debritsu.app.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.debritsu.app.data.*
import com.debritsu.app.player.PlayerActivity
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(anilistId: Int, onBack: () -> Unit, onOpen: (Int) -> Unit = {}) {

    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var anime by remember { mutableStateOf<Anime?>(null) }
    var selectedEpisode by remember { mutableStateOf(1) }
    var results by remember { mutableStateOf<List<Stremio.AddonResult>>(emptyList()) }
    val streams = results.flatMap { it.streams }
    var searching by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var showSheet by remember { mutableStateOf(false) }
    var subtitles by remember { mutableStateOf<List<Subtitle>>(emptyList()) }
    // Bumped on return from the player so resume bars redraw.
    var progressTick by remember { mutableStateOf(0) }
    var relations by remember { mutableStateOf<List<Relation>>(emptyList()) }
    var showListEditor by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) progressTick++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    LaunchedEffect(anilistId, progressTick) {
        anime = runCatching { AniList.media(anilistId) }.getOrNull()
        selectedEpisode = ((anime?.progress ?: 0) + 1).coerceAtLeast(1)
    }
    LaunchedEffect(anilistId) {
        relations = runCatching { AniList.relations(anilistId) }.getOrDefault(emptyList())
    }

    fun findStreams(episode: Int) {
        scope.launch {
            searching = true
            status = null
            results = emptyList()
            showSheet = true
            val ids = Mappings.forAniList(anilistId, anime?.title)
            val movie = (anime?.episodes ?: 1) <= 1
            val target = Stremio.contentId(ids, episode, movie)
            if (target == null) {
                status = "Couldn't map this title to a Kitsu or IMDb ID — the addons " +
                    "index by those, so there is nothing to ask for. Very new shows " +
                    "often aren't in the mapping tables yet."
            } else {
                results = Stremio.streams(target.first, target.second)
                subtitles = Stremio.subtitles(target.first, target.second)
                if (results.none { it.streams.isNotEmpty() }) {
                    // Show what each addon actually said rather than a blanket failure.
                    status = results.joinToString("\n") { r ->
                        "${r.addon}: ${r.error ?: "no streams"}"
                    }.ifEmpty { "No addons configured." }
                }
            }
            searching = false
        }
    }

    fun play(stream: StreamOption) {
        scope.launch {
            status = "Resolving link…"
            runCatching { Debrid.resolve(stream) }
                .onSuccess { url ->
                    showSheet = false
                    status = null
                    // Subtitles carried on the stream itself take priority over
                    // whatever the subtitle addons returned.
                    val subs = (stream.subtitles + subtitles).distinctBy { it.url }
                    context.startActivity(
                        Intent(context, PlayerActivity::class.java)
                            // Pass the whole list so sources can be swapped mid-episode.
                            .putExtra(
                                PlayerActivity.EXTRA_SOURCES,
                                runCatching {
                                    json.encodeToString(ListSerializer(StreamOption.serializer()), streams)
                                }.getOrDefault("[]")
                            )
                            .putExtra(PlayerActivity.EXTRA_SUB_URLS, subs.map { it.url }.toTypedArray())
                            .putExtra(PlayerActivity.EXTRA_SUB_LANGS, subs.map { it.lang }.toTypedArray())
                            .putExtra(PlayerActivity.EXTRA_URL, url)
                            .putExtra(PlayerActivity.EXTRA_TITLE, "${anime?.title} — EP $selectedEpisode")
                            .putExtra(PlayerActivity.EXTRA_ANILIST_ID, anilistId)
                            .putExtra(PlayerActivity.EXTRA_EPISODE, selectedEpisode)
                    )
                }
                .onFailure { status = it.message ?: "Could not resolve that stream." }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(anime?.title ?: "", maxLines = 1) },
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
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            item {
                Box(Modifier.fillMaxWidth().height(220.dp)) {
                    AsyncImage(
                        model = anime?.banner ?: anime?.cover,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    // Fade the banner into the page so nothing has a hard edge.
                    Box(Modifier.fillMaxSize().background(Ink.Dusk))
                    Text(
                        anime?.title ?: "",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                    )
                }
            }
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text(
                        listOfNotNull(
                            anime?.episodes?.let { "${it.toString().padStart(2, '0')} EP" },
                            anime?.progress?.takeIf { it > 0 }?.let { "WATCHED $it" }
                        ).joinToString("  ·  "),
                        style = MaterialTheme.typography.labelSmall,
                        color = Ink.Mist
                    )
                    if (Settings.aniListToken.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Surface(
                            onClick = { showListEditor = true },
                            color = Ink.Veil,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    anime?.listStatus?.replace('_', ' ') ?: "NOT ON LIST",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (anime?.listStatus != null) Ink.Iris else Ink.Mist
                                )
                                if ((anime?.score ?: 0.0) > 0) {
                                    Text(
                                        "  ·  ${anime?.score?.toInt()}/10",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = Ink.Mist
                                    )
                                }
                                Icon(
                                    Icons.Default.Edit,
                                    contentDescription = "Edit list entry",
                                    tint = Ink.Mist,
                                    modifier = Modifier.padding(start = 8.dp).size(15.dp)
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { findStreams(selectedEpisode) },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().height(50.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        val resumeFrac = remember(selectedEpisode, progressTick) {
                            Progress.fraction(anilistId, selectedEpisode)
                        }
                        Text(
                            if (resumeFrac > 0f)
                                "Resume episode ${selectedEpisode.toString().padStart(2, '0')} · ${(resumeFrac * 100).toInt()}%"
                            else
                                "Play episode ${selectedEpisode.toString().padStart(2, '0')}",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(
                        anime?.description?.take(400) ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = Ink.Mist
                    )
                }
            }
            item {
                Text(
                    "Episodes",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 16.dp, top = 22.dp, bottom = 10.dp)
                )
            }
            item {
                val total = (anime?.episodes ?: 1).coerceAtLeast(1)
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items((1..total).toList()) { ep ->
                        val selected = ep == selectedEpisode
                        val watched = ep <= (anime?.progress ?: 0)
                        val resume = remember(ep, progressTick) { Progress.fraction(anilistId, ep) }
                        Box(
                            Modifier
                                .size(54.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (selected) Ink.Iris else Ink.Veil)
                                .clickable { selectedEpisode = ep; findStreams(ep) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                ep.toString().padStart(2, '0'),
                                style = MaterialTheme.typography.labelMedium,
                                color = when {
                                    selected -> MaterialTheme.colorScheme.onPrimary
                                    watched -> Ink.Mist
                                    else -> Ink.Bone
                                }
                            )
                            when {
                                // Part-watched wins over the watched dot: it is
                                // the more actionable state.
                                resume > 0f -> Box(
                                    Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(horizontal = 8.dp, vertical = 6.dp)
                                        .fillMaxWidth()
                                        .height(3.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(Ink.Edge)
                                ) {
                                    Box(
                                        Modifier
                                            .fillMaxWidth(resume)
                                            .fillMaxHeight()
                                            .background(Ink.Orchid)
                                    )
                                }
                                watched && !selected -> Box(
                                    Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(bottom = 7.dp)
                                        .size(4.dp)
                                        .clip(CircleShape)
                                        .background(Ink.Orchid)
                                )
                            }
                        }
                    }
                }
            }
            if (relations.isNotEmpty()) {
                item {
                    Text(
                        "Related",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 16.dp, top = 26.dp, bottom = 10.dp)
                    )
                }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(relations) { rel ->
                            Column(
                                Modifier
                                    .width(104.dp)
                                    .clickable { onOpen(rel.anime.id) }
                            ) {
                                AsyncImage(
                                    model = rel.anime.cover,
                                    contentDescription = rel.anime.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(2f / 3f)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Ink.Veil)
                                )
                                Text(
                                    rel.type.uppercase(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Ink.Orchid,
                                    modifier = Modifier.padding(top = 6.dp)
                                )
                                Text(
                                    rel.anime.title,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }

    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            containerColor = Ink.Veil
        ) {
            Column(
                Modifier
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 32.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    "Sources",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    "EPISODE ${selectedEpisode.toString().padStart(2, '0')}" +
                        if (streams.isNotEmpty()) "  ·  ${streams.size} FOUND" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = Ink.Mist
                )
                Spacer(Modifier.height(10.dp))

                if (searching) {
                    LinearProgressIndicator(
                        color = Ink.Iris,
                        trackColor = Ink.Edge,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                status?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = Ink.Orchid,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }

                streams.forEach { s ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { play(s) }
                            .padding(vertical = 12.dp)
                    ) {
                        Box(
                            Modifier
                                .width(3.dp)
                                .height(34.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(if (s.isDirect) Ink.Iris else Ink.Edge)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                s.name,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                s.description.replace("\n", " ").take(120),
                                style = MaterialTheme.typography.bodySmall,
                                color = Ink.Mist,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            if (s.isDirect) "DIRECT" else "DEBRID",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (s.isDirect) Ink.Iris else Ink.Mist
                        )
                    }
                    HorizontalDivider(color = Color(0xFF221A36))
                }
            }
        }
    }

    if (showListEditor) {
        val statuses = listOf(
            "CURRENT" to "Watching",
            "PLANNING" to "Planning",
            "COMPLETED" to "Completed",
            "PAUSED" to "Paused",
            "DROPPED" to "Dropped",
            "REPEATING" to "Rewatching"
        )
        var pendingProgress by remember(anime?.progress) { mutableStateOf(anime?.progress ?: 0) }
        var pendingScore by remember(anime?.score) { mutableStateOf(anime?.score ?: 0.0) }

        ModalBottomSheet(
            onDismissRequest = { showListEditor = false },
            containerColor = Ink.Veil
        ) {
            Column(
                Modifier
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 32.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text("List entry", style = MaterialTheme.typography.titleMedium)
                Text(
                    anime?.title?.uppercase()?.take(40) ?: "",
                    style = MaterialTheme.typography.labelSmall,
                    color = Ink.Mist
                )
                Spacer(Modifier.height(14.dp))

                Text("Status", style = MaterialTheme.typography.bodySmall, color = Ink.Mist)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    statuses.forEach { (value, label) ->
                        val on = anime?.listStatus == value
                        Surface(
                            onClick = {
                                scope.launch {
                                    runCatching { AniList.saveEntry(anilistId, status = value) }
                                    progressTick++
                                }
                            },
                            color = if (on) Ink.Iris else Ink.Edge,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (on) MaterialTheme.colorScheme.onPrimary else Ink.Bone,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))
                Text(
                    "Episodes watched — ${pendingProgress}${anime?.episodes?.let { " / $it" } ?: ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink.Mist
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { if (pendingProgress > 0) pendingProgress-- }) {
                        Icon(Icons.Default.Remove, contentDescription = "One fewer", tint = Ink.Bone)
                    }
                    Text(
                        pendingProgress.toString().padStart(2, '0'),
                        style = MaterialTheme.typography.labelMedium
                    )
                    IconButton(onClick = {
                        val max = anime?.episodes ?: Int.MAX_VALUE
                        if (pendingProgress < max) pendingProgress++
                    }) {
                        Icon(Icons.Default.Add, contentDescription = "One more", tint = Ink.Bone)
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    "Score — ${if (pendingScore > 0) pendingScore.toInt().toString() else "none"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink.Mist
                )
                Slider(
                    value = pendingScore.toFloat(),
                    onValueChange = { pendingScore = it.toDouble() },
                    valueRange = 0f..10f,
                    steps = 9
                )

                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            scope.launch {
                                runCatching {
                                    AniList.saveEntry(
                                        anilistId,
                                        progress = pendingProgress,
                                        score = pendingScore
                                    )
                                }
                                progressTick++
                                showListEditor = false
                            }
                        },
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("Save", style = MaterialTheme.typography.labelLarge) }

                    anime?.entryId?.let { id ->
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    runCatching { AniList.deleteEntry(id) }
                                    progressTick++
                                    showListEditor = false
                                }
                            },
                            shape = RoundedCornerShape(12.dp)
                        ) { Text("Remove", color = Ink.Orchid) }
                    }
                }
            }
        }
    }
}
