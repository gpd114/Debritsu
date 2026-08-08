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
