# Debritsu on Windows

For travelling with a laptop: download episodes before a flight, stream them at
somebody else's house, and have AniList agree with both afterwards.

**Downloading and streaming are the same app.** mpv takes a local path and an
HTTPS debrid URL through one code path, so the only thing that differs between
the two cases is what the URL points at. There is no reason to build them
separately.

Branched from `main` rather than `tv`, because `tv` dropped `Downloads.kt` — a
television has nowhere to put the files.

## What actually has to move

Counted from the working tree at v1.3.15, roughly 6,500 lines in total.

| Layer            | Lines | Fate        | Why                                              |
| ---------------- | ----: | ----------- | ------------------------------------------------ |
| `data/`          | 1,952 | Ports as-is | 11 of 16 files import no Android at all          |
| `ui/`            | 2,361 | Mechanical  | Compose Multiplatform desktop; most code survives |
| `player/`        | 1,389 | Rewrite     | media3 is Android-only — replaced by driving mpv  |
| `cast/`          |   641 | Drop        | You are already at the machine with the screen    |
| `DebritsuApp.kt` |   157 | Mechanical  | Holds the two OkHttp clients and the global Context |

**1,413 of the 1,952 lines in `data/` already have zero Android imports** —
`AniList.kt`, `SourceFilter.kt`, `Stremio.kt`, `Mappings.kt`, `Debrid.kt`,
`AniSkip.kt`, `Jikan.kt`, `Models.kt`, `Json.kt`, `AniListConfig.kt`,
`SourceHandoff.kt`. Every expensive lesson in this project lives in those files,
which is why this is cheap: the knowledge is not in the Android code.

The five coupled files are shallow about it. Between them they import only
`Context`, `DownloadManager`, `Environment`, `Uri` and `Log`. `Settings`,
`Progress` and `SyncQueue` want a Context purely for DataStore; `Downloads.kt`
is the only one where an Android API does real work.

**Debrid needs no porting at all.** All four providers authenticate with a plain
API token the user pastes in. There is no OAuth flow to rebuild.

## Build order

Each phase depends on the one above it, and each is usable on its own.

### 1. Extract `data/` into a shared module

About a day, and the only step that is awkward to unpick later.

Move the Android-free files first and keep building the existing app on top of
them, so `main` and `tv` stay green throughout. Then put the five coupled files
behind small interfaces — a settings store, a file location, a logger — with the
Android implementations staying where they are. `DebritsuApp.ctx` is the single
global Context holder, so it is the seam that decides how tidy this gets.

Done when both existing APKs build and run unchanged against the shared module.

### 2. Desktop shell, driving mpv

About a weekend. This is the borrowed-wifi case, finished.

A Compose Desktop window showing Continue watching. Resolve an episode through
the existing addon and debrid code and hand mpv the URL, with subtitle tracks as
`--sub-file=` arguments. Launch it with `--input-ipc-server`, poll `time-pos`
and `duration` over the pipe, and push progress at 85% through the
`looksLikeTheEpisode` check that already exists.

**Do not rewrite the player.** Driving mpv removes 1,389 lines of rewrite, the
native library bundling and most of the packaging weight in one decision — and
gives better subtitle handling than the Android build has.

Done when an episode finished at an Airbnb shows up on anilist.co.

### 3. Swap `DownloadManager` for an OkHttp transfer

Around 60 replaced lines of 160. This is the plane case.

The valuable half of `Downloads.kt` is the index: `Downloaded` records keyed by
`anilistId` and `episode`, which is what lets you play a local file and still
know which episode you watched. That model ports unchanged. Only the transfer is
Android, and streaming to a file with OkHttp is simpler than DownloadManager,
with Range-header resume for wifi that drops.

`SyncQueue.kt` is 44 lines and already solves the offline half: queue the
progress, push it when there is a network again. Nothing to redesign.

Downloads goes last deliberately — it is the half that cannot be tested properly
until the rest works, and phase 2 already gives you something worth carrying.

Done when three episodes pulled down at home play on the plane and sync on
landing.

## Problems that stop existing

- **`LenientPgsParser.kt` becomes unnecessary.** It exists because ExoPlayer's
  PGS parser is strict. mpv renders PGS and ASS natively and picks tracks without
  the DEFAULT/FORCED fight that lands anime players on the signs-and-songs
  subtitles.
- **`SourceHandoff.kt` becomes unnecessary.** It exists only to dodge the Binder
  transaction limit. No Binder, no class.
- **The casting dead end goes away.** Debrid links are HTTPS and most televisions
  cannot fetch HTTPS. On a laptop the question never arises.
- **Debugging becomes ordinary.** No adb over flaky wireless, no log ring that
  flushes in seconds and swallows tombstones, no debug-versus-release signing
  trap that costs the user their settings on every install.

## Open questions — none of these were verified

Settle them before writing code. This project's record on reasoning instead of
measuring is poor, and all four are cheap.

- **How sign-in works without a WebView.** The app uses AniList's implicit grant
  (`response_type=token`), which returns the token in the URL *fragment* — and a
  fragment is never sent to a server, so a plain loopback redirect will not see
  it. Either serve a one-line local page that reads `location.hash` and posts it
  back, or use AniList's pin redirect and paste the token, which is how debrid
  already works. **It is not established that the pin flow accepts the implicit
  grant.** Open the authorize URL in a browser with each redirect and look.
- **Whether the registered client needs a second redirect URI.** The client id
  comes from `anilist.properties` and is registered for the Android app. One more
  URI or a second client — both trivial, but it decides whether one id serves all
  three builds.
- **mpv's IPC on Windows.** `--input-ipc-server` uses a named pipe rather than a
  Unix socket. Well established, but the position-polling loop is the one piece
  of phase 2 with no equivalent already in this codebase. Play any file and poll
  `time-pos` from a scratch script first.
- **Compose Multiplatform against Kotlin 2.0.20.** The 1.7.x line targets it, but
  confirm against the compatibility table before phase 2 — it may pull the whole
  toolchain forward. One throwaway module that renders a `Text` settles it.

## What this is not

Not a port of the Android app. Browse, search, recommendations, cast and the
television layout stay where they are; `androidx.tv` has no desktop equivalent
and does not need one. The desktop build is Continue watching, play, download,
sync, and nothing else until it has earned more.

If it works, the shared module makes a third front end cheap. That is an argument
for doing phase 1 properly, not an argument for building anything else now.
