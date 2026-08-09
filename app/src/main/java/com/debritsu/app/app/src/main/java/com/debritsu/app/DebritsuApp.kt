package com.debritsu.app

import android.app.Application
import android.content.Context
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class DebritsuApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ctx = this
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
