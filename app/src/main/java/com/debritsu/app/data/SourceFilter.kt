package com.debritsu.app.data

/**
 * What a stream actually is, dug out of the text an addon gave us.
 *
 * Stremio addons return no structured metadata at all — resolution, size and
 * language live inside the name and description, written however that addon's
 * author felt like writing them. Torrentio produces something like:
 *
 *     Clevatess - 07 [1080P][Baha][WEB-DL][AAC AVC][CHT].mp4
 *     👤 12 💾 1.36 GB ⚙️ Nyaa
 *
 * So this is pattern matching over free text, and it will not always be right.
 * Every field is nullable: absent means "the addon didn't say", never zero.
 */
/**
 * The least an episode of this length could weigh, in megabytes.
 *
 * 1.5 MB a minute — roughly 200 kbps — is beneath every real encode and far
 * above a ninety second opening. Zero when the running time is unknown, which
 * turns the check off rather than guessing at it.
 */
fun minEpisodeSizeMb(episodeMinutes: Int): Int =
    if (episodeMinutes > 0) (episodeMinutes * 3) / 2 else 0

data class StreamMeta(
    val resolution: Int?,
    val sizeMb: Int?,
    val seeders: Int?,
    /** The release names a language we understand as English audio or subs. */
    val declaresEnglish: Boolean,
    /** It names some other language and never mentions English. */
    val declaresOtherLanguageOnly: Boolean,
    /**
     * Whether the debrid provider already holds this, when the addon says so.
     * Null means it didn't. The provider's own API offers no usable answer —
     * Real-Debrid retired its instant-availability endpoint — but debrid-aware
     * addons put it right in the name, as ⚡ against ⏳ or a trailing +.
     * An uncached torrent is exactly the one that fails to resolve.
     */
    val cached: Boolean?,
    /** The whole torrent, when it is larger than the file we want from it. */
    val packSizeMb: Int?,
    /**
     * One episode inside a much bigger torrent. Harmless in itself — debrid
     * hands back the single file — but only when the stream carries a fileIdx
     * saying which one. Without it the resolver falls back to the largest video
     * in the torrent, which on a season pack is some other episode entirely.
     */
    val isPack: Boolean,
    /** An archive or disc image. No player here can open one. */
    val unplayable: Boolean,
    /** A creditless opening, a trailer — something other than the episode. */
    val isExtra: Boolean
) {
    companion object {
        private val RESOLUTION = Regex("""(2160|1440|1080|720|480|360)\s*p""", RegexOption.IGNORE_CASE)
        private val FOUR_K = Regex("""\b(4k|uhd)\b""", RegexOption.IGNORE_CASE)
        private val SIZE = Regex("""(\d+(?:[.,]\d+)?)\s*(tib|tb|gib|gb|mib|mb)\b""", RegexOption.IGNORE_CASE)
        private val SEEDERS = Regex("""(?:👤\s*|\b)(\d+)\s*(?:seeders?|seeds)?""")
        private val SEEDERS_EMOJI = Regex("""👤\s*(\d+)""")

        private val ENGLISH = listOf(
            "english", "[eng]", "(eng)", "eng sub", "engsub", "eng-sub",
            "multi-sub", "multi sub", "multisub", "dual audio", "dualaudio",
            "🇬🇧", "🇺🇸"
        )

        // Archives and disc images. ExoPlayer cannot open any of them, so a
        // source built from one is a guaranteed failure however good it looks.
        private val UNPLAYABLE = listOf(
            ".rar", ".zip", ".7z", ".tar", ".iso", "bdmv", "video_ts", ".part1."
        )

        /**
         * The extras that ship inside a season pack and are not the episode.
         *
         * A creditless opening is, literally, the opening song with no titles
         * over it — about ninety seconds of it. Picked instead of the episode
         * it plays perfectly and looks like a working source, which is how
         * "I opened episode 1 of season 2 and it was just a song" happens.
         *
         * Deliberately narrow. NCOP, NCED, creditless and textless are release
         * conventions that mean one thing; "opening" and "ending" on their own
         * are not, since they turn up in episode titles — Bleach alone has
         * several. A missed extra is one bad tap; a wrongly rejected episode is
         * a show that will not play at all.
         */
        private val EXTRA = Regex(
            """\b(nc(op|ed)\d*|creditless|textless|clean\s+(opening|ending)|""" +
                """trailer|teaser|promo\s*video|preview)\b""",
            RegexOption.IGNORE_CASE
        )

        // Debrid-aware addons mark what the provider already holds. Torrentio
        // and friends use ⚡ against ⏳; others append a plus to the tag.
        private val CACHED = listOf("⚡", "[rd+]", "[ad+]", "[tb+]", "[pm+]", "[dl+]")
        private val UNCACHED = listOf("⏳", "download]", "uncached")

        // Tags that name a language other than English. Anime releases often
        // carry exactly one of these and nothing else.
        private val OTHER_LANGUAGES = listOf(
            "[cht]", "[chs]", "[chi]", "chinese", "[jpn]", "[jap]",
            "vostfr", "french", "[fre]", "[fra]", "german", "[ger]", "[deu]",
            "spanish", "[spa]", "italian", "[ita]", "russian", "[rus]",
            "portuguese", "[por]", "korean", "[kor]", "[tha]", "[vie]",
            "arabic", "[ara]", "polish", "[pol]", "turkish", "[tur]"
        )

        fun of(stream: StreamOption): StreamMeta {
            val text = "${stream.name} ${stream.description}"
            val lower = text.lowercase()

            val resolution = RESOLUTION.find(text)?.groupValues?.get(1)?.toIntOrNull()
                ?: if (FOUR_K.containsMatchIn(text)) 2160 else null

            // Take the first figure quoted, not the largest. Descriptions of a
            // pack read "📦 493 MB / 53 GB" — the episode you receive, then the
            // whole torrent. The largest is the pack, which is the wrong number
            // to hold a per-episode limit against, and picking it rejected
            // cached 500MB files as though they were 53GB.
            val sizes = SIZE.findAll(text).mapNotNull { m ->
                val value = m.groupValues[1].replace(',', '.').toDoubleOrNull()
                val unit = m.groupValues[2].lowercase()
                value?.let {
                    when {
                        unit.startsWith("t") -> it * 1024 * 1024
                        unit.startsWith("g") -> it * 1024
                        else -> it
                    }
                }
            }.toList()

            val sizeMb = sizes.firstOrNull()?.toInt()
            val packSizeMb = sizes.drop(1).maxOrNull()?.toInt()
            // A second, much larger figure is the addon saying "this file, out
            // of this torrent" — which is what a pack looks like.
            val isPack = sizeMb != null && packSizeMb != null && packSizeMb > sizeMb * 1.5

            val seeders = SEEDERS_EMOJI.find(text)?.groupValues?.get(1)?.toIntOrNull()

            val english = ENGLISH.any { it in lower }
            val other = OTHER_LANGUAGES.any { it in lower }

            val cached = when {
                CACHED.any { it in lower } -> true
                UNCACHED.any { it in lower } -> false
                else -> null
            }

            return StreamMeta(
                resolution = resolution,
                sizeMb = sizeMb,
                seeders = seeders,
                declaresEnglish = english,
                declaresOtherLanguageOnly = other && !english,
                cached = cached,
                packSizeMb = packSizeMb,
                isPack = isPack,
                unplayable = UNPLAYABLE.any { it in lower },
                isExtra = EXTRA.containsMatchIn(text)
            )
        }
    }
}

/**
 * The rules auto-play picks by.
 *
 * Resolution is a ceiling rather than a target — capping it is what keeps a
 * 4K remux off a phone — and the highest allowed is preferred. Size is a hard
 * cap: a source that never says how big it is fails a cap it cannot be checked
 * against, because the point of the cap is not spending data you didn't agree
 * to spend.
 */
data class SourceFilter(
    val maxResolution: Int,
    val maxSizeMb: Int,
    val preferEnglish: Boolean
) {
    /**
     * @param minSizeMb the smallest this episode could plausibly be, from its
     *   running time. Zero where that is unknown, which skips the check.
     */
    fun accepts(stream: StreamOption, meta: StreamMeta, minSizeMb: Int = 0): Boolean {
        if (meta.unplayable) return false

        // Far too small to be the episode.
        //
        // This is the one that catches what naming cannot. The addon behind
        // the Shield Hero report returns no filename at all — only size,
        // bitrate and language — so there is no "NCOP" to match on. But the
        // file it offered for a 24 minute episode was 13.2 MB, which is about
        // 73 kbps: not a bad encode, not an encode at all. It was the ninety
        // second opening.
        //
        // The floor is 1.5 MB a minute, well beneath any real episode — a poor
        // 480p rip runs four or five times that — and far above a clip.
        if (minSizeMb > 0) {
            val size = meta.sizeMb
            if (size != null && size < minSizeMb) return false
        }

        // A creditless opening plays flawlessly and is not the episode. It is
        // the one wrong source that gives no sign of being wrong until the
        // song starts.
        if (meta.isExtra) return false

        // A pack is fine when the stream says which file it wants. Without that
        // the resolver picks the largest video in the torrent, which on a season
        // pack is the wrong episode — and it would play without complaint.
        if (meta.isPack && stream.fileIdx == null && !stream.isDirect) return false

        if (maxResolution > 0) {
            val r = meta.resolution ?: return false
            if (r > maxResolution) return false
        }
        if (maxSizeMb > 0) {
            val s = meta.sizeMb ?: return false
            if (s > maxSizeMb) return false
        }
        if (preferEnglish && meta.declaresOtherLanguageOnly) return false
        return true
    }

    /**
     * Higher sorts first. Anything already resolved to a direct link wins
     * outright — it plays without touching the debrid provider at all, which is
     * most of what makes this feel instant.
     */
    fun score(stream: StreamOption, meta: StreamMeta): Int {
        var score = 0
        if (stream.isDirect) score += 10_000

        // Cached beats everything short of an already-resolved link: it is the
        // difference between playing now and waiting on a torrent that may
        // never resolve at all.
        when (meta.cached) {
            true -> score += 5_000
            false -> score -= 3_000
            null -> Unit
        }

        meta.resolution?.let { score += it }

        // Deliberately small. At 2000 this outweighed the 360-point gap between
        // 1080p and 720p, so an English dub beat every subbed 1080p release —
        // and the marker cannot tell a dub from subtitles. It breaks ties now
        // rather than deciding.
        if (preferEnglish && meta.declaresEnglish) score += 200

        // Capped low for the same reason: seeders matter to whoever is fetching
        // the torrent, not to picking between two releases of the same episode.
        meta.seeders?.let { score += it.coerceAtMost(150) }
        return score
    }

    companion object {
        /** What the settings screen starts from. */
        val Default = SourceFilter(maxResolution = 1080, maxSizeMb = 600, preferEnglish = true)
    }
}
