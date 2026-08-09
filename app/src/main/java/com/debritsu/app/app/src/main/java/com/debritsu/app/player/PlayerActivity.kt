package com.debritsu.app.player

import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.ViewGroup
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
import com.debritsu.app.data.Settings
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    private var player: ExoPlayer? = null
    private var progressPushed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = intent.getStringExtra(EXTRA_URL) ?: run { finish(); return }
        val anilistId = intent.getIntExtra(EXTRA_ANILIST_ID, 0)
        val episode = intent.getIntExtra(EXTRA_EPISODE, 0)
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
        setContentView(view)
        applySubtitleStyle(view.subtitleView)

        val subtitleConfigs = subUrls.mapIndexed { i, subUrl ->
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(subUrl))
                .setMimeType(mimeFor(subUrl))
                .setLanguage(subLangs.getOrNull(i) ?: "und")
                .setSelectionFlags(0)
                .build()
        }

        val item = MediaItem.Builder()
            .setUri(url)
            .setSubtitleConfigurations(subtitleConfigs)
            .build()

        player = ExoPlayer.Builder(this).build().also { exo ->
            view.player = exo
            // Prefer English subs by default; the user can override from the CC button.
            exo.trackSelectionParameters = exo.trackSelectionParameters
                .buildUpon()
                .setPreferredTextLanguage("en")
                .build()
            exo.setMediaItem(item)
            exo.prepare()
            exo.playWhenReady = true
            exo.addListener(object : Player.Listener {
                override fun onEvents(p: Player, events: Player.Events) {
                    val duration = p.duration
                    if (!progressPushed && anilistId > 0 && episode > 0 &&
                        duration > 0 && p.currentPosition > duration * 0.85
                    ) {
                        progressPushed = true
                        lifecycleScope.launch {
                            runCatching { AniList.setProgress(anilistId, episode) }
                        }
                    }
                }
            })
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

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_ANILIST_ID = "anilist_id"
        const val EXTRA_EPISODE = "episode"
        const val EXTRA_SUB_URLS = "sub_urls"
        const val EXTRA_SUB_LANGS = "sub_langs"
    }
}
