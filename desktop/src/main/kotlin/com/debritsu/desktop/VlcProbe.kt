package com.debritsu.desktop

import com.sun.jna.NativeLibrary
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import java.io.File

/**
 * Finding libVLC, and saying so plainly when it is not there.
 *
 * vlcj loads a native library rather than launching a program, so this is a
 * harder dependency than mpv was: a missing or mismatched libvlc is a link
 * error at startup rather than a process that fails to run. It is looked for in
 * the usual places and the answer is remembered.
 */
object Vlc {

    private val candidates = listOf(
        "C:\\Program Files\\VideoLAN\\VLC",
        "C:\\Program Files (x86)\\VideoLAN\\VLC"
    )

    fun directory(configured: String = ""): File? {
        if (configured.isNotBlank()) {
            val f = File(configured)
            if (File(f, "libvlc.dll").exists()) return f
        }
        return candidates.map(::File).firstOrNull { File(it, "libvlc.dll").exists() }
    }

    /**
     * Points JNA at the VLC directory before anything tries to load it.
     *
     * Both the library path and the plugin path are needed: libvlc finds its
     * codecs through VLC_PLUGIN_PATH, and without it the library loads and then
     * plays nothing, which is a far more confusing failure than not loading.
     */
    fun prepare(configured: String = ""): File? {
        val dir = directory(configured) ?: return null
        NativeLibrary.addSearchPath("libvlc", dir.absolutePath)
        System.setProperty("jna.library.path", dir.absolutePath)
        System.setProperty("VLC_PLUGIN_PATH", File(dir, "plugins").absolutePath)
        return dir
    }

    /** Version string if libVLC loads, null if it does not. */
    fun version(configured: String = ""): String? {
        prepare(configured) ?: return null
        return runCatching {
            val factory = MediaPlayerFactory()
            val v = factory.application().version()
            factory.release()
            v
        }.getOrNull()
    }
}
