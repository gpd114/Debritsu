package com.debritsu.app.data

import kotlinx.serialization.Serializable

data class Anime(
    val id: Int,
    val title: String,
    val cover: String?,
    val banner: String? = null,
    val episodes: Int? = null,
    val description: String? = null,
    val progress: Int = 0,
    /** AniList list status: CURRENT, PLANNING, COMPLETED, DROPPED, PAUSED, REPEATING. */
    val listStatus: String? = null,
    /** Id of the user's list entry, needed to delete it. */
    val entryId: Int? = null,
    val score: Double = 0.0
)

/** A prequel, sequel or side story hanging off a title. */
data class Relation(val anime: Anime, val type: String)

@Serializable
data class Subtitle(val url: String, val lang: String)

@Serializable
data class StreamOption(
    val addon: String,
    val name: String,
    val description: String,
    val url: String?,
    val infoHash: String?,
    val fileIdx: Int?,
    val subtitles: List<Subtitle> = emptyList()
) {
    /** True when the link is already an HTTP(S) stream (e.g. a debrid direct link). */
    val isDirect: Boolean get() = url?.startsWith("http") == true
}
