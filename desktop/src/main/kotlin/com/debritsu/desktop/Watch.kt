package com.debritsu.desktop

import com.debritsu.app.data.AniList
import com.debritsu.app.data.AutoPlay
import com.debritsu.app.data.Settings
import kotlinx.coroutines.delay
import java.io.File

/**
 * Finding something to play, playing it, and telling AniList afterwards.
 *
 * The finding is entirely [AutoPlay], which is shared with the phone and the
 * television — the uncached rule, the plausible-size floor and the four
 * attempts are not reimplemented here. What is desktop-specific is only the
 * playing and the watching of it.
 */
object Watch {

    sealed interface State {
        data class Preparing(val what: String) : State
        data class Playing(val title: String) : State
        data class Finished(val pushed: Boolean) : State
        data class Failed(val why: String) : State
    }

    /**
     * Whether what is playing is long enough to be the episode it claims.
     *
     * The same rule the Android player uses, and for the same reason. Progress
     * is pushed at 85% watched, which is right for an episode and disastrous
     * for anything short: a creditless opening runs about ninety seconds, so
     * 85% of it arrives after little over a minute. A season of those once
     * marked a whole season complete on AniList that had never been played.
     *
     * Half of AniList's own minutes-per-episode where it knows, since a file
     * can legitimately be a little short of its nominal running time; four
     * minutes where it does not, which is beneath any real episode and above
     * every opening, ending and trailer.
     */
    private fun looksLikeTheEpisode(durationMs: Long, episodeMinutes: Int): Boolean {
        val floorMs = if (episodeMinutes > 0) episodeMinutes * 60_000L / 2 else 4 * 60_000L
        return durationMs >= floorMs
    }

    /** Watched far enough to count. */
    private const val FINISHED_FRACTION = 0.85

    suspend fun episode(
        anilistId: Int,
        title: String,
        episode: Int,
        episodeMinutes: Int,
        isMovie: Boolean,
        onState: (State) -> Unit
    ) {
        val exe = Mpv.locate(Settings.store.getString("mpv_path", ""))
        if (exe == null) {
            onState(
                State.Failed(
                    "mpv was not found. Install it (winget install shinchiro.mpv) " +
                        "or set its path in Settings — it does not go on PATH."
                )
            )
            return
        }

        onState(State.Preparing("Locating"))
        val outcome = AutoPlay.run(
            anilistId = anilistId,
            title = title,
            episode = episode,
            isMovie = isMovie,
            filter = Settings.sourceFilter,
            episodeMinutes = episodeMinutes
        ) { step ->
            onState(
                State.Preparing(
                    when (step) {
                        is AutoPlay.Step.Locating -> "Locating"
                        is AutoPlay.Step.Searching -> "Searching addons"
                        is AutoPlay.Step.Filtering -> "Filtering ${step.found} sources — ${step.kept} kept"
                        is AutoPlay.Step.Resolving -> "Resolving ${step.attempt} of ${step.of}"
                        is AutoPlay.Step.Ready -> "Starting"
                    }
                )
            )
        }

        val url = outcome.url
        if (url == null) {
            onState(State.Failed(outcome.message ?: "Nothing playable was found."))
            return
        }

        val session = Mpv.play(exe, url, "$title — episode $episode", outcome.subtitles)
        if (session == null) {
            onState(State.Failed("mpv would not start, or its pipe never appeared."))
            return
        }

        onState(State.Playing(title))

        // Polled about once a second rather than observed. observe_property
        // works, but pushes roughly thirty times a second, and the only
        // question being asked is whether one threshold has been crossed.
        var pushed = false
        var duration = 0L
        while (session.alive) {
            delay(1000)
            if (duration <= 0L) duration = session.durationMs() ?: 0L
            val position = session.positionMs() ?: continue

            if (!pushed && duration > 0 &&
                position >= duration * FINISHED_FRACTION &&
                looksLikeTheEpisode(duration, episodeMinutes)
            ) {
                pushed = runCatching { AniList.setProgress(anilistId, episode) }.isSuccess
                if (pushed && Settings.aniListToken.isNotEmpty()) {
                    onState(State.Playing("$title — episode $episode marked watched"))
                }
            }
        }

        session.close()
        onState(State.Finished(pushed))
    }
}
