package com.debritsu.desktop

import com.debritsu.app.data.AniList
import com.debritsu.app.data.AutoPlay
import com.debritsu.app.data.BuildInfo
import com.debritsu.app.data.Debrid
import com.debritsu.app.data.DownloadIndex
import com.debritsu.app.data.StreamOption
import com.debritsu.app.data.Progress
import com.debritsu.app.data.Settings
import com.debritsu.app.data.Subtitle
import com.debritsu.app.data.SyncQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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

    /**
     * Everything needed to play something, once the finding is done.
     *
     * Handed to the player screen, which owns the window mpv draws into and so
     * has to be the thing that starts it — the resolving cannot also start
     * playback without knowing where to put it.
     */
    data class Target(
        val url: String,
        val title: String,
        val episode: Int,
        val episodeMinutes: Int,
        val anilistId: Int,
        /** Carried so the player can list sources without asking the shelves. */
        val isMovie: Boolean,
        val subtitles: List<Subtitle>,
        val resumeFromMs: Long,
        /**
         * Which release this is playing, so the source list can mark it.
         *
         * A hash rather than a name, because names do not identify anything —
         * four rows of one episode came back all called "[TB ⚡] Comet 1080p".
         * Null for a downloaded file, which came from no list.
         */
        val sourceKey: String?,
        /** Where libVLC lives. Held here so the screen need not look again. */
        val vlcDir: File
    )

    /**
     * The little a running player has to expose for progress to be followed.
     *
     * Deliberately small: what is watched is a position, a duration and whether
     * it is still going. Nothing about how it decodes belongs here, which is
     * what let the player be replaced without touching the rule that pushes
     * progress.
     */
    interface Playing {
        val alive: Boolean
        fun positionMs(): Long?
        fun durationMs(): Long?
    }

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

        /** Found something playable. The player screen takes it from here. */
        data class Ready(val target: Target) : State

        /**
         * Automatic selection is off, and these are the sources found.
         *
         * Everything needed to play one is carried along, because the choosing
         * happens in the interface and the playing happens back here.
         */
        data class Choose(
            val outcome: AutoPlay.Outcome,
            val anilistId: Int,
            val title: String,
            val episode: Int,
            val episodeMinutes: Int,
            val isMovie: Boolean
        ) : State
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
        val vlcDir = Vlc.directory(Settings.store.getString("vlc_path", ""))
        if (vlcDir == null) {
            onState(
                State.Failed(
                    "VLC was not found. Debritsu decodes through libVLC, which " +
                        "comes with VLC — install it, or set its folder in Settings."
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
            onState(
                State.Ready(
                    target(
                        vlcDir, file.absolutePath, title, episode, episodeMinutes, anilistId,
                        isMovie, emptyList(), null
                    )
                )
            )
            return
        }

        onState(State.Preparing("Locating"))
        val outcome = find(anilistId, title, episode, episodeMinutes, isMovie, Settings.autoPlay, onState)

        // Automatic selection switched off: hand the list back and let the
        // caller choose. Both routes go through the same finding, so a manual
        // pick sees exactly the sources the automatic one considered.
        if (!Settings.autoPlay && outcome.url == null && outcome.message == null) {
            onState(State.Choose(outcome, anilistId, title, episode, episodeMinutes, isMovie))
            return
        }

        val url = outcome.url
        if (url == null) {
            onState(State.Failed(outcome.message ?: "Nothing playable was found."))
            return
        }

        onState(
            State.Ready(
                target(
                    vlcDir, url, title, episode, episodeMinutes, anilistId, isMovie,
                    outcome.subtitles, outcome.chosen
                )
            )
        )
    }

    /**
     * Assembles what the player screen needs, including where to resume.
     *
     * Progress clears itself once an episode is effectively finished, so this
     * never offers to resume at the credits of something already watched.
     */
    private fun target(
        vlcDir: File,
        url: String,
        title: String,
        episode: Int,
        episodeMinutes: Int,
        anilistId: Int,
        isMovie: Boolean,
        subtitles: List<Subtitle>,
        source: StreamOption?
    ): Target {
        val resume = Progress.position(anilistId, episode)
        if (resume > 0) BuildInfo.log("DebritsuWatch", "resuming at ${resume}ms")
        source?.let {
            BuildInfo.log("DebritsuWatch", "playing ${it.name.lineSequence().first()} (${it.identity()})")
        }
        return Target(
            url, title, episode, episodeMinutes, anilistId, isMovie, subtitles, resume,
            source?.identity(), vlcDir
        )
    }

    /**
     * Finds sources and hands them over without playing anything.
     *
     * The same finding as playing does, with the choosing left out. Reachable
     * whatever the auto-play setting says: wanting to pick a source for one
     * episode — because the automatic choice was dubbed, or the wrong release,
     * or would not resolve — is a normal thing to want, and making it require a
     * change to a global setting first was the wrong shape.
     */
    suspend fun sources(
        anilistId: Int,
        title: String,
        episode: Int,
        episodeMinutes: Int,
        isMovie: Boolean,
        onState: (State) -> Unit
    ) {
        onState(State.Preparing("Locating"))
        val outcome = find(anilistId, title, episode, episodeMinutes, isMovie, false, onState)
        if (outcome.results.flatMap { it.streams }.isEmpty()) {
            onState(State.Failed(outcome.message ?: "No sources were found."))
            return
        }
        onState(State.Choose(outcome, anilistId, title, episode, episodeMinutes, isMovie))
    }

    /** The finding half, shared by playing and by listing sources. */
    private suspend fun find(
        anilistId: Int,
        title: String,
        episode: Int,
        episodeMinutes: Int,
        isMovie: Boolean,
        autoSelect: Boolean,
        onState: (State) -> Unit
    ): AutoPlay.Outcome {
        return AutoPlay.run(
            anilistId = anilistId,
            title = title,
            episode = episode,
            isMovie = isMovie,
            filter = Settings.sourceFilter,
            episodeMinutes = episodeMinutes,
            autoSelect = autoSelect
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
    }

    /**
     * Plays a source the viewer chose themselves.
     *
     * Resolved here rather than when the list was built: resolving every source
     * to show a list would add each one to the debrid account, which is a
     * download nobody asked for — the same reason auto-play never touches an
     * uncached source.
     */
    suspend fun chosen(
        stream: StreamOption,
        subtitles: List<Subtitle>,
        anilistId: Int,
        title: String,
        episode: Int,
        episodeMinutes: Int,
        isMovie: Boolean,
        onState: (State) -> Unit
    ) {
        val vlcDir = Vlc.directory(Settings.store.getString("vlc_path", ""))
        if (vlcDir == null) {
            onState(State.Failed("VLC was not found. Set its folder in Settings."))
            return
        }

        onState(State.Preparing("Resolving ${stream.name.lineSequence().first().take(60)}"))
        val url = withContext(Dispatchers.IO) { runCatching { Debrid.resolve(stream) }.getOrNull() }
        if (url == null) {
            onState(
                State.Failed(
                    "That source would not resolve — most likely it is not cached with " +
                        "${Settings.debridProvider.label}."
                )
            )
            return
        }

        val subs = (stream.subtitles + subtitles).distinctBy { it.url }
        onState(
            State.Ready(
                target(
                    vlcDir, url, title, episode, episodeMinutes, anilistId, isMovie, subs,
                    stream
                )
            )
        )
    }

    /**
     * Plays whatever it is handed and watches it to the end.
     *
     * The same for a debrid URL and a file on disk: libVLC opens either from one
     * string, which is why downloading and streaming are one feature here
     * rather than two.
     */
    fun start(player: VlcPlayer, target: Target) = player.play(
        url = target.url,
        subtitles = target.subtitles,
        startAtMs = target.resumeFromMs,
        audioLanguage = Settings.preferredAudioLanguage,
        subtitleLanguage = Settings.store.getString("sub_lang", "eng")
    )

    /**
     * Watches a running player to the end: saving position, and pushing
     * progress once far enough in.
     *
     * Separate from starting it, because the player screen owns the player — it
     * draws the frames and has to be able to pause and seek the same thing this
     * is reading. Takes [Playing] rather than a concrete player, which is what
     * let mpv be swapped for libVLC without touching the progress rule.
     */
    suspend fun follow(
        session: Playing,
        target: Target,
        onState: (State) -> Unit
    ) {
        val episode = target.episode
        val anilistId = target.anilistId
        val episodeMinutes = target.episodeMinutes

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
                    onState(State.Playing("Episode $episode will sync when online"))
                    // Not retried every second for the rest of the episode.
                    pushed = true
                }
            }
        }

        onState(State.Finished(pushed))
    }
}
