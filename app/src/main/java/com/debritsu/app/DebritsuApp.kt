package com.debritsu.app

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import com.debritsu.app.cast.GoogleCast
import com.debritsu.app.data.BuildInfo
import com.debritsu.app.data.DownloadIndex
import com.debritsu.app.data.KeyValueStore
import com.debritsu.app.data.Progress
import com.debritsu.app.data.Settings
import com.debritsu.app.data.SyncQueue

class DebritsuApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ctx = this

        // Before anything else. The shared module reads settings from whatever
        // store it is given and answers with defaults until it has one, so a
        // screen built ahead of this would quietly see a signed-out app with no
        // addons rather than fail in any way that pointed here.
        Settings.store = SharedPrefsStore(
            getSharedPreferences("debritsu", Context.MODE_PRIVATE)
        )
        // Its own file, as it has always been: a growing collection of resume
        // positions rather than a fixed set of settings.
        Progress.store = SharedPrefsStore(
            getSharedPreferences("progress", Context.MODE_PRIVATE)
        )
        // Same preference files and same keys these have always used, so an
        // existing library and an existing queue are read back unchanged.
        DownloadIndex.store = SharedPrefsStore(
            getSharedPreferences("downloads", Context.MODE_PRIVATE)
        )
        SyncQueue.store = SharedPrefsStore(
            getSharedPreferences("sync_queue", Context.MODE_PRIVATE)
        )
        BuildInfo.debug = BuildConfig.DEBUG
        BuildInfo.anilistClientId = BuildConfig.ANILIST_CLIENT_ID
        BuildInfo.log = { tag, message -> android.util.Log.d(tag, message) }

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

/**
 * Settings on Android, which is where they have always been.
 *
 * The keys are the settings' own, and unchanged from when this was written
 * inline, so an app updating into this build reads back everything it had.
 */
private class SharedPrefsStore(private val sp: SharedPreferences) : KeyValueStore {
    override fun getString(key: String, fallback: String) = sp.getString(key, fallback) ?: fallback
    override fun putString(key: String, value: String) = sp.edit().putString(key, value).apply()

    override fun getInt(key: String, fallback: Int) = sp.getInt(key, fallback)
    override fun putInt(key: String, value: Int) = sp.edit().putInt(key, value).apply()

    override fun getFloat(key: String, fallback: Float) = sp.getFloat(key, fallback)
    override fun putFloat(key: String, value: Float) = sp.edit().putFloat(key, value).apply()

    override fun getLong(key: String, fallback: Long) = sp.getLong(key, fallback)
    override fun putLong(key: String, value: Long) = sp.edit().putLong(key, value).apply()

    override fun getBoolean(key: String, fallback: Boolean) = sp.getBoolean(key, fallback)
    override fun putBoolean(key: String, value: Boolean) = sp.edit().putBoolean(key, value).apply()

    override fun remove(key: String) = sp.edit().remove(key).apply()
    override fun keys(): Set<String> = sp.all.keys
}
