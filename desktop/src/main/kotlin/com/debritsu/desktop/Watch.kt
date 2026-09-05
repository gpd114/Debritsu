package com.debritsu.desktop

import com.debritsu.app.data.AniList
import com.debritsu.app.data.AutoPlay
import com.debritsu.app.data.BuildInfo
import com.debritsu.app.data.DownloadIndex
import com.debritsu.app.data.Progress
import com.debritsu.app.data.Settings
import com.debritsu.app.data.Subtitle
import com.debritsu.app.data.SyncQueue
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

        /**
         * Progress reached AniList, reported the moment it happens rather than
         * when playback ends.
         *
         * The watch loop runs for as long as mpv does, so anything reported at
         * the end waits for the window to be closed — which made a push that
         * had already succeeded look like it had not happened.
         */
        data class Pushed(val episode: Int) : State
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

        // A downloaded copy wins outright. It plays instantly, costs no debrid
        // call, and is the only thing that works with no network — which is the
        // entire reason downloads exist.
        val downloaded = DownloadIndex.get(anilistId, episode)
            ?.takeIf { Downloader.isComplete(it) }
        if (downloaded != null) {
            val file = Downloader.fileFor(downloaded)
            BuildInfo.log("DebritsuWatch", "playing local file ${file.absolutePath}")
            playFile(
                exe, file.absolutePath, title, episode, episodeMinutes,
                anilistId, emptyList(), onState
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

        playFile(
            exe, url, title, episode, episodeMinutes, anilistId, outcome.subtitles, onState
        )
    }

    /**
     * Plays whatever it is handed and watches it to the end.
     *
     * The same for a debrid URL and a file on disk: mpv takes either through one
     * argument, which is the reason downloading and streaming are one feature
     * here rather than two.
     */
    private suspend fun playFile(
        exe: File,
        url: String,
        title: String,
        episode: Int,
        episodeMinutes: Int,
        anilistId: Int,
        subtitles: List<Subtitle>,
        onState: (State) -> Unit
    ) {
        // Where this episode was left, if it was. Progress clears itself once an
        // episode is effectively finished, so this never offers to resume at the
        // credits of something already watched.
        val resumeFrom = Progress.position(anilistId, episode)
        if (resumeFrom > 0) {
            BuildInfo.log("DebritsuWatch", "resuming at ${resumeFrom}ms")
        }

        val session = Mpv.play(
            exe, url, "$title — episode $episode", subtitles, startAtMs = resumeFrom
        )
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
        var ticks = 0
        while (session.alive) {
            delay(1000)
            ticks++
            if (duration <= 0L) duration = session.durationMs() ?: 0L
            val position = session.positionMs() ?: continue

            // Saved as it goes rather than at the end, because playback does not
            // always end tidily — the window is closed, the machine sleeps, the
            // stream dies — and a position written only on a clean exit is
            // missing exactly when it was wanted.
            //
            // Every five seconds, not every one. The store rewrites its whole
            // file per key, so a per-second save would be some thousands of
            // rewrites across an episode to buy four seconds of accuracy.
            if (ticks % 5 == 0) Progress.save(anilistId, episode, position, duration)

            // Every tenth tick, so a failure to push can be read back rather
            // than guessed at. This is the rule that once marked a season
            // watched wrongly, so it is worth being able to see it work.
            if (BuildInfo.debug && ticks % 10 == 0) {
                val target = if (duration > 0) (duration * FINISHED_FRACTION).toLong() else -1
                BuildInfo.log(
                    "DebritsuWatch",
                    "pos=${position}ms dur=${duration}ms mark=${target}ms " +
                        "plausible=${looksLikeTheEpisode(duration, episodeMinutes)} pushed=$pushed"
                )
            }

            if (!pushed && duration > 0 &&
                position >= duration * FINISHED_FRACTION &&
                looksLikeTheEpisode(duration, episodeMinutes)
            ) {
                val result = runCatching { AniList.setProgress(anilistId, episode) }
                pushed = result.isSuccess
                BuildInfo.log(
                    "DebritsuWatch",
                    if (pushed) "pushed episode $episode for $anilistId"
                    else "push failed, queued: ${result.exceptionOrNull()}"
                )
                if (pushed) {
                    onState(State.Pushed(episode))
                } else {
                    // Watching a downloaded episode on a plane still counts. It
                    // is parked and replayed on the next connection, which is
                    // what SyncQueue has always been for — it simply had no
                    // caller here until downloads existed.
                    SyncQueue.queue(anilistId, episode)
                    onState(State.Playing("$title — episode $episode will sync when online"))
                    // Not retried every second for the rest of the episode.
                    pushed = true
                }
            }
        }

        session.close()
        onState(State.Finished(pushed))
    }
}
