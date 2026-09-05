package com.debritsu.desktop

import com.debritsu.app.data.BuildInfo
import com.debritsu.app.data.Subtitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.RandomAccessFile

/**
 * Playback, by driving mpv rather than embedding a player.
 *
 * This is the decision the whole desktop build rests on. Writing a player would
 * have meant reimplementing `player/` — some 1,400 lines — against a native
 * library, and would have inherited ExoPlayer's subtitle problems without
 * ExoPlayer. mpv renders PGS and ASS natively, takes an HTTPS debrid URL and a
 * local file through the same argument, and reports where it has got to.
 *
 * Everything below was verified against mpv 0.41.0 before it was written; see
 * `tools-mpv-ipc-test.ps1` and WINDOWS-PORT.md.
 */
object Mpv {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * mpv is not on PATH after a winget install — it lands in
     * `C:\Program Files\MPV Player` and the shell knows nothing about it. So it
     * is looked for, and the answer is remembered in settings once found.
     */
    private val candidates = listOf(
        "C:\\Program Files\\MPV Player\\mpv.exe",
        "C:\\Program Files\\mpv\\mpv.exe",
        "C:\\Program Files (x86)\\MPV Player\\mpv.exe",
        "C:\\mpv\\mpv.exe"
    )

    fun locate(configured: String): File? {
        if (configured.isNotBlank()) {
            val f = File(configured)
            if (f.canExecute()) return f
        }
        candidates.map(::File).firstOrNull { it.canExecute() }?.let { return it }
        // PATH last rather than first: an mpv on PATH is unusual here, and a
        // stale shim would shadow a real install.
        val onPath = System.getenv("PATH").orEmpty().split(File.pathSeparator)
            .map { File(it, "mpv.exe") }
            .firstOrNull { it.canExecute() }
        return onPath
    }

    /**
     * A running mpv, and the pipe to ask it things.
     *
     * Held open for the life of the playback. Closing it does not stop mpv —
     * the viewer may still be watching — so [close] only lets go of the pipe.
     */
    class Session internal constructor(
        private val process: Process,
        private val pipe: RandomAccessFile
    ) {
        private var nextId = 1

        private val writer = OutputStreamWriter(
            object : java.io.OutputStream() {
                override fun write(b: Int) = pipe.write(b)
                override fun write(b: ByteArray, off: Int, len: Int) = pipe.write(b, off, len)
            },
            Charsets.UTF_8
        )

        val alive: Boolean get() = process.isAlive && !ended

        /**
         * Ask mpv for one property.
         *
         * Replies and events share the pipe: a property change or a playback
         * event can arrive between the command and its answer. So this reads
         * lines until it sees its own `request_id` and hands anything else to
         * [onEvent]. A client that wrote one line and read one line would work
         * until the first event and then be permanently one reply behind.
         */
        /**
         * True once the pipe has gone. mpv closing it is the normal end of
         * playback, not a fault: the viewer shut the window.
         */
        @Volatile
        var ended = false
            private set

        @Synchronized
        fun getDouble(property: String, onEvent: (String) -> Unit = {}): Double? {
            if (ended) return null
            val id = nextId++
            try {
                writer.write("""{"command":["get_property","$property"],"request_id":$id}""" + "\n")
                writer.flush()

                val deadline = System.currentTimeMillis() + 4000
                while (System.currentTimeMillis() < deadline) {
                    val line = pipe.readLine() ?: run { ended = true; return null }
                    val obj = runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull()
                        ?: continue
                    val replyId = (obj["request_id"] as? JsonPrimitive)?.content?.toIntOrNull()
                    if (replyId == id) {
                        val error = (obj["error"] as? JsonPrimitive)?.content
                        if (error != "success") {
                            if (BuildInfo.debug) BuildInfo.log("DebritsuMpv", "$property -> $error")
                            return null
                        }
                        return obj["data"]?.jsonPrimitive?.doubleOrNull
                    }
                    (obj["event"] as? JsonPrimitive)?.content?.let(onEvent)
                }
                if (BuildInfo.debug) BuildInfo.log("DebritsuMpv", "$property timed out")
                return null
            } catch (e: java.io.IOException) {
                // Windows reports a pipe whose other end has gone as "The pipe
                // is being closed". Uncaught, that escaped the watch loop and
                // surfaced as an error dialog on an ordinary quit.
                ended = true
                if (BuildInfo.debug) BuildInfo.log("DebritsuMpv", "pipe closed: ${e.message}")
                return null
            }
        }

        /** Where playback has got to, in milliseconds. */
        fun positionMs(): Long? = getDouble("time-pos")?.let { (it * 1000).toLong() }

        /**
         * How long the file is, in milliseconds.
         *
         * Trustworthy on a real container and not on a generated stream — a
         * lavfi test source reports nonsense while playing well past it. Since
         * the finished-watching rule divides by this, a bad value would mark an
         * episode watched almost immediately, which is the fault that once
         * marked a whole season complete.
         */
        fun durationMs(): Long? = getDouble("duration")?.let { (it * 1000).toLong() }

        fun close() {
            runCatching { pipe.close() }
        }

        fun stop() {
            close()
            runCatching { process.destroy() }
        }
    }

    /**
     * Starts mpv on [url] and connects to its IPC pipe.
     *
     * [subtitles] are passed as `--sub-file`, which is an append alias, so all
     * of them are offered and the viewer picks. Nothing here tries to choose
     * between two English tracks the way the Android player has to: mpv's own
     * track menu is better at it than the flag-guessing that reads a
     * signs-and-songs track as the main one.
     */
    suspend fun play(
        exe: File,
        url: String,
        title: String,
        subtitles: List<Subtitle>,
        startAtMs: Long = 0
    ): Session? = withContext(Dispatchers.IO) {
        val pipeName = "debritsu-" + System.nanoTime()
        val args = buildList {
            add(exe.absolutePath)
            add("--input-ipc-server=\\\\.\\pipe\\$pipeName")
            add("--force-media-title=$title")
            // Otherwise a fresh mpv opens small and behind whatever is in front.
            add("--force-window=immediate")
            if (startAtMs > 0) add("--start=${startAtMs / 1000}")
            subtitles.forEach { add("--sub-file=${it.url}") }
            add(url)
        }

        if (BuildInfo.debug) BuildInfo.log("DebritsuMpv", args.joinToString(" "))

        val process = runCatching {
            ProcessBuilder(args)
                // Anywhere but here. A child inherits the parent's working
                // directory, and the packaged app's is its own install folder —
                // so a running mpv held that folder open and the next build
                // could not replace it, failing on a directory that was by then
                // empty. mpv has no use for a working directory of its own.
                .directory(File(System.getProperty("user.home")))
                .redirectErrorStream(true)
                .start()
        }.getOrNull() ?: return@withContext null

        // Drain mpv's output, or a full pipe buffer eventually blocks it.
        Thread({
            runCatching {
                BufferedReader(InputStreamReader(process.inputStream)).forEachLine { line ->
                    if (BuildInfo.debug) BuildInfo.log("DebritsuMpv", line)
                }
            }
        }, "mpv-output").apply { isDaemon = true }.start()

        // The pipe does not exist the instant the process does. Measured at
        // first attempt on this machine, but a cold start after an update is
        // slower, so it is given a few seconds rather than one try.
        val path = "\\\\.\\pipe\\$pipeName"
        repeat(60) {
            if (!process.isAlive) return@withContext null
            val f = runCatching { RandomAccessFile(path, "rw") }.getOrNull()
            if (f != null) return@withContext Session(process, f)
            Thread.sleep(100)
        }
        process.destroy()
        null
    }
}
