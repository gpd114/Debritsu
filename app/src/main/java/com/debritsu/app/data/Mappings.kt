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

    /**
     * @param season which season of the IMDb/TVDB series this AniList entry is.
     *
     * AniList gives every season its own entry; IMDb and TVDB keep one entry
     * for the whole series and number the seasons inside it. All four seasons
     * of Shield Hero share tt9529546, so an IMDb-shaped request has to say
     * which season it means or it asks for the first one. Null where the
     * source could not say, which is treated as season one.
     */
    data class Ids(
        val kitsu: String?,
        val imdb: String?,
        val mal: String?,
        val season: Int? = null
    ) {
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
            ids = Ids(
                fribb.kitsu ?: ids.kitsu,
                ids.imdb ?: fribb.imdb,
                ids.mal ?: fribb.mal,
                // Fribb's table has no season column, so whatever ani.zip knew
                // is kept rather than overwritten with nothing.
                ids.season
            )
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
            val root = json.parseToJsonElement(res.body?.string().orEmpty())
            val m = root.obj("mappings")
            // ani.zip carries the season each episode belongs to, which is the
            // only place this is available — AniList does not model it, since
            // to AniList a season is simply another show. Read off episode one,
            // which every entry has; the specials are keyed "S1" and so are
            // skipped by asking for "1" directly.
            val season = root.obj("episodes").obj("1").int("seasonNumber")
            Ids(
                kitsu = m.int("kitsu_id")?.toString() ?: m.str("kitsu_id"),
                imdb = m.str("imdb_id"),
                mal = m.int("mal_id")?.toString(),
                season = season
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
