package com.debritsu.app.data

import com.debritsu.app.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import okhttp3.Request
import java.net.URLEncoder

/**
 * Talks to Stremio addons over their public HTTP protocol.
 *
 * Point this at a debrid-backed addon (AIOStreams, Comet, Torrentio with an
 * RD/AD/Premiumize key, MediaFusion, ...) and every returned stream is already
 * a cached, direct HTTPS link — no scraping, no local torrent client.
 */
object Stremio {

    data class Manifest(val id: String, val name: String, val types: List<String>)

    suspend fun manifest(addonBase: String): Manifest? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url("$addonBase/manifest.json").build()
            Http.client.newCall(req).execute().use { res ->
                val root = json.parseToJsonElement(res.body?.string().orEmpty())
                Manifest(
                    id = root.str("id") ?: addonBase,
                    name = root.str("name") ?: addonBase,
                    types = (root.arr("types") as? JsonArray)?.mapNotNull { it.toString().trim('"') }
                        ?: emptyList()
                )
            }
        }.getOrNull()
    }

    /**
     * Builds the Stremio content ID. Anime series use `kitsu:<id>:<episode>`;
     * single-episode entries (films, OVAs) use the bare id.
     */
    fun contentId(ids: Mappings.Ids, episode: Int?, isMovie: Boolean): Pair<String, String>? {
        val kitsu = ids.kitsu
        return when {
            kitsu != null && !isMovie && episode != null -> "series" to "kitsu:$kitsu:$episode"
            kitsu != null -> "movie" to "kitsu:$kitsu"
            ids.imdb != null && !isMovie && episode != null -> "series" to "${ids.imdb}:1:$episode"
            ids.imdb != null -> "movie" to ids.imdb
            else -> null
        }
    }

    /** What one addon gave back, including why it gave back nothing. */
    data class AddonResult(
        val addon: String,
        val streams: List<StreamOption>,
        val error: String? = null
    )

    /** Queries every configured addon in parallel, keeping failures visible. */
    suspend fun streams(type: String, id: String): List<AddonResult> = coroutineScope {
        Settings.addons.map { base ->
            async(Dispatchers.IO) { streamsFrom(base, type, id) }
        }.map { it.await() }
    }

    /**
     * Stremio subtitle addons (OpenSubtitles v3, etc.) expose the same protocol
     * under /subtitles/. Results are side-loaded into the player as selectable
     * tracks alongside anything embedded in the video itself.
     */
    suspend fun subtitles(targets: List<Pair<String, String>>): List<Subtitle> = coroutineScope {
        Settings.addons.flatMap { base ->
            targets.map { (type, id) ->
                async(Dispatchers.IO) { subtitlesFrom(base, type, id) }
            }
        }.flatMap { it.await() }.distinctBy { it.url }
    }

    private fun subtitlesFrom(base: String, type: String, id: String): List<Subtitle> =
        runCatching {
            val encoded = URLEncoder.encode(id, "UTF-8").replace("+", "%20")
            val req = Request.Builder().url("$base/subtitles/$type/$encoded.json").build()
            Http.client.newCall(req).execute().use { res ->
                val root = json.parseToJsonElement(res.body?.string().orEmpty())
                root.arr("subtitles")?.mapNotNull { sub ->
                    sub.str("url")?.let { Subtitle(it, sub.str("lang") ?: "und") }
                } ?: emptyList()
            }
        }.getOrDefault(emptyList())

    /**
     * Every id form worth asking under.
     *
     * Anime stream addons index by Kitsu, so [contentId] prefers it. Subtitle
     * addons do not: OpenSubtitles indexes by IMDb and answers a Kitsu id with
     * an empty list and a 200, which is indistinguishable from a title having
     * no subtitles at all. Asking under both costs one extra request per addon
     * and covers either convention.
     *
     * The IMDb form assumes season one, which is right for a single-season
     * show and wrong for a later season mapped as its own AniList entry —
     * the season number simply isn't in the mapping tables we read.
     */
    fun contentIds(ids: Mappings.Ids, episode: Int?, isMovie: Boolean): List<Pair<String, String>> =
        buildList {
            ids.kitsu?.let {
                if (!isMovie && episode != null) add("series" to "kitsu:$it:$episode")
                else add("movie" to "kitsu:$it")
            }
            ids.imdb?.let {
                if (!isMovie && episode != null) add("series" to "$it:1:$episode")
                else add("movie" to it)
            }
        }.distinct()

    private fun streamsFrom(addonBase: String, type: String, id: String): AddonResult {
        val host = addonBase.substringAfter("://").substringBefore("/")
        return runCatching {
            val encoded = URLEncoder.encode(id, "UTF-8").replace("+", "%20")
            val req = Request.Builder().url("$addonBase/stream/$type/$encoded.json").build()
            Http.client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) {
                    return AddonResult(host, emptyList(), "HTTP ${res.code}")
                }
                val body = res.body?.string().orEmpty()
                val root = json.parseToJsonElement(body)
                val parsed = root.arr("streams")?.map { s ->
                    StreamOption(
                        addon = addonBase.substringAfter("://").substringBefore("/"),
                        name = s.str("name") ?: "Stream",
                        description = s.str("description") ?: s.str("title") ?: "",
                        url = s.str("url"),
                        infoHash = s.str("infoHash"),
                        fileIdx = s.int("fileIdx"),
                        subtitles = s.arr("subtitles")?.mapNotNull { sub ->
                            sub.str("url")?.let { Subtitle(it, sub.str("lang") ?: "und") }
                        } ?: emptyList()
                    )
                }?.filter { it.url != null || it.infoHash != null }
                when {
                    parsed == null -> AddonResult(host, emptyList(), "no streams field in response")
                    parsed.isEmpty() -> AddonResult(host, emptyList(), "nothing for this episode")
                    else -> AddonResult(host, parsed)
                }
            }
        }.getOrElse { e ->
            AddonResult(host, emptyList(), e.message?.take(90) ?: e.javaClass.simpleName)
        }
    }
}
