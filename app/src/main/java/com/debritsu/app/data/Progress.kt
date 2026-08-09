package com.debritsu.app.data

import android.content.Context
import com.debritsu.app.DebritsuApp

/**
 * Where you got to in each episode, kept locally.
 *
 * AniList only stores "episode N watched", so mid-episode position has to live
 * on the device. An episode past the finish threshold is cleared rather than
 * stored, so finished episodes don't offer to resume at the credits.
 */
object Progress {

    private const val FINISHED = 0.92f

    private val sp by lazy {
        DebritsuApp.ctx.getSharedPreferences("progress", Context.MODE_PRIVATE)
    }

    private fun key(anilistId: Int, episode: Int) = "$anilistId:$episode"

    fun save(anilistId: Int, episode: Int, positionMs: Long, durationMs: Long) {
        if (anilistId <= 0 || episode <= 0 || durationMs <= 0) return
        val k = key(anilistId, episode)
        val fraction = positionMs.toFloat() / durationMs
        if (fraction >= FINISHED || positionMs < 15_000) {
            // Finished, or barely started — nothing worth resuming.
            sp.edit().remove(k).remove("$k:dur").apply()
        } else {
            sp.edit().putLong(k, positionMs).putLong("$k:dur", durationMs).apply()
        }
    }

    /** Saved position in ms, or 0 if there is nothing to resume. */
    fun position(anilistId: Int, episode: Int): Long =
        sp.getLong(key(anilistId, episode), 0L)

    /** 0f..1f for the progress bar under an episode chip. */
    fun fraction(anilistId: Int, episode: Int): Float {
        val k = key(anilistId, episode)
        val pos = sp.getLong(k, 0L)
        val dur = sp.getLong("$k:dur", 0L)
        return if (pos > 0 && dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
    }

    fun clear(anilistId: Int, episode: Int) {
        val k = key(anilistId, episode)
        sp.edit().remove(k).remove("$k:dur").apply()
    }
}
