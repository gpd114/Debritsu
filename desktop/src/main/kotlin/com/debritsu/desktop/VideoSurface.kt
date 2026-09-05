package com.debritsu.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import com.debritsu.app.data.BuildInfo
import com.sun.jna.Native
import java.awt.Canvas
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 * The surface mpv draws into, inside our own window.
 *
 * mpv takes `--wid=<handle>` and renders into a window somebody else owns, so
 * the player is embedded rather than opening beside us. Everything already
 * built stays: the same process, the same IPC, the same progress rule — only
 * where the picture lands changes.
 *
 * The cost, and it is worth naming: this is a heavyweight native surface, so
 * Compose cannot draw on top of it. Controls go beside the video rather than
 * floating over it. Overlaid controls would mean decoding frames ourselves and
 * drawing them as an image, which is a different and much larger piece of work.
 */
object VideoSurface {

    /**
     * A canvas and the native handle mpv needs.
     *
     * The handle only exists once the component is displayable, which is why
     * this is asked for after it is on screen rather than when it is made.
     */
    class Handle(val canvas: Canvas) {
        fun wid(): Long? = runCatching {
            val pointer = Native.getComponentPointer(canvas) ?: return null
            com.sun.jna.Pointer.nativeValue(pointer)
        }.onFailure {
            BuildInfo.log("DebritsuMpv", "no window handle: $it")
        }.getOrNull()
    }

    fun makeCanvas(): Handle {
        val canvas = object : Canvas() {
            // mpv paints every pixel of this. Letting AWT clear it first is a
            // flash of grey on every resize.
            override fun update(g: java.awt.Graphics) = paint(g)
        }
        canvas.background = java.awt.Color.BLACK
        canvas.ignoreRepaint = true
        canvas.isFocusable = true
        return Handle(canvas)
    }

    /**
     * Keyboard handling for the video area, through AWT rather than Compose.
     *
     * Compose's own key handling never fires here: the canvas is a heavyweight
     * component and takes the keys before Compose sees them, which is why
     * Escape did nothing. AWT is where they actually arrive, so this is where
     * they have to be caught.
     *
     * mpv's default bindings are switched off, so nothing else is competing for
     * these — the player has exactly the keys given to it here.
     */
    fun onKeys(
        handle: Handle,
        onEscape: () -> Unit,
        onSpace: () -> Unit,
        onSeek: (Int) -> Unit
    ) {
        val listener = object : java.awt.event.KeyAdapter() {
            override fun keyPressed(e: java.awt.event.KeyEvent) {
                when (e.keyCode) {
                    java.awt.event.KeyEvent.VK_ESCAPE -> onEscape()
                    java.awt.event.KeyEvent.VK_SPACE -> onSpace()
                    java.awt.event.KeyEvent.VK_LEFT -> onSeek(-10)
                    java.awt.event.KeyEvent.VK_RIGHT -> onSeek(30)
                    else -> return
                }
                e.consume()
            }
        }
        handle.canvas.addKeyListener(listener)
        handle.canvas.requestFocusInWindow()
    }
}

/**
 * Puts the canvas into the Compose layout.
 *
 * Wrapped in a JPanel because SwingPanel expects a container, and a bare Canvas
 * inside one resizes more predictably than one added directly.
 */
@Composable
fun VideoPanel(handle: VideoSurface.Handle, modifier: Modifier = Modifier) {
    val panel = remember(handle) {
        JPanel(BorderLayout()).apply {
            background = java.awt.Color.BLACK
            isOpaque = true
            add(handle.canvas, BorderLayout.CENTER)
        }
    }
    SwingPanel(
        background = Color.Black,
        modifier = modifier,
        factory = { panel }
    )
}
