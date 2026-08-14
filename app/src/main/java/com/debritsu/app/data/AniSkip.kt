package com.debritsu.app.data

import com.debritsu.app.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.Request

/**
 * Opening and ending timestamps, from the community database behind the skip
 * buttons in most anime players.
 *
 * Keyed by MyAnimeList id and episode number — the same pair the filler and
 * recap flags already resolve — so this costs one extra request per episode and
 * no new mapping work. Coverage is contributed rather than complete: popular
 * shows are usually there, a brand new episode often isn't, and nothing is
 * shown when the answer is unknown.
 */
object AniSkip {

    /** One stretch worth skipping, in player time. */
    data class Segment(val kind: String, val startMs: Long, val endMs: Long) {
        val label: String get() = if (kind == "ed") "Skip ending" else "Skip intro"
    }

    @Serializable
    private data class Response(
        val found: Boolean = false,
        val results: List<Entry> = emptyList()
    )

    @Serializable
    private data class Entry(
        val interval: Interval? = null,
        val skipType: String = ""
    )

    @Serializable
    private data class Interval(
        val startTime: Double = 0.0,
        val endTime: Double = 0.0
    )

    /**
     * [durationMs] lets the service scale timings to this particular encode;
     * zero is accepted and simply means "unknown", which is the case before
     * the player has worked out how long the file is.
     */
    suspend fun segments(malId: Int?, episode: Int, durationMs: Long): List<Segment> =
        withContext(Dispatchers.IO) {
            if (malId == null || malId <= 0 || episode <= 0) return@withContext emptyList()

            runCatching {
                val seconds = (durationMs / 1000).coerceAtLeast(0)
                val request = Request.Builder()
                    .url(
                        "https://api.aniskip.com/v2/skip-times/$malId/$episode" +
                            "?types=op&types=ed&episodeLength=$seconds"
                    )
                    .build()

                val body = Http.client.newCall(request).execute().use {
                    if (!it.isSuccessful) return@runCatching emptyList<Segment>()
                    it.body?.string().orEmpty()
                }

                val parsed = json.decodeFromString(Response.serializer(), body)
                if (!parsed.found) return@runCatching emptyList<Segment>()

                parsed.results.mapNotNull { entry ->
                    val span = entry.interval ?: return@mapNotNull null
                    if (span.endTime <= span.startTime) return@mapNotNull null
                    Segment(
                        kind = entry.skipType,
                        startMs = (span.startTime * 1000).toLong(),
                        endMs = (span.endTime * 1000).toLong()
                    )
                }
            }.getOrDefault(emptyList())
        }
}
