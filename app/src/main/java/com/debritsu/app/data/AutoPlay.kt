package com.debritsu.app.data

import android.util.Log
import com.debritsu.app.BuildConfig

/**
 * Everything between pressing play and a URL that will actually play, narrated
 * as it goes.
 *
 * The steps are reported rather than inferred because each one can take a
 * noticeable moment for a different reason — a mapping lookup, a fan-out across
 * addons, a debrid provider waking a torrent up — and "loading" for eight
 * seconds tells nobody which of those is happening, or whether it has hung.
 *
 * There is no cache lookup to make first: the resolvers add the magnet and
 * report failure if the provider hasn't got it. So "checking" here means trying
 * the best match and moving down the list, which is the only honest version.
 */
object AutoPlay {

    sealed interface Step {
        /** Working out which ids the addons index this title under. */
        data object Locating : Step
        data object Searching : Step
        data class Filtering(val found: Int, val kept: Int) : Step
        data class Resolving(val name: String, val attempt: Int, val of: Int) : Step
        data object Ready : Step
    }

    /**
     * [url] non-null means play it. Null means fall back to the source list,
     * with [message] explaining why — never silently play something the
     * filters were meant to exclude.
     */
    data class Outcome(
        val url: String?,
        val chosen: StreamOption?,
        /** Kept per addon so a fallback to the list shows each one's failure. */
        val results: List<Stremio.AddonResult>,
        val subtitles: List<Subtitle>,
        val message: String?
    )

    /** How many candidates to try before giving up and handing over the list. */
    private const val MAX_ATTEMPTS = 4

    /**
     * [autoSelect] false does the finding but none of the choosing — the caller
     * gets the sources and picks. Used when automatic selection is switched
     * off, so both ways of starting an episode go through one path.
     */
    suspend fun run(
        anilistId: Int,
        title: String?,
        episode: Int,
        isMovie: Boolean,
        filter: SourceFilter,
        /** Running time of one episode, for the plausible-size floor. */
        episodeMinutes: Int = 0,
        autoSelect: Boolean = true,
        onStep: (Step) -> Unit
    ): Outcome {
        onStep(Step.Locating)
        val ids = runCatching { Mappings.forAniList(anilistId, title) }.getOrNull()
        val content = ids?.let { Stremio.contentId(it, episode, isMovie) }
            ?: return Outcome(
                null, null, emptyList(), emptyList(),
                "Couldn't map this title to a Kitsu or IMDb ID — the addons index " +
                    "by those, so there is nothing to ask for."
            )

        onStep(Step.Searching)
        val results = runCatching { Stremio.streams(content.first, content.second) }
            .getOrDefault(emptyList())
        val streams = results.flatMap { it.streams }
        val subtitles = runCatching {
            Stremio.subtitles(Stremio.contentIds(ids, episode, isMovie))
        }.getOrDefault(emptyList())

        if (streams.isEmpty()) {
            val why = results.joinToString("\n") { "${it.addon}: ${it.error ?: "no streams"}" }
            return Outcome(null, null, results, subtitles, why.ifEmpty { "No addons configured." })
        }

        if (!autoSelect) return Outcome(null, null, results, subtitles, null)

        val parsed = streams.map { it to StreamMeta.of(it) }

        val minSize = minEpisodeSizeMb(episodeMinutes)

        // Parsing addon free text is guesswork, so make it inspectable: every
        // source, what was read out of it, and whether it survived. Kept out of
        // release builds — it is a debugging aid, not telemetry — but kept,
        // because when an addon changes its wording this is the only thing that
        // says so.
        if (BuildConfig.DEBUG) parsed.forEach { (stream, meta) ->
            Log.d(
                "DebritsuFilter",
                "accept=${filter.accepts(stream, meta, minSize)} score=${filter.score(stream, meta)} " +
                    "res=${meta.resolution} size=${meta.sizeMb}MB pack=${meta.packSizeMb}MB " +
                    "isPack=${meta.isPack} fileIdx=${stream.fileIdx} direct=${stream.isDirect} " +
                    "unplayable=${meta.unplayable} cached=${meta.cached} " +
                    "eng=${meta.declaresEnglish} otherOnly=${meta.declaresOtherLanguageOnly} " +
                    "| NAME[${stream.name.replace("\n", " ")}] " +
                    "| DESC[${stream.description.replace("\n", " ").take(140)}]"
            )
        }

        val ranked = parsed
            .filter { (stream, meta) -> filter.accepts(stream, meta, minSize) }
            .sortedByDescending { (stream, meta) -> filter.score(stream, meta) }
        onStep(Step.Filtering(streams.size, ranked.size))

        if (ranked.isEmpty()) {
            return Outcome(
                null, null, results, subtitles,
                "None of the ${streams.size} sources matched your filters. " +
                    "Pick one below, or loosen them in Settings."
            )
        }

        // Never resolve something the addon has told us isn't cached. Doing so
        // starts a download on the user's debrid account that they never asked
        // for, then fails anyway, and burns one of the few attempts doing it.
        // Whether to start that download is their decision, not this one's.
        val playable = ranked.filter { (_, meta) -> meta.cached != false }
        if (playable.isEmpty()) {
            return Outcome(
                null, null, results, subtitles,
                "The ${ranked.size} sources matching your filters aren't cached with " +
                    "${Settings.debridProvider.label} yet. Playing one starts a download " +
                    "on your account, so that's your call — pick one below."
            )
        }

        val attempts = playable.take(MAX_ATTEMPTS)
        attempts.forEachIndexed { index, (stream, _) ->
            onStep(Step.Resolving(stream.name, index + 1, attempts.size))
            val url = runCatching { Debrid.resolve(stream) }.getOrNull()
            if (url != null) {
                onStep(Step.Ready)
                val subs = (stream.subtitles + subtitles).distinctBy { it.url }
                return Outcome(url, stream, results, subs, null)
            }
        }

        return Outcome(
            null, null, results, subtitles,
            "Nothing that matched your filters could be resolved — most likely " +
                "none of them are cached with ${Settings.debridProvider.label}."
        )
    }
}
