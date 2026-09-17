package com.debritsu.app.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
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
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.DefaultTimeBar
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
import com.debritsu.app.data.minEpisodeSizeMb
import com.debritsu.app.ui.Ink
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * The player, on libVLC.
 *
 * media3 played most things well enough, but it renders ASS subtitles itself
 * and only understands the parts of them it was taught: colours from the style
 * table, and `\an`, `\pos` and `\move` on a line. Everything an anime release
 * actually uses — karaoke timing on an opening, colour changes mid-line, fades,
 * typeset signs — it drops. libVLC hands those to libass, the renderer VLC
 * itself uses, so what appears here is what appears in VLC.
 *
 * It also carries its own decoders, which is the other half of the bargain:
 * DTS and TrueHD audio, and video the device has no decoder for, play rather
 * than arriving as silence or a black picture.
 *
 * The screen is the same one as before: the same controls, the same Sources and
 * subtitle pickers, the same skip button and gestures. Only what turns bytes
 * into pictures has changed.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    private var libVlc: LibVLC? = null
    private var player: MediaPlayer? = null

    private lateinit var videoLayout: VLCVideoLayout
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
    private var currentTitle = ""
    private var currentUrl: String? = null
    private var currentSourceIndex = -1
    private var sources: List<StreamOption> = emptyList()
    private var subtitleUrls: List<String> = emptyList()
    private var subtitleLangs: List<String> = emptyList()
    private var subtitleAddons: List<String> = emptyList()

    private var progressPushed = false
    private var resumeAtMs = 0L
    private var resumed = false
    private var scrubbing = false
    private var pausedByFocus = false
    private var audioFocus: AudioFocusRequest? = null
    private var audioManager: AudioManager? = null
    private var noisyReceiver: BroadcastReceiver? = null
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
        takeAudioFocus()
    }

    // ----- the player itself -----

    private fun start(url: String) {
        val vlc = LibVLC(this, vlcOptions())
        libVlc = vlc
        val mp = MediaPlayer(vlc)
        player = mp
        attachVideo(mp)
        mp.setEventListener { event -> onPlayerEvent(event) }

        resumeAtMs = Progress.position(anilistId, episode)
        load(url)
        trackPosition()
        installSkipButton()
        loadSkipSegments()
    }

    /**
     * Options for the library itself. Subtitle appearance is set here rather
     * than per file: these are VLC's own text-rendering options, and they apply
     * to plain subtitles. ASS files carry their own styling and are left alone,
     * which is the point of being on libVLC at all.
     */
    private fun vlcOptions(): ArrayList<String> {
        val options = arrayListOf(
            "--no-video-title-show",
            // Enough buffer for a debrid link over a home connection without
            // adding a wait at the start.
            "--network-caching=3000",
            "--freetype-rel-fontsize=${fontSizeOption()}",
            // libass finds no font provider on Android and would otherwise draw
            // nothing; the system font is what every other app uses anyway.
            "--freetype-font=/system/fonts/Roboto-Regular.ttf"
        )
        if (Settings.subtitleOutline) options += "--freetype-outline-thickness=4"
        else options += "--freetype-outline-thickness=0"
        options += when (Settings.subtitleBackground) {
            2 -> "--freetype-background-opacity=255"
            1 -> "--freetype-background-opacity=128"
            else -> "--freetype-background-opacity=0"
        }
        options += when (Settings.subtitleColour) {
            1 -> "--freetype-color=16776960" // pale yellow
            2 -> "--freetype-color=65535"    // cyan
            else -> "--freetype-color=16777215"
        }
        val audio = Settings.preferredAudioLanguage
        if (audio.isNotEmpty()) options += "--audio-language=$audio"
        options += "--sub-language=en"
        return options
    }

    /** VLC counts font size as a fraction of video height; smaller number, larger text. */
    private fun fontSizeOption(): Int = when {
        Settings.subtitleSizeSp >= 26f -> 12
        Settings.subtitleSizeSp >= 22f -> 14
        Settings.subtitleSizeSp >= 18f -> 16
        else -> 20
    }

    private fun load(url: String) {
        val vlc = libVlc ?: return
        val mp = player ?: return
        val media = Media(vlc, Uri.parse(url))
        media.setHWDecoderEnabled(true, false)
        // Debug only: the preview passes this so a ten-second test clip loops
        // long enough to watch its subtitles through.
        if (intent.getBooleanExtra("loop", false)) media.addOption(":input-repeat=65535")
        mp.media = media
        media.release()
        resumed = false
        subtitlePickedByHand = false
        mp.play()
        // Subtitle files from addons are handed over after play starts, which is
        // when libVLC accepts them. A slave that will not download is simply a
        // subtitle that never appears — it cannot take the episode with it.
        subtitleUrls.forEach { sub ->
            runCatching { mp.addSlave(IMedia.Slave.Type.Subtitle, Uri.parse(sub), false) }
        }
    }

    /** Set once somebody picks a track by hand; automatic choice stops then. */
    private var subtitlePickedByHand = false

    /**
     * Picks the dialogue track, not the one the file marks as default.
     *
     * Releases routinely ship two English tracks and flag "Signs & Songs" as
     * DEFAULT and FORCED — it only translates on-screen text and lyrics, so it
     * reads as subtitles that keep going missing. libVLC honours that flag.
     * Runs again as tracks arrive (addon files land after playback starts), and
     * leaves a hand-picked track alone. Only moves when it finds a track worth
     * choosing; otherwise libVLC's own choice stands.
     */
    private fun chooseSubtitleTrack() {
        if (subtitlePickedByHand) return
        val mp = player ?: return
        val tracks = mp.spuTracks?.filter { it.id != -1 }.orEmpty()
        val best = tracks.maxByOrNull { subtitleScore(it) } ?: return
        // Only an English track is worth overriding libVLC for; an untitled
        // track in some other language is not an improvement on its choice.
        if (subtitleScore(best) < ENGLISH_SCORE || mp.spuTrack == best.id) return
        mp.spuTrack = best.id
    }

    private fun subtitleScore(track: MediaPlayer.TrackDescription): Int {
        val name = track.name.orEmpty().lowercase()
        val addonIndex = subtitleUrls.indexOfFirst { track.name.orEmpty().contains(it) }
        var score = 0
        val english = if (addonIndex >= 0) {
            subtitleLangs.getOrNull(addonIndex).orEmpty().lowercase().let { it.startsWith("en") }
        } else {
            ENGLISH_TRACK.containsMatchIn(name)
        }
        if (english) score += ENGLISH_SCORE
        // "Full Subtitles + Songs" is the dialogue track, whatever else it says.
        if (FULL_TRACK.containsMatchIn(name)) score += 10
        else if (PARTIAL_TRACK.containsMatchIn(name)) score -= 100
        // The file's own track is timed to this release; an addon's may be
        // timed to another one.
        if (addonIndex < 0) score += 5
        return score
    }

    private fun onPlayerEvent(event: MediaPlayer.Event) {
        when (event.type) {
            MediaPlayer.Event.Playing -> {
                buffering.visibility = View.GONE
                if (reconnects > 0) {
                    reconnects = 0
                    recoveryStatus(null)
                }
                videoLayout.keepScreenOn = true
                playPause.setImageResource(androidx.media3.ui.R.drawable.exo_icon_pause)
                if (!resumed) {
                    resumed = true
                    if (resumeAtMs > 0) player?.time = resumeAtMs
                }
                if (refreshAfterReattach) {
                    refreshAfterReattach = false
                    player?.let { it.time = it.time }
                }
            }
            MediaPlayer.Event.ESAdded -> chooseSubtitleTrack()
            MediaPlayer.Event.Paused -> {
                videoLayout.keepScreenOn = false
                playPause.setImageResource(androidx.media3.ui.R.drawable.exo_icon_play)
            }
            MediaPlayer.Event.Buffering ->
                buffering.visibility = if (event.buffering < 100f) View.VISIBLE else View.GONE
            MediaPlayer.Event.EndReached ->
                // Debug only: the preview loops a short clip so its subtitles can
                // be watched through more than once.
                if (intent.getBooleanExtra("loop", false)) currentUrl?.let { load(it) } else finish()
            MediaPlayer.Event.EncounteredError -> {
                if (reconnect()) return
                // The source list is the useful answer: addons hand back links
                // that no longer play, and another source usually just works.
                recoveryStatus(null)
                toast("Couldn't play this source")
                if (sources.size > 1) showSourcePicker() else finish()
            }
        }
    }

    /** Drives the seek bar and the clock, and pushes progress once past 85%. */
    private fun trackPosition() {
        lifecycleScope.launch {
            while (true) {
                val mp = player
                if (mp != null && !scrubbing) {
                    val length = mp.length
                    val time = mp.time
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
                if (!canceled) player?.time = position
                showControls()
            }
        })
        showControls()
    }

    private fun seekBy(deltaMs: Long, reveal: Boolean = true) {
        val mp = player ?: return
        mp.time = (mp.time + deltaMs).coerceIn(0, if (mp.length > 0) mp.length else Long.MAX_VALUE)
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

    private fun showSubtitlePicker() {
        val mp = player ?: return
        val tracks = mp.spuTracks?.toList().orEmpty()
        val current = mp.spuTrack
        val ids = mutableListOf(-1)
        val rows = mutableListOf(
            PanelRow("Off", "No subtitles", if (current == -1) "SELECTED" else null)
        )
        tracks.filter { it.id != -1 }.forEach { track ->
            ids += track.id
            val (title, detail) = subtitleName(track)
            rows += PanelRow(title, detail, if (track.id == current) "SELECTED" else null)
        }
        val fromAddons = subtitleUrls.size
        panelDialog(
            "Subtitles",
            "${(rows.size - 1 - fromAddons).coerceAtLeast(0)} IN THIS FILE · $fromAddons FROM ADDONS",
            rows
        ) { index ->
            subtitlePickedByHand = true
            mp.spuTrack = ids[index]
        }.show()
    }

    /**
     * A side-loaded track's own name is its URL, which reads as gibberish on a
     * picker. Where a track matches one this app handed over, it is named after
     * the addon it came from, as the old picker did.
     */
    private fun subtitleName(track: MediaPlayer.TrackDescription): Pair<String, String> {
        val name = track.name.orEmpty()
        val index = subtitleUrls.indexOfFirst { name.contains(it) }
        if (index < 0) return (name.ifEmpty { "Track ${track.id}" }) to "In this file"
        val addon = subtitleAddons.getOrNull(index)?.takeIf { it.isNotBlank() } ?: "an addon"
        val lang = subtitleLangs.getOrNull(index)?.takeIf { it.isNotBlank() } ?: "und"
        return lang to "From $addon"
    }

    private fun showAudioPicker() {
        val mp = player ?: return
        val tracks = mp.audioTracks?.toList().orEmpty().filter { it.id != -1 }
        if (tracks.size < 2) {
            toast("This release has one audio track")
            return
        }
        val current = mp.audioTrack
        val rows = tracks.map { track ->
            PanelRow(
                track.name.orEmpty().ifEmpty { "Track ${track.id}" },
                "In this file",
                if (track.id == current) "SELECTED" else null
            )
        }
        panelDialog("Audio", "${tracks.size} TRACKS", rows) { index ->
            mp.audioTrack = tracks[index].id
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
        val ordered = sources.indices.sortedWith(
            compareByDescending<Int> { it == currentSourceIndex }
                .thenByDescending { filter.accepts(sources[it], StreamMeta.of(sources[it]), minSize) }
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
        val resumeAt = mp.time
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

    private var reconnects = 0

    /**
     * Retries a source that was playing, from where it was, for about half a
     * minute before giving up on it.
     *
     * The same fault was measured on the media3 player: a connection that
     * dropped for a few seconds ended the episode and never came back when the
     * network did. A source that never played is not retried — it is dead, and
     * the source list is the right answer at once. So is a downloaded file.
     */
    private fun reconnect(): Boolean {
        val url = currentUrl ?: return false
        if (!resumed || !url.startsWith("http")) return false
        val wait = RECONNECT_DELAYS_MS.getOrNull(reconnects) ?: return false
        reconnects++
        recoveryStatus("Reconnecting…")
        val at = player?.time?.takeIf { it > 0 } ?: resumeAtMs
        lifecycleScope.launch {
            delay(wait)
            resumeAtMs = at
            load(url)
        }
        return true
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
                val time = mp?.time ?: -1L
                val active = segments.firstOrNull { time in it.startMs..it.endMs }
                if (active == null) {
                    button.visibility = View.GONE
                } else {
                    button.text = active.label
                    button.visibility = View.VISIBLE
                    button.setOnClickListener {
                        player?.time = active.endMs
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
            while (waited < 5000 && (player?.length ?: 0L) <= 0L) {
                delay(250)
                waited += 250
            }
            val mal = runCatching {
                Mappings.forAniList(anilistId, seriesTitle.ifEmpty { null }).mal?.toIntOrNull()
            }.getOrNull()
            val found = AniSkip.segments(mal, forEpisode, (player?.length ?: 0L).coerceAtLeast(0L))
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
                CastTargets.send(this@PlayerActivity, CastTarget.External, url, currentTitle, player?.time ?: 0)
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
                val position = player?.time ?: 0
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

    // ----- the rest of the phone -----

    /**
     * Pause for a call or another app's audio, dip under a notification, and
     * pause when headphones or Bluetooth disconnect.
     *
     * libVLC does none of this: it is a decoder, not a media app, so the
     * politeness that media3 offered as a pair of flags is written out here.
     */
    private fun takeAudioFocus() {
        val manager = getSystemService(AUDIO_SERVICE) as AudioManager
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            .setOnAudioFocusChangeListener { change ->
                val mp = player ?: return@setOnAudioFocusChangeListener
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        pausedByFocus = mp.isPlaying
                        mp.pause()
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> mp.volume = 30
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        mp.volume = 100
                        if (pausedByFocus) {
                            pausedByFocus = false
                            mp.play()
                        }
                    }
                }
            }
            .build()
        audioFocus = request
        audioManager = manager
        manager.requestAudioFocus(request)

        // Unplugging headphones must not put the episode on the loudspeaker.
        noisyReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                player?.pause()
            }
        }
        registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
    }

    private fun releaseAudioFocus() {
        audioFocus?.let { audioManager?.abandonAudioFocusRequest(it) }
        audioFocus = null
        noisyReceiver?.let { runCatching { unregisterReceiver(it) } }
        noisyReceiver = null
    }

    // ----- lifecycle -----

    private fun savePosition() {
        val mp = player ?: return
        if (mp.length > 0) Progress.save(anilistId, episode, mp.time, mp.length)
    }

    override fun onStart() {
        super.onStart()
        // Cast routes only exist while something asks for them, and the picker
        // needs them still there when a row is tapped.
        GoogleCast.retainRoutes(this)
        player?.let { mp ->
            if (videoAttached) return@let
            attachVideo(mp)
            // Reattaching restarts the video decoder, and a new AV1 decoder
            // cannot decode anything until a keyframe brings the sequence header
            // ("Error parsing OBU data") — seconds of black in an anime encode.
            // Seeking to where it already is starts again from the keyframe
            // before, but only if done once playback resumes: seeking here, while
            // paused, is undone by the decoder restarting on play.
            refreshAfterReattach = true
        }
    }

    override fun onStop() {
        super.onStop()
        GoogleCast.releaseRoutes(this)
        // Android destroys the video surface when the screen goes off or the
        // app is left. libVLC holds on to the dead one unless told, and on
        // return plays sound over a black picture ("cannot create EGL window
        // surface"). Detaching here and attaching in onStart gives it the new one.
        player?.let { detachVideo(it) }
    }

    private var videoAttached = false
    private var refreshAfterReattach = false

    private fun attachVideo(mp: MediaPlayer) {
        if (videoAttached) return
        mp.attachViews(videoLayout, null, /* enableSubtitles = */ true, /* useTextureView = */ false)
        videoAttached = true
    }

    private fun detachVideo(mp: MediaPlayer) {
        if (!videoAttached) return
        mp.detachViews()
        videoAttached = false
    }

    override fun onPause() {
        super.onPause()
        savePosition()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        savePosition()
        releaseAudioFocus()
        player?.let {
            it.stop()
            detachVideo(it)
            it.release()
        }
        player = null
        libVlc?.release()
        libVlc = null
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
        /** AniList's minutes per episode, for judging whether this is one. */
        const val EXTRA_EPISODE_MINUTES = "episode_minutes"
        const val EXTRA_SUB_URLS = "sub_urls"
        const val EXTRA_SUB_LANGS = "sub_langs"
        /** Which addon each subtitle came from, parallel to the arrays above. */
        const val EXTRA_SUB_ADDONS = "sub_addons"

        // How far the rewind and forward buttons, and a double tap, move.
        private const val SEEK_STEP_MS = 10_000L

        // How subtitle tracks are told apart by name. libVLC names an embedded
        // track "<title> - [<language>]", e.g. "Signs & Songs - [English]".
        private val ENGLISH_TRACK = Regex("""\benglish\b|\[eng?\]|\beng\b""")
        private val PARTIAL_TRACK = Regex("""sign|song|forced|\bs&s\b|commentary""")
        private val FULL_TRACK = Regex("""full|dialogue""")
        private const val ENGLISH_SCORE = 20

        // Waits before each attempt to reconnect: about half a minute in all.
        private val RECONNECT_DELAYS_MS = longArrayOf(2_000, 4_000, 8_000, 8_000, 8_000)
    }
}
