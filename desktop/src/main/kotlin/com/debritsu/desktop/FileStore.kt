package com.debritsu.desktop

import com.debritsu.app.data.KeyValueStore
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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

        // Files.move, not File.renameTo. On Windows renameTo will not replace an
        // existing file: it returns false and throws nothing, so the first
        // setting saved and every one after it was silently discarded — the
        // token was kept because there was no file yet, and the addon URL
        // entered a minute later vanished without a word.
        Files.move(
            tmp.toPath(),
            file.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE
        )
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
        fun directory(): File {
            val appData = System.getenv("APPDATA")
            return if (!appData.isNullOrBlank()) File(appData, "Debritsu")
            else File(System.getProperty("user.home"), ".debritsu")
        }

        fun default(): FileStore = FileStore(File(directory(), "settings.properties"))
    }
}
