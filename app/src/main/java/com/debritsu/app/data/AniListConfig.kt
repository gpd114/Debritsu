package com.debritsu.app.data

/**
 * Shared AniList client for everyone using public builds of Debritsu.
 *
 * TO ENABLE ONE-TAP SIGN-IN:
 *   1. Go to https://anilist.co/settings/developer and create a new client.
 *   2. Name: Debritsu    Redirect URL: debritsu://auth
 *   3. Paste the numeric client ID below and commit.
 *
 * Leave it blank and the app falls back to asking each user for their own ID
 * under Settings → Advanced. There is no secret here — AniList's implicit
 * grant is designed for public clients, so this is safe to commit.
 */
const val DEFAULT_ANILIST_CLIENT_ID = "48123"
