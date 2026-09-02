package com.debritsu.app.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.debritsu.app.data.AniList
import com.debritsu.app.data.Anime
import com.debritsu.app.data.Settings
import com.debritsu.app.ui.Ink
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Televisions cut a slice off every edge, varying by set. Content is kept
 * inside this margin.
 */
internal val OVERSCAN = 27.dp

private val POSTER_WIDTH = 140.dp

/**
 * The browse screen, as rows of shows.
 *
 * Focus is left entirely to tv-material's [Card], which scales and outlines
 * itself when selected and moves correctly under a d-pad. The equivalent was
 * hand-written on the phone build and went wrong repeatedly; none of that is
 * repeated here.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvHomeScreen(
    onOpen: (Int) -> Unit,
    onSettings: () -> Unit,
    authFlash: Int
) {
    var watching by remember { mutableStateOf<List<Anime>>(emptyList()) }
    var planning by remember { mutableStateOf<List<Anime>>(emptyList()) }
    var trending by remember { mutableStateOf<List<Anime>>(emptyList()) }
    var recommended by remember { mutableStateOf<List<Anime>>(emptyList()) }
    var listed by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var query by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<List<Anime>>(emptyList()) }
    val searching = query.trim().length >= 3

    // The field can only be focused once search is deliberately started, and a
    // button is what starts it.
    //
    // Blocking focus for a moment after launch was tried first and does not
    // work: nothing holds focus during that window, so the first press of the
    // remote simply takes the first focusable on screen — which is the field.
    // Once it has focus the keyboard opens and owns the d-pad, and the shows
    // are unreachable behind it. Measured on the box: down, right and up all
    // left the selection sitting in the field.
    var searchActive by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }

    LaunchedEffect(searchActive) {
        if (searchActive) {
            delay(50)
            runCatching { searchFocus.requestFocus() }
        }
    }

    // Back leaves search rather than the screen, and clears what was typed so
    // the shelves come back.
    BackHandler(enabled = searchActive) {
        searchActive = false
        query = ""
    }

    // Three characters before asking, and a pause after the last keystroke, so
    // a phone keyboard app typing a title does not fire a query per letter.
    LaunchedEffect(query) {
        if (!searching) {
            found = emptyList()
            return@LaunchedEffect
        }
        delay(350)
        found = runCatching { AniList.search(query.trim()).items }.getOrDefault(emptyList())
    }

    // Three independent queries, run together rather than one after another.
    // Each row is assigned on its own so it appears as its own query lands
    // instead of every row waiting for the slowest.
    // Reloaded on every return, so Continue watching reflects the episode just
    // finished rather than the state this screen was first built with.
    var refresh by remember { mutableStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // Every one of these keeps what it had when a request fails, rather than
    // being emptied.
    //
    // They used to fall back to an empty list, and a shelf is hidden when it is
    // empty, so a single failed request made Continue watching disappear —
    // reported as it vanishing at random. It was not random: this refetches on
    // every return from a show, five requests a time against AniList's thirty a
    // minute, so a few episodes in a row is exactly when one gets refused. A
    // failure means "no answer", which is not the same as "nothing there".
    //
    // Split in two as well. Watching an episode changes your own lists and
    // nothing else, so only those are asked for again on return; Trending and
    // Recommended are fetched once. That takes a return from five requests to
    // three, and the ids are usually answered from the session cache.
    LaunchedEffect(authFlash, refresh) {
        coroutineScope {
            // Your own lists change when you watch something, so these are
            // always asked for again.
            launch { runCatching { AniList.watching() }.onSuccess { watching = it } }
            launch { runCatching { AniList.planning() }.onSuccess { planning = it } }
            launch { runCatching { AniList.listedIds() }.onSuccess { listed = it } }

            // These two do not change while you watch an episode, so they are
            // asked for only while missing. That is both halves of the problem
            // at once: a return costs three requests instead of five, and a
            // shelf that failed on the way in gets another go every time you
            // come back rather than staying empty for the session.
            if (trending.isEmpty()) {
                launch { runCatching { AniList.trending().items }.onSuccess { trending = it } }
            }
            if (recommended.isEmpty()) {
                launch { runCatching { AniList.recommended().items }.onSuccess { recommended = it } }
            }
        }
    }

    // Anything already on a list is not a discovery, so the recommendations
    // drop it — and that means the whole list, not just the two shelves above.
    // Filtering on those alone let shows through that were finished and marked
    // completed years ago, which is the one thing this shelf should never
    // suggest.
    val onMyList = listed + (watching + planning).map { it.id }

    val shelves = buildList {
        if (watching.isNotEmpty()) add("Continue watching" to watching)
        if (planning.isNotEmpty()) add("Plan to watch" to planning)
        if (trending.isNotEmpty()) add("Trending" to trending)
        val fresh = recommended.filter { it.id !in onMyList }
        if (fresh.isNotEmpty()) add("Recommended" to fresh)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Ink.Base)
            .padding(OVERSCAN)
    ) {
        // Pressing down from here lands on the fifth card rather than the
        // first, because focus moves geometrically and Search and Settings sit
        // at the right-hand end of this row.
        //
        // Aiming down at the first card with focusProperties was tried twice
        // and is not the answer. Pointed at a FocusRequester no card held, it
        // threw and closed the app; once attached, it swallowed the key press
        // and focus stopped moving at all. There is already a note further down
        // this file about focusProperties not taking on a text field either, so
        // that is twice this mechanism has not done what it says.
        //
        // Left alone deliberately rather than guessed at a third time.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, bottom = 8.dp)
        ) {
            // Search takes the space the app's name used to. Nobody needs
            // reminding what they are looking at while they are looking at it.
            //
            // The field only exists while searching. Marking it unfocusable was
            // tried and does not hold — focusProperties has no effect on a text
            // field's own focus target — so at launch the remote's first press
            // landed in it, the keyboard opened, and the shows became
            // unreachable behind it. A control that is not in the tree cannot
            // take focus, which is the only version of this that survives
            // contact with the box.
            if (searchActive) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { androidx.compose.material3.Text("Search anime") },
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = Ink.Edge,
                        focusedBorderColor = Ink.Iris,
                        unfocusedContainerColor = Ink.Veil,
                        focusedContainerColor = Ink.Veil
                    ),
                    modifier = Modifier.weight(1f).focusRequester(searchFocus)
                )
            } else {
                Text(
                    if (query.isBlank()) "Search anime" else "Results for “${query.trim()}”",
                    style = androidx.tv.material3.MaterialTheme.typography.titleMedium,
                    color = Ink.Mist,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.width(12.dp))
            if (!searchActive) {
                Button(onClick = { searchActive = true }) { Text("Search") }
                Spacer(Modifier.width(12.dp))
            }
            Button(onClick = onSettings) { Text("Settings") }
        }

        // Nothing can be played until an addon is configured, and on a fresh
        // install there is none — so say so rather than showing an empty screen
        // that looks broken.
        if (Settings.addons.isEmpty()) {
            Text(
                "No addons yet. Open Settings and paste an addon URL — a phone " +
                    "keyboard app makes that far less painful than the remote.",
                style = androidx.tv.material3.MaterialTheme.typography.bodyLarge,
                color = Ink.Mist,
                modifier = Modifier.padding(start = 8.dp, bottom = 12.dp)
            )
        }

        // Results take over from the shelves while there is a query, rather
        // than appearing beneath them — with a remote, anything below the fold
        // may as well not be there.
        if (searching) {
            if (found.isEmpty()) {
                Text(
                    "Searching…",
                    style = androidx.tv.material3.MaterialTheme.typography.bodyLarge,
                    color = Ink.Mist,
                    modifier = Modifier.padding(start = 8.dp, top = 12.dp)
                )
            } else {
                TvShelf("Results for “${query.trim()}”", found, onOpen)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                items(shelves) { (title, list) ->
                    TvShelf(title, list, onOpen)
                }
            }
        }
    }
}

/** A small dark pill over the artwork, legible at a distance. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Badge(text: String, colour: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Box(
        modifier
            .padding(6.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(androidx.compose.ui.graphics.Color(0xCC08070D))
            .padding(horizontal = 7.dp, vertical = 3.dp)
    ) {
        Text(
            text,
            style = androidx.tv.material3.MaterialTheme.typography.labelSmall,
            color = colour
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvShelf(title: String, list: List<Anime>, onOpen: (Int) -> Unit) {
    Column {
        Text(
            title,
            style = androidx.tv.material3.MaterialTheme.typography.titleMedium,
            color = Ink.Bone,
            modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            // Room for a focused card to grow into without being clipped by the
            // row's own bounds.
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
        ) {
            items(list) { anime -> TvPoster(anime, onOpen) }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvPoster(anime: Anime, onOpen: (Int) -> Unit) {
    Column(Modifier.width(POSTER_WIDTH)) {
        Card(onClick = { onOpen(anime.id) }) {
            Box {
                AsyncImage(
                    model = anime.cover,
                    contentDescription = anime.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f / 3f)
                        .background(Ink.Veil)
                )
                // How far in you are, bottom left.
                if (anime.progress > 0) {
                    Badge(
                        "EP ${anime.progress.toString().padStart(2, '0')}",
                        Ink.Bone,
                        Modifier.align(Alignment.BottomStart)
                    )
                }
                // Opposite corner, and only when AniList has a score — a new or
                // obscure title often has none, and an empty pill reads worse
                // than no pill.
                anime.averageScore?.let { score ->
                    Badge(
                        "$score%",
                        if (score >= 75) Ink.Iris else Ink.Bone,
                        Modifier.align(Alignment.BottomEnd)
                    )
                }
            }
        }
        // Both lines reserved whether the title needs them or not. Cards of
        // differing height in one row let a taller one hang below its
        // neighbours, and a downward press then finds that neighbour rather
        // than the row beneath — which is the bug that cost the phone build an
        // evening.
        Text(
            anime.title,
            style = androidx.tv.material3.MaterialTheme.typography.bodySmall,
            color = Ink.Mist,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp)
        )
    }
}
