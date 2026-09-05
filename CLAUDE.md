# Debritsu

Android anime player. Content comes from Stremio addons, links are resolved
through a debrid provider, and progress syncs to AniList. The app ships no
sources and no content of its own.

## Building

**There is no Gradle wrapper, deliberately.** CI provisions Gradle 8.9 itself and
runs `gradle assembleRelease`. For local builds you need Gradle 8.9, JDK 17 and
an Android SDK with `platforms;android-34` and `build-tools;34.0.0`.

```
gradle assembleDebug --no-daemon
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**Debug and release cannot replace each other.** Different signing keys, so
installing one over the other fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE` and
needs an uninstall — which takes the user's downloads and settings with it. Check
which is on the device before assuming an install will work.

**Debug builds are markedly slower** than release. `debuggable=true` holds back
ART optimisation and skips R8 entirely, and Compose pays heavily for both. Don't
diagnose "the app feels slow" on a debug build.

## Releases

Tag `v*` to publish; the workflow builds a signed APK and attaches it to the
GitHub release.

**Give every release its own version and never move a tag.** Five different
binaries once shipped as `versionCode 31 / 1.1.0-beta` because the tag was moved
rather than the version raised, and identifying which build was on a phone meant
unzipping the APK and grepping its dex. Bump `versionCode` for each release;
`versionName` is taken from the tag via `RELEASE_TAG` in CI.

The release workflow **overwrites the release body** on every run, so paste
release notes after it finishes, not before.

## Debugging on device

**Start log captures before reproducing.** The test phone flushes its log ring in
seconds — querying `logcat -d` afterwards routinely returns nothing, including
crash tombstones. Run `adb logcat -G 16M`, start a filtered capture in the
background, then reproduce.

Debug builds log under two tags:

- `DebritsuFilter` — every source auto-play considered, what was parsed out of it
  (resolution, size, cache state) and whether it passed
- `DebritsuSubs` — every text track on load with its label and selection flags,
  and every cue as it arrives

Both are wrapped in `BuildConfig.DEBUG` and cost nothing in release.

## Things that were true and cost time to learn

- **Addons return no structured metadata.** Resolution, size, language and cache
  state are parsed out of free text each addon author wrote however they liked.
  It is guesswork; check the log before assuming the filter is wrong.
- **Sizes are quoted as `493 MB / 53 GB`** for a pack — the file, then the whole
  torrent. Take the first.
- **Some addons give no filename at all** — only size, bitrate and language. Any
  rule that reads the release name silently does nothing against those, and will
  look like it works because it does work against Torrentio.
- **A creditless opening scores well and is not the episode.** NCOP/NCED files
  ship inside season packs: playable, small, cached, correctly resolutioned, so
  the filter ranks them highly and auto-play takes one. They are the theme song
  with no titles over it, about ninety seconds. Size is the reliable tell where
  the name is missing — 13.2 MB for a 24 minute episode is about 73 kbps, which
  is not a poor encode but a clip. The floor is 1.5 MB a minute.
- **A short file will mark an episode watched.** Progress goes at 85% of the
  duration, and 85% of ninety seconds is a minute and a quarter. A season of
  those marked a whole season complete on AniList that had never been played, so
  anything pushing progress has to check the duration is plausible first.
- **AniList has no concept of a season number.** A season is simply another show
  to it, with its own id. IMDb and TVDB keep one entry per series — all four
  seasons of Shield Hero are `tt9529546` — so an IMDb-shaped request must name
  the season or it fetches the first. `api.ani.zip/mappings?anilist_id=N` is the
  only source of that number, and of absolute numbering. It has no website: the
  bare domain does not resolve and the API root 404s. The Fribb fallback covers
  ids but not seasons.
- **Episode counts disagree because of specials.** ani.zip lists 26 entries for
  Shield Hero season 1: episodes 1..25 plus one keyed `S1`, a five minute recap
  promo. Sites that fold specials into the count show 26. Following AniList's 25
  is correct, and a report of "wrong numbering" is usually this.
- **Never resolve a source the addon marks uncached** (`⏳`). It starts a download
  on the user's debrid account they did not ask for, then fails anyway.
- **The source list must not travel in an Intent.** A few hundred sources, each
  with a ~1,500 character URL, overruns the Binder transaction limit and the app
  is torn down mid-launch with no exception, tombstone or log. It goes through
  `SourceHandoff` instead.
- **Anime releases ship two English subtitle tracks**, and the signs-and-songs one
  is routinely flagged DEFAULT and FORCED, so it wins unless those flags are
  ignored. It shows text periodically, so it reads as broken subtitles rather
  than the wrong track.
- **OpenSubtitles indexes by IMDb**, and answers a Kitsu id with `200` and an
  empty list. Subtitle addons are queried under both ids. It also genuinely has
  no English for a lot of simulcast anime — verify what an addon returns before
  investigating the app.
- **Most televisions cannot fetch HTTPS**, and debrid links are always HTTPS, so
  DLNA casting fails on them. Not fixable from this side without proxying the
  stream through the phone.
- **`PlayerView.isControllerFullyVisible` does not mean "the controls are up".**
  It is `uxState == UX_STATE_ALL_VISIBLE && controlView.isVisible()`, so it reads
  false for the whole ~250ms fade-in and in the progress-only state — both of
  which are the controls on screen and holding focus. Read as "the controls are
  hidden" it made the skip button take focus back off play/pause within 400ms of
  the remote asking for the controls. Ask what actually holds focus instead:
  `PlayerView` holds it whenever the controls are genuinely down, and it sets
  `FOCUS_AFTER_DESCENDANTS`, so `requestFocus()` on it lands on a control when
  they are up and on the player itself when they are not.
- **Anything drawn over the video but outside `PlayerView` is a focus trap.**
  The skip button is a *sibling* of it in `activity_player.xml`, so while that
  holds focus a key aimed at it never passes through the player and cannot raise
  the transport controls at all — for as long as an opening lasts. `PlayerActivity`
  answers up and down itself in that state, and only swallows the key if focus
  genuinely moved. Any future overlay there needs the same treatment.
- **media3 ships no sources to the Gradle cache.** Unzip
  `media3-ui-<version>.aar`, then `javap -c` the class. Both of the above came
  out of doing that, and neither was guessable from the method names.

## Working notes

Measure before concluding. Nearly every wrong turn in this codebase's history
came from reasoning about what the code ought to do; nearly every real answer
came from a log line, a `curl`, or reading the library's source.
