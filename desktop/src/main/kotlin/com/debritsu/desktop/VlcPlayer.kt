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
        // Not quiet: libVLC's own messages are the only thing that says why a
        // video output failed to build, and guessing at that has already cost
        // several attempts. They are forwarded into our log below.
        "--no-video-title-show",
        "--no-snapshot-preview",
        // No interface of its own. vlcj's own component passes this; without it
        // libvlc will try to start one.
        "--intf=dummy",

        // Hardware decoding off, and this is the one that matters.
        //
        // VLC on Windows decodes with DXVA2 or D3D11 by default, which puts
        // frames in GPU surfaces. Callback output cannot receive those: it
        // wants them in a buffer in main memory. The result was audio playing
        // while the video output failed to build, and VLC retrying it with
        // different buffer alignments — which is exactly what the log showed,
        // a running timer and no picture.
        //
        // The cost is software decoding, which for 1080p HEVC is real but
        // manageable. There is no way to have both a picture we can draw and
        // frames that never leave the graphics card.
        "--avcodec-hw=none",

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

    /**
     * The picture's real height, as against the buffer's.
     *
     * VLC aligns the buffer it asks for — 1080 becomes 1088 — and those extra
     * rows are padding, not picture. Drawing them puts a strip of rubbish under
     * the video, which in fullscreen reads as a border round the image.
     *
     * Zero until asked for, because it is only knowable once playback has
     * started and the video size is published.
     */
    @Volatile
    var cropHeight: Int = 0

    /** The size VLC says the video actually is, once it is playing. */
    fun sourceSize(): Pair<Int, Int>? = runCatching {
        val d = player.video().videoDimension() ?: return null
        d.width to d.height
    }.getOrNull()

    fun frame(): ImageBitmap? = current.get()

    private var bitmap: Bitmap? = null
    private var pixels: ByteArray = ByteArray(0)

    /** Logged once rather than per frame, which would be thousands of lines. */
    @Volatile
    private var reportedFailure = false

    private val bufferFormatCallback = object : BufferFormatCallback {
        override fun getBufferFormat(width: Int, height: Int): BufferFormat {
            // Called more than once, and not only when the format changes —
            // VLC settles from the coded size to an aligned one (1080 becomes
            // 1088) and asks again. Allocating unconditionally left the buffer
            // sized for one height while frames arrived at another, which
            // overflowed, threw inside a native callback, and made VLC rebuild
            // its output over and over.
            if (width != videoWidth || height != videoHeight) {
                videoWidth = width
                videoHeight = height
                BuildInfo.log("DebritsuVlc", "video buffer ${width}x$height")
            }
            return RV32BufferFormat(width, height)
        }

        override fun allocatedBuffers(buffers: Array<out ByteBuffer>) = Unit
    }

    private val renderCallback = RenderCallback { _, buffers, format ->
        try {
            val width = format.width
            val bufferHeight = format.height
            val needed = width * bufferHeight * 4

            // Only the rows that are picture. The rest is alignment padding and
            // drawing it shows as a strip below the image.
            val height = cropHeight.takeIf { it in 1..bufferHeight } ?: bufferHeight

            // Once, on the first frame. Whether this fires at all is the
            // difference between "the picture is wrong" and "there is no
            // picture", and those want completely different fixes.
            if (frames == 0L) {
                BuildInfo.log(
                    "DebritsuVlc",
                    "first frame ${width}x$height, buffers=${buffers.size}, " +
                        "capacity=${buffers.firstOrNull()?.capacity()}, need=$needed"
                )
            }

            // Sized from the frame in hand rather than from whatever the format
            // callback last said. They disagree while VLC is settling, and the
            // frame is the one that has to fit.
            var bmp = bitmap
            if (bmp == null || pixels.size != needed || bmp.height != height) {
                pixels = ByteArray(needed)
                bmp = Bitmap().apply {
                    allocPixels(
                        ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
                    )
                }
                bitmap = bmp
            }

            val buffer = buffers[0]
            buffer.rewind()
            if (buffer.remaining() < needed) {
                if (!reportedFailure) {
                    reportedFailure = true
                    BuildInfo.log(
                        "DebritsuVlc",
                        "frame too small: have ${buffer.remaining()}, need $needed"
                    )
                }
                return@RenderCallback
            }
            buffer.get(pixels, 0, needed)

            // RV32 is BGRA on a little-endian machine, which is what Skia wants
            // for BGRA_8888 — so the bytes go across without swizzling.
            bmp.installPixels(
                ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE),
                pixels,
                width * 4
            )
            current.set(bmp.asComposeImageBitmap())
            frames++
        } catch (t: Throwable) {
            // Never let this escape. An exception thrown out of a native
            // callback does not surface as a stack trace anywhere useful; it
            // tears down the video output, which VLC then rebuilds, which calls
            // this again — a loop that looks like "no picture" and says nothing.
            if (!reportedFailure) {
                reportedFailure = true
                BuildInfo.log("DebritsuVlc", "frame failed: $t")
            }
        }
    }

    /**
     * libVLC's own messages, forwarded into our log.
     *
     * Only warnings and worse, or a minute of playback writes tens of thousands
     * of lines. This exists because "the timer runs and there is no picture"
     * says nothing about which of a dozen things went wrong, and libVLC knows
     * exactly which.
     */
    private val nativeLog = runCatching {
        factory.application().newLog()?.apply {
            level = uk.co.caprica.vlcj.log.LogLevel.WARNING
            addLogListener { level, module, _, _, _, _, _, message ->
                BuildInfo.log("DebritsuVlcNative", "$level [$module] $message")
            }
        }
    }.getOrNull()

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

            /**
             * Tracks are only known once playback is running, so the subtitle
             * choice has to be made here rather than at launch.
             *
             * VLC's own --sub-language is not enough: it matches the language
             * metadata of embedded tracks, and an external file attached as a
             * slave has none to match against. Left alone the result is no
             * subtitles at all, which is what happened.
             */
            override fun mediaPlayerReady(mediaPlayer: MediaPlayer) {
                chooseSubtitles()
            }
        })
    }

    /** Picks a subtitle track, preferring the wanted language. */
    fun chooseSubtitles(preferred: String = "eng") {
        val tracks = subtitleTracks()
        BuildInfo.log(
            "DebritsuVlc",
            "subtitle tracks: " + tracks.joinToString { "${it.first}=${it.second}" }
        )
        if (tracks.isEmpty()) return

        // -1 is "disabled" and is always in the list; it is never what is
        // wanted here, and picking it silently is indistinguishable from
        // finding nothing.
        val real = tracks.filter { it.first != -1 }
        if (real.isEmpty()) return

        val wanted = real.firstOrNull { (_, name) ->
            val n = name.lowercase()
            n.contains("eng") || n.contains("english") || n.contains(preferred.lowercase())
        } ?: real.first()

        BuildInfo.log("DebritsuVlc", "selecting subtitles: ${wanted.second}")
        setSubtitleTrack(wanted.first)
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
        //
        // The first is selected, the rest are offered. Attaching them all
        // unselected was the same as not attaching them: they appeared in the
        // track list and nothing chose one, so there were no subtitles on
        // screen and no sign of why.
        subtitles.forEachIndexed { index, sub ->
            val added = runCatching {
                player.media().addSlave(
                    uk.co.caprica.vlcj.media.MediaSlaveType.SUBTITLE,
                    sub.url,
                    index == 0
                )
            }.getOrDefault(false)
            if (BuildInfo.debug) {
                BuildInfo.log("DebritsuVlc", "subtitle slave ${if (added) "added" else "refused"}: ${sub.url}")
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
    fun setVolume(percent: Int) { runCatching { player.audio().setVolume(percent.coerceIn(0, 125)) } }
    fun volume(): Int = runCatching { player.audio().volume() }.getOrDefault(100)
    fun muted(): Boolean = runCatching { player.audio().isMute }.getOrDefault(false)
    fun setMuted(muted: Boolean) { runCatching { player.audio().setMute(muted) } }

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
        runCatching { nativeLog?.release() }
        runCatching { player.controls().stop() }
        runCatching { player.release() }
        runCatching { factory.release() }
    }
}
