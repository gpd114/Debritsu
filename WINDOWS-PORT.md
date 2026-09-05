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

### 1. Extract `data/` into a shared module, and move the toolchain

About a day for the extraction, plus a Kotlin upgrade — see the Compose
Multiplatform answer below. This is the only step that is awkward to unpick
later, and the only one that touches `main` and `tv` as well as this branch.

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

## Open questions — all four settled, 5 September 2026

Kept with their answers rather than deleted, because the answers are why the
phases are shaped the way they are. Two of them changed the plan.

### Sign-in without a WebView — settled, and easier than feared

AniList's **pin redirect works with the implicit grant**, and shows the user an
**access token** to copy and paste — not an authorization code needing a secret.
Set the client's Redirect URL to `https://anilist.co/api/v2/oauth/pin` and
desktop sign-in becomes: open the browser, paste the token. Exactly how debrid
already works here.

So there is **no local HTTP server, no JavaScript bounce page reading
`location.hash`, and no Windows protocol handler**. The fragment problem
disappears entirely.

### A second AniList client id — needed, and cheap

The Android client is registered with the custom scheme `debritsu://auth`: the
manifest carries the intent filter and `SettingsScreen` tells the user to
register exactly that. Desktop needs the pin URL instead, and AniList documents
"the Redirect URL" in the singular with no way to register several.

**Plan on a separate client id for the desktop build.** The app already has a
"use my own API client" field, so the interface for it exists. Worth 30 seconds
confirming at anilist.co/settings/developer, which only the account holder sees.

### mpv IPC on Windows — settled, tested against a running player

mpv 0.41.0 installed via `winget install shinchiro.mpv`, the build mpv.io points
Windows users to. Everything phase 2 needs was exercised over the pipe:
`get_property`, `observe_property`, `seek`, and position advancing in real time.

**mpv does not go on PATH.** It installs to `C:\Program Files\MPV Player\mpv.exe`
and the shell knows nothing about it, so the app has to locate the binary — a
configured path, or a search of the usual install directories — rather than
shelling out to `mpv`.

**Events and replies share the pipe.** A reply carries the `request_id` it was
asked with; property changes and playback events arrive on the same stream
uninvited. The client loop has to read lines, match on `request_id`, and hand
anything else to an event handler. This is the one real implementation detail,
and it is the thing a naive "write a command, read one line" client gets wrong.

Connect from JVM code the same way this was tested: a named pipe client against
`\\.\pipe\<name>`, matching `--input-ipc-server=\\.\pipe\<name>`. The pipe was
ready on the first connection attempt.

**`observe_property` works, but it is far too chatty to use directly** — four
pushes inside about 130ms of playback. Progress only needs checking against the
85% mark, so poll `time-pos` about once a second instead, or throttle the
observer hard.

**`duration` is reliable on a real container and not on a synthetic one.** A
lavfi test source reported 3.17 seconds while playing well past 30; a real MP4
encoded at 25 seconds reported 24.96. That matters because `looksLikeTheEpisode`
gates the 85% progress push on duration — the check is sound, but it must be
tested against real media, never a generated stream.

VLC 3.x is also present at `C:\Program Files\VideoLAN\VLC` if a fallback is ever
wanted, but nothing here suggests one is needed.

### Compose Multiplatform against Kotlin 2.0.20 — settled, and it moves work into phase 1

**Compose Multiplatform 1.8.0 and later require Kotlin 2.1.0 or newer.** This
project is on Kotlin 2.0.20, and the current release is 1.12.0. So the choice is
pinning to the 1.7.x line, five releases behind and off the supported path, or
moving the toolchain.

Move the toolchain, and **do it in phase 1**. Kotlin and the Compose Compiler
plugin have to move in lockstep — Multiplatform wants the compiler plugin at the
same version as the Kotlin plugin — so the Compose BOM and both Android apps need
re-verifying. That lands on `main` and `tv`, not only here, which is why it
belongs with the module extraction that already touches them rather than being
discovered halfway through phase 2.

JDK 17 is sufficient: desktop needs 11+, `jpackage` needs 17+.

## What this is not

Not a port of the Android app. Browse, search, recommendations, cast and the
television layout stay where they are; `androidx.tv` has no desktop equivalent
and does not need one. The desktop build is Continue watching, play, download,
sync, and nothing else until it has earned more.

If it works, the shared module makes a third front end cheap. That is an argument
for doing phase 1 properly, not an argument for building anything else now.
