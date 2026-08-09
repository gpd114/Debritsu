package com.debritsu.app.data

import com.debritsu.app.BuildConfig

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
 */
val DEFAULT_ANILIST_CLIENT_ID: String = BuildConfig.ANILIST_CLIENT_ID
