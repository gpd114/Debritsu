package com.debritsu.desktop

import com.debritsu.app.Http
import com.debritsu.app.data.Anime
import com.debritsu.app.data.AutoPlay
import com.debritsu.app.data.BuildInfo
import com.debritsu.app.data.DownloadIndex
import com.debritsu.app.data.Downloaded
import com.debritsu.app.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

/**
 * Downloading episodes for later, which is the point of the desktop build.
 *
 * Android hands this to DownloadManager, which queues, retries and notifies on
 * the app's behalf. There is no such thing here, so this is a plain HTTP
 * transfer — which turns out to be less code, because most of DownloadManager's
 * value is surviving the app being killed, and a desktop program that is closed
 * simply stops.
 *
 * Resumable, because the case this exists for is a hotel connection the night
 * before a flight. A partial file is kept and continued with a Range request
 * rather than started again.
 */
object Downloader {

    /** How far along each download is, 0f..1f, while this program is running. */
    private val live = ConcurrentHashMap<String, Float>()

    /** Downloads that should stop at the next chunk boundary. */
    private val cancelled = ConcurrentHashMap.newKeySet<String>()

    fun progressOf(item: Downloaded): Float = live[item.key] ?: -1f

    fun isRunning(item: Downloaded): Boolean = live.containsKey(item.key)

    fun cancel(item: Downloaded) {
        cancelled += item.key
    }

    fun directory(): File {
        val configured = Settings.store.getString("download_dir", "")
        if (configured.isNotBlank()) return File(configured)
        return File(FileStore.directory(), "downloads")
    }

    fun fileFor(item: Downloaded): File = File(directory(), item.fileName)

    /** On disk in full, as far as the index and the file agree. */
    fun isComplete(item: Downloaded): Boolean {
        val f = fileFor(item)
        return f.exists() && f.length() > 0 && !isRunning(item)
    }

    fun remove(item: Downloaded) {
        cancel(item)
        runCatching { fileFor(item).delete() }
        DownloadIndex.forget(item)
    }

    sealed interface Result {
        data class Done(val item: Downloaded) : Result
        data class Failed(val why: String) : Result
    }

    /**
     * Resolves the episode the same way playing it would, then fetches it.
     *
     * Deliberately the same [AutoPlay] path: a download that picked its source
     * by different rules could hand you a file the filters would have rejected,
     * and finding that out on a plane is too late.
     */
    suspend fun episode(
        anime: Anime,
        episode: Int,
        onStep: (String) -> Unit
    ): Result = withContext(Dispatchers.IO) {
        onStep("Locating")
        val outcome = AutoPlay.run(
            anilistId = anime.id,
            title = anime.title,
            episode = episode,
            isMovie = (anime.episodes ?: 0) == 1,
            filter = Settings.sourceFilter,
            episodeMinutes = anime.durationMins ?: 0
        ) { step ->
            onStep(
                when (step) {
                    is AutoPlay.Step.Locating -> "Locating"
                    is AutoPlay.Step.Searching -> "Searching addons"
                    is AutoPlay.Step.Filtering -> "Filtering ${step.found} — ${step.kept} kept"
                    is AutoPlay.Step.Resolving -> "Resolving ${step.attempt} of ${step.of}"
                    is AutoPlay.Step.Ready -> "Starting download"
                }
            )
        }

        val url = outcome.url
            ?: return@withContext Result.Failed(outcome.message ?: "Nothing downloadable was found.")

        // Fetched now so the downloads screen has a picture on a plane. Posters
        // caches to disk by URL, so storing the URL is enough — but only if it
        // has been fetched at least once, and the moment of downloading is the
        // last moment there is certainly a network.
        runCatching { Posters.load(anime.cover) }

        val item = Downloaded(
            anilistId = anime.id,
            episode = episode,
            title = anime.title,
            coverPath = anime.cover,
            fileName = DownloadIndex.fileNameFor(anime.title, episode),
            sourceName = outcome.chosen?.name.orEmpty(),
            totalEpisodes = anime.episodes
        )
        DownloadIndex.put(item)

        val target = fileFor(item)
        target.parentFile?.mkdirs()
        cancelled -= item.key

        val result = runCatching { fetch(url, target, item.key, onStep) }
        live -= item.key

        result.fold(
            onSuccess = { ok ->
                if (ok) Result.Done(item)
                else {
                    // Cancelled, or ended short. The part file stays: it is what
                    // makes carrying on cheaper than starting again.
                    Result.Failed("Download stopped.")
                }
            },
            onFailure = { e ->
                BuildInfo.log("DebritsuDownload", "failed: $e")
                Result.Failed("Download failed: ${e.message}")
            }
        )
    }

    /**
     * The transfer itself, continuing a partial file where there is one.
     *
     * A server that ignores the Range header answers 200 with the whole file
     * rather than 206 with the rest of it, so the offset is only trusted when
     * it says 206 — otherwise the file is written from the start. Appending a
     * whole second copy onto a partial first one is the classic way to produce
     * a file that is the right size and unplayable.
     */
    private fun fetch(
        url: String,
        target: File,
        key: String,
        onStep: (String) -> Unit
    ): Boolean {
        val already = if (target.exists()) target.length() else 0L
        val request = Request.Builder().url(url)
            .apply { if (already > 0) header("Range", "bytes=$already-") }
            .build()

        Http.client.newCall(request).execute().use { res ->
            if (!res.isSuccessful) throw java.io.IOException("HTTP ${res.code}")

            val resuming = res.code == 206
            val body = res.body ?: throw java.io.IOException("empty response")
            val total = body.contentLength().let { if (it > 0) it + (if (resuming) already else 0) else -1L }

            BuildInfo.log(
                "DebritsuDownload",
                "$key: had ${already}B, HTTP ${res.code}, ${if (resuming) "resuming" else "from start"}, total ${total}B"
            )

            RandomAccessFile(target, "rw").use { out ->
                if (resuming) out.seek(already) else out.setLength(0)

                var written = if (resuming) already else 0L
                val buffer = ByteArray(1 shl 16)
                body.byteStream().use { input ->
                    while (true) {
                        if (key in cancelled) {
                            cancelled -= key
                            return false
                        }
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        written += n
                        if (total > 0) {
                            val fraction = (written.toFloat() / total).coerceIn(0f, 1f)
                            live[key] = fraction
                            onStep("${(fraction * 100).toInt()}%  ·  ${written / 1_048_576} MB")
                        } else {
                            onStep("${written / 1_048_576} MB")
                        }
                    }
                }
            }
            return true
        }
    }
}
