# Debritsu

An AniList-driven anime client for Android that gets its video from **Stremio addons**
instead of scraping streaming sites. Point it at a debrid-backed addon
(AIOStreams, Comet, MediaFusion, Torrentio + RD) and playback is a cached direct
link from your debrid provider.

## What it does

- **AniList** — sign in, browse Trending, search, see your Watching list with
  progress, and push progress back automatically at 85% of an episode.
- **Stremio addons** — add any number of addon URLs. Debritsu queries them all in
  parallel over the standard `/stream/{type}/{id}.json` protocol and shows the
  merged results in a source picker.
- **ID mapping** — AniList IDs are translated to Kitsu (and IMDb as a fallback)
  via `api.ani.zip`, because anime addons index by `kitsu:<id>:<episode>`.
- **Debrid** — if an addon returns a ready HTTPS link, it plays directly. If it
  returns only an `infoHash`, Debritsu resolves it through your chosen provider:
  **Real-Debrid**, **AllDebrid**, **Premiumize** or **TorBox**. Keys are stored
  per provider, so you can switch between them without re-entering anything.
  Uncached torrents are rejected rather than queued.
- **Subtitles** — tracks embedded in the file, tracks attached to the stream, and
  anything returned by a Stremio subtitle addon (OpenSubtitles v3 etc.) are all
  listed under the CC button. Size, colour, background and outline are
  configurable, and English is auto-selected where available.
- **Playback** — Media3/ExoPlayer, landscape, PiP-capable.

## Install

Grab the APK from [Releases](../../releases) and open it on your phone. Android
will ask you to allow installs from whichever app you downloaded it with.

For automatic updates, add the repository to
[Obtainium](https://github.com/ImranR98/Obtainium) — it watches GitHub Releases
and installs new versions as they appear.

## Build

No Gradle wrapper jar is committed. Either:

**GitHub Actions (easiest)** — push to `main`, then grab `debritsu-debug-apk` from the
workflow run artifacts.

**Locally** — open in Android Studio (it will generate the wrapper), or with
Gradle 8.9+ and JDK 17 installed:

```
gradle assembleDebug
```

APK lands in `app/build/outputs/apk/debug/`.

## First-run setup

1. **Settings → Stremio addons** — paste your addon URL. For AIOStreams this is
   the configured manifest URL; note that AIOStreams mints a new URL on every
   config save, so re-paste it after changes.
2. **Settings → Debrid provider** — optional. Only needed for addons that return
   bare infoHashes. Pick your service and paste its API key; if your addon
   already holds your debrid key, leave this blank.
3. **Settings → AniList** — tap Sign in. If you are building this yourself, set
   `DEFAULT_ANILIST_CLIENT_ID` in `data/AniListConfig.kt` once (see the comment
   in that file) and everyone using your build gets one-tap sign-in. Leave it
   blank and each user supplies their own client ID under "Use my own API
   client". Browsing and playback work without signing in at all.

## Known limits

- Episode counts come from AniList, so seasonal/absolute numbering mismatches
  can occur on long-running shows — the usual Kitsu-vs-AniList problem.
- No local download/offline support.
- Debrid fallback resolves cached content only; nothing is queued for download.
- Debug-signed only; the release build reuses the debug key.

## Releasing

Releases are cut by tag:

```
git tag v0.7.1
git push origin v0.7.1
```

That builds a signed APK and publishes it to the Releases page. The workflow
fails deliberately if no signing key is configured, because a debug-signed
release can never be updated in place.

## Legal

Debritsu ships no sources, no scrapers and no content. It is a client for addons and
debrid accounts that you configure and are responsible for.
