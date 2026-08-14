package com.debritsu.app.cast

import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

/**
 * One list of everywhere a stream can be sent.
 *
 * No single protocol reaches every television: Cast covers Chromecast, Android
 * TV and Cast-enabled sets; DLNA covers Samsung, LG and Sony; an external
 * player covers whatever is left, including apps the user already trusts.
 */
sealed interface CastTarget {
    val label: String
    val detail: String

    data class Cast(val routeId: String, val deviceName: String) : CastTarget {
        override val label = deviceName
        override val detail = "Google Cast"
    }

    data class DlnaDevice(val renderer: Dlna.Renderer) : CastTarget {
        override val label = renderer.name
        override val detail = "DLNA · ${renderer.address}"
    }

    data object External : CastTarget {
        override val label = "Other app"
        override val detail = "VLC, MX Player, and anything else installed"
    }
}

object CastTargets {

    /**
     * Both protocols need a live network scan, so this suspends for a few
     * seconds by design. They run concurrently — sequentially it would be the
     * sum of two scans, and the user is staring at a spinner for all of it.
     */
    suspend fun discover(context: Context, isLocalFile: Boolean = false): List<CastTarget> =
        coroutineScope {
            val out = mutableListOf<CastTarget>()

            // A TV can't fetch a file from inside app storage, so network
            // targets are pointless for downloads — but another app on this
            // device can still be handed the file directly.
            if (!isLocalFile) {
                val cast = async { GoogleCast.discoverRoutes(context) }
                val dlna = async {
                    runCatching { Dlna.discover(context) }.getOrDefault(emptyList())
                }
                out += cast.await().map { CastTarget.Cast(it.id, it.name) }
                out += dlna.await().map { CastTarget.DlnaDevice(it) }
            }

            out += CastTarget.External
            out
        }

    /** Returns null on success, or a message explaining what went wrong. */
    suspend fun send(
        context: Context,
        target: CastTarget,
        url: String,
        title: String,
        positionMs: Long
    ): String? = when (target) {
        is CastTarget.Cast ->
            if (!GoogleCast.connect(context, target.routeId)) {
                "Couldn't connect to ${target.deviceName}. Make sure it's awake " +
                    "and on the same network."
            } else if (!GoogleCast.load(context, url, title, positionMs)) {
                "${target.deviceName} wouldn't play that stream — try a different source."
            } else {
                null
            }

        is CastTarget.DlnaDevice -> {
            val ok = Dlna.play(target.renderer, url, title)
            if (!ok) {
                // Most renderers have no TLS at all — DLNA's protocolInfo is
                // literally "http-get" — and debrid links are always HTTPS, so
                // this is the overwhelmingly likely cause rather than the codec.
                if (url.startsWith("https", ignoreCase = true)) {
                    "${target.renderer.name} can't play HTTPS links, which is what " +
                        "debrid gives us. Cast to a Chromecast or Android TV instead, " +
                        "or hand the stream to another app."
                } else {
                    "${target.renderer.name} refused the stream. Some TVs only accept " +
                        "MP4 — try a different source."
                }
            } else {
                if (positionMs > 15_000) {
                    // Most renderers reject a seek until they've buffered, so
                    // give it a moment and try once more before giving up. Not
                    // every set supports seeking at all; failure isn't fatal.
                    val seconds = positionMs / 1000
                    runCatching {
                        delay(1_500)
                        if (!Dlna.seek(target.renderer, seconds)) {
                            delay(2_500)
                            Dlna.seek(target.renderer, seconds)
                        }
                    }
                }
                null
            }
        }

        CastTarget.External -> {
            runCatching { ExternalPlayer.launch(context, url, title) }
                .exceptionOrNull()?.let { "No app on this device can play that link." }
        }
    }
}
