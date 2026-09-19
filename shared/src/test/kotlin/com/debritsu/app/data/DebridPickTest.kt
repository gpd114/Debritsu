package com.debritsu.app.data

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The file picked out of a resolved torrent.
 *
 * Worth testing because it cannot be tested any other way: everything around it
 * needs a debrid account, and the cost of getting it wrong is an episode from
 * another season that plays perfectly.
 *
 * The names here are real ones, from what Torrentio returns for DanMachi.
 */
class DebridPickTest {

    private fun file(name: String, size: Long = 0L) = RemoteFile(name, name, size, "link:$name")

    /** A season pack as a provider lists it: no folders, extras dropped. */
    private val pack = listOf(
        file("DanMachi S02E09.mkv", 340L * 1024 * 1024),
        file("DanMachi S02E10.mkv", 350L * 1024 * 1024),
        file("DanMachi S02E11.mkv", 363L * 1024 * 1024),
        file("DanMachi S02E12.mkv", 370L * 1024 * 1024)
    )

    @Test
    fun `takes the file the addon named, not its index`() {
        // The addon counted folders and extras, so its index is four out.
        val chosen = Debrid.pick(pack, fileIdx = 0, filename = "Season 2/DanMachi S02E11.mkv")
        assertEquals("DanMachi S02E11.mkv", chosen.name)
    }

    @Test
    fun `matches a name the provider rewrote`() {
        val renamed = listOf(
            file("DanMachi.S02E10.1080p.mkv"),
            file("DanMachi.S02E11.1080p.mkv")
        )
        val chosen = Debrid.pick(renamed, fileIdx = 0, filename = "DanMachi S02E11 1080p.mkv")
        assertEquals("DanMachi.S02E11.1080p.mkv", chosen.name)
    }

    @Test
    fun `falls back to the episode number when names do not match`() {
        val chosen = Debrid.pick(
            pack,
            fileIdx = 0,
            filename = "[Group] Dungeon ni Deai - S02E11 [BD][1080p].mkv"
        )
        assertEquals("DanMachi S02E11.mkv", chosen.name)
    }

    @Test
    fun `reads a bare episode number as fansubs write it`() {
        val fansub = listOf(
            file("[sam] Dungeon ni Deai - 10 [BD 1080p FLAC].mkv"),
            file("[sam] Dungeon ni Deai - 11 [BD 1080p FLAC].mkv")
        )
        val chosen = Debrid.pick(fansub, fileIdx = 0, filename = "Dungeon ni Deai II - 11 [1080p].mkv")
        assertEquals("[sam] Dungeon ni Deai - 11 [BD 1080p FLAC].mkv", chosen.name)
    }

    @Test
    fun `uses the size the addon quoted when the name is no help`() {
        val chosen = Debrid.pick(pack, fileIdx = 0, filename = "totally-different-name.mkv", sizeMb = 363)
        assertEquals("DanMachi S02E11.mkv", chosen.name)
    }

    @Test
    fun `honours the index when nothing else identifies the file`() {
        val chosen = Debrid.pick(pack, fileIdx = 2, filename = null)
        assertEquals("DanMachi S02E11.mkv", chosen.name)
    }

    @Test
    fun `takes the largest video when there is nothing to go on`() {
        val withExtras = pack + file("readme.nfo", 900L * 1024 * 1024)
        val chosen = Debrid.pick(withExtras, fileIdx = null, filename = null)
        assertEquals("DanMachi S02E12.mkv", chosen.name)
    }

    @Test
    fun `an episode in a multi-season pack is not confused with another season`() {
        val seasons = listOf(
            file("Season 1/Show S01E11.mkv", 300L * 1024 * 1024),
            file("Season 2/Show S02E11.mkv", 363L * 1024 * 1024),
            file("Season 3/Show S03E11.mkv", 400L * 1024 * 1024)
        )
        val chosen = Debrid.pick(seasons, fileIdx = 0, filename = "Show S02E11.mkv")
        assertEquals("Season 2/Show S02E11.mkv", chosen.name)
    }
}
