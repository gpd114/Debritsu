package com.debritsu.app.data

import com.debritsu.app.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.FormBody
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.math.abs

/**
 * Multi-provider debrid resolution.
 *
 * Most debrid-backed Stremio addons already return a ready HTTPS link, in which
 * case none of this runs. These providers are the fallback for addons that hand
 * back a bare infoHash.
 */
enum class DebridProvider(val label: String, val tokenHint: String) {
    REAL_DEBRID("Real-Debrid", "real-debrid.com/apitoken"),
    ALL_DEBRID("AllDebrid", "alldebrid.com/apikeys"),
    PREMIUMIZE("Premiumize", "premiumize.me/account"),
    TORBOX("TorBox", "torbox.app settings → API key")
}

class DebridException(message: String) : Exception(message)

/** One candidate file inside a resolved torrent. */
internal data class RemoteFile(val id: String, val name: String, val size: Long, val link: String?)

private val SEASON_EPISODE = Regex("""s(\d{1,2})[\s._-]*e(\d{1,3})|(?<!\d)(\d{1,2})x(\d{1,3})(?!\d)""", RegexOption.IGNORE_CASE)

/** A number standing on its own between separators, as fansubs name episodes. */
private val BARE_EPISODE = Regex("""[\s._-]-[\s._-](\d{1,3})(?:v\d)?[\s._\[(-]""")

object Debrid {

    suspend fun resolve(stream: StreamOption): String = withContext(Dispatchers.IO) {
        stream.url?.let {
            if (it.startsWith("http")) {
                // Nothing below runs for these: the addon resolved the file
                // itself and handed back a link. Logged because a wrong episode
                // then comes from the addon, not from anything here.
                BuildInfo.log(
                    "DebritsuResolve",
                    "addon link · wanted ${stream.filename ?: "(no name)"} · serving ${linkFile(it)}"
                )
                return@withContext it
            }
        }

        val hash = stream.infoHash
            ?: throw DebridException("This stream has no playable link or infoHash.")
        val token = Settings.debridToken.ifEmpty {
            throw DebridException("Add a ${Settings.debridProvider.label} API key in Settings to play this stream.")
        }
        // What the addon says this file weighs, read back out of its own text.
        // The last way to tell one episode of a pack from another when the
        // names have been rewritten.
        val sizeMb = StreamMeta.of(stream).sizeMb

        when (Settings.debridProvider) {
            DebridProvider.REAL_DEBRID -> realDebrid(hash, stream.fileIdx, stream.filename, sizeMb, token)
            DebridProvider.ALL_DEBRID -> allDebrid(hash, stream.fileIdx, stream.filename, sizeMb, token)
            DebridProvider.PREMIUMIZE -> premiumize(hash, stream.fileIdx, stream.filename, sizeMb, token)
            DebridProvider.TORBOX -> torbox(hash, stream.fileIdx, stream.filename, sizeMb, token)
        }
    }

    // ---------- provider implementations ----------

    private suspend fun realDebrid(hash: String, fileIdx: Int?, filename: String?, sizeMb: Int?, token: String): String {
        val api = "https://api.real-debrid.com/rest/1.0"
        val added = post("$api/torrents/addMagnet", bearer(token), form("magnet" to magnet(hash)))
        val id = added.str("id") ?: throw DebridException("Real-Debrid rejected the magnet.")

        post("$api/torrents/selectFiles/$id", bearer(token),
            form("files" to (fileIdx?.let { (it + 1).toString() } ?: "all")))

        repeat(20) {
            val info = get("$api/torrents/info/$id", bearer(token))
            val status = info.str("status")
            val links = info.arr("links")
            if (status == "downloaded" && !links.isNullOrEmpty()) {
                // One link per selected file, in file order. Adding a magnet
                // already in the account returns that torrent as it stands —
                // every file selected, if anything ever took the whole thing —
                // so the first link is the pack's first episode rather than
                // the one asked for.
                val selected = info.arr("files").orEmpty().filter { it.int("selected") == 1 }
                val wanted = pick(
                    selected.map {
                        RemoteFile(
                            it.int("id")?.toString() ?: "0",
                            it.str("path").orEmpty(),
                            it.long("bytes") ?: 0L,
                            null
                        )
                    },
                    fileIdx?.takeIf { selected.size > 1 },
                    filename,
                    sizeMb
                )
                val at = selected.indexOfFirst { it.int("id")?.toString() == wanted.id }
                val link = (links.getOrNull(at.coerceAtLeast(0)) as? JsonPrimitive)?.content
                    ?: (links.first() as JsonPrimitive).content
                val un = post("$api/unrestrict/link", bearer(token), form("link" to link))
                return un.str("download") ?: throw DebridException("Real-Debrid returned no download URL.")
            }
            if (status in setOf("magnet_error", "error", "virus", "dead")) {
                throw DebridException("Real-Debrid could not fetch this torrent ($status).")
            }
            delay(1500)
        }
        throw DebridException("Not cached on Real-Debrid — pick a different stream.")
    }

    private suspend fun allDebrid(hash: String, fileIdx: Int?, filename: String?, sizeMb: Int?, token: String): String {
        val api = "https://api.alldebrid.com/v4"
        val auth = "agent=debritsu&apikey=${enc(token)}"

        val up = get("$api/magnet/upload?$auth&magnets[]=${enc(hash)}", emptyMap())
        val id = up.obj("data").arr("magnets")?.firstOrNull().let { it.int("id") ?: it.str("id") }
            ?: throw DebridException("AllDebrid rejected the magnet.")

        repeat(20) {
            val st = get("$api/magnet/status?$auth&id=$id", emptyMap())
            val m = st.obj("data").let { d ->
                (d?.get("magnets") as? JsonObject)
                    ?: (d.arr("magnets")?.firstOrNull() as? JsonObject)
            }
            val ready = m.str("status") == "Ready" || m.str("statusCode") == "4"
            val links = m.arr("links")
            if (ready && !links.isNullOrEmpty()) {
                val files = links.map {
                    RemoteFile("", it.str("filename") ?: "", it.long("size") ?: 0L, it.str("link"))
                }
                val chosen = pick(files, fileIdx, filename, sizeMb).link
                    ?: throw DebridException("AllDebrid returned no link for that file.")
                val un = get("$api/link/unlock?$auth&link=${enc(chosen)}", emptyMap())
                return un.obj("data").str("link")
                    ?: throw DebridException("AllDebrid could not unlock the link.")
            }
            if (m.str("status")?.contains("Error", true) == true) {
                throw DebridException("AllDebrid could not fetch this torrent.")
            }
            delay(1500)
        }
        throw DebridException("Not cached on AllDebrid — pick a different stream.")
    }

    /**
     * Premiumize resolves cached magnets in a single call — directdl returns
     * every file in the torrent with a ready link, no transfer created.
     */
    private fun premiumize(hash: String, fileIdx: Int?, filename: String?, sizeMb: Int?, token: String): String {
        val res = post(
            "https://www.premiumize.me/api/transfer/directdl?apikey=${enc(token)}",
            emptyMap(),
            form("src" to magnet(hash))
        )
        if (res.str("status") != "success") {
            throw DebridException(res.str("message") ?: "Premiumize could not resolve this magnet.")
        }
        val files = res.arr("content")?.map {
            RemoteFile("", it.str("path") ?: "", it.long("size") ?: 0L, it.str("link"))
        }.orEmpty()
        if (files.isEmpty()) throw DebridException("Not cached on Premiumize — pick a different stream.")
        return pick(files, fileIdx, filename, sizeMb).link ?: throw DebridException("Premiumize returned no link.")
    }

    private suspend fun torbox(hash: String, fileIdx: Int?, filename: String?, sizeMb: Int?, token: String): String {
        val api = "https://api.torbox.app/v1/api"
        val created = post(
            "$api/torrents/createtorrent", bearer(token),
            MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("magnet", magnet(hash))
                .build()
        )
        val id = created.obj("data").let { it.int("torrent_id") ?: it.int("id") }
            ?: throw DebridException(created.str("detail") ?: "TorBox rejected the magnet.")

        repeat(20) {
            val list = get("$api/torrents/mylist?id=$id&bypass_cache=true", bearer(token))
            val d = list.obj("data")
            val files = d.arr("files")?.map {
                RemoteFile(it.int("id")?.toString() ?: "0", it.str("name") ?: "", it.long("size") ?: 0L, null)
            }.orEmpty()
            if (d.str("download_finished") == "true" && files.isNotEmpty()) {
                val chosen = pick(files, fileIdx, filename, sizeMb)
                val dl = get(
                    "$api/torrents/requestdl?token=${enc(token)}&torrent_id=$id&file_id=${chosen.id}",
                    bearer(token)
                )
                return (dl as? JsonObject)?.get("data").let { (it as? JsonPrimitive)?.content }
                    ?: throw DebridException("TorBox returned no download URL.")
            }
            delay(1500)
        }
        throw DebridException("Not cached on TorBox — pick a different stream.")
    }

    // ---------- helpers ----------

    /**
     * The file the addon meant: by name where it gave one, then by its index,
     * then the largest video.
     *
     * The name comes first because the index does not survive the trip. An
     * addon counts every file in the torrent; a provider lists only what it
     * kept, so the same number lands on a different file — and in a pack of
     * several seasons that is another season's episode, which plays perfectly
     * and is not what was asked for.
     */
    internal fun pick(
        files: List<RemoteFile>,
        fileIdx: Int?,
        filename: String? = null,
        sizeMb: Int? = null
    ): RemoteFile {
        if (files.isEmpty()) throw DebridException("No files in that torrent.")
        val video = files.filter { it.name.extension() in VIDEO }
        val candidates = video.ifEmpty { files }

        fun chosen(rule: String, file: RemoteFile): RemoteFile {
            BuildInfo.log(
                "DebritsuResolve",
                "wanted ${filename ?: "(no name)"} idx $fileIdx ${sizeMb ?: "?"}MB " +
                    "· ${files.size} files (${video.size} video) · $rule · took ${file.name.base()}"
            )
            return file
        }

        val wanted = filename?.base()
        if (wanted != null) {
            candidates.firstOrNull { it.name.base() == wanted }
                ?.let { return chosen("name", it) }
            // Providers rename: spaces to dots, brackets dropped, the folder
            // flattened in. Comparing letters and digits alone survives that.
            val loose = wanted.squash()
            candidates.firstOrNull { it.name.base().squash() == loose }
                ?.let { return chosen("name (loose)", it) }
            // Last resort on the name: the season and episode it carries. A
            // pack holds one file per episode, so this is unambiguous when it
            // matches at all.
            episodeTag(wanted)?.let { tag ->
                val tagged = candidates.filter { episodeTag(it.name.base()) == tag }
                if (tagged.size == 1) return chosen("episode $tag", tagged.first())
            }
        }
        // The size the addon quoted, which it takes from the torrent itself.
        // Within 1% covers rounding in what the addon printed.
        if (sizeMb != null && sizeMb > 0) {
            val target = sizeMb * 1024L * 1024L
            val near = candidates.filter { it.size > 0 && abs(it.size - target) <= target / 100 }
            if (near.size == 1) return chosen("size", near.first())
        }
        fileIdx?.let { if (it in files.indices) return chosen("index", files[it]) }
        return chosen("largest", candidates.maxByOrNull { it.size } ?: files.first())
    }

    private val VIDEO = setOf("mkv", "mp4", "avi", "m4v", "webm")

    private fun String.extension() = substringAfterLast('.', "").lowercase()

    /** The file's own name, without the folders any provider may keep or drop. */
    private fun String.base() = substringAfterLast('/').substringAfterLast('\\').lowercase()

    private fun String.squash() = filter { it.isLetterOrDigit() }

    /** "s02e11" out of S02E11, 02x11, or a bare " - 11 " with no season. */
    private fun episodeTag(name: String): String? {
        SEASON_EPISODE.find(name)?.let { m ->
            // Either S02E11 (groups 1 and 2) or 02x11 (groups 3 and 4) matched.
            val season = m.groupValues[1].ifEmpty { m.groupValues[3] }
            val episode = m.groupValues[2].ifEmpty { m.groupValues[4] }
            if (season.isNotEmpty() && episode.isNotEmpty()) {
                return "s${season.padded()}e${episode.padded()}"
            }
        }
        return BARE_EPISODE.find(name)?.let { "e${it.groupValues[1].padded()}" }
    }

    private fun String.padded() = trimStart('0').ifEmpty { "0" }.padStart(2, '0')

    /**
     * The file a link points at, for the log: the last part of the path, and
     * only when it reads as a filename.
     *
     * Deliberately not the link. A debrid link carries the account's own token,
     * which has no business in a log.
     */
    private fun linkFile(url: String): String {
        val path = url.substringBefore('?').substringBefore('#')
        val last = runCatching { URLDecoder.decode(path.substringAfterLast('/'), "UTF-8") }
            .getOrDefault(path.substringAfterLast('/'))
        return if (last.extension() in VIDEO) last else "(link names no file)"
    }

    private fun magnet(hash: String) = "magnet:?xt=urn:btih:$hash"
    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
    private fun bearer(token: String) = mapOf("Authorization" to "Bearer $token")
    private fun form(vararg pairs: Pair<String, String>) =
        FormBody.Builder().apply { pairs.forEach { add(it.first, it.second) } }.build()

    private fun get(url: String, headers: Map<String, String>): JsonElement =
        Http.client.newCall(
            Request.Builder().url(url).apply { headers.forEach { header(it.key, it.value) } }.build()
        ).execute().use { parse(it.body?.string()) }

    private fun post(url: String, headers: Map<String, String>, body: RequestBody): JsonElement =
        Http.client.newCall(
            Request.Builder().url(url).post(body)
                .apply { headers.forEach { header(it.key, it.value) } }.build()
        ).execute().use { parse(it.body?.string()) }

    private fun parse(text: String?): JsonElement =
        runCatching { json.parseToJsonElement(text.orEmpty().ifBlank { "{}" }) }
            .getOrElse { JsonObject(emptyMap()) }
}
