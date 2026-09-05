package com.debritsu.desktop

import com.debritsu.app.data.KeyValueStore
import java.io.File
import java.util.Properties

/**
 * Settings on the desktop: one properties file beside the user's other
 * application data.
 *
 * The keys are the same ones the Android build writes into SharedPreferences,
 * because they belong to [com.debritsu.app.data.Settings] rather than to either
 * host. Nothing reads across today — the two machines share nothing — but the
 * names matching is what would make copying a profile between them a file copy
 * rather than a migration.
 *
 * Written out on every change. These are a few hundred bytes and are changed by
 * hand, at human speed; batching writes would only add a way to lose them.
 */
class FileStore(private val file: File) : KeyValueStore {

    private val props = Properties().apply {
        if (file.exists()) file.inputStream().use { load(it) }
    }

    private fun save() {
        file.parentFile?.mkdirs()
        // Through a temporary file: a half-written settings file is worse than
        // an old one, and a crash mid-write is exactly when it would happen.
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.outputStream().use { props.store(it, "Debritsu") }
        tmp.renameTo(file)
    }

    private fun put(key: String, value: String) {
        props.setProperty(key, value)
        save()
    }

    override fun getString(key: String, fallback: String): String =
        props.getProperty(key) ?: fallback

    override fun putString(key: String, value: String) = put(key, value)

    override fun getInt(key: String, fallback: Int): Int =
        props.getProperty(key)?.toIntOrNull() ?: fallback

    override fun putInt(key: String, value: Int) = put(key, value.toString())

    override fun getFloat(key: String, fallback: Float): Float =
        props.getProperty(key)?.toFloatOrNull() ?: fallback

    override fun putFloat(key: String, value: Float) = put(key, value.toString())

    override fun getBoolean(key: String, fallback: Boolean): Boolean =
        props.getProperty(key)?.toBooleanStrictOrNull() ?: fallback

    override fun putBoolean(key: String, value: Boolean) = put(key, value.toString())

    companion object {
        /**
         * `%APPDATA%\Debritsu\settings.properties` on Windows, and a dotted
         * directory in the home folder anywhere else. Beside the user's other
         * application data rather than beside the executable, so it survives the
         * program being moved or reinstalled.
         */
        fun default(): FileStore {
            val appData = System.getenv("APPDATA")
            val dir =
                if (!appData.isNullOrBlank()) File(appData, "Debritsu")
                else File(System.getProperty("user.home"), ".debritsu")
            return FileStore(File(dir, "settings.properties"))
        }
    }
}
