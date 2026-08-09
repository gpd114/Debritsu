package com.debritsu.app.player

import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.app.AlertDialog
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
import com.debritsu.app.data.Debrid
import com.debritsu.app.data.Progress
import com.debritsu.app.data.StreamOption
import com.debritsu.app.data.json
import com.debritsu.app.data.Settings
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer

@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    private var player: ExoPlayer? = null
    private var progressPushed = false
    private var anilistId = 0
    private var episode = 0
    private var sources: List<StreamOption> = emptyList()
    private var subtitleConfigs: List<MediaItem.SubtitleConfiguration> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = intent.getStringExtra(EXTRA_URL) ?: run { finish(); return }
        anilistId = intent.getIntExtra(EXTRA_ANILIST_ID, 0)
        episode = intent.getIntExtra(EXTRA_EPISODE, 0)
        sources = runCatching {
            json.decodeFromString(
                ListSerializer(StreamOption.serializer()),
                intent.getStringExtra(EXTRA_SOURCES) ?: "[]"
            )
        }.getOrDefault(emptyList())
        val subUrls = intent.getStringArrayExtra(EXTRA_SUB_URLS).orEmpty()
        val subLangs = intent.getStringArrayExtra(EXTRA_SUB_LANGS).orEmpty()

        val view = PlayerView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setKeepContentOnPlayerReset(true)
            // Adds the CC button to the control bar, which opens the built-in
            // track picker listing embedded and side-loaded subtitle tracks.
            setShowSubtitleButton(true)
            setShowNextButton(false)
            setShowPreviousButton(false)
        }
        val root = FrameLayout(this).apply { addView(view) }

        // Release names rarely say whether a stream is subbed or dubbed, so the
        // switcher has to be reachable once playback has started.
        val switcher = TextView(this).apply {
            text = "SOURCES"
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(28, 16, 28, 16)
            setBackgroundColor(Color.argb(150, 8, 7, 13))
            visibility = android.view.View.GONE
            setOnClickListener { showSourcePicker() }
        }
        root.addView(
            switcher,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            ).apply { topMargin = 48; rightMargin = 32 }
        )
        // Follow the transport controls so it fades with them.
        view.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { vis ->
                switcher.visibility = if (sources.size > 1) vis else android.view.View.GONE
            }
        )
        setContentView(root)
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
            exo.trackSelectionParameters = exo.trackSelectionParameters
                .buildUpon()
                .setPreferredTextLanguage("en")
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
                            runCatching { AniList.setProgress(anilistId, episode) }
                        }
                    }
                }
            })
        }
    }

    private fun mediaItem(url: String): MediaItem =
        MediaItem.Builder()
            .setUri(url)
            .setSubtitleConfigurations(subtitleConfigs)
            .build()

    /** Swap source without losing your place in the episode. */
    private fun showSourcePicker() {
        if (sources.isEmpty()) return
        val labels = sources.map { s ->
            val tag = if (s.isDirect) "direct" else "debrid"
            "${s.name}  ·  $tag\n${s.description.replace("\n", " ").take(90)}"
        }.toTypedArray()

        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Sources")
            .setItems(labels) { _, which -> switchTo(sources[which]) }
            .show()
    }

    private fun switchTo(stream: StreamOption) {
        val exo = player ?: return
        val resumeAt = exo.currentPosition
        exo.pause()
        lifecycleScope.launch {
            runCatching { Debrid.resolve(stream) }
                .onSuccess { url ->
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

    override fun onPause() {
        super.onPause()
        savePosition()
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
        savePosition()
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
        const val EXTRA_ANILIST_ID = "anilist_id"
        const val EXTRA_EPISODE = "episode"
        const val EXTRA_SOURCES = "sources"
        const val EXTRA_SUB_URLS = "sub_urls"
        const val EXTRA_SUB_LANGS = "sub_langs"
    }
}
