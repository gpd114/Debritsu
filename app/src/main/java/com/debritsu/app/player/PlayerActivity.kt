package com.debritsu.app.player

import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.app.Dialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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
import com.debritsu.app.data.Debrid
import com.debritsu.app.data.Progress
import com.debritsu.app.data.StreamOption
import com.debritsu.app.data.SyncQueue
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

        setContentView(R.layout.activity_player)
        val view = findViewById<PlayerView>(R.id.player_view)
        view.setKeepContentOnPlayerReset(true)

        // The Sources button lives in the control bar, so it fades with the
        // rest of the transport controls rather than sitting over the video.
        val sourcesButton = findViewById<ImageButton>(R.id.sources_button)
        sourcesButton.visibility = if (sources.size > 1) View.VISIBLE else View.GONE
        sourcesButton.setOnClickListener { showSourcePicker() }

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
    }

    private fun mediaItem(url: String): MediaItem =
        MediaItem.Builder()
            .setUri(url)
            .setSubtitleConfigurations(subtitleConfigs)
            .build()

    /** Swap source without losing your place in the episode. */
    private fun showSourcePicker() {
        if (sources.isEmpty()) return

        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(18), px(20), px(24))
            background = GradientDrawable().apply {
                setColor(0xFF171226.toInt())
                cornerRadii = floatArrayOf(
                    px(20).toFloat(), px(20).toFloat(), px(20).toFloat(), px(20).toFloat(),
                    0f, 0f, 0f, 0f
                )
            }
        }

        content.addView(TextView(this).apply {
            text = "Sources"
            setTextColor(0xFFF1EEF8.toInt())
            textSize = 16f
            setTypeface(Typeface.DEFAULT_BOLD)
        })
        content.addView(TextView(this).apply {
            text = "${sources.size} AVAILABLE"
            setTextColor(0xFFB9B3CC.toInt())
            textSize = 10.5f
            typeface = Typeface.MONOSPACE
            setPadding(0, px(2), 0, px(12))
        })

        val dialog = Dialog(this, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)

        sources.forEach { s ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, px(12), 0, px(12))
                isClickable = true
                setOnClickListener { dialog.dismiss(); switchTo(s) }
            }
            row.addView(TextView(this).apply {
                text = s.name
                setTextColor(0xFFF1EEF8.toInt())
                textSize = 12f
                typeface = Typeface.MONOSPACE
                maxLines = 1
            })
            row.addView(TextView(this).apply {
                text = s.description.replace("\n", " ").take(110)
                setTextColor(0xFFB9B3CC.toInt())
                textSize = 11.5f
                maxLines = 2
            })
            row.addView(TextView(this).apply {
                text = if (s.isDirect) "DIRECT" else "DEBRID"
                setTextColor(if (s.isDirect) 0xFF8B5CF6.toInt() else 0xFFB9B3CC.toInt())
                textSize = 9.5f
                typeface = Typeface.MONOSPACE
                setPadding(0, px(3), 0, 0)
            })
            content.addView(row)
            content.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, px(1)
                )
                setBackgroundColor(0xFF221A36.toInt())
            })
        }

        val scroller = ScrollView(this).apply { addView(content) }
        dialog.setContentView(scroller)
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0x99000000.toInt()))
            setLayout(
                (resources.displayMetrics.widthPixels * 0.62).toInt(),
                (resources.displayMetrics.heightPixels * 0.80).toInt()
            )
            setGravity(Gravity.BOTTOM or Gravity.END)
        }
        dialog.show()
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
