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

## Working notes

Measure before concluding. Nearly every wrong turn in this codebase's history
came from reasoning about what the code ought to do; nearly every real answer
came from a log line, a `curl`, or reading the library's source.
