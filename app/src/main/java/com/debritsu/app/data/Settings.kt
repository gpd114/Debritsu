package com.debritsu.app.data

import android.content.Context
import com.debritsu.app.DebritsuApp

/**
 * Simple persisted settings. Addons are stored as a newline-separated list of
 * Stremio addon base URLs (the manifest.json URL, minus the filename).
 */
object Settings {
    private val sp by lazy {
        DebritsuApp.ctx.getSharedPreferences("debritsu", Context.MODE_PRIVATE)
    }

    /** Falls back to the shared client baked into the build. */
    var aniListClientId: String
        get() = (sp.getString("anilist_client_id", "") ?: "")
            .ifEmpty { DEFAULT_ANILIST_CLIENT_ID }
        set(v) = sp.edit().putString("anilist_client_id", v).apply()

    var aniListToken: String
        get() = sp.getString("anilist_token", "") ?: ""
        set(v) = sp.edit().putString("anilist_token", v).apply()

    /** Which debrid service the fallback resolver talks to. */
    var debridProvider: DebridProvider
        get() = runCatching {
            DebridProvider.valueOf(sp.getString("debrid_provider", "") ?: "")
        }.getOrDefault(DebridProvider.REAL_DEBRID)
        set(v) = sp.edit().putString("debrid_provider", v.name).apply()

    /** API key / token for the selected provider. Stored per provider. */
    var debridToken: String
        get() = sp.getString("debrid_token_${debridProvider.name}", "") ?: ""
        set(v) = sp.edit().putString("debrid_token_${debridProvider.name}", v).apply()

    var addons: List<String>
        get() = (sp.getString("addons", "") ?: "")
            .split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        set(v) = sp.edit().putString("addons", v.joinToString("\n")).apply()

    /**
     * The AniList account id, kept alongside the token it belongs to.
     *
     * Every list query needs it, and asking for it is a round trip of its own —
     * which made "Continue watching" and "Plan to watch" cost two requests in
     * series where "Trending" costs one, and paid it again on every cold start.
     * It never changes for a given token, so it is stored rather than fetched.
     */
    var aniListViewerId: Int
        get() = sp.getInt("anilist_viewer_id", 0)
        set(v) = sp.edit().putInt("anilist_viewer_id", v).apply()

    /** The token that id belongs to, so switching accounts discards it. */
    var aniListViewerToken: String
        get() = sp.getString("anilist_viewer_token", "") ?: ""
        set(v) = sp.edit().putString("anilist_viewer_token", v).apply()

    // ----- audio -----

    /**
     * ISO-639 code for the audio track to prefer when a release carries more
     * than one. Empty follows the device language, which is what ExoPlayer does
     * unasked — and on an English phone that quietly picks the dub.
     */
    var preferredAudioLanguage: String
        get() = sp.getString("audio_lang", "ja") ?: "ja"
        set(v) = sp.edit().putString("audio_lang", v).apply()

    // ----- subtitle appearance -----

    var subtitleSizeSp: Float
        get() = sp.getFloat("sub_size", 20f)
        set(v) = sp.edit().putFloat("sub_size", v).apply()

    /** 0 = none, 1 = translucent, 2 = solid black. */
    var subtitleBackground: Int
        get() = sp.getInt("sub_bg", 1)
        set(v) = sp.edit().putInt("sub_bg", v).apply()

    /** 0 = white, 1 = pale yellow, 2 = cyan. */
    var subtitleColour: Int
        get() = sp.getInt("sub_colour", 0)
        set(v) = sp.edit().putInt("sub_colour", v).apply()

    var subtitleOutline: Boolean
        get() = sp.getBoolean("sub_outline", true)
        set(v) = sp.edit().putBoolean("sub_outline", v).apply()

    // ----- automatic source selection -----

    /** Play the best match straight away instead of opening the source list. */
    var autoPlay: Boolean
        get() = sp.getBoolean("auto_play", true)
        set(v) = sp.edit().putBoolean("auto_play", v).apply()

    /** Ceiling rather than a target, so a 4K remux never lands on a phone. 0 = any. */
    var maxResolution: Int
        get() = sp.getInt("filter_max_res", SourceFilter.Default.maxResolution)
        set(v) = sp.edit().putInt("filter_max_res", v).apply()

    /** Hard cap in megabytes. 0 = no cap. */
    var maxSizeMb: Int
        get() = sp.getInt("filter_max_size", SourceFilter.Default.maxSizeMb)
        set(v) = sp.edit().putInt("filter_max_size", v).apply()

    /** Skip releases that name another language and never mention English. */
    var preferEnglish: Boolean
        get() = sp.getBoolean("filter_english", SourceFilter.Default.preferEnglish)
        set(v) = sp.edit().putBoolean("filter_english", v).apply()

    val sourceFilter: SourceFilter
        get() = SourceFilter(maxResolution, maxSizeMb, preferEnglish)

    fun addAddon(url: String) {
        val n = normaliseAddon(url)
        if (n.isNotEmpty() && n !in addons) addons = addons + n
    }

    fun removeAddon(url: String) {
        addons = addons.filter { it != url }
    }

    /** Accepts stremio:// links and manifest.json URLs, returns the addon base URL. */
    fun normaliseAddon(raw: String): String {
        var u = raw.trim()
        if (u.startsWith("stremio://")) u = "https://" + u.removePrefix("stremio://")
        u = u.removeSuffix("/manifest.json")
        return u.trimEnd('/')
    }
}
