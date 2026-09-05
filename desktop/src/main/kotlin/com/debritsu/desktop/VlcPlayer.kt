package com.debritsu.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import com.debritsu.app.data.BuildInfo
import com.debritsu.app.data.Subtitle
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference

/**
 * Playback through libVLC, decoded into a buffer we paint ourselves.
 *
 * This is what mpv could not do. mpv drew into a window it did not own and
 * received no input, so nothing could be laid over the picture and every
 * control had to sit beside it. Here the video is an ordinary image in the
 * Compose tree, so controls go over it like any other interface.
 *
 * The cost is a frame copy: libVLC writes into a native buffer, and that has to
 * reach a Skia bitmap before it can be drawn. At 1080p that is about eight
 * megabytes a frame. It is the price of owning the pixels.
 */
class VlcPlayer(vlcDirectory: java.io.File) {

    private val factory: MediaPlayerFactory = MediaPlayerFactory(
        // Its own logging is not wanted in ours, and the title overlay is
        // VLC's rather than something this app asked for.
        "--quiet",
        "--no-video-title-show",
        // Network streams need a longer buffer than the default: a debrid link
        // is a long way away and a short cache stutters on it.
        "--network-caching=3000"
    )

    private val player: EmbeddedMediaPlayer = factory.mediaPlayers().newEmbeddedMediaPlayer()

    /** The most recent frame, or null before the first one arrives. */
    private val current = AtomicReference<ImageBitmap?>(null)

    /**
     * Counts frames, so Compose has something to observe.
     *
     * The bitmap reference alone is not enough to make it redraw reliably —
     * a counter that changes every frame is.
     */
    @Volatile
    var frames: Long = 0
        private set

    @Volatile
    var videoWidth: Int = 0
        private set

    @Volatile
    var videoHeight: Int = 0
        private set

    fun frame(): ImageBitmap? = current.get()

    private var bitmap: Bitmap? = null
    private var pixels: ByteArray = ByteArray(0)

    private val bufferFormatCallback = object : BufferFormatCallback {
        override fun getBufferFormat(width: Int, height: Int): BufferFormat {
            videoWidth = width
            videoHeight = height
            // RV32 is BGRA on little-endian, which is what Skia wants for
            // BGRA_8888 — so the buffer can be handed over without swizzling.
            pixels = ByteArray(width * height * 4)
            bitmap = Bitmap().apply {
                allocPixels(
                    ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
                )
            }
            BuildInfo.log("DebritsuVlc", "video is ${width}x$height")
            return RV32BufferFormat(width, height)
        }

        override fun allocatedBuffers(buffers: Array<out ByteBuffer>) = Unit
    }

    private val renderCallback = RenderCallback { _, buffers, format ->
        val bmp = bitmap ?: return@RenderCallback
        val buffer = buffers[0]
        buffer.rewind()
        buffer.get(pixels)
        bmp.installPixels(
            ImageInfo(format.width, format.height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE),
            pixels,
            format.width * 4L.toInt()
        )
        current.set(bmp.asComposeImageBitmap())
        frames++
    }

    init {
        Vlc.prepare(vlcDirectory.absolutePath)
        player.videoSurface().set(
            factory.videoSurfaces().newVideoSurface(bufferFormatCallback, renderCallback, true)
        )
    }

    /** Set when playback ends of its own accord, so the screen can react. */
    @Volatile
    var ended = false
        private set

    init {
        player.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun finished(mediaPlayer: MediaPlayer) {
                ended = true
            }

            override fun error(mediaPlayer: MediaPlayer) {
                BuildInfo.log("DebritsuVlc", "playback error")
                ended = true
            }
        })
    }

    /**
     * Starts [url], with any external subtitles attached.
     *
     * Track languages are asked for as media options rather than chosen after
     * the fact: VLC picks a track when the file opens, and correcting it
     * afterwards means a visible switch a second in.
     */
    fun play(
        url: String,
        subtitles: List<Subtitle>,
        startAtMs: Long,
        audioLanguage: String,
        subtitleLanguage: String
    ) {
        val options = buildList {
            if (startAtMs > 0) add(":start-time=${startAtMs / 1000}")
            if (audioLanguage.isNotBlank()) add(":audio-language=${languages(audioLanguage)}")
            if (subtitleLanguage.isNotBlank()) add(":sub-language=${languages(subtitleLanguage)}")
        }.toTypedArray()

        player.media().play(url, *options)

        // Added after starting: a slave attached to media that has not been
        // opened is dropped, and these are remote files rather than local ones.
        subtitles.forEach { sub ->
            runCatching {
                player.media().addSlave(
                    uk.co.caprica.vlcj.media.MediaSlaveType.SUBTITLE,
                    sub.url,
                    false
                )
            }
        }
    }

    /**
     * Both spellings of a language, as VLC also matches inconsistently — the
     * same problem the mpv path had, for the same reason.
     */
    private fun languages(code: String): String = when (code.lowercase()) {
        "ja", "jpn" -> "jpn,ja,japanese"
        "en", "eng" -> "eng,en,english"
        else -> code
    }

    val playing: Boolean get() = player.status().isPlaying
    fun positionMs(): Long = player.status().time()
    fun durationMs(): Long = player.status().length()

    fun setPaused(paused: Boolean) = player.controls().setPause(paused)
    fun seekTo(ms: Long) = player.controls().setTime(ms)
    fun seekBy(seconds: Int) = player.controls().skipTime(seconds * 1000L)
    fun setVolume(percent: Int) { player.audio().setVolume(percent) }
    fun volume(): Int = player.audio().volume()

    /** Track lists, for pickers that name what they are choosing between. */
    fun subtitleTracks(): List<Pair<Int, String>> =
        runCatching { player.subpictures().trackDescriptions().map { it.id() to it.description() } }
            .getOrDefault(emptyList())

    fun audioTracks(): List<Pair<Int, String>> =
        runCatching { player.audio().trackDescriptions().map { it.id() to it.description() } }
            .getOrDefault(emptyList())

    fun subtitleTrack(): Int = runCatching { player.subpictures().track() }.getOrDefault(-1)
    fun setSubtitleTrack(id: Int) { runCatching { player.subpictures().setTrack(id) } }
    fun audioTrack(): Int = runCatching { player.audio().track() }.getOrDefault(-1)
    fun setAudioTrack(id: Int) { runCatching { player.audio().setTrack(id) } }

    fun release() {
        runCatching { player.controls().stop() }
        runCatching { player.release() }
        runCatching { factory.release() }
    }
}
