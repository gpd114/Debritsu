package com.debritsu.app.cast

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Google Cast: Chromecast, Android TV, and the many smart TVs with Cast built in.
 *
 * Everything here degrades quietly when Play Services is missing, so the app
 * still runs on de-Googled devices — where DLNA and external players remain.
 *
 * Every entry point hops to the main thread: CastContext, SessionManager and
 * MediaRouter all throw if touched from a background thread, and the failure is
 * silent once it's been swallowed by a runCatching.
 */
object GoogleCast {

    data class Route(val id: String, val name: String)

    fun isAvailable(context: Context): Boolean =
        GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS

    private fun contextOrNull(context: Context): CastContext? = runCatching {
        if (!isAvailable(context)) null else CastContext.getSharedInstance(context)
    }.getOrNull()

    /**
     * Builds CastContext early so the Cast provider is registered and scanning
     * before the user ever opens the picker. Safe on de-Googled devices — it
     * quietly does nothing when Play Services isn't there. Main thread only,
     * which Application.onCreate already is.
     */
    fun warmUp(context: Context) {
        runCatching { contextOrNull(context) }
    }

    private var retained: MediaRouter.Callback? = null

    /**
     * Keeps MediaRouter's route list alive.
     *
     * The router forgets every route the moment its last callback is removed,
     * so a route id collected during a scan is already dead by the time the
     * user taps it — selection then fails instantly with nothing in the log.
     * Holding a discovery request for as long as the player is on screen keeps
     * the list valid across that gap. Main thread only.
     */
    fun retainRoutes(context: Context) {
        runCatching {
            if (retained != null) return@runCatching
            contextOrNull(context) ?: return@runCatching
            val cb = object : MediaRouter.Callback() {}
            MediaRouter.getInstance(context)
                .addCallback(selector(), cb, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
            retained = cb
        }
    }

    fun releaseRoutes(context: Context) {
        runCatching {
            retained?.let { MediaRouter.getInstance(context).removeCallback(it) }
            retained = null
        }
    }

    private fun selector() = MediaRouteSelector.Builder()
        .addControlCategory(
            CastMediaControlIntent.categoryForCast(CastOptionsProvider.DEFAULT_RECEIVER_ID)
        )
        .build()

    /**
     * Cast receivers on the network.
     *
     * MediaRouter only keeps its route list current while something is actively
     * scanning, so this registers a callback, waits, and tears it down again —
     * reading `router.routes` without that returns whatever happened to be
     * cached, which is usually nothing.
     */
    suspend fun discoverRoutes(context: Context, timeoutMs: Long = 6000): List<Route> =
        withContext(Dispatchers.Main) {
            runCatching {
                contextOrNull(context) ?: return@runCatching emptyList<Route>()

                val router = MediaRouter.getInstance(context)
                val selector = selector()
                val callback = object : MediaRouter.Callback() {}
                router.addCallback(
                    selector, callback, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN
                )
                try {
                    // Poll rather than sleep the whole window: a warm receiver
                    // answers almost at once, and waiting the full timeout for
                    // it makes the picker feel broken. Once something answers,
                    // linger briefly so a slower second device still lands.
                    val deadline = System.currentTimeMillis() + timeoutMs
                    var found = emptyList<Route>()
                    var firstAnswer = 0L
                    while (System.currentTimeMillis() < deadline) {
                        delay(400)
                        found = router.routes
                            .filter { !it.isDefault && it.matchesSelector(selector) }
                            .map { Route(it.id, it.name) }
                        if (found.isNotEmpty()) {
                            if (firstAnswer == 0L) firstAnswer = System.currentTimeMillis()
                            if (System.currentTimeMillis() - firstAnswer >= 1200) break
                        }
                    }
                    found
                } finally {
                    router.removeCallback(callback)
                }
            }.getOrDefault(emptyList())
        }

    /**
     * Selects a route and waits for the session handshake.
     *
     * Selecting is all it takes to start a session — the Cast SDK picks that up
     * from the registered CastOptionsProvider — but the receiver may take a few
     * seconds to wake, so the connected session has to be waited for rather
     * than assumed.
     */
    suspend fun connect(context: Context, routeId: String, timeoutMs: Long = 20_000): Boolean =
        withContext(Dispatchers.Main) {
            runCatching {
                val cast = contextOrNull(context) ?: return@runCatching false
                val router = MediaRouter.getInstance(context)

                val current = cast.sessionManager.currentCastSession
                if (current?.isConnected == true && router.selectedRoute.id == routeId) {
                    return@runCatching true
                }

                // Hold discovery open for the whole handshake rather than
                // trusting the route to still be published: an unpublished
                // route can't be selected, and that failure is silent.
                val selector = selector()
                val cb = object : MediaRouter.Callback() {}
                router.addCallback(
                    selector, cb, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN
                )
                try {
                    val route = withTimeoutOrNull(5_000) {
                        var found = router.routes.firstOrNull { it.id == routeId }
                        while (found == null) {
                            delay(300)
                            found = router.routes.firstOrNull { it.id == routeId }
                        }
                        found
                    } ?: return@runCatching false

                    router.selectRoute(route)

                    withTimeoutOrNull(timeoutMs) {
                        while (cast.sessionManager.currentCastSession?.isConnected != true) {
                            delay(250)
                        }
                        true
                    } == true
                } finally {
                    router.removeCallback(cb)
                }
            }.getOrDefault(false)
        }

    suspend fun load(
        context: Context,
        url: String,
        title: String,
        positionMs: Long = 0
    ): Boolean = withContext(Dispatchers.Main) {
        runCatching {
            val session = contextOrNull(context)?.sessionManager?.currentCastSession
                ?: return@runCatching false
            val client = session.remoteMediaClient ?: return@runCatching false

            val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
                putString(MediaMetadata.KEY_TITLE, title)
            }
            val info = MediaInfo.Builder(url)
                .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                .setContentType(guessMime(url))
                .setMetadata(metadata)
                .build()

            val request = client.load(
                MediaLoadRequestData.Builder()
                    .setMediaInfo(info)
                    .setAutoplay(true)
                    .setCurrentTime(positionMs)
                    .build()
            )

            // load() is asynchronous; without waiting for the result a receiver
            // that rejects the stream still reports success.
            withTimeoutOrNull(30_000) {
                suspendCancellableCoroutine<Boolean> { cont ->
                    request.setResultCallback { result ->
                        if (cont.isActive) cont.resume(result.status.isSuccess)
                    }
                }
            } == true
        }.getOrDefault(false)
    }

    private fun guessMime(url: String) =
        when (url.substringAfterLast('.', "").substringBefore('?').lowercase()) {
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "m3u8" -> "application/x-mpegURL"
            "avi" -> "video/x-msvideo"
            else -> "video/mp4"
        }
}

/**
 * Hand the stream to whatever the user already has — VLC, MX Player, Just
 * Player, or a TV's own app. Covers everything the two cast protocols miss.
 */
object ExternalPlayer {

    /**
     * Downloads live in app-private storage, which other apps cannot browse to
     * on Android 11+. Handing out a content:// URI grants read access to that
     * one file for the life of the intent — so VLC, Web Video Caster and the
     * rest can open a downloaded episode even though they could never find it.
     */
    fun intentFor(context: Context, url: String, title: String): Intent {
        val uri = if (url.startsWith("http")) {
            Uri.parse(url)
        } else {
            val file = java.io.File(Uri.parse(url).path ?: url)
            FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file
            )
        }
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/*")
            putExtra("title", title)
            // VLC and MX Player both read these.
            putExtra("secure_uri", true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun launch(context: Context, url: String, title: String) {
        val chooser = Intent.createChooser(intentFor(context, url, title), "Play with")
        chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(chooser)
    }
}
