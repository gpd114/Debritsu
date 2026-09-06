package com.debritsu.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.debritsu.app.data.BuildInfo
import com.debritsu.app.data.Subtitle
import org.jetbrains.skia.Image
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
     * The size VLC says the video actually is, once it is playing.
     *
     * Wanted for its shape, not its size. libVLC's callback output scales the
     * picture into whatever buffer it is given, so the buffer's own dimensions
     * say nothing about the video's proportions — an AV1 release decoded to a
     * 1920x1152 buffer is still 16:9 and has to be drawn as such.
     */
    fun sourceSize(): Pair<Int, Int>? {
        if (released) return null
        return runCatching {
            val d = player.video().videoDimension() ?: return null
            d.width to d.height
        }.getOrNull()
    }

    fun frame(): ImageBitmap? = current.get()

    private var pixels: ByteArray = ByteArray(0)

    /** Logged once rather than per frame, which would be thousands of lines. */
    @Volatile
    private var reportedFailure = false

    /**
     * Whether frames should be turned into images at all.
     *
     * Off while the window is being rebuilt for fullscreen. Decoding carries on
     * — the audio must not gap — but nothing new is published, because the
     * renderer that would draw it is being disposed and drawing into one that
     * is going away crashes Skia outright, taking the whole JVM with it.
     */
    @Volatile
    var publishing: Boolean = true

    /**
     * Set the instant release begins, and checked by everything that calls into
     * libVLC.
     *
     * Releasing frees the video buffer and the player behind it, and three
     * things are still using them at that moment: this callback, the screen's
     * frame loop asking for a position every 16ms, and the progress watcher
     * asking every second. Any of them reaching a freed pointer is a segfault
     * inside a native call, which arrives as "Invalid memory access" and takes
     * the process with it — no exception, no stack, nothing in the log after
     * the last frame. That is what Back did.
     */
    @Volatile
    private var released = false

    val isReleased: Boolean get() = released

    /** Held while a frame is being copied, so release can wait for one in flight. */
    private val frameLock = Any()

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
        if (released || !publishing) return@RenderCallback
        synchronized(frameLock) {
        // Checked again inside the lock: release may have started between the
        // test above and getting in here, and the buffer would then be freed
        // under us mid-copy.
        if (released) return@RenderCallback
        try {
            val width = format.width

            // The whole buffer, every row of it.
            //
            // It used to take only the video's own height out of a taller
            // buffer, on the reasoning that VLC aligns what it asks for and the
            // extra rows are padding. They are not. libVLC's callback output
            // scales the picture into whatever buffer it is handed, so the
            // whole thing is picture — dumping a 1920x1152 frame to a file and
            // looking at it settled that after two wrong guesses. Cropping it
            // to 1080 discarded the bottom 6% of the image and stretched what
            // was left, which is what "the subtitles are bigger and cut off at
            // the bottom" was. The shape is corrected where it is drawn.
            val height = format.height
            val needed = width * height * 4

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
            if (pixels.size != needed) pixels = ByteArray(needed)

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
            //
            // An immutable raster image rather than a reused Bitmap.
            // asComposeImageBitmap wraps a Bitmap lazily and calls
            // makeFromBitmap when Compose draws it — by which time this thread
            // is already writing the next frame into the same pixels. That
            // raced, failed on the drawing thread rather than this one, and
            // surfaced as an error dialog that no catch here could have caught.
            //
            // makeRaster takes its own copy, so what is published is finished
            // and cannot be written underneath.
            val image = Image.makeRaster(
                ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE),
                pixels,
                width * 4
            )
            current.set(image.toComposeImageBitmap())
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

    @Volatile
    private var finished = false

    /**
     * Nothing more will play: it ended of its own accord, or this was released.
     *
     * Both loops that watch playback spin on this, so a release has to end them
     * — otherwise they carry on asking a freed player for a position.
     */
    val ended: Boolean get() = finished || released

    init {
        player.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun finished(mediaPlayer: MediaPlayer) {
                finished = true
            }

            override fun error(mediaPlayer: MediaPlayer) {
                BuildInfo.log("DebritsuVlc", "playback error")
                finished = true
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

        // A forced track is not the dialogue. It carries signs and song lyrics
        // for scenes the audio does not cover, shows text only now and then,
        // and reads as broken subtitles rather than as the wrong track — which
        // is exactly what this picked: "Inglese (force)" out of a file that
        // also had "Inglese (Dialog)".
        //
        // The Android build has the same trap and the same note about it: anime
        // releases ship two English subtitle tracks and the signs one is
        // routinely flagged forced or default, so it wins unless that is
        // ignored deliberately.
        fun isForced(name: String): Boolean {
            val n = name.lowercase()
            return "force" in n || "forced" in n || "signs" in n || "songs" in n
        }

        // Captions for the deaf and hard of hearing: the same dialogue, plus
        // "[door creaks]" and speaker labels for everything the audio carries.
        // Correct subtitles, and not the ones anybody watching with sound
        // wants — but they sort before the dialogue track in a release that
        // ships both, so left alone they simply won. A release that has only
        // SDH still gets it, below.
        fun isDescriptive(name: String): Boolean {
            val n = name.lowercase()
            return "sdh" in n || "cc" in n.split(' ', '(', ')', '[', ']', '-')
        }

        fun isEnglish(name: String): Boolean {
            val n = name.lowercase()
            return "eng" in n || "english" in n || preferred.lowercase() in n
        }

        fun usable(name: String) = !isForced(name) && !isDescriptive(name)

        val wanted = real.firstOrNull { isEnglish(it.second) && usable(it.second) }
            ?: real.firstOrNull { isEnglish(it.second) && !isForced(it.second) }
            ?: real.firstOrNull { usable(it.second) }
            ?: real.firstOrNull { !isForced(it.second) }
            ?: real.first()

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

    val playing: Boolean
        get() = !released && runCatching { player.status().isPlaying }.getOrDefault(false)

    /**
     * The last position and duration seen while the player was alive.
     *
     * Kept because the screen saves the resume position on its way out, and by
     * then Back has already released the player — so asking it would give
     * nothing and the spot would be lost every time somebody left mid-episode.
     */
    @Volatile private var lastPosition = -1L
    @Volatile private var lastDuration = -1L

    fun positionMs(): Long {
        if (released) return lastPosition
        val at = runCatching { player.status().time() }.getOrDefault(-1L)
        if (at >= 0) lastPosition = at
        return at
    }

    fun durationMs(): Long {
        if (released) return lastDuration
        val of = runCatching { player.status().length() }.getOrDefault(-1L)
        if (of > 0) lastDuration = of
        return of
    }

    fun setPaused(paused: Boolean) { if (!released) runCatching { player.controls().setPause(paused) } }
    fun seekTo(ms: Long) { if (!released) runCatching { player.controls().setTime(ms) } }
    fun seekBy(seconds: Int) { if (!released) runCatching { player.controls().skipTime(seconds * 1000L) } }
    fun setVolume(percent: Int) { if (!released) runCatching { player.audio().setVolume(percent.coerceIn(0, 125)) } }
    fun volume(): Int = if (released) 0 else runCatching { player.audio().volume() }.getOrDefault(100)
    fun muted(): Boolean = !released && runCatching { player.audio().isMute }.getOrDefault(false)
    fun setMuted(muted: Boolean) { if (!released) runCatching { player.audio().setMute(muted) } }

    /** Track lists, for pickers that name what they are choosing between. */
    fun subtitleTracks(): List<Pair<Int, String>> =
        if (released) emptyList()
        else runCatching { player.subpictures().trackDescriptions().map { it.id() to it.description() } }
            .getOrDefault(emptyList())

    fun audioTracks(): List<Pair<Int, String>> =
        if (released) emptyList()
        else runCatching { player.audio().trackDescriptions().map { it.id() to it.description() } }
            .getOrDefault(emptyList())

    fun subtitleTrack(): Int = if (released) -1 else runCatching { player.subpictures().track() }.getOrDefault(-1)
    fun setSubtitleTrack(id: Int) { if (!released) runCatching { player.subpictures().setTrack(id) } }
    fun audioTrack(): Int = if (released) -1 else runCatching { player.audio().track() }.getOrDefault(-1)
    fun setAudioTrack(id: Int) { if (!released) runCatching { player.audio().setTrack(id) } }

    /**
     * Frees libVLC, once nothing is inside it.
     *
     * The order is the whole point. The flag goes up first, so every accessor
     * and the render callback turn into no-ops from this instant; then the lock
     * is taken, which waits for a frame already being copied to finish; only
     * then is anything freed. Releasing first and hoping — which is what this
     * did — is a segfault whenever a frame or a position poll lands in the
     * window between, and at 60 frames a second that is most of the time.
     */
    @Synchronized
    fun release() {
        if (released) return
        released = true
        publishing = false

        // Empty on purpose: this is a barrier, not work. It returns once no
        // frame is in the callback, and none can enter now.
        synchronized(frameLock) { }

        runCatching { nativeLog?.release() }
        runCatching { player.controls().stop() }
        runCatching { player.release() }
        runCatching { factory.release() }
    }
}
