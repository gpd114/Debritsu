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
 * Truncated at each start rather than appended, so the file is always the
 * current session and never has to be searched for the relevant part.
 */
object Logging {

    private val clock = DateTimeFormatter.ofPattern("HH:mm:ss")

    fun install(): (String, String) -> Unit {
        val file = File(FileStore.directory(), "debritsu.log")
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText("Debritsu ${LocalTime.now().format(clock)}\n")
        }
        return { tag, message ->
            val line = "${LocalTime.now().format(clock)}  $tag  $message"
            println(line)
            runCatching { file.appendText(line + "\n") }
        }
    }
}
