package com.debritsu.app.data

/**
 * Watch progress that couldn't reach AniList.
 *
 * Finishing an episode offline still counts, so the update is parked here and
 * replayed on the next successful connection. Only the highest episode per
 * title is kept — AniList progress is a single number, so replaying every
 * intermediate value would be pointless traffic.
 *
 * Its own store: a queue that empties itself, rather than settings that persist.
 */
object SyncQueue {

    /** Installed by the host at startup, like [Settings.store]. */
    var store: KeyValueStore = NoStore

    fun queue(mediaId: Int, episode: Int) {
        val existing = store.getInt(mediaId.toString(), 0)
        if (episode > existing) store.putInt(mediaId.toString(), episode)
    }

    fun pending(): Map<Int, Int> =
        store.keys().mapNotNull { k ->
            val id = k.toIntOrNull() ?: return@mapNotNull null
            val ep = store.getInt(k, 0)
            if (ep > 0) id to ep else null
        }.toMap()

    val count: Int get() = pending().size

    /** Replays everything queued; anything that fails stays for next time. */
    suspend fun flush() {
        if (Settings.aniListToken.isEmpty()) return
        pending().forEach { (mediaId, episode) ->
            val ok = runCatching { AniList.setProgress(mediaId, episode) }.isSuccess
            if (ok) store.remove(mediaId.toString())
        }
    }
}
