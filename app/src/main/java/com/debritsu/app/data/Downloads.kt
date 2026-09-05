package com.debritsu.app.data

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import com.debritsu.app.DebritsuApp
import java.io.File

/**
 * Downloading on Android, which is DownloadManager's job.
 *
 * The list of what has been downloaded lives in [DownloadIndex], shared with
 * the desktop build; what is here is the transferring, which is not shared —
 * DownloadManager does the queueing, the retrying and the notification, and has
 * no equivalent off Android.
 */
object Downloads {

    private val dm by lazy {
        DebritsuApp.ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    }

    private fun dir(): File =
        DebritsuApp.ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: DebritsuApp.ctx.filesDir

    fun all(): List<Downloaded> = DownloadIndex.all()

    fun get(anilistId: Int, episode: Int): Downloaded? = DownloadIndex.get(anilistId, episode)

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
        val fileName = DownloadIndex.fileNameFor(anime.title, episode)

        // Starting this episode again while it is already downloading would put
        // two DownloadManager jobs on one destination file, and saving below
        // replaces the list entry — discarding the first job's id along with it.
        // That job would carry on writing to the same file, untracked, and
        // remove() could never reach it. Cancel it, and clear whatever it left.
        DownloadIndex.get(anime.id, episode)?.let { previous ->
            runCatching { dm.remove(previous.downloadId) }
            runCatching { File(dir(), previous.fileName).delete() }
        }
        runCatching { File(dir(), fileName).delete() }

        val safeTitle = fileName.substringBefore(" - E")
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
        DownloadIndex.put(item)
        return item
    }

    fun remove(d: Downloaded) {
        runCatching { dm.remove(d.downloadId) }
        runCatching { fileFor(d).delete() }
        d.coverPath?.let { runCatching { File(it).delete() } }
        DownloadIndex.forget(d)
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
