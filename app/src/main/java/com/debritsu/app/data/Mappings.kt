package com.debritsu.app.data

import com.debritsu.app.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * Stremio addons index anime by Kitsu ID (and films/live-action by IMDb ID),
 * while AniList uses its own IDs. api.ani.zip provides the cross-map.
 */
object Mappings {

    data class Ids(val kitsu: String?, val imdb: String?, val mal: String?)

    private val cache = mutableMapOf<Int, Ids>()

    suspend fun forAniList(anilistId: Int): Ids = withContext(Dispatchers.IO) {
        cache[anilistId]?.let { return@withContext it }
        val req = Request.Builder()
            .url("https://api.ani.zip/mappings?anilist_id=$anilistId")
            .build()
        val ids = runCatching {
            Http.client.newCall(req).execute().use { res ->
                val root = json.parseToJsonElement(res.body?.string().orEmpty())
                val m = root.obj("mappings")
                Ids(
                    kitsu = m.int("kitsu_id")?.toString() ?: m.str("kitsu_id"),
                    imdb = m.str("imdb_id"),
                    mal = m.int("mal_id")?.toString()
                )
            }
        }.getOrDefault(Ids(null, null, null))
        cache[anilistId] = ids
        ids
    }
}
