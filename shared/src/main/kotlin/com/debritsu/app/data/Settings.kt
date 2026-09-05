package com.debritsu.app.data

/**
 * Simple persisted settings. Addons are stored as a newline-separated list of
 * Stremio addon base URLs (the manifest.json URL, minus the filename).
 *
 * Backed by whatever [KeyValueStore] the host installs at startup — the only
 * Android in here was ever the SharedPreferences handle, so the properties
 * below are unchanged apart from where they read and write.
 */
object Settings {
    /**
     * Installed once by the host before anything reads a setting. Until then
     * every read returns its default and every write is dropped, which is a
     * better failure than throwing out of an unrelated screen.
     */
    var store: KeyValueStore = NoStore

    /** Falls back to the shared client baked into the build. */
    var aniListClientId: String
        get() = store.getString("anilist_client_id", "")
            .ifEmpty { DEFAULT_ANILIST_CLIENT_ID }
        set(v) = store.putString("anilist_client_id", v)

    var aniListToken: String
        get() = store.getString("anilist_token", "")
        set(v) = store.putString("anilist_token", v)

    /** Which debrid service the fallback resolver talks to. */
    var debridProvider: DebridProvider
        get() = runCatching {
            DebridProvider.valueOf(store.getString("debrid_provider", ""))
        }.getOrDefault(DebridProvider.REAL_DEBRID)
        set(v) = store.putString("debrid_provider", v.name)

    /** API key / token for the selected provider. Stored per provider. */
    var debridToken: String
        get() = store.getString("debrid_token_${debridProvider.name}", "")
        set(v) = store.putString("debrid_token_${debridProvider.name}", v)

    var addons: List<String>
        get() = store.getString("addons", "")
            .split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        set(v) = store.putString("addons", v.joinToString("\n"))

    /**
     * The AniList account id, kept alongside the token it belongs to.
     *
     * Every list query needs it, and asking for it is a round trip of its own —
     * which made "Continue watching" and "Plan to watch" cost two requests in
     * series where "Trending" costs one, and paid it again on every cold start.
     * It never changes for a given token, so it is stored rather than fetched.
     */
    var aniListViewerId: Int
        get() = store.getInt("anilist_viewer_id", 0)
        set(v) = store.putInt("anilist_viewer_id", v)

    /** The token that id belongs to, so switching accounts discards it. */
    var aniListViewerToken: String
        get() = store.getString("anilist_viewer_token", "")
        set(v) = store.putString("anilist_viewer_token", v)

    // ----- audio -----

    /**
     * ISO-639 code for the audio track to prefer when a release carries more
     * than one. Empty follows the device language, which is what ExoPlayer does
     * unasked — and on an English phone that quietly picks the dub.
     */
    var preferredAudioLanguage: String
        get() = store.getString("audio_lang", "ja")
        set(v) = store.putString("audio_lang", v)

    // ----- subtitle appearance -----

    var subtitleSizeSp: Float
        get() = store.getFloat("sub_size", 20f)
        set(v) = store.putFloat("sub_size", v)

    /** 0 = none, 1 = translucent, 2 = solid black. */
    var subtitleBackground: Int
        get() = store.getInt("sub_bg", 1)
        set(v) = store.putInt("sub_bg", v)

    /** 0 = white, 1 = pale yellow, 2 = cyan. */
    var subtitleColour: Int
        get() = store.getInt("sub_colour", 0)
        set(v) = store.putInt("sub_colour", v)

    var subtitleOutline: Boolean
        get() = store.getBoolean("sub_outline", true)
        set(v) = store.putBoolean("sub_outline", v)

    // ----- automatic source selection -----

    /** Play the best match straight away instead of opening the source list. */
    var autoPlay: Boolean
        get() = store.getBoolean("auto_play", true)
        set(v) = store.putBoolean("auto_play", v)

    /** Ceiling rather than a target, so a 4K remux never lands on a phone. 0 = any. */
    var maxResolution: Int
        get() = store.getInt("filter_max_res", SourceFilter.Default.maxResolution)
        set(v) = store.putInt("filter_max_res", v)

    /** Hard cap in megabytes. 0 = no cap. */
    var maxSizeMb: Int
        get() = store.getInt("filter_max_size", SourceFilter.Default.maxSizeMb)
        set(v) = store.putInt("filter_max_size", v)

    /** Skip releases that name another language and never mention English. */
    var preferEnglish: Boolean
        get() = store.getBoolean("filter_english", SourceFilter.Default.preferEnglish)
        set(v) = store.putBoolean("filter_english", v)

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
