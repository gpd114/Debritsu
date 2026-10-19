package com.debritsu.app.player

import android.graphics.Bitmap
import android.util.Log
import com.debritsu.app.BuildConfig
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.Cue
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import java.util.zip.Inflater

/**
 * A PGS subtitle parser that keeps the palette between display sets.
 *
 * Adapted from androidx.media3.extractor.text.pgs.PgsParser, © The Android Open
 * Source Project, Apache License 2.0. One behaviour differs, described below;
 * the segment handling and run-length bitmap decoding are theirs.
 *
 * PGS subtitles are images, and each carries an index into a palette rather than
 * colours directly. A display set may omit the palette segment and rely on the
 * one sent earlier, which encoders do whenever consecutive subtitles are drawn
 * in the same colours — that is, throughout a run of ordinary dialogue.
 *
 * media3 clears a `colorsSet` flag before every display set and refuses to build
 * a cue while it is false, though it never clears the palette those colours live
 * in. So a subtitle relying on an earlier palette is discarded despite the
 * colours being present and correct, silently and with nothing logged. On one
 * episode measured, 20 of 426 subtitles vanished this way, clustered into rapid
 * exchanges where two characters speak over one another — precisely where a
 * viewer can least afford to lose a line.
 *
 * The fix is to remember that a palette has been seen at all, and to let a cue
 * build on that. VLC behaves this way, which is why it renders these files
 * correctly where media3 and ffmpeg do not.
 */
@UnstableApi
class LenientPgsParser : SubtitleParser {

    private val buffer = ParsableByteArray()
    private val inflatedBuffer = ParsableByteArray()
    private val cueBuilder = CueBuilder()
    private var inflater: Inflater? = null

    override fun getCueReplacementBehavior(): @Format.CueReplacementBehavior Int =
        Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE

    override fun parse(
        data: ByteArray,
        offset: Int,
        length: Int,
        outputOptions: SubtitleParser.OutputOptions,
        output: Consumer<CuesWithTiming>
    ) {
        buffer.reset(data, offset + length)
        buffer.setPosition(offset)
        maybeInflateData(buffer)
        cueBuilder.reset()

        val cues = ArrayList<Cue>()
        while (buffer.bytesLeft() >= 3) {
            readNextSection(buffer, cueBuilder)?.let { cues.add(it) }
        }
        output.accept(CuesWithTiming(cues, C.TIME_UNSET, C.TIME_UNSET))
    }

    override fun reset() {
        cueBuilder.forgetPalette()
    }

    private fun maybeInflateData(buffer: ParsableByteArray) {
        if (buffer.bytesLeft() > 0 && buffer.peekUnsignedByte() == INFLATE_HEADER) {
            val i = inflater ?: Inflater().also { inflater = it }
            if (Util.inflate(buffer, inflatedBuffer, i)) {
                buffer.reset(inflatedBuffer.data, inflatedBuffer.limit())
            } // else assume the data is not compressed.
        }
    }

    private fun readNextSection(buffer: ParsableByteArray, cueBuilder: CueBuilder): Cue? {
        val limit = buffer.limit()
        val sectionType = buffer.readUnsignedByte()
        val sectionLength = buffer.readUnsignedShort()

        val nextSectionPosition = buffer.position + sectionLength
        if (nextSectionPosition > limit) {
            buffer.setPosition(limit)
            return null
        }

        var cue: Cue? = null
        when (sectionType) {
            SECTION_TYPE_PALETTE -> cueBuilder.parsePaletteSection(buffer, sectionLength)
            SECTION_TYPE_BITMAP_PICTURE -> cueBuilder.parseBitmapSection(buffer, sectionLength)
            SECTION_TYPE_IDENTIFIER -> cueBuilder.parseIdentifierSection(buffer, sectionLength)
            SECTION_TYPE_END -> {
                cue = cueBuilder.build()
                cueBuilder.reset()
            }
        }

        buffer.setPosition(nextSectionPosition)
        return cue
    }

    private class CueBuilder {
        private val bitmapData = ParsableByteArray()
        private val colors = IntArray(256)

        /** True once any palette has been read, and deliberately not cleared by [reset]. */
        private var paletteEverSet = false
        private var planeWidth = 0
        private var planeHeight = 0
        private var bitmapX = 0
        private var bitmapY = 0
        private var bitmapWidth = 0
        private var bitmapHeight = 0

        fun parsePaletteSection(buffer: ParsableByteArray, sectionLength: Int) {
            // Two bytes then a whole number of (index, Y, Cr, Cb, alpha) entries.
            if ((sectionLength % 5) != 2) return
            buffer.skipBytes(2)

            colors.fill(0)
            val entryCount = sectionLength / 5
            repeat(entryCount) {
                val index = buffer.readUnsignedByte()
                val y = buffer.readUnsignedByte()
                val cr = buffer.readUnsignedByte()
                val cb = buffer.readUnsignedByte()
                val a = buffer.readUnsignedByte()
                val r = (y + 1.40200 * (cr - 128)).toInt()
                val g = (y - 0.34414 * (cb - 128) - 0.71414 * (cr - 128)).toInt()
                val b = (y + 1.77200 * (cb - 128)).toInt()
                colors[index] = (a shl 24) or
                    (Util.constrainValue(r, 0, 255) shl 16) or
                    (Util.constrainValue(g, 0, 255) shl 8) or
                    Util.constrainValue(b, 0, 255)
            }
            paletteEverSet = true
        }

        fun parseBitmapSection(buffer: ParsableByteArray, length: Int) {
            var sectionLength = length
            if (sectionLength < 4) return
            buffer.skipBytes(3) // Id (2 bytes), version (1 byte).
            val isBaseSection = (0x80 and buffer.readUnsignedByte()) != 0
            sectionLength -= 4

            if (isBaseSection) {
                if (sectionLength < 7) return
                val totalLength = buffer.readUnsignedInt24()
                if (totalLength < 4) return
                bitmapWidth = buffer.readUnsignedShort()
                bitmapHeight = buffer.readUnsignedShort()
                bitmapData.reset(totalLength - 4)
                sectionLength -= 7
            }

            val position = bitmapData.position
            val limit = bitmapData.limit()
            if (position < limit && sectionLength > 0) {
                val bytesToRead = minOf(sectionLength, limit - position)
                buffer.readBytes(bitmapData.data, position, bytesToRead)
                bitmapData.setPosition(position + bytesToRead)
            }
        }

        fun parseIdentifierSection(buffer: ParsableByteArray, sectionLength: Int) {
            if (sectionLength < 19) return
            planeWidth = buffer.readUnsignedShort()
            planeHeight = buffer.readUnsignedShort()
            buffer.skipBytes(11)
            bitmapX = buffer.readUnsignedShort()
            bitmapY = buffer.readUnsignedShort()
        }

        fun build(): Cue? {
            // The only departure from media3: it requires a palette in this very
            // display set, where this accepts one carried over from an earlier
            // one. Everything else still has to be present and complete.
            if (planeWidth == 0 ||
                planeHeight == 0 ||
                bitmapWidth == 0 ||
                bitmapHeight == 0 ||
                bitmapData.limit() == 0 ||
                bitmapData.position != bitmapData.limit() ||
                !paletteEverSet
            ) {
                return null
            }

            bitmapData.setPosition(0)
            val argb = IntArray(bitmapWidth * bitmapHeight)
            var i = 0
            while (i < argb.size) {
                val colorIndex = bitmapData.readUnsignedByte()
                if (colorIndex != 0) {
                    argb[i++] = colors[colorIndex]
                } else {
                    val switchBits = bitmapData.readUnsignedByte()
                    if (switchBits != 0) {
                        val runLength = if ((switchBits and 0x40) == 0) {
                            switchBits and 0x3F
                        } else {
                            ((switchBits and 0x3F) shl 8) or bitmapData.readUnsignedByte()
                        }
                        val color = if ((switchBits and 0x80) == 0) {
                            colors[0]
                        } else {
                            colors[bitmapData.readUnsignedByte()]
                        }
                        argb.fill(color, i, i + runLength)
                        i += runLength
                    }
                }
            }

            val bitmap = Bitmap.createBitmap(argb, bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
            return Cue.Builder()
                .setBitmap(bitmap)
                .setPosition(bitmapX.toFloat() / planeWidth)
                .setPositionAnchor(Cue.ANCHOR_TYPE_START)
                .setLine(bitmapY.toFloat() / planeHeight, Cue.LINE_TYPE_FRACTION)
                .setLineAnchor(Cue.ANCHOR_TYPE_START)
                .setSize(bitmapWidth.toFloat() / planeWidth)
                .setBitmapHeight(bitmapHeight.toFloat() / planeHeight)
                .build()
        }

        /** Clears the geometry between display sets. The palette deliberately survives. */
        fun reset() {
            planeWidth = 0
            planeHeight = 0
            bitmapX = 0
            bitmapY = 0
            bitmapWidth = 0
            bitmapHeight = 0
            bitmapData.reset(0)
        }

        /** Drops the palette too, for a seek or a new track where it no longer applies. */
        fun forgetPalette() {
            reset()
            colors.fill(0)
            paletteEverSet = false
        }
    }

    /**
     * Uses the lenient parser for PGS, hands ASS to [ass] where there is one —
     * libass, which draws what media3's own parser drops — and defers to media3
     * for everything else, with every parser wrapped in [Forgiving].
     */
    @UnstableApi
    class Factory(ass: SubtitleParser.Factory? = null) : SubtitleParser.Factory {
        // libass's factory already falls back to media3's for anything that is
        // not ASS, so it can stand in for the default wholesale.
        private val fallback = ass ?: DefaultSubtitleParserFactory()

        private fun isPgs(format: Format) =
            MimeTypes.APPLICATION_PGS.equals(format.sampleMimeType, ignoreCase = true)

        override fun supportsFormat(format: Format): Boolean =
            (isPgs(format) || fallback.supportsFormat(format)).also {
                if (BuildConfig.DEBUG && !it) {
                    Log.d("DebritsuSubs", "no parser for mime=${format.sampleMimeType} lang=${format.language}")
                }
            }

        override fun getCueReplacementBehavior(format: Format): @Format.CueReplacementBehavior Int =
            if (isPgs(format)) Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE
            else fallback.getCueReplacementBehavior(format)

        override fun create(format: Format): SubtitleParser {
            val parser = if (isPgs(format)) LenientPgsParser() else fallback.create(format)
            if (BuildConfig.DEBUG) {
                Log.d(
                    "DebritsuSubs",
                    "parser ${parser.javaClass.simpleName} for mime=${format.sampleMimeType} " +
                        "lang=${format.language} label=${format.label} " +
                        "codecs=${format.codecs} init=${format.initializationData.size}"
                )
                // An ASS track declares its styles in the header. A signs-only
                // track names them Sign, Caption, OP, ED and nothing resembling
                // dialogue, which is the one thing about it knowable before a
                // frame has played.
                format.initializationData.forEach { data ->
                    val text = runCatching { String(data) }.getOrDefault("")
                    val styles = text.lineSequence()
                        .filter { it.startsWith("Style:") || it.startsWith("Title:") }
                        .map { it.take(60) }
                        .joinToString(" / ")
                    if (styles.isNotEmpty()) {
                        Log.d("DebritsuSubs", "header ${format.language}/${format.label}: $styles")
                    }
                }
            }
            return Forgiving(parser, format)
        }
    }

    /**
     * Tracks whose samples turned up zlib-compressed, by track.
     *
     * Matroska lets a track be compressed; media3 supports only header
     * stripping, so such a track reaches libass as packed bytes and nothing is
     * drawn — every language silent, with no error anywhere. The samples are
     * inflated here instead and parsed as ordinary text, which costs the
     * typesetting libass would have done: it was handed the packed copy long
     * before this point and cannot be given another.
     *
     * The picker reads this to say so rather than leaving someone to wonder.
     */
    object Compressed {
        private val seen = java.util.Collections.synchronizedSet(mutableSetOf<String>())

        // Language and label, never the id: the track the parser is handed and
        // the track the player reports are the same track in two shapes — the
        // first text/x-ssa, the second x-media3-cues once parsed during
        // extraction — and their ids do not agree. Matching on the id meant the
        // player never recognised a track it had itself just unpacked, so the
        // group behind it was never learnt.
        private fun key(format: Format) = "${format.language}/${format.label}"

        fun mark(format: Format) { seen += key(format) }

        operator fun contains(format: Format) = key(format) in seen

        /** Cleared when a new file loads, since it is about this file only. */
        fun forget() = seen.clear()
    }

    /**
     * A subtitle line its parser rejects is dropped, and playback carries on.
     *
     * Subtitles embedded in a video are parsed while the video is read, on the
     * same loader, so anything a parser throws is a loader failure and ends
     * the whole episode — reported as "Couldn't play this source", partway
     * through and at the same point on every attempt, which reads exactly like
     * a corrupt file and is not one.
     *
     * Measured on a Pixel 10: media3 1.4.1's SsaParser.parse ends in a bare
     * `throw IllegalStateException()` (SsaParser.java:166) whenever an event's
     * last start time still has cues, which is what a line whose end is not
     * after its start produces. Some encoders write such lines into MKVs. A
     * line like that could never have been on screen anyway, so losing it
     * costs nothing; losing the episode to it cost the rest of the episode.
     *
     * Every format goes through this, not only ASS: the side-loaded files from
     * subtitle addons are parsed by the same factory, and LenientPgsParser does
     * no bounds checking of its own on the bitmaps it decodes.
     */
    @UnstableApi
    private class Forgiving(private val inner: SubtitleParser, private val format: Format) : SubtitleParser {
        // Only built if a compressed sample actually turns up.
        private val plain by lazy(LazyThreadSafetyMode.NONE) {
            runCatching { DefaultSubtitleParserFactory().create(format) }.getOrNull()
        }
        private val inflater = Inflater()
        // Debug only: how many lines this track has produced. Every track is
        // parsed as the file is read, selected or not, so this counts them all
        // — a dialogue track runs to hundreds where a signs one runs to a few.
        private var lines = 0

        override fun parse(
            data: ByteArray,
            offset: Int,
            length: Int,
            outputOptions: SubtitleParser.OutputOptions,
            output: Consumer<CuesWithTiming>
        ) {
            if (BuildConfig.DEBUG) {
                lines++
                if (lines < 3) {
                    val head = String(data, offset, minOf(length, 70)).replace(Regex("[\r\n]+"), " ")
                    Log.d("DebritsuSubs", "line $lines ${format.language}/${format.label}: $head")
                } else if (lines % 25 == 0) {
                    Log.d("DebritsuSubs", "lines $lines for ${format.language}/${format.label}")
                }
            }
            // A compressed sample goes to the ordinary parser, inflated. The
            // ASS parser in front of libass would only discard it: libass draws
            // those tracks itself, and what it was handed is the packed copy.
            val packed = zlibStart(data, offset, length)
            if (packed >= 0) {
                val body = inflate(inflater, data, packed, offset + length)
                val parser = plain
                if (body != null && parser != null) {
                    Compressed.mark(format)
                    val head = packed - offset
                    val line = ByteArray(head + body.size)
                    System.arraycopy(data, offset, line, 0, head)
                    System.arraycopy(body, 0, line, head, body.size)
                    runCatching { parser.parse(line, 0, line.size, outputOptions, output) }
                    return
                }
            }
            try {
                inner.parse(data, offset, length, outputOptions, output)
            } catch (e: RuntimeException) {
                if (BuildConfig.DEBUG) {
                    Log.w("DebritsuSubs", "dropped a ${format.sampleMimeType} line its parser rejected", e)
                }
            }
        }

        override fun getCueReplacementBehavior(): @Format.CueReplacementBehavior Int =
            inner.cueReplacementBehavior

        override fun reset() = inner.reset()
    }

    private companion object {
        const val SECTION_TYPE_PALETTE = 0x14
        const val SECTION_TYPE_BITMAP_PICTURE = 0x15
        const val SECTION_TYPE_IDENTIFIER = 0x16
        const val SECTION_TYPE_END = 0x80
        const val INFLATE_HEADER = 0x78
    }
}

/**
 * Where the zlib stream starts inside a subtitle sample, or -1.
 *
 * media3 rebuilds an SSA line as "Dialogue: <start>,<end>," and then the
 * track's own bytes, so a compressed track carries the packed data after that
 * prefix rather than at the front; a compressed SRT has it at the front. Both
 * are looked for.
 *
 * The two-byte zlib header carries its own check — read big-endian, the pair
 * is a multiple of 31 — which keeps ordinary text beginning with "x" out of
 * it.
 */
private fun zlibStart(data: ByteArray, offset: Int, length: Int): Int {
    val end = offset + length

    fun looksZlib(at: Int): Boolean {
        if (at + 1 >= end) return false
        if (data[at] != 0x78.toByte()) return false
        val header = (data[at].toInt() and 0xFF shl 8) or (data[at + 1].toInt() and 0xFF)
        return header % 31 == 0
    }

    if (looksZlib(offset)) return offset
    var commas = 0
    var i = offset
    while (i < end && i - offset <= 64) {
        if (data[i] == ','.code.toByte()) {
            commas++
            // "Dialogue: <start>,<end>," — the track's own bytes follow.
            if (commas == 2) return if (looksZlib(i + 1)) i + 1 else -1
        }
        i++
    }
    return -1
}

/** The zlib stream from [from] to [end], or null if it will not inflate. */
private fun inflate(inflater: Inflater, data: ByteArray, from: Int, end: Int): ByteArray? {
    inflater.reset()
    inflater.setInput(data, from, end - from)
    val out = java.io.ByteArrayOutputStream(maxOf(64, (end - from) * 4))
    val chunk = ByteArray(1024)
    return runCatching {
        while (!inflater.finished()) {
            val n = inflater.inflate(chunk)
            if (n == 0) {
                if (inflater.needsInput() || inflater.needsDictionary()) break
            } else {
                out.write(chunk, 0, n)
            }
        }
        out.toByteArray().takeIf { it.isNotEmpty() }
    }.getOrNull()
}
