package com.debritsu.app

import android.app.Application
import android.content.Context
import com.debritsu.app.cast.GoogleCast
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class DebritsuApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ctx = this
        // Nothing discovers Cast devices until CastContext exists, and building
        // it is asynchronous — it binds to Play Services, then registers the
        // Cast provider with MediaRouter. Built here, that's long done by the
        // time anyone taps cast; built on demand, the scan races the handshake
        // and usually wins nothing.
        GoogleCast.warmUp(this)
    }
    companion object {
        lateinit var ctx: Context
            private set
    }
}

object Http {
    /**
     * For addons, debrid and downloads: things that are genuinely big or
     * genuinely slow, and worth waiting on.
     */
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(150, TimeUnit.SECONDS)
        .build()

    /**
     * For metadata: AniList, Jikan, the id mappers. Small requests that either
     * answer in about a second or are not going to.
     *
     * These used to share the client above and inherited its 150 second call
     * timeout, which is right for a debrid link and badly wrong here. Measured
     * against AniList on 2026-08-30, one request in five simply hung — the
     * other four answered in 1 to 2 seconds — so a shelf that drew one of the
     * bad ones sat there for two and a half minutes rather than failing and
     * being asked again. The app's own media query answers in 0.9 to 3.1
     * seconds, so ten is about three times the worst healthy case.
     *
     * The connection and thread pools are shared, so this costs nothing to
     * keep around.
     */
    val meta: OkHttpClient = client.newBuilder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()
}
