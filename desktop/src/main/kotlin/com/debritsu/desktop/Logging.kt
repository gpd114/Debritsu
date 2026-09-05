package com.debritsu.desktop

import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * A log file beside the settings, because the packaged build has no console.
 *
 * `DebritsuFilter` and `DebritsuWatch` are the same tags the Android build uses
 * for the same things — every source considered and why it passed, and the
 * progress rule as it decides. On Android they go to logcat; here there is
 * nowhere for them to go but a file.
 *
 * Appended to, with a marker at each start.
 *
 * It truncated on start at first, on the reasoning that the current session is
 * the interesting one. That was wrong in the only situation this file exists
 * for: something goes wrong, the app is restarted to see whether it persists,
 * and the restart erases the evidence of what happened. It cost exactly that
 * once, within an hour of being written.
 *
 * Capped instead, so it cannot grow without limit — the oldest half is dropped
 * when it gets large, which keeps several sessions rather than only this one.
 */
object Logging {

    private val clock = DateTimeFormatter.ofPattern("HH:mm:ss")

    /** Roughly a few thousand lines; enough for several sessions. */
    private const val MAX_BYTES = 512 * 1024

    fun install(): (String, String) -> Unit {
        val file = File(FileStore.directory(), "debritsu.log")
        runCatching {
            file.parentFile?.mkdirs()
            if (file.length() > MAX_BYTES) {
                val kept = file.readLines().takeLast(2000)
                file.writeText(kept.joinToString("\n") + "\n")
            }
            file.appendText("\n=== started ${LocalTime.now().format(clock)} ===\n")
        }
        return { tag, message ->
            val line = "${LocalTime.now().format(clock)}  $tag  $message"
            println(line)
            runCatching { file.appendText(line + "\n") }
        }
    }
}
