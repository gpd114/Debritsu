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
data class StreamMeta(
    val resolution: Int?,
    val sizeMb: Int?,
    val seeders: Int?,
    /** The release names a language we understand as English audio or subs. */
    val declaresEnglish: Boolean,
    /** It names some other language and never mentions English. */
    val declaresOtherLanguageOnly: Boolean
) {
    companion object {
        private val RESOLUTION = Regex("""(2160|1440|1080|720|480|360)\s*p""", RegexOption.IGNORE_CASE)
        private val FOUR_K = Regex("""\b(4k|uhd)\b""", RegexOption.IGNORE_CASE)
        private val SIZE = Regex("""(\d+(?:[.,]\d+)?)\s*(gib|gb|mib|mb)\b""", RegexOption.IGNORE_CASE)
        private val SEEDERS = Regex("""(?:👤\s*|\b)(\d+)\s*(?:seeders?|seeds)?""")
        private val SEEDERS_EMOJI = Regex("""👤\s*(\d+)""")

        private val ENGLISH = listOf(
            "english", "[eng]", "(eng)", "eng sub", "engsub", "eng-sub",
            "multi-sub", "multi sub", "multisub", "dual audio", "dualaudio",
            "🇬🇧", "🇺🇸"
        )

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

            // Take the largest figure quoted: descriptions sometimes mention
            // both the episode and the whole season pack.
            val sizeMb = SIZE.findAll(text).mapNotNull { m ->
                val value = m.groupValues[1].replace(',', '.').toDoubleOrNull()
                val unit = m.groupValues[2].lowercase()
                value?.let { if (unit.startsWith("g")) it * 1024 else it }
            }.maxOrNull()?.toInt()

            val seeders = SEEDERS_EMOJI.find(text)?.groupValues?.get(1)?.toIntOrNull()

            val english = ENGLISH.any { it in lower }
            val other = OTHER_LANGUAGES.any { it in lower }

            return StreamMeta(
                resolution = resolution,
                sizeMb = sizeMb,
                seeders = seeders,
                declaresEnglish = english,
                declaresOtherLanguageOnly = other && !english
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
    fun accepts(meta: StreamMeta): Boolean {
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
        if (preferEnglish && meta.declaresEnglish) score += 2_000
        meta.resolution?.let { score += it }
        meta.seeders?.let { score += it.coerceAtMost(500) }
        return score
    }

    companion object {
        /** What the settings screen starts from. */
        val Default = SourceFilter(maxResolution = 1080, maxSizeMb = 600, preferEnglish = true)
    }
}
