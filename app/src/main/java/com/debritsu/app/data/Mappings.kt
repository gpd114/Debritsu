package com.debritsu.app.data

import com.debritsu.app.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.net.URLEncoder

/**
 * Stremio anime addons index by Kitsu ID, AniList uses its own. This resolves
 * the gap, with fallbacks because recent shows are often missing from the
 * community mapping tables for weeks after they air.
 */
object Mappings {

    data class Ids(val kitsu: String?, val imdb: String?, val mal: String?) {
        val any: Boolean get() = kitsu != null || imdb != null || mal != null
    }

    private val cache = mutableMapOf<Int, Ids>()

    suspend fun forAniList(anilistId: Int, title: String? = null): Ids = withContext(Dispatchers.IO) {
        cache[anilistId]?.let { if (it.any) return@withContext it }

        // 1. ani.zip — richest source when it has the entry at all.
        var ids = aniZip(anilistId)

        // 2. Fribb's anime-lists — the mapping table most tools build on, and
        //    usually updated before ani.zip picks a new season up.
        if (ids.kitsu == null) {
            val fribb = fribb(anilistId)
            ids = Ids(fribb.kitsu ?: ids.kitsu, ids.imdb ?: fribb.imdb, ids.mal ?: fribb.mal)
        }

        // 3. Last resort: ask Kitsu directly by title.
        if (ids.kitsu == null && !title.isNullOrBlank()) {
            ids = ids.copy(kitsu = kitsuSearch(title))
        }

        if (ids.any) cache[anilistId] = ids
        ids
    }

    private fun aniZip(anilistId: Int): Ids = runCatching {
        val req = Request.Builder()
            .url("https://api.ani.zip/mappings?anilist_id=$anilistId")
            .build()
        Http.meta.newCall(req).execute().use { res ->
            val m = json.parseToJsonElement(res.body?.string().orEmpty()).obj("mappings")
            Ids(
                kitsu = m.int("kitsu_id")?.toString() ?: m.str("kitsu_id"),
                imdb = m.str("imdb_id"),
                mal = m.int("mal_id")?.toString()
            )
        }
    }.getOrDefault(Ids(null, null, null))

    /**
     * Deliberately still on the long client: this one downloads the whole
     * mapping table, which is far too big to hold to a metadata timeout.
     */
    private fun fribb(anilistId: Int): Ids = runCatching {
        val req = Request.Builder()
            .url("https://raw.githubusercontent.com/Fribb/anime-lists/master/anime-list-full.json")
            .build()
        Http.client.newCall(req).execute().use { res ->
            val all = json.parseToJsonElement(res.body?.string().orEmpty()) as? kotlinx.serialization.json.JsonArray
            val hit = all?.firstOrNull { it.int("anilist_id") == anilistId }
            Ids(
                kitsu = hit.int("kitsu_id")?.toString(),
                imdb = hit.str("imdb_id"),
                mal = hit.int("mal_id")?.toString()
            )
        }
    }.getOrDefault(Ids(null, null, null))

    /** Kitsu's own public search — no account or key needed. */
    private fun kitsuSearch(title: String): String? = runCatching {
        val q = URLEncoder.encode(title, "UTF-8")
        val req = Request.Builder()
            .url("https://kitsu.io/api/edge/anime?filter[text]=$q&page[limit]=1")
            .header("Accept", "application/vnd.api+json")
            .build()
        Http.meta.newCall(req).execute().use { res ->
            val root = json.parseToJsonElement(res.body?.string().orEmpty())
            root.arr("data")?.firstOrNull().str("id")
        }
    }.getOrNull()
}
