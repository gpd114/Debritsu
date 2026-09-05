package com.debritsu.desktop

import com.debritsu.app.data.BuildInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Installing VLC on the user's behalf, through winget.
 *
 * Debritsu decodes through libVLC, which ships with VLC. Bundling it was
 * considered for mpv and rejected for the same reasons that apply here: VLC is
 * large, and shipping it means redistributing GPL software with an obligation
 * to publish the corresponding source for that exact build. Asking winget to
 * fetch it from upstream leaves both with the people whose job they are.
 *
 * Nothing here runs unasked. It happens when somebody presses a button that
 * says what it will do.
 */
object VlcInstall {

    const val PACKAGE = "VideoLAN.VLC"

    val command = "winget install --id $PACKAGE --exact " +
        "--accept-package-agreements --accept-source-agreements"

    fun available(): Boolean = System.getenv("PATH").orEmpty()
        .split(File.pathSeparator)
        .any { File(it, "winget.exe").canExecute() }

    sealed interface Result {
        data class Installed(val dir: File) : Result
        data class Failed(val why: String) : Result
    }

    suspend fun install(onProgress: (String) -> Unit): Result = withContext(Dispatchers.IO) {
        if (!available()) {
            return@withContext Result.Failed(
                "winget is not on this machine. Install VLC yourself from videolan.org, " +
                    "then set its folder in Settings."
            )
        }

        onProgress("Asking winget for VLC — Windows may ask permission…")

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
                // winget draws a progress bar out of block characters, which
                // says nothing useful in a status line.
                if (text.isNotEmpty() && !text.all { it == '█' || it == '▒' || it == '-' }) {
                    BuildInfo.log("DebritsuVlc", "winget: $text")
                    onProgress(text.take(90))
                }
            }
        }

        val code = process.waitFor()
        BuildInfo.log("DebritsuVlc", "winget exited $code")

        // Looked for again rather than assumed: an exit code is not a working
        // library, and winget can report success having installed somewhere
        // this does not look.
        val dir = Vlc.directory()
        return@withContext when {
            dir != null -> Result.Installed(dir)
            code != 0 -> Result.Failed("winget exited with $code. Try the command by hand.")
            else -> Result.Failed(
                "winget finished but VLC was not found afterwards. " +
                    "Set its folder in Settings if it went somewhere unusual."
            )
        }
    }
}
