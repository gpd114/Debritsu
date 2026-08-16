package com.debritsu.app.player

import android.content.Context
import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.app.Dialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import com.debritsu.app.data.AniList
import com.debritsu.app.R
import com.debritsu.app.cast.CastTarget
import com.debritsu.app.cast.CastTargets
import com.debritsu.app.cast.GoogleCast
import com.debritsu.app.data.AniSkip
import com.debritsu.app.data.AutoPlay
import com.debritsu.app.data.Debrid
import com.debritsu.app.data.Downloads
import com.debritsu.app.data.Mappings
import com.debritsu.app.data.Progress
import com.debritsu.app.data.SourceHandoff
import com.debritsu.app.data.StreamOption
import com.debritsu.app.data.Subtitle
import com.debritsu.app.data.SyncQueue
import com.debritsu.app.data.json
import com.debritsu.app.data.Settings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer

/** Marks the panel subheading so it can be rewritten while the panel is open. */
private const val SUBHEADING_TAG = "panel_subheading"

@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    private var player: ExoPlayer? = null
    private var progressPushed = false
    private var anilistId = 0
    private var episode = 0
    private var sources: List<StreamOption> = emptyList()
    private var subtitleConfigs: List<MediaItem.SubtitleConfiguration> = emptyList()
    private var currentUrl: String? = null
    private var currentTitle: String = "Debritsu"
    private var episodeCount = 0
    private var seriesTitle: String = ""
    private var switchingEpisode = false
    private var segments: List<AniSkip.Segment> = emptyList()
    /** Index into [sources] of what is playing, or -1 when it isn't one of them. */
    private var currentSourceIndex = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = intent.getStringExtra(EXTRA_URL) ?: run { finish(); return }
        currentUrl = url
        currentTitle = intent.getStringExtra(EXTRA_TITLE) ?: "Debritsu"
        anilistId = intent.getIntExtra(EXTRA_ANILIST_ID, 0)
        episode = intent.getIntExtra(EXTRA_EPISODE, 0)
        episodeCount = intent.getIntExtra(EXTRA_EPISODE_COUNT, 0)
        seriesTitle = intent.getStringExtra(EXTRA_SERIES_TITLE).orEmpty()
        currentSourceIndex = intent.getIntExtra(EXTRA_SOURCE_INDEX, -1)
        // Not an Intent extra: a busy episode returns hundreds of sources, each
        // carrying a debrid URL of some 1,500 characters, and serialising that
        // into the launch overruns the Binder transaction limit — which kills
        // the app during startActivity without raising anything catchable.
        sources = SourceHandoff.take()
        val subUrls = intent.getStringArrayExtra(EXTRA_SUB_URLS).orEmpty()
        val subLangs = intent.getStringArrayExtra(EXTRA_SUB_LANGS).orEmpty()

        setContentView(R.layout.activity_player)
        val view = findViewById<PlayerView>(R.id.player_view)
        view.setKeepContentOnPlayerReset(true)

        // The Sources button lives in the control bar, so it fades with the
        // rest of the transport controls rather than sitting over the video.
        val sourcesButton = findViewById<ImageButton>(R.id.sources_button)
        sourcesButton.visibility = if (sources.size > 1) View.VISIBLE else View.GONE
        sourcesButton.setOnClickListener { showSourcePicker() }

        findViewById<ImageButton>(R.id.cast_button).setOnClickListener { showCastPicker() }

        findViewById<ImageButton>(R.id.prev_episode)
            .setOnClickListener { goToEpisode(episode - 1) }
        findViewById<ImageButton>(R.id.next_episode)
            .setOnClickListener { goToEpisode(episode + 1) }
        updateEpisodeButtons()

        installGestures(view)
        installSkipButton()
        applySubtitleStyle(view.subtitleView)

        subtitleConfigs = subUrls.mapIndexed { i, subUrl ->
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(subUrl))
                .setMimeType(mimeFor(subUrl))
                .setLanguage(subLangs.getOrNull(i) ?: "und")
                .setSelectionFlags(0)
                .build()
        }

        val item = mediaItem(url)

        player = ExoPlayer.Builder(this).build().also { exo ->
            view.player = exo
            // Prefer English subs by default; the user can override from the CC button.
            //
            // Audio has to be asked for explicitly. Left alone, ExoPlayer takes
            // the device language, so an English phone plays the dub on any
            // dual-audio release — an odd thing for the app to decide silently
            // while also insisting on English subtitles. Empty means defer to
            // the device after all, so it is simply not set.
            exo.trackSelectionParameters = exo.trackSelectionParameters
                .buildUpon()
                .setPreferredTextLanguage("en")
                .apply {
                    val audio = Settings.preferredAudioLanguage
                    if (audio.isNotEmpty()) setPreferredAudioLanguage(audio)
                }
                .build()
            exo.setMediaItem(item)
            exo.prepare()
            // Pick up where this episode was left, if anywhere.
            val resumeAt = Progress.position(anilistId, episode)
            if (resumeAt > 0) exo.seekTo(resumeAt)
            exo.playWhenReady = true
            exo.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    // Only hold the screen awake while video is actually running,
                    // so a paused player doesn't drain the battery.
                    view.keepScreenOn = isPlaying
                }

                override fun onEvents(p: Player, events: Player.Events) {
                    val duration = p.duration
                    if (!progressPushed && anilistId > 0 && episode > 0 &&
                        duration > 0 && p.currentPosition > duration * 0.85
                    ) {
                        progressPushed = true
                        Progress.clear(anilistId, episode)
                        lifecycleScope.launch {
                            // Offline finishes still count — park them for later.
                            val sent = runCatching {
                                AniList.setProgress(anilistId, episode)
                            }.isSuccess
                            if (!sent) SyncQueue.queue(anilistId, episode)
                        }
                    }
                }
            })
        }

        loadSkipSegments()
    }

    private fun mediaItem(url: String): MediaItem =
        MediaItem.Builder()
            .setUri(url)
            .setSubtitleConfigurations(subtitleConfigs)
            .build()

    /**
     * Episode stepping needs an AniList id and a title to resolve against, so
     * it's hidden for downloads and one-off links, which have nothing to step
     * through. A null episode count means an ongoing show — allow forward and
     * let the lookup fail honestly rather than guessing where the series ends.
     */
    private fun updateEpisodeButtons() {
        val navigable = anilistId > 0 && episode > 0 && seriesTitle.isNotEmpty()
        findViewById<ImageButton>(R.id.prev_episode).visibility =
            if (navigable && episode > 1) View.VISIBLE else View.GONE
        findViewById<ImageButton>(R.id.next_episode).visibility =
            if (navigable && (episodeCount <= 0 || episode < episodeCount)) View.VISIBLE
            else View.GONE
    }

    /**
     * Resolves an adjacent episode and swaps it in without leaving the player.
     *
     * There is no playlist to seek through — every episode is a fresh addon
     * lookup and debrid resolve — so this repeats what the detail screen does,
     * ending in the same source picker. Choosing matters here: sources vary by
     * several gigabytes, language and release group, and picking automatically
     * would spend someone's mobile data for them.
     */
    private fun goToEpisode(target: Int) {
        if (switchingEpisode) return
        if (target < 1 || (episodeCount > 0 && target > episodeCount)) return
        val exo = player ?: return

        switchingEpisode = true
        savePosition()
        exo.pause()
        val loading = panelDialog("Episode $target", "FINDING SOURCES", emptyList()) {}
        loading.show()

        lifecycleScope.launch {
            // True once something else is responsible for what plays next,
            // either because the episode already changed or because the picker
            // is up and waiting on a choice.
            var handedOff = false
            try {
                // A downloaded copy plays straight from disk, same as the
                // detail screen does, and never touches the network.
                val offline = Downloads.get(anilistId, target)
                    ?.takeIf { Downloads.isComplete(it) }
                if (offline != null) {
                    startEpisode(
                        target,
                        Uri.fromFile(Downloads.fileFor(offline)).toString(),
                        emptyList(),
                        emptyList(),
                        -1
                    )
                    handedOff = true
                    return@launch
                }

                // The same rules as pressing play on the detail screen. Asking
                // every time made sense before those rules existed; now that a
                // quality ceiling and a size limit are enforced, stopping to
                // ask is just an inconsistency between two ways of starting the
                // same episode.
                val outcome = AutoPlay.run(
                    anilistId = anilistId,
                    title = seriesTitle,
                    episode = target,
                    isMovie = episodeCount == 1,
                    filter = Settings.sourceFilter,
                    autoSelect = Settings.autoPlay
                ) { step -> setPanelSubheading(loading, stepLabel(step)) }

                val found = outcome.results.flatMap { it.streams }
                val url = outcome.url
                if (url != null) {
                    startEpisode(
                        target, url, found, outcome.subtitles, found.indexOf(outcome.chosen)
                    )
                    handedOff = true
                    return@launch
                }

                if (found.isEmpty()) {
                    toast(outcome.message ?: "No sources found for episode $target.")
                    return@launch
                }

                // Nothing matched, nothing resolved, or automatic selection is
                // switched off — either way the choice comes back to the user.
                val rows = found.map { s ->
                    Row(
                        s.name,
                        s.description.replace("\n", " ").take(110),
                        if (s.isDirect) "DIRECT" else "DEBRID"
                    )
                }
                val picker = panelDialog("Episode $target", "${found.size} AVAILABLE", rows) { index ->
                    val chosen = found[index]
                    lifecycleScope.launch {
                        toast("Resolving link…")
                        val url2 = runCatching { Debrid.resolve(chosen) }.getOrNull()
                        if (url2 == null) {
                            toast("Couldn't resolve that source.")
                            player?.play()
                        } else {
                            startEpisode(
                                target, url2, found,
                                (chosen.subtitles + outcome.subtitles).distinctBy { it.url },
                                index
                            )
                        }
                    }
                }
                outcome.message?.let { toast(it) }
                // Backing out of the picker leaves the current episode playing.
                picker.setOnCancelListener { player?.play() }
                picker.show()
                handedOff = true
            } finally {
                runCatching { loading.dismiss() }
                switchingEpisode = false
                if (!handedOff) player?.play()
            }
        }
    }

    private fun startEpisode(
        target: Int,
        url: String,
        newSources: List<StreamOption>,
        subs: List<Subtitle>,
        sourceIndex: Int
    ) {
        val exo = player ?: return
        episode = target
        currentUrl = url
        currentSourceIndex = sourceIndex
        currentTitle = if (seriesTitle.isNotEmpty()) "$seriesTitle — EP $target" else "EP $target"
        sources = newSources
        // A new episode has its own completion threshold to cross.
        progressPushed = false

        subtitleConfigs = subs.map { s ->
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(s.url))
                .setMimeType(mimeFor(s.url))
                .setLanguage(s.lang)
                .setSelectionFlags(0)
                .build()
        }

        findViewById<ImageButton>(R.id.sources_button).visibility =
            if (sources.size > 1) View.VISIBLE else View.GONE
        updateEpisodeButtons()

        exo.setMediaItem(mediaItem(url))
        exo.prepare()
        val resumeAt = Progress.position(anilistId, target)
        if (resumeAt > 0) exo.seekTo(resumeAt)
        exo.playWhenReady = true
        loadSkipSegments()
    }

    /**
     * Double tap the left or right third to skip, drag up or down the left half
     * for brightness and the right half for volume.
     *
     * The listener only swallows an event once a gesture has actually fired,
     * otherwise a plain tap would stop toggling the transport controls.
     */
    private fun installGestures(view: PlayerView) {
        val hud = findViewById<TextView>(R.id.gesture_hud)
        hud.background = GradientDrawable().apply {
            setColor(0xCC171226.toInt())
            cornerRadius = 14 * resources.displayMetrics.density
        }

        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val hide = Runnable { hud.visibility = View.GONE }

        fun readout(text: String) {
            hud.text = text
            hud.visibility = View.VISIBLE
            hud.removeCallbacks(hide)
            hud.postDelayed(hide, 700)
        }

        var consumed = false
        var dragging = false
        var startVolume = 0
        var startBrightness = 0f

        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {

            override fun onDown(e: MotionEvent): Boolean {
                consumed = false
                dragging = false
                startVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                // -1 means "follow the system", which is the state on first touch.
                startBrightness = window.attributes.screenBrightness
                    .takeIf { it >= 0f } ?: systemBrightness()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val exo = player ?: return false
                when {
                    e.x < view.width / 3f -> {
                        exo.seekBack()
                        readout("−${exo.seekBackIncrement / 1000}s")
                    }
                    e.x > view.width * 2f / 3f -> {
                        exo.seekForward()
                        readout("+${exo.seekForwardIncrement / 1000}s")
                    }
                    // The middle is left alone so double-tapping the centre
                    // still just shows the controls.
                    else -> return false
                }
                consumed = true
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                val start = e1 ?: return false
                if (!dragging) {
                    val dy = abs(e2.y - start.y)
                    // Wait until the drag is clearly vertical, so horizontal
                    // movement never nudges the volume.
                    if (dy < abs(e2.x - start.x) || dy < 24f) return false
                    dragging = true
                }

                val fraction = (start.y - e2.y) / (view.height * 0.7f)
                if (start.x < view.width / 2f) {
                    val level = (startBrightness + fraction).coerceIn(0.01f, 1f)
                    window.attributes = window.attributes.apply { screenBrightness = level }
                    readout("Brightness  ${(level * 100).roundToInt()}%")
                } else {
                    val level = (startVolume + fraction * maxVolume)
                        .roundToInt().coerceIn(0, maxVolume)
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0)
                    readout("Volume  ${(level * 100f / maxVolume).roundToInt()}%")
                }
                consumed = true
                return true
            }
        })

        view.setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
            consumed
        }
    }

    /**
     * Shows a skip button whenever playback is inside a known opening or
     * ending, and seeks past it when tapped.
     *
     * Polling beats listening here: ExoPlayer reports position changes on
     * seeks and state changes, not as playback advances, so there is no event
     * that fires when an opening simply arrives.
     */
    private fun installSkipButton() {
        val button = findViewById<TextView>(R.id.skip_segment)
        button.background = GradientDrawable().apply {
            setColor(0xE68B5CF6.toInt())
            cornerRadius = 26 * resources.displayMetrics.density
        }

        lifecycleScope.launch {
            while (true) {
                val exo = player
                val active = if (exo == null) null
                else segments.firstOrNull { exo.currentPosition in it.startMs..it.endMs }

                if (active == null) {
                    button.visibility = View.GONE
                } else {
                    button.text = active.label
                    button.visibility = View.VISIBLE
                    button.setOnClickListener {
                        exo?.seekTo(active.endMs)
                        button.visibility = View.GONE
                    }
                }
                delay(400)
            }
        }
    }

    /** Looks up opening and ending times for whatever is playing now. */
    private fun loadSkipSegments() {
        segments = emptyList()
        if (anilistId <= 0 || episode <= 0) return

        val forEpisode = episode
        lifecycleScope.launch {
            // Give the player a moment to work out the duration: the service
            // uses it to fit its timings to this particular encode.
            var waited = 0
            while (waited < 5000 && (player?.duration ?: 0L) <= 0L) {
                delay(250)
                waited += 250
            }

            val mal = runCatching {
                Mappings.forAniList(anilistId, seriesTitle.ifEmpty { null }).mal?.toIntOrNull()
            }.getOrNull()
            val found = AniSkip.segments(
                mal, forEpisode, (player?.duration ?: 0L).coerceAtLeast(0L)
            )

            // A quick jump to the next episode can land mid-lookup; timings
            // from the episode we just left would be worse than none.
            if (episode == forEpisode) segments = found
        }
    }

    /** The system brightness, as a starting point for the first drag. */
    private fun systemBrightness(): Float = runCatching {
        android.provider.Settings.System.getInt(
            contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS
        ) / 255f
    }.getOrDefault(0.5f)

    /**
     * Local files can't be cast — a debrid URL is reachable from the TV, a
     * path inside app storage is not.
     */
    private fun showCastPicker() {
        val url = currentUrl ?: return
        val isLocal = !url.startsWith("http")

        // Local files skip the network scan entirely: only an app on this
        // device can open them, so there is nothing to discover.
        val loading = if (isLocal) null
        else panelDialog("Finding devices", "SEARCHING YOUR NETWORK", emptyList()) {}
        loading?.show()

        lifecycleScope.launch {
            // If the activity goes away mid-scan the coroutine is cancelled, so
            // the dismiss has to happen in a finally or the dialog leaks its window.
            val targets = try {
                runCatching { CastTargets.discover(this@PlayerActivity, isLocal) }
                    .getOrDefault(listOf(CastTarget.External))
            } finally {
                // Dismissing against a window that's already gone throws.
                runCatching { loading?.dismiss() }
            }

            val heading = if (isLocal) "Open with" else "Cast to"
            // "Other app" is always in the list, so counting it reports a
            // device found when the scan actually came back empty.
            val devices = targets.count { it !is CastTarget.External }
            val sub = when {
                isLocal -> "DOWNLOADED EPISODE"
                devices == 0 -> "NO TVS FOUND ON THIS NETWORK"
                devices == 1 -> "1 DEVICE FOUND"
                else -> "$devices DEVICES FOUND"
            }
            val rows = targets.map { Row(it.label, it.detail, null) }
            panelDialog(heading, sub, rows) { index ->
                val target = targets[index]
                val position = player?.currentPosition ?: 0
                lifecycleScope.launch {
                    // Cast waits on a session handshake that can take a while
                    // on a sleeping receiver, so say something first.
                    if (target is CastTarget.Cast) toast("Connecting to ${target.label}…")

                    val error = CastTargets.send(
                        this@PlayerActivity, target, url, currentTitle, position
                    )
                    if (error != null) toast(error) else {
                        player?.pause()
                        if (target !is CastTarget.External) toast("Playing on ${target.label}")
                    }
                }
            }.show()
        }
    }

    /** Updates a shown panel's subheading in place. */
    private fun setPanelSubheading(dialog: Dialog, text: String) {
        runCatching {
            dialog.window?.decorView
                ?.findViewWithTag<TextView>(SUBHEADING_TAG)?.text = text
        }
    }

    /** What auto-play is doing, for the panel subheading. */
    private fun stepLabel(step: AutoPlay.Step): String = when (step) {
        AutoPlay.Step.Locating -> "FINDING THIS EPISODE"
        AutoPlay.Step.Searching -> "SEARCHING YOUR ADDONS"
        is AutoPlay.Step.Filtering -> "${step.kept} OF ${step.found} MATCH"
        is AutoPlay.Step.Resolving -> "CHECKING SOURCE ${step.attempt} OF ${step.of}"
        AutoPlay.Step.Ready -> "STARTING PLAYBACK"
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
    }

    private data class Row(val title: String, val subtitle: String, val tag: String?)

    /** The app's panel styling, shared by the source and cast pickers. */
    private fun panelDialog(
        heading: String,
        subheading: String,
        rows: List<Row>,
        onPick: (Int) -> Unit
    ): Dialog {
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(18), px(20), px(24))
            background = GradientDrawable().apply {
                setColor(0xFF171226.toInt())
                cornerRadius = px(20).toFloat()
            }
        }
        content.addView(TextView(this).apply {
            text = heading
            setTextColor(0xFFF1EEF8.toInt())
            textSize = 16f
            setTypeface(Typeface.DEFAULT_BOLD)
        })
        content.addView(TextView(this).apply {
            text = subheading
            setTextColor(0xFFB9B3CC.toInt())
            textSize = 10.5f
            typeface = Typeface.MONOSPACE
            setPadding(0, px(2), 0, px(12))
            // Tagged so a long-running panel can narrate what it is doing
            // rather than sitting on one line of text.
            tag = SUBHEADING_TAG
        })

        val dialog = Dialog(this, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)

        rows.forEachIndexed { index, row ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, px(12), 0, px(12))
                isClickable = true
                setOnClickListener { dialog.dismiss(); onPick(index) }
            }
            item.addView(TextView(this).apply {
                text = row.title
                setTextColor(0xFFF1EEF8.toInt())
                textSize = 12f
                typeface = Typeface.MONOSPACE
                maxLines = 1
            })
            item.addView(TextView(this).apply {
                text = row.subtitle
                setTextColor(0xFFB9B3CC.toInt())
                textSize = 11.5f
                maxLines = 2
            })
            row.tag?.let { tag ->
                item.addView(TextView(this@PlayerActivity).apply {
                    text = tag
                    setTextColor(
                        if (tag == "DIRECT" || tag == "PLAYING") 0xFF8B5CF6.toInt()
                        else 0xFFB9B3CC.toInt()
                    )
                    textSize = 9.5f
                    typeface = Typeface.MONOSPACE
                    setPadding(0, px(3), 0, 0)
                })
            }
            content.addView(item)
            content.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, px(1)
                )
                setBackgroundColor(0xFF221A36.toInt())
            })
        }

        dialog.setContentView(ScrollView(this).apply { addView(content) })
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0x99000000.toInt()))
            setLayout(
                (resources.displayMetrics.widthPixels * 0.62).toInt(),
                (resources.displayMetrics.heightPixels * 0.80).toInt()
            )
            setGravity(Gravity.BOTTOM or Gravity.END)
        }
        return dialog
    }

    /** Swap source without losing your place in the episode. */
    private fun showSourcePicker() {
        if (sources.isEmpty()) return

        // Whatever is playing goes to the top and says so. Auto-play picks
        // silently, and without this there is no way to tell which of forty
        // near-identical releases you ended up watching.
        val ordered = sources.indices.sortedByDescending { it == currentSourceIndex }
        val rows = ordered.map { i ->
            val s = sources[i]
            Row(
                s.name,
                s.description.replace("\n", " ").take(110),
                when {
                    i == currentSourceIndex -> "PLAYING"
                    s.isDirect -> "DIRECT"
                    else -> "DEBRID"
                }
            )
        }
        panelDialog("Sources", "${sources.size} AVAILABLE", rows) { position ->
            val index = ordered[position]
            if (index != currentSourceIndex) switchTo(sources[index], index)
        }.show()
    }

    private fun switchTo(stream: StreamOption, index: Int) {
        val exo = player ?: return
        val resumeAt = exo.currentPosition
        exo.pause()
        lifecycleScope.launch {
            runCatching { Debrid.resolve(stream) }
                .onSuccess { url ->
                    // Keep these in step, or casting after a switch sends the
                    // stream the user just moved away from, and the picker
                    // marks the wrong row as playing.
                    currentUrl = url
                    currentSourceIndex = index
                    exo.setMediaItem(mediaItem(url))
                    exo.prepare()
                    exo.seekTo(resumeAt)
                    exo.playWhenReady = true
                }
                .onFailure {
                    android.widget.Toast.makeText(
                        this@PlayerActivity,
                        it.message ?: "Could not switch source",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                    exo.play()
                }
        }
    }

    private fun applySubtitleStyle(subtitleView: SubtitleView?) {
        val view = subtitleView ?: return

        val foreground = when (Settings.subtitleColour) {
            1 -> Color.parseColor("#FFF6C84C")
            2 -> Color.parseColor("#FF6FE7DD")
            else -> Color.WHITE
        }
        val background = when (Settings.subtitleBackground) {
            0 -> Color.TRANSPARENT
            2 -> Color.BLACK
            else -> Color.argb(140, 0, 0, 0)
        }
        val edgeType =
            if (Settings.subtitleOutline) CaptionStyleCompat.EDGE_TYPE_OUTLINE
            else CaptionStyleCompat.EDGE_TYPE_NONE

        // Ignore styling baked into the subtitle file so these choices actually win.
        view.setApplyEmbeddedStyles(false)
        view.setStyle(
            CaptionStyleCompat(
                foreground, background, Color.TRANSPARENT,
                edgeType, Color.BLACK, null
            )
        )
        view.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, Settings.subtitleSizeSp)
        view.setBottomPaddingFraction(0.08f)
    }

    private fun mimeFor(url: String) =
        when (url.substringAfterLast('.', "").lowercase().take(4)) {
            "vtt" -> MimeTypes.TEXT_VTT
            "ass", "ssa" -> MimeTypes.TEXT_SSA
            "ttml", "xml" -> MimeTypes.APPLICATION_TTML
            else -> MimeTypes.APPLICATION_SUBRIP
        }

    private fun savePosition() {
        val p = player ?: return
        if (p.duration > 0) {
            Progress.save(anilistId, episode, p.currentPosition, p.duration)
        }
    }

    override fun onStart() {
        super.onStart()
        // Routes only exist while something is asking for them, and the cast
        // picker needs them to still be there when a row is tapped.
        GoogleCast.retainRoutes(this)
    }

    override fun onPause() {
        super.onPause()
        savePosition()
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
        savePosition()
        GoogleCast.releaseRoutes(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        savePosition()
        player?.release()
        player = null
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        /** Series title on its own, for resolving other episodes. */
        const val EXTRA_SERIES_TITLE = "series_title"
        /** Total episodes, or 0 when unknown — an ongoing show, say. */
        const val EXTRA_EPISODE_COUNT = "episode_count"
        /** Which entry in the handed-over source list is playing. */
        const val EXTRA_SOURCE_INDEX = "source_index"
        const val EXTRA_ANILIST_ID = "anilist_id"
        const val EXTRA_EPISODE = "episode"
        const val EXTRA_SUB_URLS = "sub_urls"
        const val EXTRA_SUB_LANGS = "sub_langs"
    }
}
