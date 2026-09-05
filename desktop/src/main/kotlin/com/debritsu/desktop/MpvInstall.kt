package com.debritsu.desktop

import com.debritsu.app.data.BuildInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Installing mpv on the user's behalf, through winget.
 *
 * Bundling it was the alternative and is worse in two ways: mpv.exe is 120MB of
 * statically linked binary, which would double the download; and shipping it
 * means redistributing GPL software, which carries an obligation to publish the
 * corresponding source for that exact build. Asking winget to fetch it from
 * upstream leaves both with the people whose job they are.
 *
 * Nothing here runs unasked. It happens when somebody presses a button that
 * says what it will do.
 */
object MpvInstall {

    /** The package winget knows mpv by — the build mpv.io points Windows at. */
    const val PACKAGE = "shinchiro.mpv"

    val command = "winget install --id $PACKAGE --exact " +
        "--accept-package-agreements --accept-source-agreements"

    /** Whether winget is on this machine at all. It is absent on older Windows. */
    fun available(): Boolean = System.getenv("PATH").orEmpty()
        .split(File.pathSeparator)
        .any { File(it, "winget.exe").canExecute() }

    sealed interface Result {
        data class Installed(val exe: File) : Result
        data class Failed(val why: String) : Result
    }

    /**
     * Runs winget and waits for it.
     *
     * Windows will raise its own elevation prompt, because mpv installs into
     * Program Files — so this can sit for a while on a dialog this program
     * cannot see. Progress lines are passed out as they arrive rather than
     * after, or the wait looks like a hang.
     */
    suspend fun install(onProgress: (String) -> Unit): Result = withContext(Dispatchers.IO) {
        if (!available()) {
            return@withContext Result.Failed(
                "winget is not on this machine. Install mpv yourself from mpv.io, " +
                    "then set its path in Settings."
            )
        }

        onProgress("Asking winget for mpv — Windows may ask permission…")

        val process = runCatching {
            ProcessBuilder(
                "winget", "install", "--id", PACKAGE, "--exact",
                "--accept-package-agreements", "--accept-source-agreements"
            ).directory(File(System.getProperty("user.home")))
                .redirectErrorStream(true)
                .start()
        }.getOrElse { return@withContext Result.Failed("Could not start winget: ${it.message}") }

        runCatching {
            BufferedReader(InputStreamReader(process.inputStream)).forEachLine { line ->
                val text = line.trim()
                // winget draws a progress bar with carriage returns and block
                // characters; those lines say nothing useful in a status bar.
                if (text.isNotEmpty() && !text.all { it == '█' || it == '▒' || it == '-' }) {
                    BuildInfo.log("DebritsuMpv", "winget: $text")
                    onProgress(text.take(90))
                }
            }
        }

        val code = process.waitFor()
        BuildInfo.log("DebritsuMpv", "winget exited $code")

        // Asked for again rather than assumed: winget can report success having
        // installed somewhere this does not look, and an exit code is not a
        // working player.
        val exe = Mpv.locate("")
        return@withContext when {
            exe != null -> Result.Installed(exe)
            code != 0 -> Result.Failed("winget exited with $code. Try the command by hand.")
            else -> Result.Failed(
                "winget finished but mpv was not found afterwards. " +
                    "Set its path in Settings if it went somewhere unusual."
            )
        }
    }
}
