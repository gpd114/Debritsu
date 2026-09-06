package com.debritsu.desktop

import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat
import java.nio.ByteBuffer

/**
 * Where in its padded buffer libVLC actually puts the picture.
 *
 * Throwaway. The player crops the buffer down to the video's real height
 * because VLC asks for an aligned one — 1080 becomes 1088 — and drawing the
 * spare rows shows as a strip under the video. Cropping assumes the picture
 * sits at the top of that buffer. If it does not, the crop takes rows off the
 * wrong end, which is what "cuts a bit at the bottom" would be.
 *
 * Guessing at this has already cost two wrong fixes elsewhere, and the file in
 * question is on disk, so measure it: dump how many rows at each end are blank
 * and where the picture actually starts and stops.
 */
fun main(args: Array<String>) {
    val path = args.firstOrNull() ?: error("give a file to probe")
    Vlc.prepare()

    val factory = MediaPlayerFactory("--intf=dummy", "--no-audio", "--avcodec-hw=none", "--quiet")
    val player = factory.mediaPlayers().newEmbeddedMediaPlayer()

    val reported = java.util.concurrent.atomic.AtomicBoolean(false)
    var seen = 0

    val format = object : BufferFormatCallback {
        override fun getBufferFormat(width: Int, height: Int): BufferFormat {
            println("getBufferFormat asked for ${width}x$height")
            return RV32BufferFormat(width, height)
        }

        override fun allocatedBuffers(buffers: Array<out ByteBuffer>) {
            println("allocated ${buffers.size} buffer(s), capacity ${buffers.firstOrNull()?.capacity()}")
        }
    }

    val render = RenderCallback { _, buffers, fmt ->
        seen++
        // Not the first frame: the first few of a fade-in are black all over,
        // which would say every row is blank and answer nothing.
        if (reported.get() || seen < 200) return@RenderCallback
        reported.set(true)

        val w = fmt.width
        val h = fmt.height
        val pitch = w * 4
        val bytes = ByteArray(pitch * h)
        buffers[0].rewind()
        buffers[0].get(bytes, 0, minOf(bytes.size, buffers[0].remaining()))

        fun rowIsBlank(y: Int): Boolean {
            val start = y * pitch
            for (x in start until start + pitch) if (bytes[x].toInt() != 0) return false
            return true
        }

        fun rowMean(y: Int): Int {
            val start = y * pitch
            var sum = 0L
            for (x in start until start + pitch) sum += (bytes[x].toInt() and 0xFF)
            return (sum / pitch).toInt()
        }

        val blank = (0 until h).map { rowIsBlank(it) }
        val leading = blank.takeWhile { it }.size
        val trailing = blank.reversed().takeWhile { it }.size

        println("--- frame $seen, buffer ${w}x$h, pitch $pitch ---")
        println("blank rows: $leading at the top, $trailing at the bottom")
        println("first non-blank row ${blank.indexOfFirst { !it }}, last ${blank.indexOfLast { !it }}")
        println("row means: " + listOf(0, 1, 2, 3, 4, 7, 8, 1076, 1079, 1080, 1083, 1087)
            .filter { it < h }
            .joinToString { "$it=${rowMean(it)}" })

        // The question the means cannot answer: are the rows past the video's
        // real height a copy of the last one — padding, which is safe to crop —
        // or more picture, which means VLC scaled into the whole buffer and
        // cropping throws away image.
        fun sameRow(a: Int, b: Int): Boolean {
            val pa = a * pitch
            val pb = b * pitch
            for (x in 0 until pitch) if (bytes[pa + x] != bytes[pb + x]) return false
            return true
        }

        fun rowDiff(a: Int, b: Int): Int {
            val pa = a * pitch
            val pb = b * pitch
            var worst = 0
            for (x in 0 until pitch) {
                val d = kotlin.math.abs((bytes[pa + x].toInt() and 0xFF) - (bytes[pb + x].toInt() and 0xFF))
                if (d > worst) worst = d
            }
            return worst
        }

        val real = player.video().videoDimension()
        println("videoDimension() says ${real?.width}x${real?.height}")
        val edge = (real?.height ?: 1080).coerceIn(1, h - 1)
        println(
            "rows past $edge identical to row ${edge - 1}? " +
                ((edge until h).all { sameRow(it, edge - 1) })
        )
        println(
            "worst byte difference: ${edge - 1} vs $edge = ${rowDiff(edge - 1, edge)}, " +
                "${edge - 1} vs ${h - 1} = ${rowDiff(edge - 1, h - 1)}, " +
                "0 vs 1 = ${rowDiff(0, 1)}"
        )

        // A max over 7,680 bytes is one sharp edge away from meaningless. The
        // mean says whether the rows either side of the boundary belong to the
        // same picture.
        fun meanDiff(a: Int, b: Int): Int {
            val pa = a * pitch
            val pb = b * pitch
            var sum = 0L
            for (x in 0 until pitch) {
                sum += kotlin.math.abs((bytes[pa + x].toInt() and 0xFF) - (bytes[pb + x].toInt() and 0xFF))
            }
            return (sum / pitch).toInt()
        }
        println(
            "mean adjacent-row difference: " +
                listOf(500 to 501, 1077 to 1078, edge - 1 to edge, edge to edge + 1, h - 2 to h - 1)
                    .joinToString { (a, b) -> "$a/$b=${meanDiff(a, b)}" }
        )

        // And the thing that settles it beyond argument: look at it.
        val img = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val p = y * pitch + x * 4
                val b = bytes[p].toInt() and 0xFF
                val g = bytes[p + 1].toInt() and 0xFF
                val r = bytes[p + 2].toInt() and 0xFF
                img.setRGB(x, y, (r shl 16) or (g shl 8) or b)
            }
        }
        val out = java.io.File(System.getProperty("java.io.tmpdir"), "debritsu-frame.png")
        javax.imageio.ImageIO.write(img, "png", out)
        println("wrote $out")
    }

    player.videoSurface().set(factory.videoSurfaces().newVideoSurface(format, render, true))
    player.media().play(path, ":start-time=300")

    val until = System.currentTimeMillis() + 40_000
    while (System.currentTimeMillis() < until && !reported.get()) Thread.sleep(100)
    if (!reported.get()) println("no frame reported after $seen callbacks")

    player.controls().stop()
    player.release()
    factory.release()
    kotlin.system.exitProcess(0)
}
