<div align="center">

<img src="assets/logo.png" width="360" alt="Debritsu">

**An AniList anime client that streams from your debrid account — no scraping, no dead sources.**

[![Release](https://img.shields.io/github/v/release/gpd114/Debritsu?include_prereleases&label=release&color=8B5CF6)](../../releases)
[![Downloads](https://img.shields.io/github/downloads/gpd114/Debritsu/total?color=8B5CF6)](../../releases)
[![License](https://img.shields.io/github/license/gpd114/Debritsu?color=8B5CF6)](LICENSE)
![Android](https://img.shields.io/badge/Android-8.0%2B-8B5CF6)

</div>

---

## Why Debritsu

Most anime apps scrape streaming sites. Sources break weekly, quality is a lottery, and half the extensions are dead by the time you install them.

Debritsu doesn't scrape anything. It talks to **Stremio addons** — the same ones you might already run with AIOStreams, Comet or MediaFusion — and plays the cached, direct link your **debrid provider** hands back. Your library lives on AniList, so your progress follows you everywhere.

If you already have a debrid subscription and a configured addon, this is the client that gets out of the way.

---

## Features

**Stremio addon support** — Add as many addon URLs as you like. Debritsu queries them all in parallel and merges the results into one source list.

**Four debrid services** — Real-Debrid, AllDebrid, Premiumize and TorBox. Most addons return a ready link so nothing extra is needed, but bare infoHashes get resolved through your provider automatically.

**Play in one tap** — Press Play and Debritsu works out which ids the addons index the show under, queries them all, ranks what comes back and resolves the best match, showing each step as it goes. Filtering starts at 1080p or below, 600 MB or less and English, and every part of that is yours to change in Settings — or turn off, and get the source list instead.

**Full AniList integration** — Sign in with one tap. Browse Trending, search, and see Continue watching and Plan to watch as side-scrolling shelves that open into a full grid when you want the whole list. Scores sit on the poster, and an airing strip counts down the next episode of everything you're partway through. Change list status, progress and score from inside the app. Progress pushes itself at 85% of an episode.

**Genuinely offline downloads** — Download an episode and it plays with **no connection at all**. Titles, posters and episode numbers are cached at download time, so the library isn't a blank screen when you're on a plane. Anything watched offline is queued and syncs the moment you're back online.

**Filler and recap flags** — Episodes are marked FILLER or RECAP from MyAnimeList data, so you know what's safe to skip before you start.

**Cast to a Google Cast device** — Chromecast, Android TV and Cast-enabled televisions. DLNA renderers are found and listed too, but most televisions can't fetch a debrid link over it, so treat that half as a bonus rather than a promise — see [Known limits](#known-limits). Failing either, hand the stream to VLC, MX Player or anything else installed; that option is offered straight away rather than after the network scan finishes.

**Switch source mid-episode** — Discovered it's a dub three minutes in? Tap Sources in the player, pick another, and it resumes at the same second.

**Next and previous episode** — Buttons either side of play move through the season. Both open the source list rather than choosing for you, since sources differ by gigabytes, language and release group, and picking automatically spends your data on your behalf.

**Skip openings and endings** — A skip button appears while playback sits inside a known opening or ending, timed from AniSkip and fitted to the encode you're actually watching.

**Gestures where you'd expect them** — Double-tap the left or right third to seek back and forward; drag up and down the left half for brightness, the right half for volume. The middle third is left alone so a double-tap there still just shows the controls.

**Subs, not dubs, by default** — The audio track is chosen by preferring Japanese instead of following your phone's language, which on an English handset quietly picks the dub. Set it to whatever you like in Settings.

**Proper subtitle control** — Embedded tracks, addon-supplied tracks and Stremio subtitle addons all appear under one CC button, each labelled with the addon it came from so two English tracks can be told apart. Subtitle addons are asked under both the Kitsu and IMDb id, because some index only one. Size, colour, background and outline are yours to set, and embedded styling is overridden so your choices stick. Image-based tracks render in full, including releases that reuse a palette between cues.

**Resume where you stopped** — Per-episode positions saved locally, with a progress bar on every episode chip. Nothing under 15 seconds is saved, and nothing past 92% offers to resume you into the credits.

**Know before you watch** — Average score, popularity rank, favourites, studio, genres, season, format and runtime on every show, plus a Related row for prequels, sequels and side stories.

**Made to look like something** — Night-violet theme throughout, with technical detail set in monospace so the source list reads like the torrent metadata it actually is.

---

## Install

Download the APK from [**Releases**](../../releases) and open it on your phone. Android will ask you to allow installs from whichever app you downloaded it with.

For automatic updates, add this repository to [**Obtainium**](https://github.com/ImranR98/Obtainium).

Requires Android 8.0 or newer.

---

## Setup

**1. Add an addon.** Settings → Stremio addons → paste your addon URL. A debrid-backed addon such as AIOStreams, Comet, MediaFusion or Torrentio configured with your debrid key is what you want — those return cached direct links. Both `manifest.json` URLs and `stremio://` links work.

**2. Sign in to AniList.** Settings → AniList → Sign in. Optional; browsing and playback work without an account, but you lose progress tracking and your lists.

**3. Add a debrid key.** Only needed if your addon returns bare infoHashes rather than links. If your addon already holds your debrid key, leave this blank.

That's it. Open a show and tap Play — it finds a source and starts on its own. If you'd rather choose every time, turn off **Play automatically** in Settings → Playback, where the resolution, size and language rules live too.

---

## Building it yourself

Requires Gradle 8.9, JDK 17 and an Android SDK with `platforms;android-34` and `build-tools;34.0.0`. There is deliberately no Gradle wrapper in the repository, so use your own Gradle install.

```
gradle assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`. For one-tap AniList sign-in in your own builds, register a client at [anilist.co/settings/developer](https://anilist.co/settings/developer) with redirect URL `debritsu://auth`, then put the numeric ID in `anilist.properties`:

```
clientId=12345
```

Releases are cut by publishing a tagged release on GitHub, which builds and attaches a signed APK automatically.

---

## Known limits

- Very new shows sometimes aren't in the community mapping tables yet, so sources may not be found for a week or two after airing.
- Episode numbering follows AniList, which can disagree with Kitsu on long-running shows — the usual absolute-versus-seasonal mismatch.
- Downloads resolve links when you start them, and debrid URLs expire, so a download paused for hours may need restarting.
- Downloaded episodes can't be sent to a TV directly, since the file lives in app storage where the TV can't reach it — but they can be handed to another app on your phone (VLC, Web Video Caster) which can then cast them.
- **Casting straight to a smart TV usually won't work.** DLNA is an `http-get` protocol and plenty of televisions ship no TLS at all, while debrid links are always HTTPS — so the set is discovered, accepts the cast, and then can't fetch the file. Nothing this app can fix from its side without proxying the whole stream through the phone. Use a Chromecast, Android TV or other Google Cast device, or hand the stream to an app like VLC or Web Video Caster that does proxy it. Where DLNA does work, seeking isn't always honoured.
- Source filtering reads resolution, size and language out of free text, because addons return no structured metadata — every addon author writes that line however they like. It is good, not infallible, and a source that never states its size fails a size cap rather than sneaking under it.
- Skip timings come from AniSkip, which is community-submitted. Plenty of shows have none, and newly aired episodes often take a while to appear.
- AniList tokens last a year and can't be refreshed, so you'll sign in again annually.

---

## Disclaimer

Debritsu ships **no sources, no scrapers and no content**. It is a client for Stremio addons and debrid accounts that you configure and are responsible for. Nothing is hosted, indexed or provided by this project.

Please don't upload this app, or any fork of it, to Google Play or other app stores.

---

<div align="center">

Built with Kotlin, Jetpack Compose and Media3.

</div>
