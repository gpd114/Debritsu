package com.debritsu.app.data

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import com.debritsu.app.DebritsuApp
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/**
 * A downloaded episode.
 *
 * Everything needed to browse and play it is stored here, deliberately: the
 * library has to work with no network at all, so nothing may depend on an
 * AniList lookup at read time.
 */
@Serializable
data class Downloaded(
    val anilistId: Int,
    val episode: Int,
    val title: String,
    val coverPath: String?,
    val fileName: String,
    val sourceName: String,
    val downloadId: Long,
    val totalEpisodes: Int? = null
) {
    val key: String get() = "$anilistId:$episode"
}

object Downloads {

    private val sp by lazy {
        DebritsuApp.ctx.getSharedPreferences("downloads", Context.MODE_PRIVATE)
    }

    private val dm by lazy {
        DebritsuApp.ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    }

    private fun dir(): File =
        DebritsuApp.ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: DebritsuApp.ctx.filesDir

    fun all(): List<Downloaded> = runCatching {
        json.decodeFromString(
            ListSerializer(Downloaded.serializer()),
            sp.getString("items", "[]") ?: "[]"
        )
    }.getOrDefault(emptyList())

    private fun save(items: List<Downloaded>) {
        sp.edit().putString(
            "items",
            json.encodeToString(ListSerializer(Downloaded.serializer()), items)
        ).apply()
    }

    fun get(anilistId: Int, episode: Int): Downloaded? =
        all().firstOrNull { it.anilistId == anilistId && it.episode == episode }

    fun fileFor(d: Downloaded): File = File(dir(), d.fileName)

    /** True once the bytes are actually on disk and playable. */
    fun isComplete(d: Downloaded): Boolean = fileFor(d).let { it.exists() && it.length() > 0 } &&
        statusOf(d.downloadId) == DownloadManager.STATUS_SUCCESSFUL

    fun statusOf(downloadId: Long): Int {
        val q = DownloadManager.Query().setFilterById(downloadId)
        dm.query(q).use { c ->
            if (c.moveToFirst()) {
                return c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            }
        }
        return DownloadManager.STATUS_FAILED
    }

    /** 0f..1f, or -1f when the total size isn't known yet. */
    fun progressOf(downloadId: Long): Float {
        val q = DownloadManager.Query().setFilterById(downloadId)
        dm.query(q).use { c ->
            if (c.moveToFirst()) {
                val soFar = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                return if (total > 0) (soFar.toFloat() / total).coerceIn(0f, 1f) else -1f
            }
        }
        return -1f
    }

    fun enqueue(
        url: String,
        anime: Anime,
        episode: Int,
        sourceName: String,
        coverPath: String?
    ): Downloaded {
        val safeTitle = anime.title.replace(Regex("[^A-Za-z0-9 ._-]"), "").take(60).trim()
        val fileName = "$safeTitle - E${episode.toString().padStart(2, '0')}.mp4"

        // Starting this episode again while it is already downloading would put
        // two DownloadManager jobs on one destination file, and saving below
        // replaces the list entry — discarding the first job's id along with it.
        // That job would carry on writing to the same file, untracked, and
        // remove() could never reach it. Cancel it, and clear whatever it left.
        val key = "${anime.id}:$episode"
        all().firstOrNull { it.key == key }?.let { previous ->
            runCatching { dm.remove(previous.downloadId) }
            runCatching { File(dir(), previous.fileName).delete() }
        }
        runCatching { File(dir(), fileName).delete() }

        val req = DownloadManager.Request(Uri.parse(url))
            .setTitle("$safeTitle · Episode $episode")
            .setDescription("Debritsu")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(File(dir(), fileName)))
            .setAllowedOverRoaming(false)

        val id = dm.enqueue(req)
        val item = Downloaded(
            anilistId = anime.id,
            episode = episode,
            title = anime.title,
            coverPath = coverPath,
            fileName = fileName,
            sourceName = sourceName,
            downloadId = id,
            totalEpisodes = anime.episodes
        )
        save(all().filterNot { it.key == item.key } + item)
        return item
    }

    fun remove(d: Downloaded) {
        runCatching { dm.remove(d.downloadId) }
        runCatching { fileFor(d).delete() }
        d.coverPath?.let { runCatching { File(it).delete() } }
        save(all().filterNot { it.key == d.key })
    }

    /** Stores the poster next to the video so the library renders offline. */
    fun cacheCover(anilistId: Int, url: String?): String? {
        if (url.isNullOrEmpty()) return null
        return runCatching {
            val f = File(dir(), "cover-$anilistId.jpg")
            if (!f.exists()) {
                val req = okhttp3.Request.Builder().url(url).build()
                com.debritsu.app.Http.client.newCall(req).execute().use { res ->
                    res.body?.byteStream()?.use { input ->
                        f.outputStream().use { out -> input.copyTo(out) }
                    }
                }
            }
            f.absolutePath
        }.getOrNull()
    }
}
