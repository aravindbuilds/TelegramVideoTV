# TG Video TV

[![Release](https://img.shields.io/github/v/release/aravindbuilds/TelegramVideoTV)](https://github.com/aravindbuilds/TelegramVideoTV/releases)
[![Platform](https://img.shields.io/badge/platform-Android%20TV-green)](#supported-versions)
[![License](https://img.shields.io/github/license/aravindbuilds/TelegramVideoTV)](LICENSE)

**Turn your Telegram into a private Netflix for your TV.** TG Video TV is a free, open-source
Android TV app that you sideload onto your TV box to browse, stream and download videos stored
in your Telegram account — Saved Messages, private library groups, channels.
No ads. No upload limits. Your Telegram account *is* the server.

- **Runs on**: Xiaomi Mi Box, NVIDIA Shield, Fire TV (sideload), Chromecast with Google TV, and any Android 8.0+ TV box
- **Works with**: Telegram groups, channels, Saved Messages — videos travel as documents and still play
- **Stack**: Kotlin · TDLib · Leanback · Media3 / ExoPlayer

## Why it's different

- Other "Telegram to TV" apps forward your file to a **third-party hosting bot** and stream the resulting public URL. TG Video TV streams entirely **in-process**: TDLib downloads the byte ranges the player needs straight onto the device and ExoPlayer reads them from disk — your files never leave the Telegram network on the way to your screen.
- No file-size limit from the Bot API (which caps at 20 MB), no external player dependency, no public file links.

Streaming design details: see [How streaming works](#how-streaming-works) below.

---

## Features

- Slim TV-first UI (Leanback rows / focus navigation with the remote, Netflix-black + red theme)
- **Two login modes** — bot token (live-sync: videos appear as they're posted) or **phone/QR user login** (`Pair via phone` in Settings → scans Telegram's QR to authorise a full user session with complete chat history). See *Why two login modes?* below for the trade-offs.
- **Details screen for every video** — full-bleed TMDB backdrop + poster, ringing rating, runtime, status, overview, genres, Play / **Add to Watchlist** / Download buttons
- **TMDB metadata, built in** — every file is identified by parsing its release-style name (quality tags, audio languages, group junk), with spelling-tolerant retries and a **web-assisted fallback** (Wikipedia / DuckDuckGo → canonical title → TMDB). Cards show the real title, ★ rating, year and the official TMDB backdrop
- **Version picker** — multiple files of the same title (1080p vs 720p, language cuts) are grouped on the details screen with size/quality/audio labels; tap to play that copy
- **My List** — watchlist row bookmarked locally on the device
- Settings row in the library: Refresh / Pair via phone / Sign out / TMDB metadata; long-press a card for Play / Download
- Streaming via TDLib range downloads read straight from the partial local file (no Bot API 20 MB limit, no external file hosts)
- Seeking pulls data from the seek position (not the file start)
- YouTube-style time-based buffering: starts with ~2 s buffered, look-ahead grows to 5 minutes of video, plus a buffering spinner
- Real video frame thumbnails on every card (in-memory bitmap cache), newest messages first in every row
- Embedded subtitles (MKV/MP4 text tracks) render automatically; the PlayerView CC button toggles them
- Full download to `Movies/TgTV` via MediaStore on API >= 29, private app dir on older devices; foreground-service download with progress notifications
- **Streaming cache hygiene** — 20 s after you exit a video, its cached bytes are deleted automatically (instant replays within those 20 s keep the cache)
- **2 GB storage cap** — the TDLib media cache is trimmed back to ~75% automatically whenever it grows past 2 GB

## Known limitations

- **Bots have no chat history** — Telegram's server refuses `LoadChats` / `GetChatHistory` / `SearchChatMessages` for bot accounts. Bot mode is live-only: the library fills as videos are posted to groups the bot is a member of. For full history use the **QR phone login** instead.
- **Exotic codecs** — playback decoded by the TV box's hardware codecs; AVI/DTS-style media may fail on some android TVs.
- **Streaming accumulates on disk** — streamed bytes are cached by TDLib as they are watched (roughly the whole file by the end of a full playthrough, plus look-ahead). The cache is purged ~20 s after you exit and capped at 2 GB while idle, but *during* playback local usage grows with what you have watched — TDLib has no API to trim a streaming file's already-read prefix. Files bigger than the box's free `/data` space cannot stream fully: the player warns at start (preflight) and pauses before storage runs out instead of dying mid-play. For huge files use a TV with more storage, or point the TDLib cache root at USB storage (Settings toggle on the roadmap).
- One video per chat can download at a time (foreground service processes a single job; parallel queueing is a TODO).
- Thumbnails are optional — if a video has no thumbnail its card is blank but still playable.

---

## Project structure

```
Documents/Default Project/
├── README.md                          <-- this file
├── settings.gradle.kts                Module graph + Maven Central / Google repos
├── build.gradle.kts                   Root: AGP 8.9.3 + Kotlin 1.9.24 plugins
├── gradle.properties                  JVM args, AndroidX flags
├── gradle/
│   └── wrapper/
│       └── gradle-wrapper.properties  Gradle 8.11.1 (Studio syncs automatically)
└── app/
    ├── build.gradle.kts               App module deps: tdlib-android:core:0.1.1,
    │                                  leanback, media3 (ExoPlayer), coroutines
    ├── proguard-rules.pro             Keep org.drinkless.tdlib classes
    └── src/main/
        ├── AndroidManifest.xml        Permissions (INTERNET, FOREGROUND_SERVICE+
        │                              FOREGROUND_SERVICE_DATA_SYNC, POST_NOTIFICATIONS),
        │                              LEANBACK_LAUNCHER intent filter, download service,
        │                              landscape/keep-screen-on player activity
        ├── res/
        │   ├── drawable/
        │   │   ├── tv_banner.xml            Leanback launcher banner (gradient)
        │   │   ├── ic_launcher_foreground.xml   Play-triangle launcher icon
        │   │   └── input_background.xml     Rounded text-field background
        │   ├── layout/
        │   │   ├── activity_login.xml       api_id / api_hash / bot token form
        │   │   └── activity_player.xml      Full-screen PlayerView + error overlay
        │   └── values/
        │       ├── strings.xml  colors.xml  themes.xml
        └── java/com/tgtv/videotv/
            ├── App.kt                       Application: starts TDLib at boot when
            │                                credentials are already saved
            ├── model/
            │   ├── VideoItem.kt             Playable-video data class + size/duration
            │   │                            pretty-print helpers
            │   └── VideoMapper.kt           TdApi.Message -> VideoItem (bottom-half of
            │                                the streaming pipeline). Handles video /
            │                                video-note / GIF / video-document with the
            │                                full file-extension whitelist
            ├── td/
            │   ├── Credentials.kt           SharedPreferences for api id/hash/token
            │   ├── TdError.kt               TDLib error wrapper (code + message)
            │   └── TdLib.kt                 Singleton TDLib client:
            │                                - bot authorization state machine
            │                                - thread-safe suspend send()
            │                                - downloadFully() + waitForFileUpdate()
            ├── ui/
            │   ├── MainActivity.kt          Activity host; adds the browse fragment
            │   ├── MainBrowseFragment.kt    Leanback BrowseSupportFragment:
            │   │                            list chats (LoadChats+GetChats), per-chat
            │   │                            filter search, rows, card click/long-press
            │   ├── VideoCardPresenter.kt    ImageCardView renderer; lazy thumbnail
            │   │                            decode from TDLib-downloaded thumbs
            │   ├── VideoRow.kt              One row = one chat + its VideoItems
            │   ├── LoginActivity.kt         Credentials entry form (tracked in prefs)
            │   ├── PlayerActivity.kt        Full-screen ExoPlayer + error handling
            │   └── TdVideoStreamDataSource.kt   Media3 DataSource mapping reads to
            │                                TdApi.ReadFilePart chunks (1 MiB), the core
            │                                of telegram streaming
            └── svc/
                └── DownloadService.kt       Foreground service: full file download
                                             + MediaStore Movies/TgTV copy + progress
                                             notification cleanup
```

---

## How streaming works

```
ExoPlayer (progressive) -> TdVideoStreamDataSource.read()
    - 1 KiB-aligned window around the read cursor
    -> TdLib.downloadRange(fileId, offset, limit)     [mutex-serialised,
        synchronous TdApi.DownloadFile — TDLib physically writes the range
        into its partial local file]
    <- read that range straight from the partial file (RandomAccessFile)
```

- Why not `ReadFilePart`? TDLib's `FileManager::read_file_part` rejects any
  range beyond the *contiguous downloaded prefix from byte 0*
  ("There is not enough downloaded bytes in the file to read"), which breaks
  every stream that touches a far range (MKV Cues at EOF, MP4 tail atoms).
  Range `DownloadFile` writes are not subject to that gate, so the DataSource
  downloads the window synchronously and reads bytes directly off disk.
- All range downloads share one mutex because TDLib keeps at most one pending
  `DownloadFile` per file - a new request cancels the in-flight one.
- Each DataSource gets a fresh instance: ExoPlayer opens several DataSources
  concurrently (MP4 reads the `moov` trailer at EOF while the main loader
  reads the start) and they must not share mutable state.
- After each window a single 4 MiB fire-and-forget prefetch is queued.

## Streaming cache management

- **Per-video purge** — `PlayerActivity.onDestroy` cancels pending downloads
  (`CancelDownloadFile`) of that file and deletes its cache entry
  (`DeleteFile`) after a 20-second grace period, so instant replays keep
  their cached bytes.
- **Global 2 GB cap** — `TdLib.enforceCacheCap()` measures `tdlib_files/` and,
  when over 2 GB, calls `OptimizeStorage(size = 1.5 GB)` so TDLib drops the
  least-recently used files first. Runs after login, after every per-video
  purge, and is skipped while a video is actively streaming.

## Full (offline) download path

Long-press a card → `DownloadService` starts (foreground, dataSync):

1. `DownloadFile(fileId, priority=24, offset=0, limit=0)` — whole file, plus `UpdateFile` listener for % progress.
2. On `isDownloadingCompleted` → copy `local.path` into `Movies/TgTV/<fileName>` via MediaStore (`IS_PENDING` round-trip).
3. `RemoveFileFromDownloads(fileId, deleteFromCache=false)` — drops the manager record, keeps the cached bytes.

---

## Supported versions

| | |
|---|---|
| **App version** | v0.2 |
| **Android** | 8.0 (API 26) and newer |
| **Tested on** | Xiaomi Mi Box 4 (Android 9, armeabi-v7a) |
| **Architectures** | two variants: **v7a** (armeabi-v7a only, ~24 MB — recommended for all TV boxes) and **universal** (all 4 ABIs, ~96 MB — emulators/exotic arm64 boxes) |
| **Target SDK** | 35 |
| **TDLib** | io.github.tdlib-android:core 0.1.1 (TDLib 1.8) |

Downloads: see [Releases](../../releases) — grab **`TGVideoTV-v0.2-v7a.apk`** for any
real TV box (works on arm64 devices too, they run v7a natively) or
`TGVideoTV-v0.2-universal.apk` for emulators, then follow the
[Sideload guide](#sideload-install-guide) below.

---

<p align="center"><img src="art/logo.png" alt="TG Video TV logo" width="140"></p>

## Sideload install guide

Android TVs don't ship an app store for this APK, so you **sideload** it. Pick whichever
method suits your setup — all of them end with the same APK install dialog on the TV.

### Step 0 — allow app installs on the TV (any method)

1. On the TV open **Settings → Device Preferences (or Apps) → Security & Restrictions**
2. Under **Install unknown apps**, pick the app you'll install *from* (file manager,
   Send files to TV, or ADB shell) and toggle **Allow from this source**
3. (For the ADB method only) also enable **Settings → Device Preferences → About →
   Network → tap Build / Android TV OS build 7 times** to unlock
   **Developer options → Network debugging (ADB)**

### Method A — USB drive (no computer needed)

1. Copy `TGVideoTV-v0.1.apk` to a USB stick (FAT32/exFAT both work)
2. Plug the stick into the TV's USB port
3. Open any **file manager** on the TV (most boxes have one; if not, install
   [FX File Explorer](https://play.google.com/store/apps/details?id=nextapp.fx) or
   Solid Explorer from Play Store first)
4. Browse to the stick → **TGVideoTV-v0.1.apk** → press OK → **Install** → **Open**

### Method B — over your Wi-Fi (no USB, no ADB)

1. Install **[Send Files to TV](https://play.google.com/store/apps/details?id=com.smartsysands.sendfilestotv)**
   on **both** the TV (Play Store on Android TV) and your phone
2. Open the app on the TV, **Receive** → note the TV's IP
3. On the phone: **Send** → select the APK → pick the TV's IP → wait for the transfer
4. On the TV the received APK opens from inside the app → **Install**

*(Any LAN transfer app works the same way: LocalSend, TV File Transfer, etc.)*

### Method C — ADB over the network (if the box has no file manager and no Play Store)

From a PC on the same Wi-Fi, in the folder containing the APK:

```powershell
adb connect <tv-ip>:5555      # some boxes use :5037, Fire TV shows the port in Settings
adb install -r TGVideoTV-v0.1.apk
adb shell am start -n com.tgtv.videotv/.ui.MainActivity
```

If `adb connect` fails, enable **Developer options → Network debugging** (Step 0),
or connect once with USB-ADB (Fire TV: Settings → My Fire TV → Developer options).

### Troubleshooting

| Symptom | Fix |
|---|---|
| "App not installed" mid-install | Internal storage full — delete unused apps/empty app caches, then retry |
| Install button does nothing after "unknown apps" toggle | Re-do Step 0 and toggle the *actual app performing the install* |
| Wrong-architecture APK | Not possible here — the APK ships armeabi-v7a which every TV box runs |
| Launch opens nothing | Reboot the box once after install, then reopen |

After install, continue with **[Setup (5 minutes)](#setup-5-minutes)**.

## Setup (5 minutes)

1. [my.telegram.org/apps](https://my.telegram.org/apps) → log in → "API development tools" → create an app → copy **api_id** and **api_hash**.
2. Telegram → **@BotFather** → `/newbot` → copy the **bot token** (format `123456:AAH...`).
3. Create a **private channel or group** (e.g. "My TV Library") → **add the bot as member / admin**. Edit bot to allow read access in groups.
4. Install the APK on the TV, open it, fill in the three values → Done.

Post or forward videos into your library channel; then the home screen shows a row named after the channel with all the videos.

---

## Why two login modes?

The app supports a **bot session** and a **user (phone/QR) session** because they answer two different needs — control vs. reach.

### Bot mode — your own private library "server"

With bot mode you stay in full control of what the TV shows:

- You create a **private group/channel** ("My TV Library") and add your bot as a member/admin. That group becomes the **directory** the TV displays — one row per group, and *only* the videos you deliberately send or forward there.
- Nothing else from your Telegram account is ever visible on the TV. Family members posting in other chats, work groups, personal media — none of it can appear.
- You manage the "server" from your phone like a normal Telegram group: rename it, post from anywhere (or from any other device), delete entries, and the TV library updates live.
- Bots are the natural fit for an appliance: the TV session is tied to the bot token, not to your personal account — you can sign out / re-pair / reset without touching your real account, and there is no risk of your personal chats being browsable from the living room.
- The trade-off (Telegram's own rule): **bots cannot read chat history** (`LoadChats`/`GetChatHistory`/`SearchChatMessages` are refused server-side for bot accounts). So bot mode is *live-sync only* — the library fills as videos are posted while the app is connected. Older content must be forwarded into the library group once to appear.

### User (phone/QR) login — full archive, shared control

Scanning the QR (Settings → *Pair via phone*) logs the TV in as **your own Telegram account**:

- The library mirrors **your real account**: every chat, complete history, all videos already sitting in your groups — no forwarding needed.
- The flip side is exactly that: you **lose the curation boundary**. The TV now displays whatever your account can see, including chats you may not want on a shared screen. There is no "only these groups" filter (yet) — the display scope is your whole account.
- Session management is heavier: it consumes one of your account's device slots, and logging out from the TV is an account-level action.

### Recommendation

- Use **bot mode as the daily driver**: it is the "TV library appliance" pattern — a private group you own, bot as the librarian, you decide exactly what appears.
- Use **QR login when you specifically need old content** — e.g. to pull the full history of a channel into the library — and switch back afterwards (Settings → *Sign out*).

## Build & install

Open the project root in **Android Studio** → let Gradle sync (fetches `io.github.tdlib-android:core:0.1.1` (~40 MB AAR) from Maven Central) → Run target is an Android TV box / emulator.

Via terminal (Network-connected TV):

```
adb connect <tv-ip>
.\gradlew installDebug        # Gradle 8.11.1+ needed locally
adb shell am start -n com.tgtv.videotv/.ui.MainActivity
```

No wrapper jar is committed, so command-line `gradlew` needs a local Gradle install; Android Studio's bundled Gradle handles sync/Runtime-Run automatically.

## Extending it

- **Paging / infinite scroll** (user mode only — bots can't search history): `searchChatMessages` with `nextFromMessageId` (already returned in `FoundChatMessages`).
- **Storage-curve UI**: expose `TdLib.enforceCacheCap()` and the measured cache size in a Settings action ("Clear cache").
- **Multi-download queue**: hold a `ConcurrentLinkedQueue<VideoItem>` in `DownloadService` instead of the single-job guard.
