package com.debritsu.app.data

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Telling a show's own release from a spin-off's inside the same pack.
 *
 * Every name here is real: the filenames an addon returned for episodes 11 and
 * 12 of DanMachi's second season, and the file one of them actually served —
 * `DanMachi Sword Oratoria - 11`, which is the side series.
 */
class TitleMatchTest {

    /** What AniList holds for the second season: romaji, English, synonyms. */
    private val known = TitleMatch.known(
        listOf(
            "Dungeon ni Deai wo Motomeru no wa Machigatteiru Darou ka II",
            "Is It Wrong to Try to Pick Up Girls in a Dungeon? II",
            "Danmachi II",
            "Dungeon ni Deai wo Motomeru no wa Machigatteiru Darou ka 2",
            "Dungeon ni Deai o Motomeru no wa Machigatte Iru Darouka: Familia Myth II"
        )
    )

    private val correct = listOf(
        "Is It Wrong To Try To Pick Up Girls In A Dungeon - S02E11 - Rakia Army's Advance.mkv",
        "[Sokudo] Dungeon ni Deai wo Motomeru no wa Machigatteiru Darou ka II - S02E11 [1080p BD][AV1][dual audio].mkv",
        "[sam] Dungeon ni Deai wo Motomeru no wa Machigatteiru Darou ka II - 11 [BD 1080p FLAC] [2F5A456D].mkv",
        "Is.It.Wrong.to.Try.to.Pick.Up.Girls.in.a.Dungeon.S02E11.1080p.Blu-Ray.10-Bit.Dual-Audio.DTS-HD.x265-iAHD.mkv",
        "[DiabloTripleA] Is It Wrong To Try To Pick Up Girls In A Dungeon - S02E11.mkv",
        "[Erai-raws] DanMachi II - 11 [1080p][Multiple Subtitle].mkv",
        "[Cleo]Dungeon_ni_Deai_wo_Motomeru_no_wa_Machigatteiru_Darou_ka_II_-_11_(Dual Audio_10bit_1080p_x265).mkv",
        "[Pog42] DanMachi II - 11 (BD 1080p x265 Opus).mkv",
        "Danmachi S2 - 11 (Bd 1920X1080 X.265 Opus ).mkv",
        "DanMachi S02E12.mkv",
        "11.mkv"
    )

    private val wrongShow = listOf(
        "[Sokudo] DanMachi Sword Oratoria - 11 [1080p BD][AV1][dual audio].mkv",
        "Sword Oratoria S01E11.mkv",
        "[Group] Arrow of the Orion Movie.mkv"
    )

    @Test
    fun `the show's own releases are explained by its names`() {
        correct.forEach { name ->
            val share = TitleMatch.share(name, known)
            assertTrue(share >= 0.5, "only ${"%.2f".format(share)} of $name was explained")
        }
    }

    @Test
    fun `a spin-off's release is not`() {
        wrongShow.forEach { name ->
            val share = TitleMatch.share(name, known)
            assertTrue(share < 0.5, "${"%.2f".format(share)} of $name was explained, too much")
        }
    }

    @Test
    fun `a name that says nothing is left alone`() {
        assertTrue(TitleMatch.share("[Group] 11 [1080p].mkv", known) == 1.0)
    }
}
