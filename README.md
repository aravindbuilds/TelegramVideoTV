# TGTV

[![Release](https://img.shields.io/github/v/release/aravindbuilds/TelegramVideoTV)](https://github.com/aravindbuilds/TelegramVideoTV/releases)
[![Platform](https://img.shields.io/badge/platform-Android%20TV-green)](#supported-versions)
[![License](https://img.shields.io/github/license/aravindbuilds/TelegramVideoTV)](LICENSE)

**Turn your Telegram into a private Netflix for your TV.** TGTV is a free, open-source
Android TV app that you sideload onto your Android TV to browse, stream and download videos stored
in your Telegram account ΓÇö Saved Messages, private library groups, channels.
No ads. No upload limits. Your Telegram account *is* the server.

<picture>
  <img src="docs/screens/07_home_library.png" width="45%">
</picture>
<picture>
  <img src="docs/screens/08_details.png" width="45%">
</picture>

- **Runs on**: Xiaomi Mi Box, NVIDIA Shield, Fire TV (sideload), Chromecast with Google TV, and any Android TV running Android 8.0+
- **Works with**: Telegram groups, channels, Saved Messages ΓÇö videos travel as documents and still play
- **Stack**: Kotlin ┬╖ TDLib ┬╖ Leanback ┬╖ Media3 / ExoPlayer

**Start here:** [Install on your TV](#1-install-tgtv-on-your-tv) ΓåÆ [Create a bot token](#2-get-a-bot-token) ΓåÆ [Log in](#3-log-in-on-the-tv). Two 5-minute steps.

Also explore Mobile version : https://github.com/aravindbuilds/TGTV

## What's new in v1.2 (stable)

- **Watch progress syncs across your devices** ΓÇö TGTV quietly writes a position
  bookmark into your Saved Messages while you watch. Open the same title later on
  another device running TGTV and it offers to resume exactly where you left off.
  Nothing leaves your own Telegram account.
- **Cloud backup & restore** ΓÇö *Settings ΓåÆ Backup / Restore* packs your library
  layout (pinned chats, watch progress, preferences) into a private Saved Messages
  file (`#TGTVBackup`); restore it on any TV or after a factory reset in one click.
- **Search everything** ΓÇö the search icon now searches **all of Telegram** (every
  chat this session can see), not just the rows pinned to your library.
- **Playback hardening** ΓÇö stale file references self-heal (re-resolved from the
  message instead of the expiring remote id), streaming retries transient network
  failures instead of dying mid-movie, playback errors offer a working **retry**,
  and the player falls back to any other available decoder when the preferred one
  fails.
- **In-app updates** ΓÇö *Settings ΓåÆ Check for updates* downloads the newest release
  from GitHub and launches the installer; a **Beta updates** toggle opts into
  pre-releases.
- **User login is private by default** ΓÇö QR (phone) sessions now show only
  **Saved Messages** instead of every chat on the account. Your other chats,
  groups and personal media never reach the shared TV screen.
- **Add chats (Settings)** ΓÇö want more than Saved Messages? *Settings ΓåÆ Add chats*
  shows a checkbox list of every chat this session knows; pin any chat or group to
  the library and it appears as its own row. Unpin to hide it again. Bot sessions
  can pin too (it also persists their chat list across restarts).
- **Subtitles just work ΓÇö no API key needed** ΓÇö the OpenSubtitles.com key is now
  compiled into the release APK (like the Telegram credentials), so automatic
  subtitle fallback needs zero setup. All "please add your API key" prompts are gone.
- **"Add subtitles" in the player** ΓÇö *Settings ΓåÆ Add subtitles (OpenSubtitles)*
  while watching searches OpenSubtitles on demand and side-loads the SRT without
  losing your position ΓÇö handy when the file's embedded subs are wrong or missing
  a language.

## What's new in v1.1

- **No API keys needed on the TV** ΓÇö the shared Telegram app credentials are compiled into
  the release APK, so setup is now just the @BotFather token (or skip that too and log in
  with your Telegram account by QR).
- **Resume playback** ΓÇö where you left off is saved locally; the details screen offers
  **Continue** / **Play Again**, and titles watched to the end are marked as seen.
- **In-player settings panel** (press MENU or INFO while playing): switch subtitle and
  **audio tracks**, playback speed (0.25xΓÇô2x) and a **resolution cap** (Auto/4K/1080p/ΓÇª) without leaving the video. Hold MENU for a quick subtitles on/off.
- **Your library survives restarts** ΓÇö chats and videos are indexed in a local SQLite
  database, so even bot mode (which Telegram doesn't allow to read history) reopens
  full library instantly after a cold start.
- **Remove from TGTV** ΓÇö long-press ΓåÆ *Remove* hides a title from the TV only; the
  Telegram post stays untouched, hidden titles stay hidden across scans.
- **Zero-credential login on TV** ΓÇö with baked credentials the login screen goes
  **straight to the QR "Link device"** flow, no forms.
- Subtitles now render at the bottom of the **TV screen** (not the letterboxed video),
  with a Small/Medium/Large size setting; audio pauses no longer risk the box sleeping
  during playback (wake lock held); details screen shows **cast & crew** (via TMDB
  credits) and a quality badge (1080p, 4K, WEB-DLΓÇª) on the hero.

## Features

**Library & browsing**
- Netflix-style TV UI ΓÇö poster rows, focus navigation with the remote, black + red theme
- One row per chat, newest videos first, real video-frame thumbnails on every card
- Every video gets a **details screen**: full-bleed TMDB backdrop, Γÿà rating, runtime,
  status, overview, genres, cast & director, and Play / Add to Watchlist / Download buttons
- **TMDB metadata built in** ΓÇö files are identified by parsing their release-style name
  (quality tags, audio languages, group junk), with spelling-tolerant retries and a
  web-assisted fallback (Wikipedia / DuckDuckGo ΓåÆ canonical title ΓåÆ TMDB)
- **Version picker** ΓÇö multiple files of the same title (1080p vs 720p, language cuts)
  are grouped on the details screen with size/quality/audio labels
- **My List** ΓÇö watchlist bookmarked locally on the device

**Playback**
- Streaming via TDLib range downloads read straight from the partial local file
  (no Bot API 20 MB limit, no external file hosts)
- Seek anywhere ΓÇö data is fetched from the seek position, not the file start
- YouTube-style time-based buffering: starts with ~2 s buffered, look-ahead grows to
  5 minutes of video
- **Resume / Continue watching** ΓÇö position saved locally, cleared after finishing
- **In-app updates** ΓÇö *Settings ΓåÆ Check for updates* downloads the newest release
  from GitHub and launches the installer; a **Beta updates** toggle opts into
  pre-releases (like the phone app)
- Embedded subtitles plus **OpenSubtitles.com fallback** (key built into release
  builds) matched by TMDB ID and side-loaded without losing position
- Player settings: subtitles/audio track/speed/quality cap plus on-demand
  **Add subtitles (OpenSubtitles)**, reachable from MENU/INFO and the gear button

**Storage & downloads**
- Full download to `Movies/TgTV` via MediaStore on API ΓëÑ 29, private app dir on older
  devices; foreground service with progress notifications
- **Cache hygiene** ΓÇö a video's cached bytes are deleted 20 s after you exit it, and
  the whole TDLib cache is capped at 2 GB (trimmed LRU to ~1.5 GB)
- **Storage-aware playback** ΓÇö big files warn at open if free space is insufficient
  and pause before the device runs out of disk

## How it's different from other Telegram TV apps

Other "Telegram to TV" apps forward your file to a **third-party hosting bot** and
stream the resulting public URL. TGTV streams **in-process**: TDLib downloads the byte
ranges the player needs straight onto the device and ExoPlayer reads them from disk ΓÇö
your files never leave the Telegram network on the way to your screen. No Bot API
20 MB cap, no external player dependency, no public file links. Technical details:
[How streaming works](#how-streaming-works).

---

## Getting started

### 1. Install TGTV on your TV

Grab the APK from [Releases](https://github.com/aravindbuilds/TelegramVideoTV/releases):
**`TGTV-v1.3-v7a.apk`** (latest) for any Android TV (~24 MB; works on every device
including arm64/x86 ones). A **`-universal`** variant (~96 MB, all 4 ABIs) exists
for emulators.

Android TVs have no app store for this, so you **sideload** it. Pick whichever method
suits you ΓÇö all end with the same APK install dialog on the TV.

**Step 0 ΓÇö allow app installs on the TV (needed by every method)**

1. On the TV open **Settings ΓåÆ Device Preferences (or Apps) ΓåÆ Security & Restrictions**
2. Under **Install unknown apps**, pick the app you will install *from*
   (file manager, Send files to TV, or ADB shell) and toggle **Allow from this source**
3. *(ADB method only)* also enable **Settings ΓåÆ Device Preferences ΓåÆ About ΓåÆ Network ΓåÆ
   tap Build / Android TV OS build 7 times** to unlock
   **Developer options ΓåÆ Network debugging (ADB)**

**Method A ΓÇö USB drive (no computer, no internet tricks)**

1. Copy the APK you picked to a USB stick
   (FAT32/exFAT both work)
2. Plug the stick into the TV's USB port
3. Open any **file manager** on the TV (most devices have one; if not, install
   [FX File Explorer](https://play.google.com/store/apps/details?id=nextapp.fx) or
   Solid Explorer from Play Store first)
4. Browse to the stick ΓåÆ select the APK ΓåÆ press OK ΓåÆ **Install** ΓåÆ **Open**

**Method B ΓÇö over Wi-Fi (no USB, no ADB)**

1. Install **[Send Files to TV](https://play.google.com/store/apps/details?id=com.smartsysands.sendfilestotv)**
   on **both** the TV (from Android TV Play Store) and your phone
2. Open it on the TV: **Receive** ΓåÆ note the TV's IP
3. On the phone: **Send** ΓåÆ select the APK ΓåÆ pick the TV's IP ΓåÆ wait for the transfer
4. On the TV, the received APK opens inside the app ΓåÆ **Install**

*(Any LAN transfer app works the same way: LocalSend, TV File Transfer, ΓÇª)*

**Method C ΓÇö ADB over the network (last resort)**

From a PC on the same Wi-Fi, in the folder containing the APK:

```powershell
adb connect <tv-ip>:5555      # some devices use :5037, Fire TV shows the port in Settings
adb install -r TGTV-v1.3-v7a.apk  # latest release APK
adb shell am start -n com.tgtv.videotv/.ui.MainActivity
```

If `adb connect` fails, enable **Developer options ΓåÆ Network debugging** (Step 0),
or connect once with USB-ADB (Fire TV: Settings ΓåÆ My Fire TV ΓåÆ Developer options).

**Troubleshooting**

| Symptom | Fix |
|---|---|
| "App not installed" mid-install | Internal storage full ΓÇö delete unused apps / empty app caches, then retry |
| Install button does nothing after "unknown apps" toggle | Re-do Step 0 and toggle the *actual app performing the install* |
| Launch opens nothing | Reboot the TV once after install, then reopen |

### 2. Get a bot token

The release APK has Telegram's app credentials (api_id/api_hash) **built in**, so
this is the only single stop:

1. On your phone open Telegram ΓåÆ search **@BotFather** ΓåÆ send `/newbot`
2. Copy the **bot token** it gives you (format `123456:AAH...`)

### 3. Create your library and log in

1. In Telegram, create a **private group or channel** (e.g. "My TV Library") and
   **add the bot as a member / admin**. This group is your TV library ΓÇö only what
   you deliberately post or forward there will appear on the TV.
2. On the TV, open TGTV ΓåÆ choose **Bot token** ΓåÆ paste the token ΓåÆ *Save and connect*.
   (Prefer no bot at all? Choose **QR login** on the TV's login screen and scan the
   code with your phone ΓÇö Settings & Devices ΓåÆ Link Desktop Device. That logs in as
   *your own account* and shows only your **Saved Messages** ΓÇö send a video to
   yourself there and it appears on the TV.)
3. Post or forward a video into the library group ΓåÆ it should appear as a row
   on the TV within seconds.

> **Upgrading from v1.0:** the API-credentials form is gone on the TV. If the app
> opens with your old session already active, nothing to do; if it asks for
> credentials again, just use the same bot token (or switch to QR login) once.

### Recommended daily use

- Post or forward movies into your library group from your phone ΓÇö the TV library
  updates **live**.
- Watch part of a movie? The next time you open it, choose **Continue** to resume.
- Long-press a card for **Play / Download / Remove from TGTV**.
- Press **MENU/INFO** while playing for subtitles, audio track, speed and quality cap.

---

## Two login modes ΓÇö pick the right one

| | **Bot mode (recommended)** | **QR user login** |
|---|---|---|
| Log in as | your @BotFather bot | your own Telegram account |
| Library source | a private group/channel you curate | **Saved Messages**, plus any chats you pin in Settings ΓåÆ Add chats |
| History | **live only** (Telegram forbids bots from reading history) | full history of Saved Messages and pinned chats |
| Curation boundary | total ΓÇö nothing else from your account ever appears | total ΓÇö nothing else from your account ever appears |
| Resilience | library survives restarts instantly via the local index (v1.1) | same |

**Recommendation:** either mode keeps the rest of your account private; pick by
workflow. Bot mode is the "TV library appliance" ΓÇö a private group you own, the bot
as librarian, manage it from any device. QR login skips the bot entirely: send or
forward a video to **Saved Messages** on your phone and it appears on the TV, with
its full history intact.

In v1.1 the "bots can't read history" limitation mostly disappears in practice: once
a video has been captured live, it is stored in the local library index and shows
again after every restart.

## Known limitations

- **Bot live-only history** ΓÇö Telegram's server refuses history requests for bot
  accounts. The local index keeps everything the bot has *seen* in this session's DB
  forever, but the first-ever content must arrive once while the app is connected.
- **QR login is Saved Messages only (by default)** ΓÇö a deliberate privacy boundary:
  user sessions could technically read everything, so TGTV pins them to Saved
  Messages to keep the rest of your account off the shared screen. Use
  **Settings ΓåÆ Add chats** to pin specific chats or groups you *do* want.
- **Exotic codecs** ΓÇö playback relies on the TV's hardware codecs; AVI/DTS media may
  fail on some Android TVs.
- **Storage during long movies** ΓÇö streamed bytes are cached by TDLib as they are
  watched (~the whole file by the end of a full playthrough). The cache is purged
  ~20 s after you exit and capped at 2 GB when idle, but *during* playback local
  usage grows with what you've watched. TGTV warns at open and pauses before running
  out, but the practical tip remains: keep what you play under the TV's free
  `/data` space (file size is visible on every card and in the version picker; on a
  Mi Box 4 with ~2 GB free, Γëñ ~1.5 GB streams through cleanly).
- One video downloads at a time (foreground service; parallel queue is a TODO).
- Thumbnails are optional ΓÇö no thumb means a blank card, but the video still plays.

---

## Supported versions

| | |
|---|---|
| **App version** | v1.3 (stable) ΓÇö versionCode 10 |
| **Android** | 8.0 (API 26) and newer |
| **Tested on** | Xiaomi Mi Box 4 (Android 9, armeabi-v7a) |
| **Variants** | **v7a** (armeabi-v7a only, ~24 MB ΓÇö recommended for all devices) and **universal** (all 4 ABIs, ~96 MB ΓÇö emulators / exotic arm64 devices) |
| **Target SDK** | 35 |
| **TDLib** | io.github.tdlib-android:core 0.1.1 (TDLib 1.8) |

## Build from source

The project contains no API secrets. Release builds read `api_id` / `api_hash` at
build time from an **untracked** properties file and bake them (XOR-obfuscated) into
a generated Kotlin object; without one, the app builds fine but shows the good-old
manual-credentials login form. File searched in order:

1. `<project>/secrets.properties`
2. `<parent>/tgtv-secrets.properties`

Both with `api_id=<numeric>` and `api_hash=<hex string from my.telegram.org/apps>`.

**Android Studio:** open the root, let Gradle sync (pulls the ~40 MB
`io.github.tdlib-android:core:0.1.1` AAR from Maven Central), run on a TV or emulator.

**Terminal** (`adb` connected to the TV):

```powershell
# Build both release APK outputs
.\gradlew assembleV7aRelease assembleUniversalRelease   # Gradle 8.11.1+ locally

# APK output (not committed)
app/build/outputs/apk/v7a/app-v7a-release.apk
app/build/outputs/apk/universal/app-universal-release.apk
```

Releases are signed with the debug keystore, so users can install over older versions
without uninstalling.

No wrapper jar is committed: command-line `gradlew` needs a local Gradle
installation; Android Studio's bundled Gradle handles sync/run.

## How streaming works

```
ExoPlayer (progressive) -> TdVideoStreamDataSource.read()
    - 1 KiB-aligned window around the read cursor
    -> TdLib.downloadRange(fileId, offset, limit)     [mutex-serialised,
        synchronous TdApi.DownloadFile ΓÇö TDLib physically writes the range
        into its partial local file]
    <- read that range straight from the partial file (RandomAccessFile)
```

- Why not `ReadFilePart`? TDLib's `FileManager::read_file_part` rejects any range
  beyond the *contiguous downloaded prefix from byte 0* ("There is not enough
  downloaded bytes in the file to read"), which breaks every stream touching a far
  range (MKV Cues at EOF, MP4 tail atoms). Range `DownloadFile` writes are not
  subject to that gate, so the DataSource downloads the window synchronously and
  reads bytes directly off disk.
- All range downloads share one mutex: TDLib keeps at most one pending
  `DownloadFile` per file ΓÇö a new request cancels the in-flight one.
- Each DataSource gets a fresh instance: ExoPlayer opens several DataSources
  concurrently (MP4 reads the `moov` trailer at EOF while the main loader reads the
  start) and they must not share mutable state.
- After each window a single 4 MiB fire-and-forget prefetch is queued.
- **Per-video purge:** on player exit the file's pending downloads are cancelled
  and its cache entry deleted after a 20 s grace (so instant replays keep bytes).
- **Global 2 GB cap:** `TdLib.enforceCacheCap()` measures `tdlib_files/` and, when
  over 2 GB, calls `OptimizeStorage(1.5 GB)` so TDLib drops least-recently-used
  files first. Runs after login and after every purge; skipped while streaming.
- **Post-v1.0 re-resolve:** stored library rows keep the file's stable remote id;
  on restart the stale session-local file id is re-resolved to a live one instead
  of breaking playback.

## Full (offline) download path

Long-press a card ΓåÆ `DownloadService` starts (foreground, dataSync):

1. `DownloadFile(fileId, priority=24, offset=0, limit=0)` ΓÇö whole file, plus
   `UpdateFile` listener for % progress.
2. On `isDownloadingCompleted` ΓåÆ copy `local.path` into `Movies/TgTV/<fileName>` via
   MediaStore (`IS_PENDING` round-trip).
3. `RemoveFileFromDownloads(fileId, deleteFromCache=false)` ΓÇö drops the manager
   record, keeps the cached bytes.

## Project structure

```
TelegramVideoTV/
Γö£ΓöÇΓöÇ README.md                          <-- this file
Γö£ΓöÇΓöÇ settings.gradle.kts                Module graph + Maven Central / Google repos
Γö£ΓöÇΓöÇ build.gradle.kts                   Root: AGP 8.9.3 + Kotlin 1.9.24 plugins
Γö£ΓöÇΓöÇ gradle.properties                  JVM args, AndroidX flags
Γö£ΓöÇΓöÇ secrets.properties*                (untracked) build-time TDLib api_id/api_hash
Γö£ΓöÇΓöÇ docs/screens/                      README screenshots
ΓööΓöÇΓöÇ app/
    Γö£ΓöÇΓöÇ build.gradle.kts               v7a/universal ABI flavors; release builds read
    Γöé                                  the BakedApi secrets file above; deps:
    Γöé                                  tdlib-android:core:0.1.1, leanback, media3,
    Γöé                                  zxing (QR), NanoHTTPd (phone pairing), coroutines
    Γö£ΓöÇΓöÇ proguard-rules.pro             Keep org.drinkless.tdlib classes
    ΓööΓöÇΓöÇ src/main/
        Γö£ΓöÇΓöÇ AndroidManifest.xml        Permissions (INTERNET, WAKE_LOCK,
        Γöé                              FOREGROUND_SERVICE+FOREGROUND_SERVICE_DATA_SYNC,
        Γöé                              POST_NOTIFICATIONS), LEANBACK_LAUNCHER intent
        Γöé                              filter, download service, landscape /
        Γöé                              keep-screen-on player activity
        Γö£ΓöÇΓöÇ assets/setup.html          The "pair via phone" credential page served
        Γöé                              over the LAN by the embedded HTTP server
        Γö£ΓöÇΓöÇ res/
        Γöé   Γö£ΓöÇΓöÇ drawable/              Launcher/banner icons, pill/chip backgrounds
        Γöé   Γö£ΓöÇΓöÇ layout/                activity_login / activity_details /
        Γöé   Γöé                          activity_player and related views
        Γöé   ΓööΓöÇΓöÇ values/                strings.xml  colors.xml  themes.xml
        ΓööΓöÇΓöÇ java/com/tgtv/videotv/
            Γö£ΓöÇΓöÇ App.kt                 Application: starts TDLib at boot when
            Γöé                          credentials are already saved
            Γö£ΓöÇΓöÇ model/
            Γöé   Γö£ΓöÇΓöÇ VideoItem.kt       Playable-video data class, incl. the stable
            Γöé   Γöé                      remote file id + kind used to re-resolve
            Γöé   Γöé                      session-stale file ids after a restart
            Γöé   ΓööΓöÇΓöÇ VideoMapper.kt     TdApi.Message -> VideoItem (bottom half of
            Γöé                          the streaming pipeline): video / video-note /
            Γöé                          GIF / video-document w/ extension whitelist
            Γö£ΓöÇΓöÇ store/
            Γöé   ΓööΓöÇΓöÇ VideoLibrary.kt    SQLite index of the library: video refs
            Γöé                          (chat+message, metadata, remote id), hidden
            Γöé                          entries and resume-progress tables ΓÇö instant
            Γöé                          restart and bot-mode persistence
            Γö£ΓöÇΓöÇ td/
            Γöé   Γö£ΓöÇΓöÇ Credentials.kt     SharedPreferences for api id/hash/token,
            Γöé   Γöé                      subtitle settings; falls back to baked values
            Γöé   Γö£ΓöÇΓöÇ TdError.kt         TDLib error wrapper (code + message)
            Γöé   ΓööΓöÇΓöÇ TdLib.kt           Singleton TDLib client: bot + QR user login
            Γöé                          state machines, thread-safe suspend send(),
            Γöé                          cache cap, downloadRange()
            Γö£ΓöÇΓöÇ ui/
            Γöé   Γö£ΓöÇΓöÇ MainActivity.kt    Activity host; adds the browse fragment
            Γöé   Γö£ΓöÇΓöÇ MainBrowseFragment.kt  Leanback BrowseSupportFragment: chat list
            Γöé   Γöé                          (LoadChats + local index), rows, actions
            Γöé   Γöé                          (refresh / pair / sign out / TMDB /
            Γöé   Γöé                          subtitles), card click & long-press
            Γöé   Γö£ΓöÇΓöÇ VideoCardPresenter.kt  ImageCardView renderer; lazy thumbnail
            Γöé   Γöé                          decode from TDLib-downloaded thumbs
            Γöé   Γö£ΓöÇΓöÇ VideoRow.kt        One row = one chat + its VideoItems
            Γöé   Γö£ΓöÇΓöÇ LoginActivity.kt   Bot-token / QR login (baked creds: straight
            Γöé   Γöé                      to QR) + LAN "pair via phone" option
            Γöé   Γö£ΓöÇΓöÇ DetailsActivity.kt Details screen: TMDB hero, Continue / Play
            Γöé   Γöé                      Again / Watchlist / Download / Remove
            Γöé   Γö£ΓöÇΓöÇ PlayerActivity.kt  Full-screen ExoPlayer: resume, in-player
            Γöé   Γöé                      settings (subs/audio/speed/quality cap),
            Γöé   Γöé                      screen-anchored subtitles, wake lock
            Γöé   ΓööΓöÇΓöÇ TdVideoStreamDataSource.kt  Media3 DataSource mapping reads to
            Γöé                          TDLib range downloads (1 MiB), the core of
            Γöé                          Telegram streaming
            ΓööΓöÇΓöÇ svc/
                ΓööΓöÇΓöÇ DownloadService.kt Foreground service: full file download +
                                       MediaStore Movies/TgTV copy + progress
                                       notification cleanup
```

## Extending it

- **Paging / infinite scroll** (user mode only ΓÇö bots can't search history):
  `searchChatMessages` with `nextFromMessageId` (already returned in
  `FoundChatMessages`).
- **Storage-curve UI**: expose `TdLib.enforceCacheCap()` and the measured cache size
  as a Settings action ("Clear cache").
- **Multi-download queue**: hold a `ConcurrentLinkedQueue<VideoItem>` in
  `DownloadService` instead of the single-job guard.
