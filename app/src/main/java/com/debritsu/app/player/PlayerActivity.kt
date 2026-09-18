package com.debritsu.app.player

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TimeBar
import com.debritsu.app.R
import com.debritsu.app.cast.CastTarget
import com.debritsu.app.cast.CastTargets
import com.debritsu.app.cast.GoogleCast
import com.debritsu.app.data.AniList
import com.debritsu.app.data.AniSkip
import com.debritsu.app.data.AutoPlay
import com.debritsu.app.data.Debrid
import com.debritsu.app.data.Downloads
import com.debritsu.app.data.Mappings
import com.debritsu.app.data.Progress
import com.debritsu.app.data.Settings
import com.debritsu.app.data.SourceHandoff
import com.debritsu.app.data.StreamMeta
import com.debritsu.app.data.StreamOption
import com.debritsu.app.data.Subtitle
import com.debritsu.app.data.SyncQueue
import com.debritsu.app.data.TitleMatch
import com.debritsu.app.data.minEpisodeSizeMb
import com.debritsu.app.ui.Ink
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.extractor.AssMatroskaExtractor
import io.github.peerless2012.ass.media.kt.withAssSupport
import io.github.peerless2012.ass.media.parser.AssSubtitleParserFactory
import io.github.peerless2012.ass.media.type.AssRenderType
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The player: media3 for the picture and sound, libass for ASS subtitles.
 *
 * media3 alone renders ASS itself and understands only what it was taught —
 * colours from the style table, and `\an`, `\pos` and `\move` on a line —
 * dropping the karaoke, mid-line colour changes, fades and typesetting an
 * anime release actually uses. libass, the renderer VLC uses, draws those here.
 *
 * For a while libVLC did all of it. It was dropped for losing 1-2 seconds of
 * sound after a resume: its own log showed it flushing the audio as "way too
 * late" and then inserting silence as "way too early", on the speaker and over
 * Bluetooth, and nothing done around it held. media3 times audio the way
 * Android means it to be timed. The cost is decoders: only the device's own,
 * so a format the device cannot decode does not play — see CLAUDE.md.
 *
 * The screen is the one libVLC used: the same controls, pickers, skip button
 * and gestures. Only what turns bytes into pictures has changed.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    private var player: ExoPlayer? = null
    private var assHandler: AssHandler? = null

    private lateinit var videoLayout: PlayerView
    private lateinit var controls: View
    private lateinit var playPause: ImageButton
    private lateinit var timeBar: DefaultTimeBar
    private lateinit var positionText: TextView
    private lateinit var durationText: TextView
    private lateinit var buffering: ProgressBar

    private var anilistId = 0
    private var episode = 0
    private var episodeCount = 0
    private var episodeMinutes = 0
    private var seriesTitle = ""
    private var altTitles: List<String> = emptyList()
    private var currentTitle = ""
    private var currentUrl: String? = null
    private var currentSourceIndex = -1
    private var sources: List<StreamOption> = emptyList()
    private var subtitleUrls: List<String> = emptyList()
    private var subtitleLangs: List<String> = emptyList()
    private var subtitleAddons: List<String> = emptyList()

    private var progressPushed = false
    private var resumeAtMs = 0L
    private var scrubbing = false
    /** The addon subtitle files handed to media3 alongside the video. */
    private var subtitleConfigs: List<MediaItem.SubtitleConfiguration> = emptyList()
    private lateinit var hud: TextView
    private val hideHud = Runnable { hud.visibility = View.GONE }
    private var segments: List<AniSkip.Segment> = emptyList()
    private var switchingEpisode = false

    private val hideControls = Runnable { controls.visibility = View.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(PlayerActivity.EXTRA_URL) ?: run { finish(); return }
        currentUrl = url
        currentTitle = intent.getStringExtra(PlayerActivity.EXTRA_TITLE).orEmpty()
        seriesTitle = intent.getStringExtra(PlayerActivity.EXTRA_SERIES_TITLE).orEmpty()
        altTitles = intent.getStringArrayExtra(PlayerActivity.EXTRA_ALT_TITLES).orEmpty().toList()
        anilistId = intent.getIntExtra(PlayerActivity.EXTRA_ANILIST_ID, 0)
        episode = intent.getIntExtra(PlayerActivity.EXTRA_EPISODE, 0)
        episodeCount = intent.getIntExtra(PlayerActivity.EXTRA_EPISODE_COUNT, 0)
        episodeMinutes = intent.getIntExtra(PlayerActivity.EXTRA_EPISODE_MINUTES, 0)
        currentSourceIndex = intent.getIntExtra(PlayerActivity.EXTRA_SOURCE_INDEX, -1)
        // In memory rather than through the Intent, for the same reason as
        // before: a few hundred sources with long URLs overrun the Binder limit.
        sources = SourceHandoff.take()
        subtitleUrls = intent.getStringArrayExtra(PlayerActivity.EXTRA_SUB_URLS).orEmpty().toList()
        subtitleLangs = intent.getStringArrayExtra(PlayerActivity.EXTRA_SUB_LANGS).orEmpty().toList()
        subtitleAddons = intent.getStringArrayExtra(PlayerActivity.EXTRA_SUB_ADDONS).orEmpty().toList()

        setContentView(R.layout.activity_player)
        videoLayout = findViewById(R.id.video_layout)
        controls = findViewById(R.id.controls)
        playPause = findViewById(R.id.play_pause)
        timeBar = findViewById(R.id.time_bar)
        positionText = findViewById(R.id.position)
        durationText = findViewById(R.id.duration)
        buffering = findViewById(R.id.buffering)
        buffering.indeterminateTintList =
            android.content.res.ColorStateList.valueOf(Ink.palette.video.toArgb())

        wireControls()
        start(url)
    }

    // ----- the player itself -----

    private fun start(url: String) {
        val handler = AssHandler(AssRenderType.OVERLAY_OPEN_GL)
        assHandler = handler
        // Every subtitle goes through one factory: libass for ASS, the lenient
        // parser for PGS, media3 for the rest, and each wrapped so a line its
        // parser rejects is dropped rather than taking the episode with it.
        val parsers = LenientPgsParser.Factory(AssSubtitleParserFactory(handler))
        val mediaSources = DefaultMediaSourceFactory(
            DefaultDataSource.Factory(this),
            DefaultExtractorsFactory().withAssMatroska(parsers, handler)
        ).setSubtitleParserFactory(parsers)

        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSources)
            .setRenderersFactory(DefaultRenderersFactory(this).withAssSupport(handler))
            // Behave like a media app towards the rest of the phone: pause for
            // a call or another app's audio, dip under a notification, and
            // pause when headphones or Bluetooth disconnect rather than carrying
            // on out of the speaker.
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        player = exo
        videoLayout.player = exo
        videoLayout.subtitleView?.let { view ->
            applySubtitleStyle(view)
            view.withAssSupport(handler)
        }
        handler.init(exo)

        exo.trackSelectionParameters = exo.trackSelectionParameters
            .buildUpon()
            .setPreferredTextLanguage("en")
            // The file's DEFAULT and FORCED flags do not decide the subtitle
            // track: releases put them on Signs & Songs. chooseSubtitleTrack
            // picks by name once the tracks are known.
            .setIgnoredTextSelectionFlags(C.SELECTION_FLAG_DEFAULT or C.SELECTION_FLAG_FORCED)
            .apply {
                // Left alone, media3 takes the device language, so an English
                // phone would play the dub on any dual-audio release.
                val audio = Settings.preferredAudioLanguage
                if (audio.isNotEmpty()) setPreferredAudioLanguage(audio)
            }
            .build()
        // Debug only: the preview passes this so a short test clip loops long
        // enough to watch its subtitles through.
        if (intent.getBooleanExtra("loop", false)) exo.repeatMode = Player.REPEAT_MODE_ONE
        exo.addListener(listener)

        resumeAtMs = Progress.position(anilistId, episode)
        load(url)
        trackPosition()
        installSkipButton()
        loadSkipSegments()
    }

    /**
     * MKV through libass's extractor, which also hands the fonts an MKV carries
     * as attachments to libass — what typeset signs are drawn in. Built here
     * rather than with the library's own helper so its subtitles go through
     * [parsers] like every other file's.
     */
    private fun ExtractorsFactory.withAssMatroska(
        parsers: LenientPgsParser.Factory,
        handler: AssHandler
    ): ExtractorsFactory = ExtractorsFactory {
        createExtractors().also { extractors ->
            extractors.forEachIndexed { i, extractor ->
                // Replaced in place: the order is how media3 sniffs a format.
                if (extractor is MatroskaExtractor) extractors[i] = AssMatroskaExtractor(parsers, handler)
            }
        }
    }

    private fun load(url: String) {
        val exo = player ?: return
        subtitleConfigs = subtitleTracks()
        subtitlePickedByHand = false
        sourceStarted = false
        // Subtitles stay off until chooseSubtitleTrack has seen the tracks.
        // Otherwise media3 starts on whichever English track comes first —
        // Signs & Songs, often — and it is on screen for a second before the
        // right one replaces it.
        awaitingSubtitleChoice = true
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .build()
        exo.setMediaItem(mediaItem(url), resumeAtMs.coerceAtLeast(0))
        exo.prepare()
        exo.playWhenReady = true
    }

    private fun mediaItem(url: String): MediaItem =
        MediaItem.Builder()
            .setUri(url)
            .setSubtitleConfigurations(subtitleConfigs)
            .build()

    /**
     * The addon subtitle files, each labelled.
     *
     * Without a label the picker shows nothing but a language, so the file's
     * own English and thirty addon English entries look identical. Numbered
     * within an addon and language, so "opensubtitles · 3" is the third English
     * one that addon returned — which is what you are choosing between when the
     * first two are out of sync.
     */
    private fun subtitleTracks(): List<MediaItem.SubtitleConfiguration> {
        val seen = mutableMapOf<String, Int>()
        return subtitleUrls.mapIndexed { i, url ->
            val code = subtitleLangs.getOrNull(i)?.ifBlank { null } ?: "und"
            val source = subtitleAddons.getOrNull(i)?.ifBlank { null } ?: "stream"
            val key = "$source|$code"
            val n = (seen[key] ?: 0) + 1
            seen[key] = n
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(url))
                .setMimeType(mimeFor(url))
                .setLanguage(code)
                // Also how a side-loaded track is told from the file's own.
                .setLabel("$ADDON_MARKER $source · $n")
                .setSelectionFlags(0)
                .build()
        }
    }

    private fun mimeFor(url: String) =
        when (url.substringBefore('?').substringAfterLast('.', "").lowercase()) {
            "vtt" -> MimeTypes.TEXT_VTT
            "ass", "ssa" -> MimeTypes.TEXT_SSA
            "ttml", "xml" -> MimeTypes.APPLICATION_TTML
            else -> MimeTypes.APPLICATION_SUBRIP
        }

    /**
     * How plain subtitles look — SRT, WebVTT, an addon's file. ASS carries its
     * own styling and is drawn by libass, which these settings do not touch.
     */
    private fun applySubtitleStyle(view: androidx.media3.ui.SubtitleView) {
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
        val edge =
            if (Settings.subtitleOutline) CaptionStyleCompat.EDGE_TYPE_OUTLINE
            else CaptionStyleCompat.EDGE_TYPE_NONE
        view.setApplyEmbeddedStyles(false)
        view.setStyle(CaptionStyleCompat(foreground, background, Color.TRANSPARENT, edge, Color.BLACK, null))
        view.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, Settings.subtitleSizeSp)
        view.setBottomPaddingFraction(0.08f)
    }

    /** Set once somebody picks a track by hand; automatic choice stops then. */
    private var subtitlePickedByHand = false

    /**
     * Picks the dialogue track, not the one the file marks as default.
     *
     * Releases routinely ship two English tracks and flag "Signs & Songs" as
     * DEFAULT and FORCED — it only translates on-screen text and lyrics, so it
     * reads as subtitles that keep going missing. Runs as tracks arrive, and
     * leaves a hand-picked track alone. Only moves when it finds an English
     * track worth choosing; otherwise media3's own choice stands.
     */
    private fun chooseSubtitleTrack(tracks: Tracks) {
        if (subtitlePickedByHand || !awaitingSubtitleChoice) return
        val exo = player ?: return
        // Nothing known about the item yet: wait for the tracks to arrive.
        if (tracks.groups.isEmpty()) return
        awaitingSubtitleChoice = false
        val builder = exo.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        val candidates = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
            .flatMap { group -> (0 until group.length).map { group to it } }
        candidates.maxByOrNull { (g, i) -> subtitleScore(g.getTrackFormat(i)) }
            ?.takeIf { (g, i) -> subtitleScore(g.getTrackFormat(i)) >= ENGLISH_SCORE }
            ?.let { (g, i) -> builder.setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i)) }
        exo.trackSelectionParameters = builder.build()
    }

    /** Set by [load] while subtitles are held off for [chooseSubtitleTrack]. */
    private var awaitingSubtitleChoice = false

    private fun subtitleScore(format: Format): Int {
        val label = format.label.orEmpty()
        val name = label.lowercase()
        val fromAddon = label.startsWith(ADDON_MARKER)
        var score = 0
        val english = format.language?.lowercase()?.let { it.startsWith("en") } == true ||
            ENGLISH_TRACK.containsMatchIn(name)
        if (english) score += ENGLISH_SCORE
        // "Full Subtitles + Songs" is the dialogue track, whatever else it says.
        if (FULL_TRACK.containsMatchIn(name)) score += 10
        else if (PARTIAL_TRACK.containsMatchIn(name)) score -= 100
        // The file's own track is timed to this release; an addon's may be
        // timed to another one.
        if (!fromAddon) score += 5
        return score
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            buffering.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
            when (state) {
                Player.STATE_READY -> {
                    sourceStarted = true
                    endRecovery()
                }
                Player.STATE_ENDED -> finish()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // Only hold the screen awake while video is actually running, so a
            // paused player doesn't drain the battery.
            videoLayout.keepScreenOn = isPlaying
            playPause.setImageResource(
                if (isPlaying) androidx.media3.ui.R.drawable.exo_icon_pause
                else androidx.media3.ui.R.drawable.exo_icon_play
            )
        }

        override fun onTracksChanged(tracks: Tracks) = chooseSubtitleTrack(tracks)

        /**
         * A dead link would otherwise be a black screen that never resolves.
         * A subtitle that will not load and a connection that dropped are dealt
         * with first; neither is the source's fault, and the list cannot fix
         * them. Otherwise the source list is the useful answer: addons hand
         * back links that no longer play, and another source usually just works.
         */
        override fun onPlayerError(error: PlaybackException) {
            if (dropFailedSubtitle(error)) return
            if (recover(error)) return
            endRecovery()
            toast("Couldn't play this source")
            if (sources.size > 1) showSourcePicker() else finish()
        }
    }

    /** Drives the seek bar and the clock, and pushes progress once past 85%. */
    private fun trackPosition() {
        lifecycleScope.launch {
            while (true) {
                val mp = player
                if (mp != null && !scrubbing) {
                    val length = mp.duration.takeIf { it != C.TIME_UNSET } ?: 0L
                    val time = mp.currentPosition
                    if (length > 0) {
                        timeBar.setDuration(length)
                        timeBar.setPosition(time)
                        positionText.text = clock(time)
                        durationText.text = clock(length)
                        maybePushProgress(time, length)
                    }
                }
                delay(500)
            }
        }
    }

    /**
     * Progress goes at 85%, and only when the file is long enough to be the
     * episode — a creditless opening is ninety seconds, and 85% of that would
     * mark an episode watched that nobody watched.
     */
    private fun maybePushProgress(time: Long, length: Long) {
        if (progressPushed || anilistId <= 0 || episode <= 0) return
        val floorMs = if (episodeMinutes > 0) episodeMinutes * 60_000L / 2 else 4 * 60_000L
        if (length < floorMs || time <= length * 0.85) return
        progressPushed = true
        Progress.clear(anilistId, episode)
        lifecycleScope.launch {
            val sent = runCatching { AniList.setProgress(anilistId, episode) }.isSuccess
            if (!sent) SyncQueue.queue(anilistId, episode)
        }
    }

    // ----- controls -----

    private fun wireControls() {
        installGestures()
        playPause.setOnClickListener {
            val mp = player ?: return@setOnClickListener
            if (mp.isPlaying) mp.pause() else mp.play()
            showControls()
        }
        findViewById<View>(R.id.rewind).setOnClickListener { seekBy(-SEEK_STEP_MS) }
        findViewById<View>(R.id.forward).setOnClickListener { seekBy(SEEK_STEP_MS) }
        findViewById<View>(R.id.subtitle_button).setOnClickListener { showSubtitlePicker() }
        findViewById<View>(R.id.audio_button).setOnClickListener { showAudioPicker() }
        findViewById<View>(R.id.cast_button).setOnClickListener { showCastPicker() }
        findViewById<View>(R.id.sources_button).apply {
            visibility = if (sources.size > 1) View.VISIBLE else View.GONE
            setOnClickListener { showSourcePicker() }
        }
        findViewById<View>(R.id.prev_episode).setOnClickListener { goToEpisode(episode - 1) }
        findViewById<View>(R.id.next_episode).setOnClickListener { goToEpisode(episode + 1) }
        updateEpisodeButtons()

        timeBar.addListener(object : TimeBar.OnScrubListener {
            override fun onScrubStart(timeBar: TimeBar, position: Long) {
                scrubbing = true
            }

            override fun onScrubMove(timeBar: TimeBar, position: Long) {
                positionText.text = clock(position)
            }

            override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) {
                scrubbing = false
                if (!canceled) player?.seekTo(position)
                showControls()
            }
        })
        showControls()
    }

    private fun seekBy(deltaMs: Long, reveal: Boolean = true) {
        val mp = player ?: return
        val length = mp.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
        mp.seekTo((mp.currentPosition + deltaMs).coerceIn(0, length))
        if (reveal) showControls()
    }

    private fun toggleControls() {
        if (controls.visibility == View.VISIBLE) {
            controls.removeCallbacks(hideControls)
            controls.visibility = View.GONE
        } else {
            showControls()
        }
    }

    private fun showControls() {
        controls.visibility = View.VISIBLE
        controls.removeCallbacks(hideControls)
        controls.postDelayed(hideControls, 4_000)
    }

    // ----- track pickers -----

    /** Every track of one kind, as (group, index within it) pairs. */
    private fun tracksOf(type: Int): List<Pair<Tracks.Group, Int>> =
        player?.currentTracks?.groups.orEmpty()
            .filter { it.type == type }
            .flatMap { group -> (0 until group.length).map { group to it } }

    private fun showSubtitlePicker() {
        val mp = player ?: return
        val tracks = tracksOf(C.TRACK_TYPE_TEXT)
        val off = C.TRACK_TYPE_TEXT in mp.trackSelectionParameters.disabledTrackTypes ||
            tracks.none { (g, i) -> g.isTrackSelected(i) }
        val rows = mutableListOf(PanelRow("Off", "No subtitles", if (off) "SELECTED" else null))
        tracks.forEach { (g, i) ->
            val (title, detail) = subtitleName(g.getTrackFormat(i))
            rows += PanelRow(title, detail, if (!off && g.isTrackSelected(i)) "SELECTED" else null)
        }
        val fromAddons = tracks.count { (g, i) -> g.getTrackFormat(i).label.orEmpty().startsWith(ADDON_MARKER) }
        panelDialog(
            "Subtitles",
            "${tracks.size - fromAddons} IN THIS FILE · $fromAddons FROM ADDONS",
            rows
        ) { index ->
            subtitlePickedByHand = true
            val builder = mp.trackSelectionParameters.buildUpon()
            if (index == 0) {
                builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            } else {
                val (g, i) = tracks[index - 1]
                builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
            }
            mp.trackSelectionParameters = builder.build()
        }.show()
    }

    /**
     * What a subtitle row says: the file's own tracks by their title, an
     * addon's by its language and the addon it came from.
     */
    private fun subtitleName(format: Format): Pair<String, String> {
        val label = format.label.orEmpty()
        val lang = displayLanguage(format.language)
        if (label.startsWith(ADDON_MARKER)) {
            return lang to "From ${label.removePrefix(ADDON_MARKER).trim()}"
        }
        return label.ifEmpty { lang } to "In this file · $lang"
    }

    private fun displayLanguage(code: String?): String =
        code?.takeIf { it.isNotBlank() && it != "und" }
            ?.let { Locale.forLanguageTag(it).displayLanguage.ifBlank { it } }
            ?: "Unknown language"

    private fun showAudioPicker() {
        val mp = player ?: return
        val tracks = tracksOf(C.TRACK_TYPE_AUDIO)
        if (tracks.size < 2) {
            toast("This release has one audio track")
            return
        }
        val rows = tracks.map { (g, i) ->
            val format = g.getTrackFormat(i)
            val lang = displayLanguage(format.language)
            PanelRow(
                format.label?.takeIf { it.isNotBlank() } ?: lang,
                "In this file · $lang",
                if (g.isTrackSelected(i)) "SELECTED" else null
            )
        }
        panelDialog("Audio", "${tracks.size} TRACKS", rows) { index ->
            val (g, i) = tracks[index]
            mp.trackSelectionParameters = mp.trackSelectionParameters.buildUpon()
                .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
                .build()
        }.show()
    }

    /**
     * Swap source without losing your place, as the old player did: whatever is
     * playing goes to the top and says so, then the ones that meet the filters,
     * best first.
     */
    private fun showSourcePicker() {
        if (sources.isEmpty()) return
        val filter = Settings.sourceFilter
        val minSize = minEpisodeSizeMb(episodeMinutes)
        val titleWords = TitleMatch.known(listOf(seriesTitle) + altTitles)
        val ordered = sources.indices.sortedWith(
            compareByDescending<Int> { it == currentSourceIndex }
                .thenByDescending { filter.accepts(sources[it], StreamMeta.of(sources[it]), minSize, titleWords) }
                .thenByDescending { filter.score(sources[it], StreamMeta.of(sources[it])) }
        )
        val rows = ordered.map { i ->
            val s = sources[i]
            PanelRow(
                s.name,
                s.description.replace("\n", " ").take(110),
                // What is playing still says where its link comes from: a
                // direct link is the addon's own choice of file, and a wrong
                // episode in one is nothing this app picked.
                when {
                    i == currentSourceIndex && s.isDirect -> "PLAYING · DIRECT"
                    i == currentSourceIndex -> "PLAYING · DEBRID"
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
        val mp = player ?: return
        val resumeAt = mp.currentPosition
        mp.pause()
        lifecycleScope.launch {
            runCatching { Debrid.resolve(stream) }
                .onSuccess { url ->
                    currentUrl = url
                    currentSourceIndex = index
                    resumeAtMs = resumeAt
                    load(url)
                }
                .onFailure {
                    toast(it.message ?: "Could not switch source")
                    mp.play()
                }
        }
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
    }

    private fun clock(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.UK, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.UK, "%02d:%02d", m, s)
    }

    // ----- riding out a lost connection -----

    /** Whether the current source has played at all. Only then is a failure worth retrying. */
    private var sourceStarted = false
    private var reconnects = 0
    private var refreshedLink = false
    private var reconnectJob: Job? = null

    /**
     * A subtitle file that will not download is dropped, and the episode
     * carries on from where it was.
     *
     * media3 treats a failure of any side-loaded subtitle as a failure of the
     * whole item: measured on the emulator, one addon subtitle answering 404
     * left the episode black at 00:00 with "Couldn't play this source", and
     * every other source failed the same way, since each carries the same
     * subtitle list. The exception names the address, so the subtitle can be
     * told from the video.
     */
    private fun dropFailedSubtitle(error: PlaybackException): Boolean {
        val failed = generateSequence<Throwable>(error) { it.cause }
            .filterIsInstance<HttpDataSource.HttpDataSourceException>()
            .firstOrNull()?.dataSpec?.uri?.toString() ?: return false
        val index = subtitleUrls.indexOf(failed).takeIf { it >= 0 } ?: return false
        val url = currentUrl ?: return false
        subtitleUrls = subtitleUrls.filterIndexed { i, _ -> i != index }
        subtitleLangs = subtitleLangs.filterIndexed { i, _ -> i != index }
        subtitleAddons = subtitleAddons.filterIndexed { i, _ -> i != index }
        reload(url)
        return true
    }

    /**
     * Rides out a lost connection or an expired link on a source that was
     * playing, rather than giving up on it at once.
     *
     * Measured on the emulator: with the connection gone for seventeen seconds
     * media3 reported an error and stayed stopped when the network came back.
     * Now a network failure is retried from the same point for about half a
     * minute, saying so; a link the server refuses is fetched again once, since
     * debrid links expire and a fresh one for the same file usually just plays.
     * A source that never played is dead, and the list is the answer at once.
     */
    private fun recover(error: PlaybackException): Boolean {
        val url = currentUrl ?: return false
        if (!sourceStarted || !url.startsWith("http")) return false
        val status = generateSequence<Throwable>(error) { it.cause }
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
            .firstOrNull()?.responseCode
        return when {
            status in LINK_REFUSED -> refreshLink()
            error.errorCode in NETWORK_ERRORS || (status != null && status >= 500) -> reconnect()
            else -> false
        }
    }

    private fun reconnect(): Boolean {
        val wait = RECONNECT_DELAYS_MS.getOrNull(reconnects) ?: return false
        reconnects++
        recoveryStatus("Reconnecting…")
        reconnectJob?.cancel()
        reconnectJob = lifecycleScope.launch {
            delay(wait)
            // Prepared again, an item picks up from its current position.
            player?.let { it.prepare(); it.playWhenReady = true }
        }
        return true
    }

    private fun refreshLink(): Boolean {
        if (refreshedLink) return false
        val source = sources.getOrNull(currentSourceIndex) ?: return false
        refreshedLink = true
        recoveryStatus("Refreshing the link…")
        reconnectJob?.cancel()
        reconnectJob = lifecycleScope.launch {
            val fresh = runCatching { Debrid.resolve(source) }.getOrNull()
            if (fresh == null) {
                endRecovery()
                toast("Couldn't play this source")
                showSourcePicker()
                return@launch
            }
            currentUrl = fresh
            reload(fresh)
        }
        return true
    }

    /** The same item again from where it was, with whatever subtitles remain. */
    private fun reload(url: String) {
        resumeAtMs = player?.currentPosition ?: resumeAtMs
        val keepPickedByHand = subtitlePickedByHand
        load(url)
        subtitlePickedByHand = keepPickedByHand
    }

    private fun endRecovery() {
        reconnectJob?.cancel()
        // Only clear the readout if recovery put something there, or a volume
        // or brightness readout would be cut short by every return to ready.
        if (reconnects > 0 || refreshedLink) recoveryStatus(null)
        reconnects = 0
        refreshedLink = false
    }

    /** The centred readout, held on screen until cleared rather than fading. */
    private fun recoveryStatus(text: String?) {
        hud.removeCallbacks(hideHud)
        if (text == null) {
            hud.visibility = View.GONE
        } else {
            hud.text = text
            hud.visibility = View.VISIBLE
        }
    }

    // ----- gestures -----

    private fun readout(text: String) {
        val v = hud
        v.text = text
        v.visibility = View.VISIBLE
        v.removeCallbacks(hideHud)
        v.postDelayed(hideHud, 700)
    }

    /**
     * A tap shows or hides the controls; a double tap on the left or right
     * third skips back or forward; a vertical drag on the left half sets the
     * brightness and on the right half the volume.
     *
     * One detector for all of it, because a tap and a double tap have to be
     * told apart before either acts — a tap listener beside a gesture detector
     * fires on the first half of every double tap.
     */
    private fun installGestures() {
        val root = findViewById<View>(R.id.player_root)
        hud = findViewById<TextView>(R.id.gesture_hud).apply {
            // Dark in every theme: it is read over the picture, not the page.
            background = GradientDrawable().apply {
                setColor(0xE616152B.toInt())
                cornerRadius = 18 * resources.displayMetrics.density
            }
            typeface = ResourcesCompat.getFont(this@PlayerActivity, R.font.mplus_rounded_bold)
        }

        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        val maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        var dragging = false
        var startVolume = 0
        var startBrightness = 0f

        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                dragging = false
                startVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                // -1 means "follow the system", which is the state on first touch.
                startBrightness = window.attributes.screenBrightness
                    .takeIf { it >= 0f } ?: systemBrightness()
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                toggleControls()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                when {
                    e.x < root.width / 3f -> {
                        seekBy(-SEEK_STEP_MS, reveal = false)
                        readout("−${SEEK_STEP_MS / 1000}s")
                    }
                    e.x > root.width * 2f / 3f -> {
                        seekBy(SEEK_STEP_MS, reveal = false)
                        readout("+${SEEK_STEP_MS / 1000}s")
                    }
                    // The middle is left alone so a double tap there still just
                    // toggles the controls.
                    else -> toggleControls()
                }
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
                val fraction = (start.y - e2.y) / (root.height * 0.7f)
                if (start.x < root.width / 2f) {
                    val level = (startBrightness + fraction).coerceIn(0.01f, 1f)
                    window.attributes = window.attributes.apply { screenBrightness = level }
                    readout("Brightness  ${(level * 100).roundToInt()}%")
                } else {
                    val level = (startVolume + fraction * maxVolume).roundToInt().coerceIn(0, maxVolume)
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0)
                    readout("Volume  ${(level * 100f / maxVolume).roundToInt()}%")
                }
                return true
            }
        })

        root.setOnTouchListener { view, event ->
            detector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP) view.performClick()
            true
        }
    }

    /** The system brightness, as a starting point for the first drag. */
    private fun systemBrightness(): Float = runCatching {
        android.provider.Settings.System.getInt(
            contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS
        ) / 255f
    }.getOrDefault(0.5f)

    // ----- skipping openings and endings -----

    /**
     * Shows a skip button whenever playback is inside a known opening or
     * ending, and seeks past it when tapped. Polled, because nothing announces
     * that an opening has simply arrived.
     */
    private fun installSkipButton() {
        val button = findViewById<TextView>(R.id.skip_segment)
        button.background = GradientDrawable().apply {
            setColor(Ink.palette.action.copy(alpha = 0.95f).toArgb())
            cornerRadius = 26 * resources.displayMetrics.density
        }
        button.typeface = ResourcesCompat.getFont(this, R.font.mplus_rounded_extrabold)

        lifecycleScope.launch {
            while (true) {
                val mp = player
                val time = mp?.currentPosition ?: -1L
                val active = segments.firstOrNull { time in it.startMs..it.endMs }
                if (active == null) {
                    button.visibility = View.GONE
                } else {
                    button.text = active.label
                    button.visibility = View.VISIBLE
                    button.setOnClickListener {
                        player?.seekTo(active.endMs)
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
            // The service fits its timings to this particular encode by its
            // length, so wait a moment for the player to know it.
            var waited = 0
            while (waited < 5000 && (player?.duration ?: C.TIME_UNSET).let { it == C.TIME_UNSET || it <= 0L }) {
                delay(250)
                waited += 250
            }
            val mal = runCatching {
                Mappings.forAniList(anilistId, seriesTitle.ifEmpty { null }).mal?.toIntOrNull()
            }.getOrNull()
            val found = AniSkip.segments(mal, forEpisode, (player?.duration ?: 0L).coerceAtLeast(0L))
            // A quick jump to the next episode can land mid-lookup; timings from
            // the episode just left would be worse than none.
            if (episode == forEpisode) segments = found
        }
    }

    // ----- stepping between episodes -----

    /**
     * Stepping needs an AniList id and a title to resolve against, so it is
     * hidden for one-off links. An unknown episode count means an ongoing show:
     * forward is allowed and the lookup fails honestly if there is nothing.
     */
    private fun updateEpisodeButtons() {
        val navigable = anilistId > 0 && episode > 0 && seriesTitle.isNotEmpty()
        findViewById<View>(R.id.prev_episode).visibility =
            if (navigable && episode > 1) View.VISIBLE else View.GONE
        findViewById<View>(R.id.next_episode).visibility =
            if (navigable && (episodeCount <= 0 || episode < episodeCount)) View.VISIBLE else View.GONE
    }

    /**
     * Resolves an adjacent episode and swaps it in without leaving the player,
     * by the same rules as pressing play on the detail screen: a downloaded copy
     * first, then automatic selection within the filters, then the choice.
     */
    private fun goToEpisode(target: Int) {
        if (switchingEpisode) return
        if (target < 1 || (episodeCount > 0 && target > episodeCount)) return
        val mp = player ?: return

        switchingEpisode = true
        savePosition()
        mp.pause()

        val offline = Downloads.get(anilistId, target)?.takeIf { Downloads.isComplete(it) }
        if (offline != null) {
            startEpisode(target, Uri.fromFile(Downloads.fileFor(offline)).toString(), emptyList(), emptyList(), -1)
            switchingEpisode = false
            return
        }

        val loading = panelDialog("Episode $target", "FINDING SOURCES", emptyList()) {}
        loading.show()

        lifecycleScope.launch {
            var handedOff = false
            try {
                val outcome = AutoPlay.run(
                    anilistId = anilistId,
                    title = seriesTitle,
                    altTitles = altTitles,
                    episode = target,
                    isMovie = episodeCount == 1,
                    filter = Settings.sourceFilter,
                    // Half the real running time is the floor below which a file
                    // is taken for a creditless opening, not the episode.
                    episodeMinutes = episodeMinutes,
                    autoSelect = Settings.autoPlay
                ) { step -> setPanelSubheading(loading, stepLabel(step)) }

                val found = outcome.results.flatMap { it.streams }
                val url = outcome.url
                if (url != null) {
                    startEpisode(target, url, found, outcome.subtitles, found.indexOf(outcome.chosen))
                    handedOff = true
                    return@launch
                }
                if (found.isEmpty()) {
                    toast(outcome.message ?: "No sources found for episode $target.")
                    return@launch
                }

                val rows = found.map { s ->
                    PanelRow(s.name, s.description.replace("\n", " ").take(110), if (s.isDirect) "DIRECT" else "DEBRID")
                }
                val picker = panelDialog("Episode $target", "${found.size} AVAILABLE", rows) { index ->
                    val chosen = found[index]
                    lifecycleScope.launch {
                        toast("Resolving link…")
                        val resolved = runCatching { Debrid.resolve(chosen) }.getOrNull()
                        if (resolved == null) {
                            toast("Couldn't resolve that source.")
                            player?.play()
                        } else {
                            startEpisode(
                                target, resolved, found,
                                (chosen.subtitles + outcome.subtitles).distinctBy { it.url }, index
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
        episode = target
        currentUrl = url
        currentSourceIndex = sourceIndex
        currentTitle = if (seriesTitle.isNotEmpty()) "$seriesTitle — EP $target" else "EP $target"
        sources = newSources
        progressPushed = false
        subtitleUrls = subs.map { it.url }
        subtitleLangs = subs.map { it.lang }
        subtitleAddons = subs.map { it.addon.orEmpty() }
        findViewById<View>(R.id.sources_button).visibility =
            if (sources.size > 1) View.VISIBLE else View.GONE
        updateEpisodeButtons()
        resumeAtMs = Progress.position(anilistId, target)
        load(url)
        loadSkipSegments()
    }

    private fun stepLabel(step: AutoPlay.Step): String = when (step) {
        AutoPlay.Step.Locating -> "FINDING THIS EPISODE"
        AutoPlay.Step.Searching -> "SEARCHING YOUR ADDONS"
        is AutoPlay.Step.Filtering -> "${step.kept} OF ${step.found} MATCH"
        is AutoPlay.Step.Resolving -> "CHECKING SOURCE ${step.attempt} OF ${step.of}"
        AutoPlay.Step.Ready -> "STARTING PLAYBACK"
    }

    // ----- casting -----

    /**
     * Send the stream to a television or another app. A downloaded file can
     * only be opened by an app on this phone, so it skips the network scan.
     */
    private fun showCastPicker() {
        val url = currentUrl ?: return
        val isLocal = !url.startsWith("http")
        // Set when the stream is handed to another app before the scan ends, so
        // the device list does not then appear over whatever just opened.
        var handedOffEarly = false

        val loading = if (isLocal) null else panelDialog(
            "Finding devices",
            "SEARCHING YOUR NETWORK",
            listOf(PanelRow(CastTarget.External.label, CastTarget.External.detail))
        ) {
            handedOffEarly = true
            lifecycleScope.launch {
                CastTargets.send(this@PlayerActivity, CastTarget.External, url, currentTitle, player?.currentPosition ?: 0L)
                    ?.let { toast(it) }
            }
        }
        loading?.show()

        lifecycleScope.launch {
            val targets = try {
                runCatching { CastTargets.discover(this@PlayerActivity, isLocal) }
                    .getOrDefault(listOf(CastTarget.External))
            } finally {
                runCatching { loading?.dismiss() }
            }
            if (handedOffEarly) return@launch

            val devices = targets.count { it !is CastTarget.External }
            val sub = when {
                isLocal -> "DOWNLOADED EPISODE"
                devices == 0 -> "NO TVS FOUND ON THIS NETWORK"
                devices == 1 -> "1 DEVICE FOUND"
                else -> "$devices DEVICES FOUND"
            }
            val rows = targets.map { PanelRow(it.label, it.detail) }
            panelDialog(if (isLocal) "Open with" else "Cast to", sub, rows) { index ->
                val target = targets[index]
                val position = player?.currentPosition ?: 0L
                lifecycleScope.launch {
                    if (target is CastTarget.Cast) toast("Connecting to ${target.label}…")
                    val error = CastTargets.send(this@PlayerActivity, target, url, currentTitle, position)
                    if (error != null) toast(error) else {
                        player?.pause()
                        if (target !is CastTarget.External) toast("Playing on ${target.label}")
                    }
                }
            }.show()
        }
    }

    // ----- lifecycle -----

    private fun savePosition() {
        val mp = player ?: return
        val length = mp.duration
        if (length != C.TIME_UNSET && length > 0) {
            Progress.save(anilistId, episode, mp.currentPosition, length)
        }
    }

    override fun onStart() {
        super.onStart()
        // Cast routes only exist while something asks for them, and the picker
        // needs them still there when a row is tapped.
        GoogleCast.retainRoutes(this)
    }

    override fun onStop() {
        super.onStop()
        GoogleCast.releaseRoutes(this)
    }

    override fun onPause() {
        super.onPause()
        savePosition()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        savePosition()
        reconnectJob?.cancel()
        videoLayout.player = null
        player?.release()
        player = null
        assHandler?.release()
        assHandler = null
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        /** Series title on its own, for resolving other episodes. */
        const val EXTRA_SERIES_TITLE = "series_title"
        /** Every name the show goes by, so a side series is not taken for it. */
        const val EXTRA_ALT_TITLES = "alt_titles"
        /** Total episodes, or 0 when unknown — an ongoing show, say. */
        const val EXTRA_EPISODE_COUNT = "episode_count"
        /** Which entry in the handed-over source list is playing. */
        const val EXTRA_SOURCE_INDEX = "source_index"
        const val EXTRA_ANILIST_ID = "anilist_id"
        const val EXTRA_EPISODE = "episode"
        /** AniList's minutes per episode, for judging whether this is one. */
        const val EXTRA_EPISODE_MINUTES = "episode_minutes"
        const val EXTRA_SUB_URLS = "sub_urls"
        const val EXTRA_SUB_LANGS = "sub_langs"
        /** Which addon each subtitle came from, parallel to the arrays above. */
        const val EXTRA_SUB_ADDONS = "sub_addons"

        // How far the rewind and forward buttons, and a double tap, move.
        private const val SEEK_STEP_MS = 10_000L

        // How subtitle tracks are told apart by the title a release gives them,
        // e.g. "Signs & Songs" against "Full Subtitles".
        private val ENGLISH_TRACK = Regex("""\benglish\b|\[eng?\]|\beng\b""")
        private val PARTIAL_TRACK = Regex("""sign|song|forced|\bs&s\b|commentary""")
        private val FULL_TRACK = Regex("""full|dialogue""")
        private const val ENGLISH_SCORE = 20

        // Waits before each attempt to reconnect: about half a minute in all.
        private val RECONNECT_DELAYS_MS = longArrayOf(2_000, 4_000, 8_000, 8_000, 8_000)

        /** Failures that mean the connection went, not that the source is bad. */
        private val NETWORK_ERRORS = setOf(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_TIMEOUT
        )

        /** Answers that mean the link itself is no longer good — expired, typically. */
        private val LINK_REFUSED = setOf(401, 403, 404, 410)

        /**
         * Starts the label of a subtitle from an addon — "From opensubtitles · 2"
         * — which is also how one is told from the file's own tracks.
         */
        private const val ADDON_MARKER = "From"
    }
}
