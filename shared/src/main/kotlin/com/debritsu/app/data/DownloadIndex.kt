package com.debritsu.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * A downloaded episode.
 *
 * Everything needed to browse and play it is stored here, deliberately: the
 * library has to work with no network at all, so nothing may depend on an
 * AniList lookup at read time. That is the whole point of downloading — the
 * aeroplane has no AniList either.
 */
@Serializable
data class Downloaded(
    val anilistId: Int,
    val episode: Int,
    val title: String,
    val coverPath: String?,
    val fileName: String,
    val sourceName: String,
    /**
     * Android's DownloadManager job id, which is how status and progress are
     * asked for there. Nothing on the desktop side, which does its own
     * transferring and tracks progress in memory.
     */
    val downloadId: Long = 0,
    val totalEpisodes: Int? = null
) {
    val key: String get() = "$anilistId:$episode"
}

/**
 * What has been downloaded, as a list persisted under one key.
 *
 * The list rather than the files: which episode of what, its title, its poster,
 * where the bytes are. The transferring itself is the part that differs by
 * platform — DownloadManager on Android, plain HTTP on the desktop — and is not
 * here.
 *
 * The key and the JSON shape are what the Android build already writes, so an
 * existing library is read back unchanged.
 */
object DownloadIndex {

    /** Installed by the host at startup, like [Settings.store]. */
    var store: KeyValueStore = NoStore

    fun all(): List<Downloaded> = runCatching {
        json.decodeFromString(
            ListSerializer(Downloaded.serializer()),
            store.getString("items", "[]")
        )
    }.getOrDefault(emptyList())

    fun save(items: List<Downloaded>) {
        store.putString(
            "items",
            json.encodeToString(ListSerializer(Downloaded.serializer()), items)
        )
    }

    fun get(anilistId: Int, episode: Int): Downloaded? =
        all().firstOrNull { it.anilistId == anilistId && it.episode == episode }

    /** Adds or replaces, keyed by show and episode. */
    fun put(item: Downloaded) {
        save(all().filterNot { it.key == item.key } + item)
    }

    fun forget(item: Downloaded) {
        save(all().filterNot { it.key == item.key })
    }

    /**
     * A safe file name for an episode, the same on every platform so a library
     * copied between them still resolves.
     */
    fun fileNameFor(title: String, episode: Int): String {
        val safe = title.replace(Regex("[^A-Za-z0-9 ._-]"), "").take(60).trim()
        return "$safe - E${episode.toString().padStart(2, '0')}.mp4"
    }
}
