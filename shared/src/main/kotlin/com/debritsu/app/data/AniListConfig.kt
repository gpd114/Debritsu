package com.debritsu.app.data

/**
 * Shared AniList client for public builds.
 *
 * Set this once, in `anilist.properties` at the project root:
 *
 *     clientId=12345
 *
 * That file is deliberately not part of project updates, so your ID survives
 * every upgrade. Leave it unset and each user supplies their own ID in
 * Settings → "Use my own API client".
 *
 * There is no secret here: AniList's implicit grant is designed for public
 * clients, so this is safe to commit.
 *
 * Read through [BuildInfo] rather than from a generated BuildConfig, which only
 * the Android build produces. Each front end registers its own client anyway:
 * AniList allows one redirect URL per client, and Android's is the custom scheme
 * `debritsu://auth` while a desktop build needs the pin redirect.
 */
val DEFAULT_ANILIST_CLIENT_ID: String get() = BuildInfo.anilistClientId
