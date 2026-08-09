package com.debritsu.app.data

import com.debritsu.app.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

object AniList {

    private const val ENDPOINT = "https://graphql.anilist.co"

    fun authUrl(clientId: String) =
        "https://anilist.co/api/v2/oauth/authorize?client_id=$clientId&response_type=token"

    private suspend fun query(q: String, vars: JsonObject = JsonObject(emptyMap())): JsonElement =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("query", q)
                put("variables", vars)
            }.toString().toRequestBody("application/json".toMediaType())

            val req = Request.Builder()
                .url(ENDPOINT)
                .post(body)
                .header("Accept", "application/json")
                .apply {
                    val t = Settings.aniListToken
                    if (t.isNotEmpty()) header("Authorization", "Bearer $t")
                }
                .build()

            Http.client.newCall(req).execute().use { res ->
                val txt = res.body?.string().orEmpty()
                json.parseToJsonElement(txt).obj("data") ?: JsonObject(emptyMap())
            }
        }

    private fun mediaOf(m: JsonElement?, progress: Int = 0): Anime {
        val t = m.obj("title")
        return Anime(
            id = m.int("id") ?: 0,
            title = t.str("english") ?: t.str("romaji") ?: "Unknown",
            cover = m.obj("coverImage").str("large"),
            banner = m.str("bannerImage"),
            episodes = m.int("episodes"),
            description = m.str("description")?.replace(Regex("<[^>]*>"), ""),
            progress = progress
        )
    }

    private const val MEDIA_FIELDS =
        "id title { romaji english } coverImage { large } bannerImage episodes description"

    /** One page of results plus whether another page exists. */
    data class Page(val items: List<Anime>, val hasMore: Boolean)

    suspend fun trending(page: Int = 1): Page {
        val d = query(
            "query (\$p: Int) { Page(page: \$p, perPage: 40) { pageInfo { hasNextPage } " +
                "media(type: ANIME, sort: TRENDING_DESC) { $MEDIA_FIELDS } } }",
            buildJsonObject { put("p", page) }
        )
        return pageOf(d)
    }

    suspend fun search(term: String, page: Int = 1): Page {
        val d = query(
            "query (\$s: String, \$p: Int) { Page(page: \$p, perPage: 40) { pageInfo { hasNextPage } " +
                "media(type: ANIME, search: \$s) { $MEDIA_FIELDS } } }",
            buildJsonObject { put("s", term); put("p", page) }
        )
        return pageOf(d)
    }

    private fun pageOf(d: JsonElement): Page {
        val p = d.obj("Page")
        return Page(
            items = p.arr("media")?.map { mediaOf(it) } ?: emptyList(),
            hasMore = p.obj("pageInfo").str("hasNextPage") == "true"
        )
    }

    suspend fun media(id: Int): Anime? {
        val d = query(
            "query (\$id: Int) { Media(id: \$id, type: ANIME) { $MEDIA_FIELDS format nextAiringEpisode { episode } " +
                "mediaListEntry { progress } } }",
            buildJsonObject { put("id", id) }
        )
        val m = d.obj("Media") ?: return null
        val aired = m.obj("nextAiringEpisode").int("episode")?.minus(1)
        val progress = m.obj("mediaListEntry").int("progress") ?: 0
        return mediaOf(m, progress).copy(
            episodes = m.int("episodes") ?: aired ?: 1
        )
    }

    suspend fun isMovie(id: Int): Boolean {
        val d = query(
            "query (\$id: Int) { Media(id: \$id) { format episodes } }",
            buildJsonObject { put("id", id) }
        )
        val m = d.obj("Media")
        return m.str("format") == "MOVIE" || (m.int("episodes") ?: 0) == 1
    }

    /** The signed-in user's "Watching" list, with per-show progress. */
    suspend fun watching(): List<Anime> {
        if (Settings.aniListToken.isEmpty()) return emptyList()
        val viewer = query("query { Viewer { id } }").obj("Viewer").int("id") ?: return emptyList()
        val d = query(
            "query (\$u: Int) { MediaListCollection(userId: \$u, type: ANIME, status_in: [CURRENT, REPEATING]) " +
                "{ lists { entries { progress media { $MEDIA_FIELDS } } } } }",
            buildJsonObject { put("u", viewer) }
        )
        return d.obj("MediaListCollection").arr("lists")
            ?.flatMap { l -> l.arr("entries") ?: JsonArray(emptyList()) }
            ?.map { e -> mediaOf((e as? JsonObject)?.get("media"), e.int("progress") ?: 0) }
            ?: emptyList()
    }

    /** Push watch progress back to AniList after an episode finishes. */
    suspend fun setProgress(mediaId: Int, episode: Int) {
        if (Settings.aniListToken.isEmpty()) return
        query(
            "mutation (\$id: Int, \$p: Int) { SaveMediaListEntry(mediaId: \$id, progress: \$p) { id progress } }",
            buildJsonObject { put("id", mediaId); put("p", episode) }
        )
    }
}
