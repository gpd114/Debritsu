package com.debritsu.app.ui.tv

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.debritsu.app.data.AniList
import com.debritsu.app.data.Anime
import com.debritsu.app.data.Settings
import com.debritsu.app.ui.Ink
import com.debritsu.app.ui.pageBackground
import com.debritsu.app.ui.fieldColors
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Televisions cut a slice off every edge, varying by set. Content is kept
 * inside this margin.
 */
internal val OVERSCAN = 27.dp

private val POSTER_WIDTH = 116.dp

/**
 * The browse screen: a show's artwork and details across the top, rows of
 * shows beneath.
 *
 * The top follows the remote. Whichever card is focused puts its own art,
 * title and details up there, so moving along a row is browsing the shows
 * themselves rather than a strip of small posters — and the Resume and Details
 * buttons act on the show last focused. On a phone the top turns over on its
 * own; on a television that would be the picture changing under someone who
 * is deciding.
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
    onResume: (Int) -> Unit,
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
    // The show whose art and details are across the top: the last card the
    // remote was on, or, before any has been, the first show under way.
    var focusedShow by remember { mutableStateOf<Anime?>(null) }

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
    val fresh = recommended.filter { it.id !in onMyList }

    TvHomeFeed(
        watching = watching,
        planning = planning,
        trending = trending,
        recommended = fresh,
        focusedShow = focusedShow,
        onFocusShow = { focusedShow = it },
        searchActive = searchActive,
        query = query,
        onQuery = { query = it },
        searchFocus = searchFocus,
        found = found,
        searching = searching,
        needsSource = Settings.addons.isEmpty(),
        onStartSearch = { searchActive = true },
        onOpen = onOpen,
        onResume = onResume,
        onSettings = onSettings
    )
}

/**
 * The screen itself, from lists already in hand — kept apart from the
 * fetching so the debug preview can draw it with sample shows.
 */
@OptIn(ExperimentalTvMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun TvHomeFeed(
    watching: List<Anime>,
    planning: List<Anime>,
    trending: List<Anime>,
    recommended: List<Anime>,
    focusedShow: Anime?,
    onFocusShow: (Anime) -> Unit,
    searchActive: Boolean,
    query: String,
    onQuery: (String) -> Unit,
    searchFocus: FocusRequester,
    found: List<Anime>,
    searching: Boolean,
    needsSource: Boolean,
    onStartSearch: () -> Unit,
    onOpen: (Int) -> Unit,
    onResume: (Int) -> Unit,
    onSettings: () -> Unit
) {
    val shown = focusedShow ?: watching.firstOrNull() ?: trending.firstOrNull()
    val shelves = buildList {
        if (planning.isNotEmpty()) add("Plan to watch" to planning)
        if (trending.isNotEmpty()) add("Trending" to trending)
        if (recommended.isNotEmpty()) add("Recommended" to recommended)
    }

    Box(Modifier.fillMaxSize().pageBackground()) {
        // The focused show's art, across the top right, dissolving into the
        // page to the left where the words are and downward into the rows.
        if (!searching) {
            TvBackdrop(
                rememberTvArt(shown),
                Modifier
                    .align(Alignment.TopEnd)
                    .fillMaxWidth(0.74f)
                    .aspectRatio(16f / 9f)
            )
        }

        Column(Modifier.fillMaxSize().padding(OVERSCAN)) {
            // Pressing down from here lands on whatever lies beneath the
            // button, because focus moves geometrically and Search and
            // Settings sit at the right-hand end of this row. Aiming it with
            // focusProperties was tried twice and is not the answer — pointed
            // at a FocusRequester no card held, it threw and closed the app;
            // once attached, it swallowed the key press. Left alone
            // deliberately rather than guessed at a third time.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(start = 8.dp, bottom = 6.dp)
            ) {
                // The field only exists while searching. Marking it
                // unfocusable was tried and does not hold — focusProperties
                // has no effect on a text field's own focus target — so at
                // launch the remote's first press landed in it, the keyboard
                // opened, and the shows became unreachable behind it. A
                // control that is not in the tree cannot take focus, which is
                // the only version of this that survives contact with the box.
                if (searchActive) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = onQuery,
                        singleLine = true,
                        placeholder = { androidx.compose.material3.Text("Search anime") },
                        shape = RoundedCornerShape(24.dp),
                        colors = fieldColors(),
                        // Half the row rather than all of it. A search box does
                        // not get more useful for being a metre wide.
                        modifier = Modifier.width(420.dp).focusRequester(searchFocus)
                    )
                    Spacer(Modifier.weight(1f))
                } else if (query.isNotBlank()) {
                    Text(
                        "Results for “${query.trim()}”",
                        style = androidx.tv.material3.MaterialTheme.typography.titleMedium,
                        color = Ink.Mist,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    // What is airing soon. Deliberately not focusable: a
                    // focusable row here would sit between the top buttons and
                    // everything else, so every trip from Search to a show
                    // would cross it — and this is something to glance at
                    // rather than something to visit.
                    TvAiringStrip(
                        airing = (watching + planning)
                            .filter { it.nextEpisode != null && (it.airingInSeconds ?: 0) > 0 }
                            .sortedBy { it.airingInSeconds ?: Int.MAX_VALUE },
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.width(12.dp))
                if (!searchActive) {
                    TvIconButton(Icons.Default.Search, "Search", onStartSearch)
                    Spacer(Modifier.width(12.dp))
                }
                TvIconButton(Icons.Default.Settings, "Settings", onSettings)
            }

            if (searching) {
                // Results take over from everything else while there is a
                // query, rather than appearing beneath it — with a remote,
                // anything below the fold may as well not be there.
                if (found.isEmpty()) {
                    Text(
                        "Searching…",
                        style = androidx.tv.material3.MaterialTheme.typography.bodyLarge,
                        color = Ink.Mist,
                        modifier = Modifier.padding(start = 8.dp, top = 12.dp)
                    )
                } else {
                    TvShelf("Results for “${query.trim()}”", found, onOpen, onFocusShow, captions = true)
                }
                return@Column
            }

            shown?.let { TvHeroText(it, it.progress > 0, onOpen, onResume) }

            // Nothing can be played until an addon is configured, and on a
            // fresh install there is none — so say so rather than showing a
            // screen that looks broken.
            if (needsSource) {
                Text(
                    "No addons yet. Open Settings and paste an addon URL — a phone " +
                        "keyboard app makes that far less painful than the remote.",
                    style = androidx.tv.material3.MaterialTheme.typography.bodyLarge,
                    color = Ink.Mist,
                    modifier = Modifier.padding(start = 8.dp, bottom = 12.dp)
                )
            }

            // The rows scroll so the focused one sits at the top of the space
            // under the hero, title and all, rather than by the least that
            // brings the card into view. The least left a sliver of the row
            // above showing and the focused poster's lower edge under the
            // screen's — measured on the emulator.
            //
            // Only for this list. The spec is a composition local, so the rows
            // inside are handed back the default, or sideways moves would try
            // to pin each card against the left edge too.
            val rowSpec = LocalBringIntoViewSpec.current
            val density = LocalDensity.current
            val pinRows = remember(density) {
                // Row title and the room above a card for it to grow into.
                val above = with(density) { 38.dp.toPx() }
                object : BringIntoViewSpec {
                    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float) =
                        offset - above
                }
            }
            CompositionLocalProvider(LocalBringIntoViewSpec provides pinRows) {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (watching.isNotEmpty()) {
                        item {
                            CompositionLocalProvider(LocalBringIntoViewSpec provides rowSpec) {
                                TvContinueRow(watching, onOpen, onFocusShow)
                            }
                        }
                    }
                    items(shelves) { (title, list) ->
                        CompositionLocalProvider(LocalBringIntoViewSpec provides rowSpec) {
                            TvShelf(title, list, onOpen, onFocusShow)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The show across the top: what it is, how far in, and the two things to do
 * with it. A fixed height, so the rows beneath do not jump as the remote moves
 * between shows with longer and shorter titles.
 *
 * Its height and the rows' are budgeted together. The panel is 540dp tall;
 * the margins and top row take about 110 of it, this 200, which leaves one
 * row of posters whole beneath — measured on the emulator, with captions
 * under the posters as well the row was cut off at the bottom of the screen.
 * The captions went rather than the hero, since the name of whatever is
 * focused is written up here in large type anyway.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvHeroText(
    show: Anime,
    underway: Boolean,
    onOpen: (Int) -> Unit,
    onResume: (Int) -> Unit
) {
    val total = show.episodes ?: 0
    val next = (show.progress + 1).let { if (total > 0) it.coerceAtMost(total) else it }
    Column(
        verticalArrangement = Arrangement.Bottom,
        modifier = Modifier
            .fillMaxWidth(0.5f)
            .height(200.dp)
            .padding(start = 8.dp, bottom = 12.dp)
    ) {
        Text(
            if (underway) "CONTINUE WATCHING" else "TRENDING NOW",
            style = androidx.tv.material3.MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp
            ),
            color = Ink.Candy
        )
        Text(
            show.title,
            style = androidx.tv.material3.MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold),
            color = Ink.Bone,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp, bottom = 6.dp)
        )
        Text(
            buildAnnotatedString {
                show.averageScore?.let {
                    withStyle(SpanStyle(color = Ink.Candy, fontWeight = FontWeight.ExtraBold)) { append("★ $it%") }
                    append("   ")
                }
                append(
                    if (underway) "Episode $next" + (if (total > 0) " of $total" else "")
                    else show.episodes?.let { if (it == 1) "1 episode" else "$it episodes" } ?: ""
                )
            },
            style = androidx.tv.material3.MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            color = Ink.Mist
        )
        if (underway && total > 0) {
            Box(
                Modifier
                    .padding(top = 8.dp)
                    .width(260.dp)
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Ink.Edge)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(show.progress.toFloat() / total)
                        .height(5.dp)
                        .background(Ink.palette.progress)
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(top = 14.dp)
        ) {
            TvPrimaryButton(onClick = { onResume(show.id) }) {
                androidx.tv.material3.Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (underway) "Resume" else "Play")
            }
            TvSecondaryButton(onClick = { onOpen(show.id) }) { Text("Details") }
        }
    }
}

/**
 * "in 2d 4h", "in 5h 12m", "in 48m" — as much as is worth reading from a sofa.
 */
private fun airsIn(seconds: Int): String {
    val days = seconds / 86_400
    val hours = (seconds % 86_400) / 3600
    val minutes = (seconds % 3600) / 60
    return when {
        days > 0 -> "in ${days}d ${hours}h"
        hours > 0 -> "in ${hours}h ${minutes}m"
        minutes > 0 -> "in ${minutes}m"
        else -> "any moment"
    }
}

/**
 * What is airing soon, soonest first, ticking along the top row.
 *
 * Not focusable, and that is the design. A row the remote can enter would sit
 * between the buttons above and the shelves below, so every journey from Search
 * to a show would have to cross it — a toll on the common path to save the rare
 * one.
 *
 * Which leaves the problem that anything past the edge cannot be reached, so it
 * comes to the viewer instead: it creeps left for ever, slowly enough to read.
 * Only when there is something off the edge — a strip that fits sits still,
 * because motion nobody needs is just something moving in the corner of a room.
 *
 * The fades are drawn through an offscreen layer so they fade the content
 * itself. Painted straight on they would be bands of background colour, which
 * is only the same thing while the background never changes and nothing moves
 * underneath — and here something always does.
 */
@Composable
private fun TvAiringStrip(airing: List<Anime>, modifier: Modifier = Modifier) {
    if (airing.isEmpty()) {
        Spacer(modifier)
        return
    }

    val scroll = rememberScrollState()
    var copyWidth by remember { mutableStateOf(0) }
    var viewportWidth by remember { mutableStateOf(0) }

    // Only worth moving when one copy does not fit.
    val looping = viewportWidth > 0 && copyWidth > viewportWidth

    LaunchedEffect(airing, looping, copyWidth) {
        if (!looping) {
            scroll.scrollTo(0)
            return@LaunchedEffect
        }
        delay(2500)
        while (true) {
            // To the start of the second copy, then back to zero without
            // animating.
            //
            // That jump is the whole trick, and it cannot be seen: the list is
            // drawn twice, so the instant the second copy reaches the left edge
            // the screen is pixel for pixel what it was at zero. Running to the
            // end and rewinding was honest and looked like a fault — a ticker
            // should never be seen going backwards.
            scroll.animateScrollTo(
                copyWidth,
                // About a hundred pixels a second. Faster is unreadable across
                // a room; slower and the far end never arrives.
                tween(durationMillis = copyWidth * 10, easing = LinearEasing)
            )
            scroll.scrollTo(0)
        }
    }

    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        // Outside the scrolling part, so it stays put. It used to travel off
        // with the first chip, leaving the row unlabelled for most of every
        // pass.
        Text(
            "Airing next",
            style = androidx.tv.material3.MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.ExtraBold),
            color = Ink.Candy
        )
        Spacer(Modifier.width(12.dp))

        Row(
            Modifier.weight(1f)
                .onGloballyPositioned { viewportWidth = it.size.width }
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    // DstIn keeps the content where the brush is opaque and
                    // erases it where the brush is clear, so alpha runs one to
                    // nothing.
                    //
                    // Both edges together and only while looping. Testing the
                    // scroll offset instead made the left edge snap back to a
                    // hard cut for one frame at every wrap, which is the one
                    // moment the wrap must not be visible.
                    if (looping) {
                        drawRect(
                            brush = Brush.horizontalGradient(
                                0f to Color.Transparent,
                                0.05f to Color.Black,
                                startX = 0f,
                                endX = size.width
                            ),
                            blendMode = BlendMode.DstIn
                        )
                        drawRect(
                            brush = Brush.horizontalGradient(
                                0.90f to Color.Black,
                                1f to Color.Transparent,
                                startX = 0f,
                                endX = size.width
                            ),
                            blendMode = BlendMode.DstIn
                        )
                    }
                }
                .horizontalScroll(scroll, enabled = false),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                Modifier.onGloballyPositioned { copyWidth = it.size.width },
                verticalAlignment = Alignment.CenterVertically
            ) { AiringChips(airing) }

            // The second copy exists only to be scrolled into. Without it the
            // row runs out and shows empty space before the wrap.
            if (looping) {
                Row(verticalAlignment = Alignment.CenterVertically) { AiringChips(airing) }
            }
        }
    }
}

/** The chips themselves, drawn twice while the strip is looping. */
@Composable
private fun AiringChips(airing: List<Anime>) {
    // All of them, not the few that fit. The ticker is what makes the rest
    // reachable, so capping the list would undo the point of it.
    airing.forEach { show ->
        Row(
            Modifier.padding(end = 10.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(if (Ink.palette.dark) Ink.Veil else Color.White.copy(alpha = 0.85f))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                show.title,
                style = androidx.tv.material3.MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = Ink.Bone,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 260.dp)
            )
            Text(
                "  Episode ${show.nextEpisode}",
                style = androidx.tv.material3.MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = Ink.Candy,
                maxLines = 1
            )
            Text(
                "  ${airsIn(show.airingInSeconds ?: 0)}",
                style = androidx.tv.material3.MaterialTheme.typography.labelMedium,
                color = Ink.Mist,
                maxLines = 1
            )
        }
    }
}

/**
 * A button in the top row that shows its icon, and its name while focused.
 *
 * Icons alone are wrong on a television: there is no pointer to hover, no
 * tooltip, and the viewer is across the room from a glyph a few millimetres
 * across. Naming it while the remote is on it costs nothing — focus is where
 * attention already is, and only one of these can hold it — and means the row
 * reads as icons without ever being a guess.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    name: String,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    TvSecondaryButton(
        onClick = onClick,
        modifier = Modifier.onFocusChanged { focused = it.isFocused }
    ) {
        // The television library's Icon, not Compose Material 3's.
        //
        // They read different LocalContentColor composition locals, and the
        // button that supplies the colour is the television one. Material 3's
        // Icon therefore ignored it and stayed white — invisible on the white
        // pill a focused button becomes, so focusing Search made the magnifying
        // glass disappear and left the word alone.
        androidx.tv.material3.Icon(
            imageVector = icon,
            contentDescription = name,
            modifier = Modifier.size(20.dp)
        )
        if (focused) {
            Spacer(Modifier.width(8.dp))
            Text(name)
        }
    }
}

/**
 * The shows under way as wide cards, in their own fanart — the next episode
 * and a bar through the series over the bottom of each.
 */
@OptIn(ExperimentalTvMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun TvContinueRow(list: List<Anime>, onOpen: (Int) -> Unit, onFocusShow: (Anime) -> Unit) {
    val firstCard = remember { FocusRequester() }
    Column {
        RowTitle("Continue watching")
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            // Room for a focused card to grow into without being clipped by the
            // row's own bounds.
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
            modifier = Modifier.focusRestorer { firstCard }
        ) {
            itemsIndexed(list) { index, anime ->
                val total = anime.episodes ?: 0
                val next = (anime.progress + 1).let { if (total > 0) it.coerceAtMost(total) else it }
                // No caption beneath: the hero names whichever card is focused.
                Column(Modifier.width(236.dp)) {
                    Card(
                        onClick = { onOpen(anime.id) },
                        shape = TvCardShape,
                        modifier = Modifier
                            .then(if (index == 0) Modifier.focusRequester(firstCard) else Modifier)
                            .onFocusChanged { if (it.isFocused) onFocusShow(anime) }
                    ) {
                        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Ink.Veil)) {
                            AsyncImage(
                                model = rememberTvArt(anime),
                                contentDescription = anime.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                            Box(
                                Modifier.fillMaxSize().background(
                                    Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color(0xB3000000))
                                )
                            )
                            Text(
                                "EP $next",
                                style = androidx.tv.material3.MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = Color.White,
                                modifier = Modifier.align(Alignment.BottomStart).padding(10.dp)
                            )
                            if (total > 0) {
                                Box(
                                    Modifier
                                        .align(Alignment.BottomCenter)
                                        .fillMaxWidth()
                                        .height(4.dp)
                                        .background(Color(0x40FFFFFF))
                                ) {
                                    Box(
                                        Modifier
                                            .fillMaxWidth(anime.progress.toFloat() / total)
                                            .height(4.dp)
                                            .background(Ink.palette.progress)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun RowTitle(title: String) {
    Text(
        title,
        style = androidx.tv.material3.MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
        color = Ink.Bone,
        modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)
    )
}

@OptIn(ExperimentalTvMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun TvShelf(
    title: String,
    list: List<Anime>,
    onOpen: (Int) -> Unit,
    onFocusShow: (Anime) -> Unit,
    /** Names under the posters — for search results, which have no hero above to name them. */
    captions: Boolean = false
) {
    // Held by the first card, and used only when there is nothing to restore.
    val firstCard = remember { FocusRequester() }

    Column {
        RowTitle(title)
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            // Room for a focused card to grow into without being clipped by the
            // row's own bounds.
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
            // The row remembers which card you were on and gives focus back to
            // it, and starts at the first card when there is nothing to
            // remember.
            //
            // Measured: the Settings button sits at x=1518..1672 and the cards
            // at 70, 378, 686, 994, 1302, so entering the row by geometry
            // found the one at 1302 — the fifth — and the row scrolled to meet
            // it. Restoring alone was not enough: it faithfully brought back
            // that same fifth card, since that is where geometry had put it
            // the first time. The fallback is what makes the first entry land
            // at the start.
            modifier = Modifier.focusRestorer { firstCard }
        ) {
            itemsIndexed(list) { index, anime ->
                TvPoster(anime, onOpen, onFocusShow, captions, firstCard.takeIf { index == 0 })
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvPoster(
    anime: Anime,
    onOpen: (Int) -> Unit,
    onFocusShow: (Anime) -> Unit,
    captions: Boolean,
    focus: FocusRequester? = null
) {
    Column(Modifier.width(POSTER_WIDTH)) {
        Card(
            onClick = { onOpen(anime.id) },
            shape = TvCardShape,
            modifier = (focus?.let { Modifier.focusRequester(it) } ?: Modifier)
                .onFocusChanged { if (it.isFocused) onFocusShow(anime) }
        ) {
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
                    TvBadge("EP ${anime.progress}", Modifier.align(Alignment.BottomStart), solid = true)
                }
                // Top right, and only when AniList has a score — a new or
                // obscure title often has none, and an empty pill reads worse
                // than no pill.
                anime.averageScore?.let { score ->
                    TvBadge("★ $score%", Modifier.align(Alignment.TopEnd))
                }
            }
        }
        // Both lines reserved whether the title needs them or not. Cards of
        // differing height in one row let a taller one hang below its
        // neighbours, and a downward press then finds that neighbour rather
        // than the row beneath — which is the bug that cost the phone build an
        // evening.
        if (captions) {
            Text(
                anime.title,
                style = androidx.tv.material3.MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                color = Ink.Bone,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp, start = 2.dp)
            )
        }
    }
}
