package com.debritsu.app.data

/**
 * Where you got to in each episode, kept locally.
 *
 * AniList only stores "episode N watched", so mid-episode position has to live
 * on the device. An episode past the finish threshold is cleared rather than
 * stored, so finished episodes don't offer to resume at the credits.
 *
 * Its own store rather than the settings one: this is a collection that grows
 * with every episode started, and mixing it into a file of named settings would
 * make both harder to read and impossible to clear separately.
 */
object Progress {

    private const val FINISHED = 0.92f

    /** Installed by the host at startup, like [Settings.store]. */
    var store: KeyValueStore = NoStore

    private fun key(anilistId: Int, episode: Int) = "$anilistId:$episode"

    fun save(anilistId: Int, episode: Int, positionMs: Long, durationMs: Long) {
        if (anilistId <= 0 || episode <= 0 || durationMs <= 0) return
        val k = key(anilistId, episode)
        val fraction = positionMs.toFloat() / durationMs
        if (fraction >= FINISHED || positionMs < 15_000) {
            // Finished, or barely started — nothing worth resuming.
            store.remove(k)
            store.remove("$k:dur")
        } else {
            store.putLong(k, positionMs)
            store.putLong("$k:dur", durationMs)
        }
    }

    /** Saved position in ms, or 0 if there is nothing to resume. */
    fun position(anilistId: Int, episode: Int): Long =
        store.getLong(key(anilistId, episode), 0L)

    /** 0f..1f for the progress bar under an episode chip. */
    fun fraction(anilistId: Int, episode: Int): Float {
        val k = key(anilistId, episode)
        val pos = store.getLong(k, 0L)
        val dur = store.getLong("$k:dur", 0L)
        return if (pos > 0 && dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
    }

    fun clear(anilistId: Int, episode: Int) {
        val k = key(anilistId, episode)
        store.remove(k)
        store.remove("$k:dur")
    }
}
