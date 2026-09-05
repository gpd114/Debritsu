package com.debritsu.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.debritsu.app.Http
import com.debritsu.app.data.BuildInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.jetbrains.skia.Image as SkiaImage
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Posters and banners, cached on disk and in memory.
 *
 * Hand-written rather than reached for from a library: Coil 2 is Android-only,
 * and Coil 3 would be a dependency for one screen's worth of images. What is
 * actually needed here is small — fetch, keep, decode — and the disk half has
 * to exist regardless, because a downloaded library has to render on a plane
 * where nothing can be fetched.
 *
 * Files are named by a hash of the URL, so the same image is never fetched
 * twice and a changed URL simply misses rather than serving the wrong picture.
 */
object Posters {

    private val memory = ConcurrentHashMap<String, ImageBitmap>()

    /** Attempted and failed, so it is not retried on every recomposition. */
    private val failed = ConcurrentHashMap.newKeySet<String>()

    private fun dir(): File = File(FileStore.directory(), "covers").apply { mkdirs() }

    private fun fileFor(url: String): File {
        val hash = MessageDigest.getInstance("SHA-1")
            .digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(24)
        return File(dir(), "$hash.img")
    }

    /** Already decoded and in hand, for a synchronous first draw. */
    fun cached(url: String?): ImageBitmap? = url?.let { memory[it] }

    /**
     * Fetches if it must, from disk if it can.
     *
     * Returns null when there is nothing to show — no URL, or a fetch that
     * failed — and the caller draws its placeholder instead. A missing poster
     * is not worth an error.
     */
    suspend fun load(url: String?): ImageBitmap? {
        if (url.isNullOrBlank() || url in failed) return null
        memory[url]?.let { return it }

        return withContext(Dispatchers.IO) {
            runCatching {
                val file = fileFor(url)
                val bytes = if (file.exists() && file.length() > 0) {
                    file.readBytes()
                } else {
                    val request = Request.Builder().url(url).build()
                    Http.meta.newCall(request).execute().use { res ->
                        if (!res.isSuccessful) throw java.io.IOException("HTTP ${res.code}")
                        val body = res.body?.bytes() ?: throw java.io.IOException("empty")
                        // Written before decoding: a picture that decodes today
                        // will decode offline tomorrow, which is the point.
                        runCatching { file.writeBytes(body) }
                        body
                    }
                }
                SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
            }.onFailure {
                failed += url
                BuildInfo.log("DebritsuPosters", "failed $url: $it")
            }.getOrNull()?.also { memory[url] = it }
        }
    }
}
