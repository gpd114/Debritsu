package com.debritsu.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The group behind a filename, which decides whether a release is ranked down
 * for compressing its subtitles.
 *
 * These exist because the patterns shipped once with their escapes eaten. The
 * expression threw the moment it was built, scoring calls it for every source,
 * and v1.3.48 crashed on Resume — a release that could not play anything.
 * Nothing here is clever; it is the smoke alarm.
 */
class ReleaseGroupTest {

    @Test
    fun `reads a bracketed group at the front`() {
        assertEquals(
            "ironclad",
            SourceFilter.releaseGroup("[Ironclad] Daemons of the Shadow Realm - 09.mkv")
        )
        assertEquals("erai-raws", SourceFilter.releaseGroup("[Erai-raws] Show - 01 [1080p].mkv"))
    }

    @Test
    fun `reads a group after the last dash`() {
        assertEquals("ironclad", SourceFilter.releaseGroup("Show.S01E09.1080p.WEB-DL-Ironclad.mkv"))
        assertEquals("subsplease", SourceFilter.releaseGroup("Show.S01E09.1080p-SubsPlease"))
    }

    @Test
    fun `says nothing when the name carries no group`() {
        assertNull(SourceFilter.releaseGroup(null))
        assertNull(SourceFilter.releaseGroup("   "))
        assertNull(SourceFilter.releaseGroup("Episode 9"))
        // An addon's own title, which names a cache state and a resolution.
        assertNull(SourceFilter.releaseGroup("[TB⚡] Comet 1080p"))
    }

    @Test
    fun `ranks a known group below an equal release from anyone else`() {
        val filter = SourceFilter(
            maxResolution = 1080,
            maxSizeMb = 0,
            preferEnglish = false,
            plainSubtitleGroups = setOf("ironclad")
        )
        fun score(filename: String): Int {
            val stream = StreamOption("addon", filename, "1080p", "http://x", null, null, filename)
            return filter.score(stream, StreamMeta.of(stream))
        }
        val marked = score("[Ironclad] Show - 09 [1080p].mkv")
        val other = score("[Someone] Show - 09 [1080p].mkv")
        assert(marked < other) { "a marked group should rank below an equal release" }
        // ...but not so far that it loses to a worse picture.
        assert(marked > other - 360) { "the penalty should not cost a resolution tier" }
    }
}
