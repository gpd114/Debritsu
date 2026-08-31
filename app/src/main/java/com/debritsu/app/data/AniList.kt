package com.debritsu.app.data

import com.debritsu.app.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
            progress = progress,
            // Requested for every list and search result, not just the detail
            // screen, so posters can carry a rating.
            averageScore = m.int("averageScore"),
            nextEpisode = m.obj("nextAiringEpisode").int("episode"),
            airingInSeconds = m.obj("nextAiringEpisode").int("timeUntilAiring")
        )
    }

    private const val MEDIA_FIELDS =
        "id title { romaji english } coverImage { large } bannerImage episodes description " +
            "averageScore nextAiringEpisode { episode timeUntilAiring }"

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

    /**
     * What the community recommends, most-voted first.
     *
     * Not personalised — AniList has no such endpoint, and asking for one gets
     * a 401 without a token and nothing useful with one. This is the whole
     * site's recommendation graph sorted by votes, which is exactly right for
     * finding well-liked shows you have never heard of.
     *
     * Each row of that graph is a pair, "people who liked A also liked B", so
     * a popular B appears under many different A's. Deduplicated here, or the
     * shelf would be Your Name four times.
     */
    suspend fun recommended(page: Int = 1): Page {
        val d = query(
            "query (\$p: Int) { Page(page: \$p, perPage: 50) { pageInfo { hasNextPage } " +
                "recommendations(sort: RATING_DESC) { mediaRecommendation { $MEDIA_FIELDS } } } }",
            buildJsonObject { put("p", page) }
        )
        val p = d.obj("Page")
        val seen = mutableSetOf<Int>()
        val items = p.arr("recommendations")
            ?.mapNotNull { r -> (r as? JsonObject)?.get("mediaRecommendation") }
            ?.map { mediaOf(it) }
            ?.filter { it.id != 0 && seen.add(it.id) }
            ?: emptyList()
        return Page(items, p.obj("pageInfo").str("hasNextPage") == "true")
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

    /**
     * Every anime already on the signed-in user's list, whatever its status.
     *
     * For deciding what is not a discovery. The two shelves the home screen
     * already holds cover only Watching and Plan to watch, so recommending
     * something finished years ago slipped straight past them — which is
     * exactly the thing a Recommended shelf should never do.
     *
     * Ids alone, so it stays small: a list of 697 entries came back in 876ms
     * at 16KB.
     */
    suspend fun listedIds(): Set<Int> {
        if (Settings.aniListToken.isEmpty()) return emptySet()
        val viewer = viewerId() ?: return emptySet()
        val d = query(
            "query (\$u: Int) { MediaListCollection(userId: \$u, type: ANIME) " +
                "{ lists { entries { media { id } } } } }",
            buildJsonObject { put("u", viewer) }
        )
        return d.obj("MediaListCollection").arr("lists")
            ?.flatMap { l -> l.arr("entries") ?: JsonArray(emptyList()) }
            ?.mapNotNull { e -> (e as? JsonObject)?.get("media").int("id") }
            ?.toSet()
            ?: emptySet()
    }

    private val viewerLock = Mutex()

    /**
     * The signed-in account's id, fetched at most once per token.
     *
     * Held on disk rather than in memory, because in memory it was gone on
     * every cold start and every list query then began with a round trip of its
     * own — which is why the two list shelves lagged Trending, which needs no
     * such thing. The lock matters now the shelves load together: without it
     * both would find nothing cached and ask for the same id at the same time.
     */
    private suspend fun viewerId(): Int? = viewerLock.withLock {
        val token = Settings.aniListToken
        if (token.isEmpty()) return@withLock null
        // Signing out or switching accounts must discard it.
        if (token != Settings.aniListViewerToken) {
            Settings.aniListViewerId = 0
            Settings.aniListViewerToken = token
        }
        Settings.aniListViewerId.takeIf { it != 0 }?.let { return@withLock it }

        val id = query("query { Viewer { id } }").obj("Viewer").int("id")
            ?: return@withLock null
        Settings.aniListViewerId = id
        id
    }

    private suspend fun listFor(statuses: List<String>): List<Anime> {
        if (Settings.aniListToken.isEmpty()) return emptyList()
        val viewer = viewerId() ?: return emptyList()
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

    /** The two side lists a show page carries, fetched together. */
    data class Extras(val relations: List<Relation>, val recommended: List<Anime>)

    /**
     * Prequels and sequels, and what people who liked this went on to like.
     *
     * One query for both. They are different fields of the same Media, and
     * asking separately would spend two of AniList's thirty requests a minute
     * where one does.
     *
     * Relations are a graph of every edge AniList models, most of which are
     * not worth showing a viewer, so only these five kinds survive.
     */
    suspend fun extras(id: Int): Extras {
        val wanted = setOf("PREQUEL", "SEQUEL", "SIDE_STORY", "PARENT", "ALTERNATIVE")
        val d = query(
            "query (\$id: Int) { Media(id: \$id) { relations { edges { relationType " +
                "node { id type title { romaji english } coverImage { large } episodes } } } " +
                "recommendations(sort: RATING_DESC, perPage: 12) { nodes { " +
                "mediaRecommendation { $MEDIA_FIELDS } } } } }",
            buildJsonObject { put("id", id) }
        )
        val m = d.obj("Media")

        val relations = m.obj("relations").arr("edges")
            ?.mapNotNull { e ->
                val type = e.str("relationType") ?: return@mapNotNull null
                val node = (e as? JsonObject)?.get("node")
                if (type !in wanted || node.str("type") != "ANIME") return@mapNotNull null
                Relation(mediaOf(node), type.lowercase().replace('_', ' '))
            }
            ?: emptyList()

        val recommended = m.obj("recommendations").arr("nodes")
            ?.mapNotNull { n -> (n as? JsonObject)?.get("mediaRecommendation") }
            ?.map { mediaOf(it) }
            ?.filter { it.id != 0 && it.id != id }
            ?: emptyList()

        return Extras(relations, recommended)
    }

    /** Kept for the phone screen, which this build does not show. */
    suspend fun relations(id: Int): List<Relation> = extras(id).relations

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
