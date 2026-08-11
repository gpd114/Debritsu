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
            "query (\$id: Int) { Media(id: \$id, type: ANIME) { $MEDIA_FIELDS format duration status " +
                "averageScore popularity favourites genres season seasonYear " +
                "studios(isMain: true) { nodes { name } } nextAiringEpisode { episode } " +
                "mediaListEntry { id status progress score(format: POINT_10) } } }",
            buildJsonObject { put("id", id) }
        )
        val m = d.obj("Media") ?: return null
        val aired = m.obj("nextAiringEpisode").int("episode")?.minus(1)
        val entry = m.obj("mediaListEntry")
        val season = m.str("season")?.lowercase()?.replaceFirstChar { it.uppercase() }
        val year = m.int("seasonYear")
        return mediaOf(m, entry.int("progress") ?: 0).copy(
            episodes = m.int("episodes") ?: aired ?: 1,
            listStatus = entry.str("status"),
            entryId = entry.int("id"),
            score = entry.str("score")?.toDoubleOrNull() ?: 0.0,
            averageScore = m.int("averageScore"),
            popularity = m.int("popularity"),
            favourites = m.int("favourites"),
            genres = (m.arr("genres"))?.mapNotNull { it.toString().trim('"') } ?: emptyList(),
            studio = m.obj("studios").arr("nodes")?.firstOrNull().str("name"),
            format = m.str("format")?.replace('_', ' '),
            seasonLabel = listOfNotNull(season, year?.toString()).joinToString(" ").ifBlank { null },
            airingStatus = m.str("status")?.lowercase()?.replace('_', ' ')
                ?.replaceFirstChar { it.uppercase() },
            durationMins = m.int("duration")
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
    suspend fun watching(): List<Anime> = listFor(listOf("CURRENT", "REPEATING"))

    /** Anything on the user's "Plan to watch" list. */
    suspend fun planning(): List<Anime> = listFor(listOf("PLANNING"))

    private var cachedViewerId: Int? = null
    private var cachedViewerToken: String? = null

    private suspend fun listFor(statuses: List<String>): List<Anime> {
        if (Settings.aniListToken.isEmpty()) return emptyList()
        // Signing out or switching accounts must invalidate the cached id.
        val token = Settings.aniListToken
        if (token != cachedViewerToken) {
            cachedViewerId = null
            cachedViewerToken = token
        }
        val viewer = cachedViewerId
            ?: query("query { Viewer { id } }").obj("Viewer").int("id")?.also { cachedViewerId = it }
            ?: return emptyList()
        val statusList = statuses.joinToString(", ")
        val d = query(
            "query (\$u: Int) { MediaListCollection(userId: \$u, type: ANIME, status_in: [$statusList]) " +
                "{ lists { entries { progress updatedAt media { $MEDIA_FIELDS } } } } }",
            buildJsonObject { put("u", viewer) }
        )
        return d.obj("MediaListCollection").arr("lists")
            ?.flatMap { l -> l.arr("entries") ?: JsonArray(emptyList()) }
            ?.sortedByDescending { it.int("updatedAt") ?: 0 }
            ?.map { e -> mediaOf((e as? JsonObject)?.get("media"), e.int("progress") ?: 0) }
            ?: emptyList()
    }

    /**
     * Prequels, sequels and side stories. AniList models these as a relation
     * graph, so this keeps only the edges worth surfacing in a viewer.
     */
    suspend fun relations(id: Int): List<Relation> {
        val wanted = setOf("PREQUEL", "SEQUEL", "SIDE_STORY", "PARENT", "ALTERNATIVE")
        val d = query(
            "query (\$id: Int) { Media(id: \$id) { relations { edges { relationType " +
                "node { id type title { romaji english } coverImage { large } episodes } } } } }",
            buildJsonObject { put("id", id) }
        )
        return d.obj("Media").obj("relations").arr("edges")
            ?.mapNotNull { e ->
                val type = e.str("relationType") ?: return@mapNotNull null
                val node = (e as? JsonObject)?.get("node")
                if (type !in wanted || node.str("type") != "ANIME") return@mapNotNull null
                Relation(mediaOf(node), type.lowercase().replace('_', ' '))
            }
            ?: emptyList()
    }

    /** Set list status, progress or score in one call. */
    suspend fun saveEntry(
        mediaId: Int,
        status: String? = null,
        progress: Int? = null,
        score: Double? = null
    ) {
        if (Settings.aniListToken.isEmpty()) return
        val fields = buildList {
            add("mediaId: \$id")
            if (status != null) add("status: \$st")
            if (progress != null) add("progress: \$pr")
            if (score != null) add("score: \$sc")
        }.joinToString(", ")
        val params = buildList {
            add("\$id: Int")
            if (status != null) add("\$st: MediaListStatus")
            if (progress != null) add("\$pr: Int")
            if (score != null) add("\$sc: Float")
        }.joinToString(", ")

        query(
            "mutation ($params) { SaveMediaListEntry($fields) { id status progress score } }",
            buildJsonObject {
                put("id", mediaId)
                status?.let { put("st", it) }
                progress?.let { put("pr", it) }
                score?.let { put("sc", it) }
            }
        )
    }

    /** Remove the title from the user's list entirely. */
    suspend fun deleteEntry(entryId: Int) {
        if (Settings.aniListToken.isEmpty()) return
        query(
            "mutation (\$id: Int) { DeleteMediaListEntry(id: \$id) { deleted } }",
            buildJsonObject { put("id", entryId) }
        )
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
