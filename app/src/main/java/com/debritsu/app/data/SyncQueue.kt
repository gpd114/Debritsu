package com.debritsu.app.data

import android.content.Context
import com.debritsu.app.DebritsuApp

/**
 * Watch progress that couldn't reach AniList.
 *
 * Finishing an episode offline still counts, so the update is parked here and
 * replayed on the next successful connection. Only the highest episode per
 * title is kept — AniList progress is a single number, so replaying every
 * intermediate value would be pointless traffic.
 */
object SyncQueue {

    private val sp by lazy {
        DebritsuApp.ctx.getSharedPreferences("sync_queue", Context.MODE_PRIVATE)
    }

    fun queue(mediaId: Int, episode: Int) {
        val existing = sp.getInt(mediaId.toString(), 0)
        if (episode > existing) {
            sp.edit().putInt(mediaId.toString(), episode).apply()
        }
    }

    fun pending(): Map<Int, Int> =
        sp.all.mapNotNull { (k, v) ->
            val id = k.toIntOrNull() ?: return@mapNotNull null
            val ep = (v as? Int) ?: return@mapNotNull null
            id to ep
        }.toMap()

    val count: Int get() = pending().size

    /** Replays everything queued; anything that fails stays for next time. */
    suspend fun flush() {
        if (Settings.aniListToken.isEmpty()) return
        pending().forEach { (mediaId, episode) ->
            val ok = runCatching { AniList.setProgress(mediaId, episode) }.isSuccess
            if (ok) sp.edit().remove(mediaId.toString()).apply()
        }
    }
}
