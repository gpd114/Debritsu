package com.debritsu.app.data

import com.debritsu.app.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * Filler and recap flags, which AniList doesn't track.
 *
 * MyAnimeList does, via Jikan. Results are cached per title because Jikan is a
 * free community service with a low rate limit — roughly three requests a
 * second — and an episode list never changes for a finished show.
 */
object Jikan {

    data class EpisodeMeta(val title: String?, val filler: Boolean, val recap: Boolean)

    private val cache = mutableMapOf<Int, Map<Int, EpisodeMeta>>()

    /** Keyed by episode number. Empty map when unknown — never blocks playback. */
    suspend fun episodes(malId: Int?): Map<Int, EpisodeMeta> = withContext(Dispatchers.IO) {
        if (malId == null) return@withContext emptyMap()
        cache[malId]?.let { return@withContext it }

        val out = mutableMapOf<Int, EpisodeMeta>()
        runCatching {
            // MAL paginates at 100 episodes; two pages covers all but the giants.
            for (page in 1..2) {
                val req = Request.Builder()
                    .url("https://api.jikan.moe/v4/anime/$malId/episodes?page=$page")
                    .build()
                val more = Http.meta.newCall(req).execute().use { res ->
                    if (!res.isSuccessful) return@use false
                    val root = json.parseToJsonElement(res.body?.string().orEmpty())
                    root.arr("data")?.forEach { e ->
                        val n = e.int("mal_id") ?: return@forEach
                        out[n] = EpisodeMeta(
                            title = e.str("title"),
                            filler = e.str("filler") == "true",
                            recap = e.str("recap") == "true"
                        )
                    }
                    root.obj("pagination").str("has_next_page") == "true"
                }
                if (!more) break
            }
        }
        if (out.isNotEmpty()) cache[malId] = out
        out
    }
}
