package com.debritsu.desktop

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import com.debritsu.app.data.BuildInfo
import java.io.File

/**
 * The running player, held outside the composition.
 *
 * Going fullscreen has to recreate the window: Java can only take a window
 * borderless if it was built undecorated, and `undecorated` is fixed when the
 * window is made. Recreating it throws away everything composed inside, which
 * would have stopped playback and started it again from the beginning every
 * time somebody pressed F.
 *
 * So the player lives here instead, keyed by what it is playing. The screen
 * asks for the one it wants and gets the same instance back across a window
 * being rebuilt.
 */
object ActivePlayer {

    private var key: String? = null
    private var player: VlcPlayer? = null

    /**
     * What is playing, or null for nothing.
     *
     * Out here rather than in the app's composition for the same reason the
     * player is, and for one more: the fullscreen window and the main window
     * are two separate compositions, and hiding a window stops its own. So a
     * source switched from inside fullscreen would be invisible to the window
     * underneath, which would then compose with the old target and restart
     * playback of the release that had just been swapped away. Held as Compose
     * state so either window sees a change the other made.
     */
    val playing: MutableState<Watch.Target?> = mutableStateOf(null)

    /** The player for [url], made if there is not one already. */
    @Synchronized
    fun of(url: String, vlcDirectory: File): VlcPlayer {
        val existing = player
        if (existing != null && key == url) return existing

        // A different episode: the old one goes before the new one starts, or
        // two players hold the same audio device and both are heard.
        existing?.let {
            BuildInfo.log("DebritsuVlc", "releasing previous player")
            runCatching { it.release() }
        }

        val fresh = VlcPlayer(vlcDirectory)
        player = fresh
        key = url
        return fresh
    }

    /** Whether [url] is the one currently held, and so already playing. */
    @Synchronized
    fun holds(url: String): Boolean = key == url && player != null

    @Synchronized
    fun release() {
        player?.let { runCatching { it.release() } }
        player = null
        key = null
        playing.value = null
    }
}
