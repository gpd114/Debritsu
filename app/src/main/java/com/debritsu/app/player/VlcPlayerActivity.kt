package com.debritsu.app.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.TimeBar
import com.debritsu.app.R
import com.debritsu.app.data.AniList
import com.debritsu.app.data.Debrid
import com.debritsu.app.data.Progress
import com.debritsu.app.data.Settings
import com.debritsu.app.data.SourceHandoff
import com.debritsu.app.data.StreamMeta
import com.debritsu.app.data.StreamOption
import com.debritsu.app.data.minEpisodeSizeMb
import com.debritsu.app.data.SyncQueue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout
import java.util.Locale

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
class VlcPlayerActivity : ComponentActivity() {

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

        setContentView(R.layout.activity_vlc_player)
        videoLayout = findViewById(R.id.video_layout)
        controls = findViewById(R.id.controls)
        playPause = findViewById(R.id.play_pause)
        timeBar = findViewById(R.id.time_bar)
        positionText = findViewById(R.id.position)
        durationText = findViewById(R.id.duration)
        buffering = findViewById(R.id.buffering)

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
        mp.attachViews(videoLayout, null, /* enableSubtitles = */ true, /* useTextureView = */ false)
        mp.setEventListener { event -> onPlayerEvent(event) }

        resumeAtMs = Progress.position(anilistId, episode)
        load(url)
        trackPosition()
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
        mp.play()
        // Subtitle files from addons are handed over after play starts, which is
        // when libVLC accepts them. A slave that will not download is simply a
        // subtitle that never appears — it cannot take the episode with it.
        subtitleUrls.forEach { sub ->
            runCatching { mp.addSlave(IMedia.Slave.Type.Subtitle, Uri.parse(sub), true) }
        }
    }

    private fun onPlayerEvent(event: MediaPlayer.Event) {
        when (event.type) {
            MediaPlayer.Event.Playing -> {
                buffering.visibility = View.GONE
                videoLayout.keepScreenOn = true
                playPause.setImageResource(androidx.media3.ui.R.drawable.exo_icon_pause)
                if (!resumed) {
                    resumed = true
                    if (resumeAtMs > 0) player?.time = resumeAtMs
                }
            }
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
                // The source list is the useful answer: addons hand back links
                // that no longer play, and another source usually just works.
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
        findViewById<View>(R.id.player_root).setOnClickListener { toggleControls() }
        playPause.setOnClickListener {
            val mp = player ?: return@setOnClickListener
            if (mp.isPlaying) mp.pause() else mp.play()
            showControls()
        }
        findViewById<View>(R.id.rewind).setOnClickListener { seekBy(-15_000) }
        findViewById<View>(R.id.forward).setOnClickListener { seekBy(15_000) }
        findViewById<View>(R.id.subtitle_button).setOnClickListener { showSubtitlePicker() }
        findViewById<View>(R.id.audio_button).setOnClickListener { showAudioPicker() }
        findViewById<View>(R.id.cast_button).visibility = View.GONE
        findViewById<View>(R.id.sources_button).apply {
            visibility = if (sources.size > 1) View.VISIBLE else View.GONE
            setOnClickListener { showSourcePicker() }
        }
        findViewById<View>(R.id.prev_episode).visibility = View.GONE
        findViewById<View>(R.id.next_episode).visibility = View.GONE

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

    private fun seekBy(deltaMs: Long) {
        val mp = player ?: return
        mp.time = (mp.time + deltaMs).coerceIn(0, if (mp.length > 0) mp.length else Long.MAX_VALUE)
        showControls()
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
        ) { index -> mp.spuTrack = ids[index] }.show()
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
            it.detachViews()
            it.release()
        }
        player = null
        libVlc?.release()
        libVlc = null
    }
}
