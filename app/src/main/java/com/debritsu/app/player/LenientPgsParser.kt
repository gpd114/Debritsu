package com.debritsu.app.player

import android.graphics.Bitmap
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

    /** Uses the lenient parser for PGS and defers to media3 for everything else. */
    @UnstableApi
    class Factory : SubtitleParser.Factory {
        private val fallback = DefaultSubtitleParserFactory()

        private fun isPgs(format: Format) =
            MimeTypes.APPLICATION_PGS.equals(format.sampleMimeType, ignoreCase = true)

        override fun supportsFormat(format: Format): Boolean =
            isPgs(format) || fallback.supportsFormat(format)

        override fun getCueReplacementBehavior(format: Format): @Format.CueReplacementBehavior Int =
            if (isPgs(format)) Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE
            else fallback.getCueReplacementBehavior(format)

        override fun create(format: Format): SubtitleParser =
            if (isPgs(format)) LenientPgsParser() else fallback.create(format)
    }

    private companion object {
        const val SECTION_TYPE_PALETTE = 0x14
        const val SECTION_TYPE_BITMAP_PICTURE = 0x15
        const val SECTION_TYPE_IDENTIFIER = 0x16
        const val SECTION_TYPE_END = 0x80
        const val INFLATE_HEADER = 0x78
    }
}
