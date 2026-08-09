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
import java.net.URLEncoder

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
private data class RemoteFile(val id: String, val name: String, val size: Long, val link: String?)

object Debrid {

    suspend fun resolve(stream: StreamOption): String = withContext(Dispatchers.IO) {
        stream.url?.let { if (it.startsWith("http")) return@withContext it }

        val hash = stream.infoHash
            ?: throw DebridException("This stream has no playable link or infoHash.")
        val token = Settings.debridToken.ifEmpty {
            throw DebridException("Add a ${Settings.debridProvider.label} API key in Settings to play this stream.")
        }

        when (Settings.debridProvider) {
            DebridProvider.REAL_DEBRID -> realDebrid(hash, stream.fileIdx, token)
            DebridProvider.ALL_DEBRID -> allDebrid(hash, stream.fileIdx, token)
            DebridProvider.PREMIUMIZE -> premiumize(hash, stream.fileIdx, token)
            DebridProvider.TORBOX -> torbox(hash, stream.fileIdx, token)
        }
    }

    // ---------- provider implementations ----------

    private suspend fun realDebrid(hash: String, fileIdx: Int?, token: String): String {
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
                val link = (links.first() as JsonPrimitive).content
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

    private suspend fun allDebrid(hash: String, fileIdx: Int?, token: String): String {
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
                    RemoteFile("", it.str("filename") ?: "", it.int("size")?.toLong() ?: 0L, it.str("link"))
                }
                val chosen = pick(files, fileIdx).link
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
    private fun premiumize(hash: String, fileIdx: Int?, token: String): String {
        val res = post(
            "https://www.premiumize.me/api/transfer/directdl?apikey=${enc(token)}",
            emptyMap(),
            form("src" to magnet(hash))
        )
        if (res.str("status") != "success") {
            throw DebridException(res.str("message") ?: "Premiumize could not resolve this magnet.")
        }
        val files = res.arr("content")?.map {
            RemoteFile("", it.str("path") ?: "", it.int("size")?.toLong() ?: 0L, it.str("link"))
        }.orEmpty()
        if (files.isEmpty()) throw DebridException("Not cached on Premiumize — pick a different stream.")
        return pick(files, fileIdx).link ?: throw DebridException("Premiumize returned no link.")
    }

    private suspend fun torbox(hash: String, fileIdx: Int?, token: String): String {
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
                RemoteFile(it.int("id")?.toString() ?: "0", it.str("name") ?: "", it.int("size")?.toLong() ?: 0L, null)
            }.orEmpty()
            if (d.str("download_finished") == "true" && files.isNotEmpty()) {
                val chosen = pick(files, fileIdx)
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

    /** Honour the addon's fileIdx when present, otherwise take the largest file. */
    private fun pick(files: List<RemoteFile>, fileIdx: Int?): RemoteFile {
        if (files.isEmpty()) throw DebridException("No files in that torrent.")
        fileIdx?.let { if (it in files.indices) return files[it] }
        val video = files.filter {
            it.name.substringAfterLast('.', "").lowercase() in setOf("mkv", "mp4", "avi", "m4v", "webm")
        }
        return (video.ifEmpty { files }).maxByOrNull { it.size } ?: files.first()
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
