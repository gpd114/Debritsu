package com.debritsu.app.data

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

    suspend fun run(
        anilistId: Int,
        title: String?,
        episode: Int,
        isMovie: Boolean,
        filter: SourceFilter,
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
        val subtitles = runCatching { Stremio.subtitles(content.first, content.second) }
            .getOrDefault(emptyList())

        if (streams.isEmpty()) {
            val why = results.joinToString("\n") { "${it.addon}: ${it.error ?: "no streams"}" }
            return Outcome(null, null, results, subtitles, why.ifEmpty { "No addons configured." })
        }

        val ranked = streams
            .map { it to StreamMeta.of(it) }
            .filter { (_, meta) -> filter.accepts(meta) }
            .sortedByDescending { (stream, meta) -> filter.score(stream, meta) }
        onStep(Step.Filtering(streams.size, ranked.size))

        if (ranked.isEmpty()) {
            return Outcome(
                null, null, results, subtitles,
                "None of the ${streams.size} sources matched your filters. " +
                    "Pick one below, or loosen them in Settings."
            )
        }

        val attempts = ranked.take(MAX_ATTEMPTS)
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
