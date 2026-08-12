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
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(150, TimeUnit.SECONDS)
        .build()
}
