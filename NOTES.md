# Hand-off notes

Running log between milestones. Newest entries at the bottom.

## Milestone 1 — Gradle scaffold + `:core` API client

### Layout
- `settings.gradle.kts`: `:core`, `:ui-common`, `:tv`, `:mobile`. Versions in `gradle/libs.versions.toml`
  (AGP 8.7.3, Kotlin 2.1.0 + compose plugin, Compose BOM 2024.12.01, tv-material 1.0.0, media3 1.5.1,
  OkHttp 4.12, kotlinx-serialization 1.7.3, coroutines 1.9.0, Coil 2.7.0, navigation 2.8.5, lifecycle 2.8.7,
  DataStore 1.1.1). Wrapper: Gradle 8.14.3. Java/Kotlin target 17 (no `jvmToolchain`, the container has JDK 21 only).
- minSdk 26 for all Android modules (adaptive icons only, no legacy PNG launcher icons), compile/target 35.
- CI: `.github/workflows/ci.yml` runs `./gradlew :core:test assembleDebug` on JDK 17 (extended since; see
  "Release engineering + screenshots").
- `local.properties` (`sdk.dir=/opt/android-sdk`) is gitignored; create it in fresh clones.

### `:core` (pure Kotlin/JVM, package `app.antresol.core`)
- `api/AntresolClient` — suspend functions for the whole native API: health, setup, profiles,
  auth/token (`signIn`), me, logout, devices, media-key; home, items, item, search, library status;
  progress get/put, watched, watchlist; play, HLS keepalive/stop, subtitle VTT download; music home,
  albums, album, artists, artist, songs, track, genres, shuffle, lyrics (404 → null), plays, star/unstar,
  playlists CRUD; books home/list/detail; comic pages. Errors → `ApiException(status, message, code, retryAfterSec)`.
  A 401 on an authenticated call invokes `onUnauthorized` (not for a wrong password at sign-in).
- `api/AntresolHeadersInterceptor` adds Bearer + `Accept-Language` and strips `Origin`, **only for the
  configured server origin**, and skips Bearer when the URL carries `mk` (so HLS gives the VOD playlist).
  `client.httpClient` already has it — share it with Coil and ExoPlayer's `OkHttpDataSource`.
- `api/MediaUrls` (`client.urls`): image/stream/download/playback(HLS+mk)/track/audio/comic page/zip URLs,
  and `normalizeBaseUrl` (bare host → `http://host:8420`). `api/MediaKeyCache` (`client.mediaKeys.get()`)
  refreshes the 12 h key when < 1 h is left.
- `model/*` — kotlinx.serialization models; lenient `AntresolClient.json` (ignoreUnknownKeys,
  coerceInputValues, explicitNulls=false). Card-shaped detail types implement `CardFields` (`toCard()`).
- `session/` — `SessionStore` interface (device id + `SessionState`: server URL, active session, one saved
  token per (serverId, userId) for instant profile switching), `InMemorySessionStore`, `DeviceIds`.
- `playback/PlaybackReporter` — progress every 10 s when moved ≥ 0.5 s (audiobook preset 1 s), HLS keepalive
  every 20 s, `flush()` on pause/background, `stop()` = final write + `DELETE /api/hls`; keepalive 404 →
  `onSessionLost`. Takes a `CoroutineScope` (use Main for ExoPlayer) and a `PlaybackApi` (`client.playbackApi()`).
- `playback/Timelines.kt` — `StreamTimeline` (HLS startSec offset, when a seek needs a new session,
  subtitle offset), `AudiobookTimeline` (whole-book ↔ part/offset), `Resume` rules, `ScrobbleTracker`.
- `PlaybackCaps.EXOPLAYER_BASELINE` (h264/hevc; aac/mp3/ac3/eac3/opus/flac; mp4/mkv/webm). Apps should
  refine it from `MediaCodecList` (AV1/VP9, audio passthrough) — not done yet.

### Tests
- `./gradlew :core:test` — 43 unit tests: every `api-samples/*.json` decodes (a new sample without a
  registered decoder fails the test), request shapes via MockWebServer, URL building, reporter timing
  with virtual time, timelines, session state.
- Integration: `ANTRESOL_TEST_SERVER=1 ./gradlew :core:test` (or a base URL instead of `1`;
  `ANTRESOL_TEST_PASSWORD` defaults to `admin123`). 6 read-only tests against the live server, all
  passing on 0.3.0: sign-in admin + kid, me/devices/media key, every movie/series/season/episode detail,
  images with Bearer, direct play (206 with mk) and HLS (playlist via mk, keepalive, VTT, stop), music
  endpoints + track stream, books/audiobooks (audio stream) /comics (pages + page image). The test device
  id is fixed (`antresol-android-core-tests`) and logs out at the end, so no tokens pile up.
  Wrong-password sign-in is intentionally not hit live (failed logins throttle the whole IP).

### `:ui-common` (Android library, package `app.antresol.ui`)
- `theme/` — `AntresolColors`, `AntresolGradients` (grad-1..8 avatars at 145°, brand 160°, CSS-angle
  linear gradients, placeholder/hero fades), `AntresolTypography.phone/.tv`, `AntresolShapes`
  (12/10/12/20/pill/6), `AntresolMotion` (focus 1.07, card 1.04, ease-out curve, durations, aspect ratios),
  `AntresolTheme` (Material3 dark scheme + `Antresol.typography`).
- `components/` — `ProfileAvatar`, `ArtworkImage`/`PosterImage` (title-on-gradient fallback, progress bar),
  `Badge`/`BadgeRow`, `BrandSplash`, `LogoMark`.
- `image/AntresolImageLoader` — Coil `ImageLoader` with the Bearer interceptor, 256 MB disk cache.
- `format/` — `Durations` (clock, localized spoken, remaining), `Badges` (classify/order), `TrackLabels`.
- `data/DataStoreSessionStore` — DataStore-backed `SessionStore` (lives here because it is the only shared
  Android module).
- Strings: `values/strings.xml` + `values-ru/strings.xml` (nav, sign-in, actions, sorts, kinds, detail,
  states, durations). Add new shared UI strings here in both languages.
- Font: SF Pro is not licensed for Android; the system sans is used with SF-like weights/tracking.

### `:tv` / `:mobile`
- `MainActivity` shows `BrandSplash` (the design's launch splash) inside the theme; real navigation is
  the next milestone. TV: leanback + phone launcher intents, `tv_banner` 320×180 (xhdpi),
  `touchscreen`/`leanback` not required, cleartext allowed (LAN http servers). `AntresolTvTheme`
  wraps the shared theme with tv-material3 colours.
- Icons: adaptive icons — gradient vector background + `ic_launcher_foreground` PNGs (mdpi..xxxhdpi)
  rendered from `docs/design-ref/logo-mark.svg` with Playwright; `ui-common` `drawable-nodpi/logo_mark.png`.

### Build environment notes
- Maven Central answered 429 (rate limiting) in this container. Locally I built with an init script that
  prefers Google's mirror: `./gradlew -I <init> ...` with
  `beforeSettings { val m = "https://maven-central.storage-download.googleapis.com/maven2/"; pluginManagement.repositories { maven(m) }; dependencyResolutionManagement.repositories { maven(m) } }`.
  The repo itself uses plain `google()` + `mavenCentral()`.

### Known gaps
- No screens yet beyond the splash (server entry, profile picker, home, detail, player are milestone 2+).
- `PlaybackCaps` device probing (MediaCodecList / passthrough) not implemented.
- SSE (`/api/events`) and admin endpoints (settings, users, scan trigger, app passwords) are not wrapped;
  `libraryStatus()` is. Not needed by the TV/phone clients so far.
- `:ui-common` has no unit tests (formatters use Android resources).

## TV milestone 1 — onboarding, profiles, shell, home

### `:core`
- `session/SessionManager` (shared with `:mobile`): owns the `AntresolClient` and an `AuthState` flow
  (`Starting` → `NoServer` → `ChooseProfile` → `SignedIn`). `restore()` re-uses the stored token (validated
  with `/api/me`; offline keeps the stored session), `connect(address)` validates `/api/health` + `/api/setup`
  (`NotAntresolServerException`, `ServerNeedsSetupException`), `resume(profile)` / `signIn(profile, password)`,
  `switchProfile()`, `signOut()` (best-effort `/api/logout`), `changeServer()`. A 401 for the active token
  forgets it and returns to `ChooseProfile(notice = SessionExpired)`.
- `SessionState` gained `serverId`, `connected()`, `disconnected()`. 9 new unit tests (`SessionManagerTest`).
- Decision: saved tokens are re-used only for **passwordless** profiles; switching into a password profile always
  asks for the password (a shared TV must not let a kid hop into the admin profile). The active session still
  survives app restarts.

### `:ui-common`
- `AntresolTypography.tv` re-scaled for the 960×540 dp TV canvas (about half the web's 1920 px sizes).
- `components/AntresolIcons` (24-grid line icons: search, info, chevron, lock, server, sign-out, profile, play).
- `image/ArtworkTint` + `rememberArtworkTint(url, fallback)`: vivid, readable tint from artwork (hero glow).
- New EN/RU strings: server/onboarding errors, password, account, home empty, search sections, detail rows.

### `:tv` (package `app.antresol.tv`)
- `AntresolTvApp` builds the `SessionManager` (DataStore store, device name `Android TV (<Build.MODEL>)`,
  `Accept-Language` from the system locale) and is Coil's `ImageLoaderFactory` (Bearer images).
- `ui/root/AntresolTvRoot`: crossfades splash → `ServerScreen` → `ProfilesScreen` (avatars, lock icon,
  password step, "Change Server") → `MainShell` (keyed per profile, so a switch resets navigation).
- `ui/shell`: type-safe navigation-compose routes (`Routes.kt`), `TopNav` (brand, centred glass pill: search icon,
  Home, Movies, TV Shows, Music, Books; avatar → `AccountScreen`). Tabs switch on OK with save/restore state.
  Entering the pill from the content lands on the current tab. The nav fades out while content is scrolled
  and returns when focused. Detail pages hide it.
- `ui/home`: hero (backdrop + artwork-tinted glow, eyebrow "NEW IN LIBRARY · MOVIE", huge title, year/badges,
  white Play/Resume + glass Details pills, rotation every 10 s while the hero is focused, dots), Continue
  Watching as 16:9 wide cards with progress, every non-empty `/api/home` shelf (albums square, others 2:3).
  When the server sends no hero, the first video card of Continue/Recent/Watchlist is used.
- Focus: initial focus on hero Play; `FocusMemory` (rememberSaveable keys) restores the focused card after Back;
  rows use `focusRestorer` (up/down returns to the row's last card) and `trapHorizontalFocus()` (Left/Right at a
  row end stays in the row); `VerticalShelfScrolling`/`HorizontalRowScrolling` keep the focused card inside a
  comfortable zone (captions stay visible). Back deep in Home returns to the hero first. Screens never steal
  focus from the nav (switching tabs keeps focus on the pill).
- Also usable already: Movies / TV Shows grids (sort pills, paging), Music / Books shelf pages, Search
  (debounced, sections; songs open their album), a basic detail page for every card kind (backdrop/artwork,
  meta, overview, seasons → episode rows, artist albums, comic volumes), Account (switch profile, sign out,
  change server).
- Tests: `HomeContentTest` (JVM). `TvLiveUiTest` (Robolectric, NATIVE graphics) drives the real UI against the
  live server with D-pad key events — server entry (error + success), profile picker, password, Play focused,
  shelves, detail, Back focus restore, row focus restore, nav entry on the current tab, Movies, Music — and writes
  screenshots to `tv/build/screenshots/`. Run: `ANTRESOL_TEST_SERVER=1 ./gradlew :tv:testDebugUnitTest`
  (excluded otherwise). Robolectric fetches `android-all-instrumented` 14 and 15 from Maven Central; in this
  container I pre-downloaded them into `~/.m2/repository` from the Google mirror.
- CI now runs `:core:test :tv:testDebugUnitTest assembleDebug`. `:tv:assembleRelease` (R8) also builds.

### Known gaps (for TV milestones 2–3)
- **No player yet**: hero Play/Resume and Continue Watching cards open the detail page. Milestone 2 should route
  `onPlay` in `MainShell` to the player.
- Detail page is basic (no Play/watchlist/watched actions, no track lists, no cast, no audio/subtitle pickers).
- Tokens are stored in app-private DataStore, not encrypted with the Keystore (nice-to-have).
- No on-device run was possible (no emulator/KVM here); behaviour was verified with Robolectric only.
- Account page is minimal; settings (language override, quality, devices) belong to milestone 3.

## Mobile milestone 1 — onboarding, shell, home, browse, detail, search

### `:mobile` (package `app.antresol.mobile`, Compose Material 3, dark only, edge to edge)
- `AntresolMobileApp` builds the shared `SessionManager` (DataStore store, device name = phone model such as
  "Google Pixel 8", `Accept-Language` from the system locale) and is Coil's `ImageLoaderFactory` (Bearer images).
- `ui/root/AntresolMobileRoot`: crossfades splash → `ServerScreen` (address, Connect, specific errors for
  unreachable / not Antresol / needs setup) → `ProfilesScreen` (avatars with lock badge, password step with
  show/hide, "wrong password" on 401, session-expired notice, Change Server) → `MainShell` (keyed per profile).
- `ui/shell`: type-safe navigation-compose routes (`Routes.kt`). Floating glass `BottomBar` like phone_*.png:
  pill with Home, Movies, TV Shows, Music, Books plus a round Search button. Each tab keeps its back stack
  (save/restore state); re-tapping the current tab pops to its root. The tab header (logo + "Antresol",
  avatar) scrolls with the content; the avatar opens `AccountSheet` (switch profile, change server, sign out).
  `LocalBottomBarPadding` tells scrolling content how much room the bar needs.
- Home: hero pager (artwork-tinted glow, eyebrow, huge title, white Play + glass Details; rotates every 8 s,
  centred on phones, left-aligned on tablets) and every `/api/home` shelf (Continue Watching as 16:9 tiles).
  Shelf chevrons open the matching library grid, or the shelf's own cards (`GridSource.forShelf`).
- Grids (`browse/CardGridScreen` + `GridSource`): Movies / TV Shows tabs and pushed pages (All Albums, genre
  albums, Artists, Playlists, All Books, Audiobooks, Manga & Comics, shelf "see all"), adaptive columns
  (3 across a phone, 5–7 on tablets), segmented sort pills (New / A–Z / By Year / By Artist / By Author), paging.
- Music page: links, genre chips, music shelves. Books page: links + books shelves.
- Detail: `VideoDetailScreen` (movie/series/season/episode): tinted backdrop header, meta and badges,
  Watchlist and Watched toggles (optimistic, undone with a snackbar on failure), overview, Up Next, season pills,
  episode rows with their own watched toggle, "Mark Season as Watched", file/audio/subtitle info, cast.
  Movie/episode watched state comes from `/api/progress/{id}.finished`. `MediaDetailScreen` covers albums,
  artists, playlists, books, audiobooks and comics (track list, chapters, volumes, artist albums, Want to Read).
- Search: debounced `/api/search`, filter pills (All / Movies / TV Shows / Music / Books regroup the last answer),
  results as shelves, songs as rows that open their album; with an empty query the coloured Browse tiles.
- Strings: mobile-only strings in `mobile/src/main/res/values{,-ru}`; shared ones from `:ui-common`.
  `LoadState`/`userMessage` and `kindLabel` are small copies of the TV ones (kept in `:mobile` so the two chains
  did not edit shared files at the same time) — a later cleanup can move them into `:ui-common`.
- No changes to `:core` or `:ui-common` in this milestone.

### Tests
- `BrowseLogicTest` (JVM): grid source route encoding (found and fixed a genre containing `|`), shelf chevron
  mapping, search filter grouping. Runs in CI (`:mobile:testDebugUnitTest`).
- `MobileLiveUiTest` (Robolectric, NATIVE graphics, only with `ANTRESOL_TEST_SERVER`): phone flow — server error
  + connect, profiles, password, home, Movies sort, movie detail (watchlist toggled on the server and back),
  series detail with seasons, Music, All Albums, Books, Search browse + results, account sheet, switch profile;
  tablet (1024×768) home, Movies grid, detail. Screenshots in `mobile/build/screenshots/`. (Phone size and the tablet
  coverage changed in "Release engineering + screenshots".)
  Waits advance Robolectric's paused main looper so `delay()`-based work (search debounce) runs.

### Known gaps (for mobile milestone 2)
- **No player yet**: hero Play opens the detail page (`MainShell` `onPlay`), detail pages have no Play/Resume,
  album/playlist track rows and audiobook chapters are not tappable, books/comics have no Read/Listen. Milestone 2
  should add the player, the MediaSessionService for music/audiobooks, readers, and wire these.
- Music: no Songs list and no Shuffle All button yet (both need playback). Settings screen not built.
- Android has no backdrop blur behind the tab bar; it uses a 97 % opaque elevated fill.
- No on-device run (no emulator here); verified with Robolectric screenshots only.

## TV milestone 2 — browse, details, search, player

### `:core` (shared with `:mobile`)
- `playback/VideoSession`: opens a **file** through `/api/play` with caps/quality/audioIndex, signs the URL with
  `?mk=` (`VideoSource` = response + URL + `StreamTimeline`), replaces its own HLS session on every reopen
  (`replaceSession`), `sessionLost()` after a keepalive 404, `suspendSession()` (DELETE now, reopen later).
  Keepalives/progress/final stop stay in `PlaybackReporter` (`attachSession` after each reopen).
- `playback/WebVtt.shift(vtt, offsetMs)`: moves cues to player time for HLS (`timeline.subtitleOffsetMs`), drops
  cues before 0. `playback/CodecSupport.caps(decoderMimes, passthrough)`: h264 always, hevc/vp9/av1 by decoder,
  aac/mp3/opus/flac always, ac3/eac3 by decoder or passthrough; containers mp4/mkv/webm.
  `playback/Episodes`: season/episode order, `after(series, id)` (autoplay), `toPlay(series)` (`next` or first).
- Tests: `WebVttTest`, `CodecSupportTest`, `EpisodesTest`, `VideoSessionTest`; live
  `ServerIntegrationTest.videoSessionSwitchesTracksAndQuality` (direct mkv → audio track forces remux HLS with the
  keyframe-snapped start → VOD playlist with mk → VTT shift → 480p replaces the session (old keepalive 404) →
  suspend/reopen). All 7 live tests pass against 0.3.0.

### `:ui-common`
- `playback/DeviceCaps.current(context)`: `MediaCodecList` decoders (video: hardware decoders only, so a software
  AV1/VP9 decoder does not claim support) + AC-3/E-AC-3 encodings of HDMI/optical outputs. Use it from `:mobile` too.
- Icons: Pause, Plus, Check, CheckCircle, Audio, Subtitles, Sliders, SkipNext, Restart. EN/RU strings for detail
  actions and the player (`player_*`, `detail_play_episode`, …).

### `:tv`
- Routes: `PlayerRoute(itemId, resume)` (`ResumeMode` Ask / Resume / FromStart). Hero Play and Continue Watching
  cards resume video kinds directly; a series/season id plays its `next` episode (or the first).
- Detail (`DetailScreen`/`DetailViewModel`): movies/episodes/series/seasons show Play / Resume (or
  "Play S1 E3" / "Resume S1 E3" for series), Play from Beginning, Want to Watch (movie/series, optimistic) and
  Mark as Watched/Unwatched (refetches), an "Audio: … · Subtitles: …" line and cast. Series: season pills
  (select on focus; entering lands on the selected season) and 16:9 episode cards with progress / watched check;
  OK on an episode opens the player with the resume prompt. The page refreshes silently when it resumes (back from
  the player). Watched state of a movie/episode = `/api/progress.finished`.
- Player (`ui/player`): ExoPlayer (+HLS, OkHttp data source sharing `client.httpClient`) owned by
  `PlayerViewModel`, `PlayerView` only as surface + subtitle renderer. Custom TV controls: title/eyebrow, focusable
  seek bar (Left/Right ±10 s, OK pause), Play/Pause, Audio, Subtitles, Quality, Next Episode pills; side glass
  menus with check marks (quality menu explains the stream mode and the server's reason). Controls hide after 5 s
  of playing; with them hidden OK pauses, Left/Right seek, Up/Down show them; media keys (play/pause, ff/rew, next)
  always work. Back closes a menu → hides controls → leaves.
  - Flow: item → file `files[0]` → progress → resume prompt (≥ 10 s saved, Ask mode) → `VideoSession.open` →
    `PlaybackReporter` (progress 10 s, keepalive 20 s, flush on pause, final write + DELETE on exit via the app
    scope so it survives the ViewModel). HLS seeks before `startSec` debounce 0.7 s, then reopen with
    `replaceSession`; audio/quality changes reopen at the current position. Keepalive 404 or a player error →
    DELETE (best effort) + reopen without `replaceSession` (max 2 tries, then an error screen). 429 → "This
    profile is already watching on another device." with Try Again.
  - Subtitles: only text tracks with a `url`; the VTT is downloaded with the Bearer header (retries on
    `Retry-After`), shifted to the stream start, written to `cacheDir/subtitles`, side-loaded as
    `SubtitleConfiguration` (re-shifted after every reopen); embedded MKV text tracks are never auto-selected.
  - Background (ON_STOP): pause, flush, DELETE the HLS session; ON_START reopens it paused at the same spot.
  - Next episode: on end, "Up Next" with a 10 s countdown (Play Now / Back), then the next episode resumes.
- `TvTextField`: Up/Down leave the field (a single-line field otherwise eats them), so search results are
  reachable with the D-pad.
- Tests: `VideoInfoTest` (JVM, Play target / watched rules). `TvLiveUiTest.searchDetailActionsAndPlayer` (live):
  search → movie detail (facts, watchlist toggled and back) → player controls → back → series detail (season
  pills, episode cards) → episode → player with Next Episode. It snapshots and restores the progress of the items
  it plays (outer rule, after the player's final write). Screenshots `tv/build/screenshots/1*.png`.
  `:tv:assembleRelease` (R8) builds.

### Decisions
- Audio track changes always go through the server (`audioIndex` → remux), even for direct-play MKV, so the
  behaviour is the same for every file and matches the web player.
- Only the first file of a movie/episode is played (no version picker yet).
- Image (PGS/VobSub) subtitles are not offered (they need `burnSubtitle`, a full transcode).
- Resume prompt only from episode cards (Ask); Resume buttons and Continue Watching resume silently.

### Known gaps (for TV milestone 3)
- Never run on a real device/emulator: decoding, passthrough detection, subtitle rendering and HLS behaviour on
  hardware are unverified (Robolectric has no decoders; the player UI and server flows were exercised).
- No MediaSession for the video player (no system "now playing" / Assistant transport controls).
- No multi-version picker, no image-subtitle burn-in, no trick-play thumbnails, no chapter markers.
- Home refreshes Continue Watching on return only when its data is older than 30 s.

## Mobile milestone 2 — player, audio service, audiobooks, readers, settings

### Video (`player/`)
- `VideoPlayerActivity` is a separate landscape activity (`sensorLandscape`, `supportsPictureInPicture`,
  handles its own config changes). Only one runs at a time: starting a video finishes the previous one. Starting
  it pauses music. PiP: auto-enter on Android 12+ while playing, `onUserLeaveHint` below that, aspect ratio from
  the video, and a Media3 `MediaSession` (id `antresol-video`) supplies the PiP and headset controls. Closing the
  PiP window finishes the player.
- `VideoPlayerViewModel` is a **copy** of the TV `PlayerViewModel` (same `/api/play` flow, caps from
  `DeviceCaps`, HLS keepalive and replace, VTT side-load and shift, recovery, background suspend). It adds
  absolute `seekTo` for the scrubber, the default quality setting and the autoplay setting (`UpNext.secondsLeft`
  is null when autoplay is off). I kept it in `:mobile` so I would not touch `:tv` while the TV chain is working.
  A later cleanup should move one copy into `:ui-common`.
- Controls (`VideoPlayerScreen`): a tap shows or hides them (they hide after 4 s while playing). A double tap
  on the left or right third skips 10 s; in the middle it pauses. There are skip ±10 s buttons, a scrubber,
  audio/subtitle/quality bottom sheets (the quality sheet explains direct/remux/transcode and the reason),
  Next Episode, a resume prompt (Ask mode from episode thumbnails), an Up Next panel and an error panel.
- Entry points: hero Play (video kinds resume), the detail page's Play/Resume ("Resume S1 E3" for a series),
  Play from Beginning, and episode thumbnails (Ask). Tapping episode text still opens the episode page.

### Music & audiobooks (`playback/`)
- `PlaybackService` (`MediaSessionService`, id `antresol-audio`, `foregroundServiceType=mediaPlayback`): one
  ExoPlayer that streams through the signed-in client's OkHttp with the Bearer header (no `mk` needed; the
  client is resolved for each stream). The default Media3 notification is used. Artwork comes through
  `CoilBitmapLoader`, because server images need the Bearer header. Tapping the notification opens Now Playing
  (`MainActivity.ACTION_SHOW_NOW_PLAYING`).
- Queue entries carry a `MediaRef` in `mediaId` (`track:<id>`, `book:<bookId>:<part>:<fileId>`) and extras
  (duration, star, part start, book length). `onAddMediaItems` rebuilds the stream URI from the id, because
  URIs do not cross the controller boundary.
- The service also sends one `POST /api/music/plays` per track after min(30 s, half) of real playing
  (`ScrobbleTracker`). Audiobooks get a `PlaybackReporter` (AUDIOBOOK timing) that saves the whole-book
  position every 10 s and on pause, with a final write when the book stops being current.
- `PlaybackConnection` (in the Application) wraps the `MediaController` and holds the Compose state. It binds
  on the first command. Commands: play tracks, shuffle, play or resume an audiobook from a book position,
  chapter seek, ±15/30 s across parts, speed (remembered for audiobooks), sleep timer (minutes, or end of
  track/chapter), star (shown at once, undone if the server refuses), queue jump. Playback stops when the
  signed-in profile or server changes.
- UI: `MiniPlayer` above the tab bar (`LocalBottomBarPadding` grows by `MiniPlayerSpace`). `NowPlayingScreen`
  is a full-screen overlay with an artwork tint. It has synced lyrics that follow the song and seek on tap
  (plain lyrics scroll), the queue, chapters, speed and sleep sheets, and a two-column layout on landscape
  tablets.
- Music page: a Songs link (paged `/api/music/songs` list with Play/Shuffle) and Shuffle All
  (`/api/music/shuffle`). Album and playlist pages have Play/Shuffle and tappable tracks; the track that is
  playing is shown in the accent colour. Artist pages: Play plays the top tracks, Shuffle plays everything by
  the artist. Audiobooks: Listen/Resume (resume rules from the spec) and tappable chapters.
- On Android 13+ the app asks for notification permission once, the first time something plays.

### Readers (`reader/`)
- Comics: `ComicReaderRoute(volumeId)` → `readFileId` → `/api/comics/{fileId}/pages`. A `HorizontalPager`
  shows the pages, reversed for `rtl`. Pinch or double tap zooms (around the fingers); paging is off while
  zoomed. Tapping an edge turns the page in that direction; tapping the middle shows the bar with a page
  slider (it runs in the reading direction) and Next Volume on the last page. The reader is immersive.
  Progress is saved as `page:<n>`/fraction 1.5 s after a page change and on close. A comic series page
  continues the volume in progress.
- PDF: `PdfReaderRoute(bookId)` downloads `/api/stream/{fileId}` (Bearer) into `cacheDir/books` once, then
  renders with `PdfRenderer` on demand in a vertical list, with pinch zoom. Progress uses the same `page:<n>`
  locator. **Decision**: the web app's PDF locator format is unknown, so I reused the comic format.
- **EPUB/FB2 are "coming soon"**: their book pages say "Reading EPUB books in the app is coming soon." (no
  reader).

### Settings (`ui/settings`, `settings/AppSettings`)
- Opened from the account sheet. It has the profile (switch, sign out), the server (address, version from
  `/api/health`, change), default video quality, autoplay next episode, language (System / English / Русский)
  and signed-in devices (`/api/me/devices`, sign out others after a confirmation), plus the app version.
- `AppSettings` uses SharedPreferences because the language must be known in `attachBaseContext`. The language
  applies to every activity and the service and sets `Accept-Language`. Changing it restarts `MainActivity`
  from scratch, so server text reloads in the new language.

### Tests
- `PlaybackLogicTest` (JVM): MediaRef encoding, chapter lookup, reader locators, Listen/Read/Unsupported
  decisions (audiobook resume tail, comic series continue, PDF only).
- `MobilePlaybackLiveUiTest` (Robolectric, live): the video player activity (controls and quality sheet,
  landscape), then album → track → mini-player → Now Playing (the star is flipped on the server and back,
  queue, lyrics, sleep timer), audiobook Resume → Now Playing chapters and speed, comic series → reader, and
  Settings (server version, this device). It binds the real `PlaybackService` by handing its binder to
  Robolectric. Progress it touches is restored afterwards. Screenshots: `mobile/build/screenshots/play-*.png`.
- Verified: `ANTRESOL_TEST_SERVER=1 ./gradlew :core:test :mobile:testDebugUnitTest assembleDebug` is green.

### Known gaps
- Never run on a real device or emulator. Robolectric cannot decode media, so actual audio/video output,
  PiP, the notification, lock-screen controls and Bluetooth are unverified on hardware.
- Transcoded audio (`X-Vault-Transcode`, e.g. ALAC/WAV sources) is not seekable; `&t=` reload seeking is not
  implemented. FLAC/MP3/AAC/Opus/Vorbis files are served as originals and seek normally.
- No custom notification buttons for audiobooks (the default prev/next skip parts there; ±15/30 s is in the app).
- No playback resumption after the process dies (`onPlaybackResumption` not implemented), and no offline
  downloads.
- No video MediaSession metadata beyond what Media3 derives; no trick-play thumbnails, no image subtitles, and
  only the first file version.
- EPUB/FB2 reader not built. PDF uses the `page:<n>` locator.
- ~~`VideoPlayerViewModel` duplicates the TV one (see above).~~ Shared since "Android reconcile + gaps".
- The TV chain added `core/playback/Chapters.kt`, `LyricsSync.kt` and `core/reading/ComicProgress.kt` at the
  same time as this milestone. `:mobile` still uses its own `PlaybackConnection.chapterAt` and
  `reader/ReaderProgress`, which do the same jobs. A cleanup should switch `:mobile` to the `:core` versions.

## TV milestone 3 — music, audiobooks, comics, settings, polish

### `:core` (shared)
- `playback/Chapters` (chapter at a book position, previous/next targets with a 3 s restart threshold,
  chapter end), `playback/LyricsSync` (`Lyrics.lineIndexAt`, `plainText`), `reading/ComicProgress`
  (`page:<n>` locator, fraction, start page). Unit tests `ChaptersTest`, `ComicProgressTest`.

### `:tv`
- `playback/AudioEngine` (app-scoped, created on first use by `AntresolTvApp.audio`): one ExoPlayer for
  music queues and audiobooks. Streams go through the signed-in client's OkHttp (Bearer header), not `mk`,
  so a long queue never outlives a media key. Compose snapshot state (content, index, position, duration,
  shuffle, repeat, speed, sleep timer, error). Music scrobbles once per track (`ScrobbleTracker`);
  audiobooks play `files` as one book, `PlaybackReporter` (AUDIOBOOK timing) writes the whole-book position
  every 10 s, on pause, at the end and when the book is replaced or stopped (the final position is frozen
  first). Sleep timer: 15/30/45/60 min or end of chapter/song. Speeds 0.75–3× (audiobooks only, kept for
  the session). Stops when the profile or server changes; a video start pauses it.
- `playback/TranscodeSeekingPlayer` (ForwardingPlayer): transcoded audio (no Range) seeks by replacing the
  item with `&t=<sec>` and adding that offset to every reported position/duration; resume positions of
  unseekable parts are applied once the stream is known to be unseekable. Everything (UI, session,
  reporter) uses it.
- `playback/PlaybackService` (`MediaSessionService`, `foregroundServiceType=mediaPlayback`) wraps the
  engine's player in a MediaSession (Coil-backed `BitmapLoader` for Bearer artwork, session activity opens
  Now Playing via `MainActivity.ACTION_NOW_PLAYING`; the activity is `singleTask`).
- UI: Music page (header links All Albums / Artists / Songs / Playlists, Shuffle All pill, genre chips →
  albums of the genre with a Shuffle pill, shelves), Books page (All Books / Audiobooks / Manga & Comics),
  generic `CardGridScreen` + `GridSource` (movies, shows, albums with 4 sorts, artists, playlists, books with
  3 sorts), `SongsScreen` (paged, Play / Shuffle all), Now Playing (`ui/nowplaying`: artwork-tinted
  background, transport row, lyrics or Up Next panel for music, chapters panel, speed and sleep side panels
  for audiobooks), now-playing chip with animated bars in the top nav. Detail pages: albums/playlists/
  artists get Play, Shuffle (artists shuffle on the server), Favorite (albums; the artist detail has no
  starred flag) and track rows; audiobooks Listen/Resume, Listen from Beginning, Want to Read, time left,
  chapter rows; comic volumes Continue · Page N / Read from Beginning; comic series open the first
  unfinished volume; e-books explain they are not readable on TV.
- `ui/comics`: reader with Left/Right (swapped for rtl), media/page keys, page counter overlay, prefetch of
  the next two pages (and the previous one) through Coil, progress saved 1.2 s after a turn and on close
  (app scope), end-of-volume prompt with Next Volume (replaces the reader route).
- `ui/settings` (replaces the Account page; avatar → Settings): Switch Profile, Sign Out, Video Quality
  (default for `/api/play`), Subtitles (language auto-selected at start, matching 2- and 3-letter codes,
  non-forced first), App Language (System / English / Русский), server address/version/library counts,
  Change Server, app version. `settings/TvSettings` is SharedPreferences (read synchronously in
  `attachBaseContext` of the app and the activity); a language change restarts the activity task so server
  text reloads in the new language.
- Shared TV components: `OptionsPanel`/`MenuOption` (the player's track menu now uses it), `RoundIconButton`,
  `LinkButton`, `TrackRow`, `TvIcons`. TV-only strings live in `tv/src/main/res/values{,-ru}` (not
  `:ui-common`), so the two chains do not edit the same resource file.
- **Fixed (from milestone 1)**: Down from the top nav never entered the page — the page's focus group spans
  the whole screen behind the nav, so geometric search found nothing below the pills. The nav now handles
  Down by focusing the destination's focus group (`page<T>()` in `MainShell`, each with its own
  `focusRestorer`) and entering it, which returns to the element focused there last. A single restorer
  around the whole NavHost crashed in Compose 1.7 ("Release should only be called once") after the
  destination changed, hence one per destination.
- Media3 unstable APIs are opted in (`@androidx.annotation.OptIn(UnstableApi::class)`), so
  `:tv:lintDebug` passes (warnings only); it also failed on milestone 2's player before.

### Tests
- JVM: `TvSettingsTest` (subtitle language matching), `ComicTargetTest` (Continue page, series volume).
- `TvLiveUiTest.musicAudiobookComicAndSettings` (live): Down from the nav into Home, Music page → album
  (track list) → Play → Now Playing (lyrics) → nav chip; Books → audiobook (Resume, chapters) → Now Playing
  (speed, sleep timer); comic volume → reader (page turn); Settings via the avatar → quality panel.
  It restores audiobook and comic progress afterwards (the server's "updated" order of Currently Reading
  still changes). Screenshots `tv/build/screenshots/2*.png`, `3*.png`.
- Verified locally: `ANTRESOL_TEST_SERVER=1 ./gradlew :core:test :tv:testDebugUnitTest` (all 3 live UI tests
  pass), `:mobile:testDebugUnitTest`, `assembleDebug`, `:tv:assembleRelease` (R8) and `:tv:lintDebug`.

### Known gaps
- Never run on a real device or emulator: audio output, the MediaSession on the TV's now-playing card,
  foreground-service behaviour, transcoded-audio seeking and comic image rendering are unverified on
  hardware (Robolectric decodes nothing; artwork often has not loaded when screenshots are taken).
- The video player still has no MediaSession.
- No playback resumption after the process dies; the speed is not persisted across launches.
- No queue editing, playlist editing or per-track favorite in lists (favorite is on Now Playing and albums).
- Comics show one page at a time (no two-page spreads, no zoom).
- E-books are not readable on TV (by design for this milestone).
- ~~Search results for songs still open the album instead of playing the song.~~ Fixed in the TV
  milestone 3 follow-up below.

## TV review fixes (after milestone 3)

Reviewer findings for `:core`, `:ui-common` and `:tv`, verified against the code and fixed:

### Core (affects the phone app too)
- `MediaKeyCache` is bound to the token: a different `client.token` refetches the key (another
  profile never streams with the previous one's key; a re-sign-in that revoked the old session gets a
  fresh key). `get(minRemaining)` lets callers ask for a lifetime: `VideoSession.open` asks for the
  rest of the video plus 1 h, so long films never outlive their key. `VideoApi.mediaKey(minValidMs)`.
- `AntresolClient` sends the token read when the request starts and reports 401s with that token
  (`onUnauthorized: (failedToken: String) -> Unit`), so a late 401 for profile A no longer signs out B.
- `AntresolClient.pinned()` and `playbackApi()` (now pinned to the token at creation): final progress
  writes and HLS stops after a profile switch still reach the server as the right profile.
- `SessionManager.beforeSignOut` (suspend hook) runs before the token is revoked; the TV app stops the
  audio engine there and waits for the audiobook's final write (`AudioEngine.stopAndFlush`).
  **Phone chain:** set it too if the phone plays audiobooks in a service.
- `StreamTimeline.needsNewSession(target, availablePlayerMs)`: growing EVENT playlists
  (`hlsComplete` false) reopen for seeks past the produced part.
- `CodecSupport.caps(..., maxHeight)`; `DeviceCaps` fills it with min(tallest display mode, tallest
  H.264/HEVC hardware decode), ignoring values below 720.
- `MediaUrls.normalizeBaseUrl` throws `InvalidServerAddressException`; the TV maps only that to
  "invalid server address" (decode errors used to show it). **Phone chain:** `mobile/.../LoadState.kt`
  still maps every `IllegalArgumentException`.
- `ui-common`: `<plurals name="song_count">` (EN/RU one/few/many). `detail_song_count` stays only
  because the phone uses it; switch it to the plural and delete the string.

### TV player
- Reopens are queued, never cancelled: `/api/play` runs in `NonCancellable`, and a session nobody
  wants any more (closed player, superseded request, finished item) is DELETEd. Failed reopens stop
  keepalives and the old session; `onSessionLost` only reopens while playing.
- Backgrounding detaches the HLS session synchronously (a quick return can no longer stop the new
  one); a stream opened while in background loads paused and is suspended at once.
- `recover()` stops keepalives before stopping the session; an HTTP 401 from the stream drops the
  media key. `begin()` releases a previous attempt's reporter/session. Subtitle files live in a
  per-player cache directory. Seeks work without a known duration (falls back to the player's).
- Seeking: Left/Right scrub while held (10 s, then 30 s, 60 s, 2 % of the video per repeat), one seek on
  release (or after 1.2 s without keys); number keys 0–9 jump to 0–90 %.
- Up Next has Cancel (and Back): stays on the last frame with the controls.

### TV screens
- `MainShell` owns a `ViewModelStore` cleared when it leaves composition (profile switch, sign-out,
  session expiry): the player, comic reader and other back-stack ViewModels are released then.
- The top nav shows on every page except Player, Comic Reader and Now Playing (no tab selected on
  detail/grid/songs pages, fading when scrolled); it hides while a Settings panel is open.
  `OptionsPanel` is opaque (`BackgroundElevated`) over a 50 % black scrim, with `focusIndex`.
- Sign Out and Change Server ask for confirmation (Cancel focused).
- Focus: Music/Books header links, the grid's Shuffle and sort pills remember focus; Home refreshes on
  every resume and moves focus to the neighbouring card when the focused one disappears; error views
  never take focus from the nav; a comic volume without pages focuses its rows and says why.
- Long press OK on a Continue Watching card opens its detail page (short press resumes).
- Songs: Play queues the whole library (`AudioEngine.appendTracks` adds the remaining pages).
- Shell shuffles show a glass notice on failure or an empty result. Grids page by server offset.
  Seasons keep their own row state. Detail toggles ignore presses while a request is in flight.
- Comic reader: the end-of-volume prompt gets the D-pad; OK retries a page that failed.
- Lyrics show "Loading lyrics…"; transcoded seeks keep the scrobble counter.
- The profile password field is no longer saved in the instance state.

### Verified
`ANTRESOL_TEST_SERVER=1 ./gradlew :core:test :tv:testDebugUnitTest assembleDebug` (live UI tests pass).

### Known gaps (from the review, not fixed)
- Device tokens are stored as plain JSON in DataStore (app backup is off). Keystore encryption needs
  real-device testing (Robolectric has no AndroidKeyStore), so it is left for a later milestone.
- The scrub preview shows the target time on the seek bar only (no thumbnails).

## TV milestone 3 follow-up (re-verification)

- Re-ran the milestone on a clean checkout of `origin/main`: music, audiobooks, comics, settings
  and polish were already complete (see the two sections above).
- Search: OK on a song now plays the found songs from that one and opens Now Playing; long press
  opens the song's album. Song cards use the track id, so several songs from one album all show
  (they used to be collapsed into one album card).
- Verified: `ANTRESOL_TEST_SERVER=1 ./gradlew :core:test :tv:testDebugUnitTest :tv:assembleDebug`
  (live UI tests against 127.0.0.1:8420 pass).
- Known gaps are unchanged from the sections above (never run on hardware; no video MediaSession;
  tokens not Keystore-encrypted; single-page comics; e-books not readable on TV).

## Mobile milestone 2 follow-up — review fixes ported to the phone

Applied the "Phone chain" items from the TV review fixes and the TV player hardening:

- **Sign-out flush**: `SessionManager.beforeSignOut` now calls `PlaybackConnection.stopAndFlush()`, which
  stops the audio service, waits (≤ 3 s) for the queue to empty and then for the service's final audiobook
  progress write (`FinalProgressWrites`) before the token is revoked. Profile switches keep using `stop()`
  (the reporter's API is pinned to the old token, which stays valid).
- `userMessage` maps only `InvalidServerAddressException` to "invalid server address" (decode errors and
  other `IllegalArgumentException`s now show the generic error).
- Album/playlist song counts use the `song_count` plural (EN one/other, RU one/few/many);
  `detail_song_count` was removed from `:ui-common`.
- `:mobile` now uses the `:core` helpers: `Chapters` (current chapter, previous with the 3 s restart
  threshold, next), `Lyrics.lineIndexAt` / `plainText`, and `ComicProgress` for comic and PDF progress.
  The phone's own `ReaderProgress` and `PlaybackConnection.chapterAt` are gone (their tests live in
  `:core` as `ChaptersTest` / `ComicProgressTest`).
- `VideoPlayerViewModel` got the TV player's session hardening: `/api/play` runs in `NonCancellable` and
  unwanted sessions are DELETEd; reopens are queued and merged instead of cancelled; backgrounding detaches
  the HLS session synchronously and a stream opened in the background is loaded paused and suspended;
  `recover()` stops keepalives before stopping the broken session; an HTTP 401 from the stream drops the
  media key; a retry releases the previous attempt's reporter/session; subtitle files live in a
  per-player cache directory; seeks use `needsNewSession(target, availablePlayerMs)` (growing EVENT
  playlists) and work without a known duration.
- **Transcoded audio seeks** on the phone too: `playback/TranscodeSeekingPlayer` (copied from `:tv`) wraps
  the service's ExoPlayer; the MediaSession and the progress/scrobble code use it. A seek in a stream
  without Range support (`X-Vault-Transcode`) reloads the entry with `&t=<sec>` and adds the offset to
  every reported position. It also applies a `setMediaItems` start position (audiobook resume) once the
  stream turns out to be unseekable, so the "transcoded audio is not seekable" gap above is closed.

### Still open
- ~~`VideoPlayerViewModel` and `TranscodeSeekingPlayer` remain copies of the TV ones.~~ Both live in
  `:ui-common` now (see "Android reconcile + gaps").
- The other "Mobile milestone 2 → Known gaps" above still apply (no device testing, no
  playback resumption, no EPUB/FB2 reader).
- Verified: `ANTRESOL_TEST_SERVER=1 ./gradlew --no-daemon :core:test :mobile:testDebugUnitTest assembleDebug`
  passes with these changes (local build is authoritative).

## TV review fixes, round 2

### Repo housekeeping
- This clone held an unpushed phone commit ("Harden phone video sessions and audio playback
  shutdown") that conflicted with the phone chain's pushed `35c66fb`, plus stray uncommitted
  `mobile/` edits. Both are kept locally only: branch `stray/phone-hardening-unpushed` and
  `git stash` "stray uncommitted mobile edits found in TV clone". Their shared parts were taken over:
  `TranscodeSeekingPlayer` now lives in `ui-common` (app.antresol.ui.playback), `DeviceCaps.current`
  takes `limitToDisplay`, `Episodes.toPlay` picks the first unfinished episode when the server sends no
  `next`. **Phone chain:** `mobile/.../playback/TranscodeSeekingPlayer.kt` is a copy of the old one
  (without the duplicate-track fix below); switch to the ui-common class and delete the copy.

### Core / ui-common (affects the phone app too)
- `VideoSession.open`: a failed media key stops the session `/api/play` just created; `session` is set
  as soon as `/api/play` answers. `VideoSession.caps` is mutable (lowered after decoder failures).
- `CodecSupport.withoutFailingVideo/withoutFailingAudio`: H.264 ≤ 1080p, or decoded audio only.
- `AntresolClient` sends the Bearer token only to the server's origin (a foreign subtitle URL gets none).
- `AudiobookTimeline.isComplete`; unknown part lengths start at part 0 (a single part uses the book
  length). The TV writes no progress for such a book.
- `PendingWrites`: final progress writes of closed players; Home and detail refresh after them.
- `DeviceCaps`: decoder height from `isSizeSupported` (2160/1080/720 landscape), cached including
  "unknown"; Dolby passthrough from media3 `AudioCapabilities` (what the audio sink will bitstream).
  It enumerates codecs: call it off the main thread (the phone still calls it on Main).
- `Badge`: every badge is the dark outlined chip (quality first). `LogoTile`: the mark on the brand tile.
- `error_retry_after` string.

### TV
- Player: one client pinned to the starting profile for sessions, keepalives, progress, media key and
  subtitles. Keepalive 404s only count for the session still held; the replaced session gets no
  keepalives during a reopen. HLS stops are chained and every `/api/play` waits for them (no transient
  third session → no false 429). Background suspends the stream during Up Next (countdown restarts on
  return), errors and in-flight reopens. Giving up after errors stops the session. Decoder / audio
  output errors reopen with smaller caps before counting as a failure. Direct play selects the audio
  track the server reports. `playNext` only from Playing/Up Next, once. Resume falls back to the
  item's watched fraction when `/api/progress` fails. `/api/play` 429 → `StreamLimitException`.
- Shell: per-profile ViewModel stores live in an Activity-retained `ShellStores` (survive recreation);
  `configChanges` adds density, fontScale, layoutDirection, uiMode. NavHost fades are 160 ms; the nav
  enters only the current page (a focus requester per back stack entry). On detail/grid pages the tab
  they came from keeps a grey pill and Up lands on it. No backup or device transfer
  (`data_extraction_rules.xml`).
- Focus: OptionsPanel scrolls to the selected row and falls back to its first row; Settings, Now
  Playing and player menus give focus back to the control that opened them; Up Next keeps focus in
  the panel; detail pages without Play focus their first action or row; Music/Books fall back to the
  first genre or header link.
- Design: grid header pills flush right; logo on the brand tile; Search has the page title, search
  icon, scope pills (All/Movies/TV Shows/Music/Books, filtered on the device) and Browse tiles;
  shelves with a full list show the title chevron and end with a "See All" tile (decision: a tile
  instead of a focusable title, so Up/Down between shelves stays one press). Hero Left on Play /
  Right on Details steps through the hero items. Long press OK on an episode opens its page (hint
  line under the seasons). Comic pages show a spinner while loading.

### Verified
`ANTRESOL_TEST_SERVER=1 ./gradlew :core:test :tv:testDebugUnitTest assembleDebug` green (live UI test
updated for the scope pills on Search).

### Known gaps
- ~~Device tokens are still plain JSON in DataStore.~~ Sealed with an AndroidKeyStore key since
  "Android reconcile + gaps" (still to be verified on a real device).
- A "See All" tile is not a focusable shelf title as on the web; Search scope is client-side.

## Phone review fixes

### Fixed
- Video player (ported from TV and extended): one client pinned to the starting profile; the player
  closes on a profile switch or sign-out. Decoder or audio-output errors reopen with
  `CodecSupport.withoutFailingVideo/Audio`. The recovery budget refills only after 30 s of healthy
  playback. HLS stops are chained, and `/api/play` waits for them (no false 429). `StreamLimitException` is raised for
  `/api/play` 429s only. Caps come from `DeviceCaps.current(limitToDisplay = false)` off the main thread.
  Every pause saves progress. `playNext` runs once, from Playing or Up Next only. The final write starts in `onPause` when the activity
  is finishing and is tracked in `app.pendingWrites`. Media sessions get unique ids and are released before
  the player. PlayerView is detached on release. `configChanges` matches TV. The scrubber keeps the controls
  visible while dragging.
- Pending writes: `PendingWrites.completions`. `LoadViewModel(pendingWrites = …)` (Home, video/media detail)
  waits for writes before a refresh and refreshes after each finished write. Reader final saves are tracked too.
- Audio: a `FLUSH_AND_STOP` custom session command finishes the last audiobook write before sign-out.
  The controller reconnects after the service dies, pending commands are dropped when a connect fails, and the controller
  is released on stop. Positions are polled only while playing and while a screen is started (`PositionUpdates`). The service ticks
  only while playing. Books with unknown part lengths start at 0 and write no progress (`REPORTS_PROGRESS`).
  The repeat-one sleep timer and scrobbles work. The mobile `TranscodeSeekingPlayer` copy was deleted; the
  ui-common one now tracks the offset entry by index.
- Shell: per-profile ViewModel stores (`ShellStores`, same as TV). Sign Out, Switch Profile and Change Server run in
  `appScope`; Sign Out and Change Server ask for confirmation first. Readers use pinned clients.
- PDF: the cache is per server (hash of the base URL) and per file size. Downloads go through `AntresolClient.download`
  (401 handling, localized errors) into a unique temp file. A corrupt copy is fetched again once. `PdfDocument.closeSafely`
  closes under the render lock.
- UI: paging errors show Try Again plus a loading footer (grids, Songs). Songs Play/Shuffle queue the whole
  library (`appendTracks`). Shuffle All and artist Shuffle show a busy state and notices. Artist Play is hidden
  without top tracks. Restart buttons appear for audiobooks, comics and PDFs (`fromStart` routes). There is an album favorite.
  Continue Watching resumes (long press opens the page). Tapping a song in Search plays it (long press opens the album).
  Search focuses its field and hides the keyboard on scroll. The header uses LogoTile and the avatar has a content description.
  Custom clickables show a focus ring. The Now Playing panel falls back when it no longer applies, and a lyrics error offers retry. Comic pages
  offer retry, and Next Volume shows on reaching the end. Episodes and seasons link to their series. Cast keys are unique.
  Settings shows device errors with retry, and a failed revoke shows a notice. 429 at sign-in reads "Too many attempts". Toggling
  Watched resets the resume position. The password is not saveable. `data_extraction_rules.xml` is added. `mov` is a container.
  The hero artwork fades smoothly.

### Rejected / not changed
- Finding "LoadViewModel refresh race" and "onCleared final write" were the same issue: fixed once (see above).

### Known gaps
- ~~Tokens are still plain JSON in DataStore.~~ ~~Songs queue stops on a failed page.~~ ~~One-off rows use plain
  `clickable`.~~ All three fixed in "Android reconcile + gaps".

### Verified
`ANTRESOL_TEST_SERVER=1 ./gradlew :core:test :mobile:testDebugUnitTest assembleDebug` green (live UI tests
updated for the avatar description "Account: Admin").

## Android reconcile + gaps

### Reconciled lost work (`backup/phone-hardening-unpushed` 8cf6d96, `backup/phone-stash` d1fcbd7)
- Compared both with main. 8cf6d96 (phone video/audio hardening) was redone, more thoroughly, in 35c66fb,
  5937f80, 30190d5 and 5f281b5: the video session fixes, `STOP_AND_FLUSH`/`FLUSH_AND_STOP`, controller
  reconnect, position polling only while watched, `Episodes.toPlay`, `DeviceCaps.limitToDisplay`, the
  shared `TranscodeSeekingPlayer`, closing the video player on a profile switch and the scrubber keeping the
  controls are all on main. **Ported** the one fix main lacked: the audiobook reporter keeps its own last
  position (`LastPosition`), so a final write after the queue moved to another book no longer uses the new
  book's position.
- The stash (working tree on top of 8cf6d96 plus two untracked files) held phone UI work that main never got.
  **Ported**: pull to refresh (`RefreshBox`) on Home, Music, Books and every grid; `PagedViewModel` (grids and
  Songs share paging, Try Again, refresh); Shuffle All above the Music links so it works while the shelves
  failed. **Not ported** (main has newer equivalents): the 429 `streaming` flag (main uses
  `StreamLimitException`), `NothingToPlayException`/`LocalNotices` (main shows notices per page), the stash's
  own Songs queue (replaced, see below).
- The branches and the local `stray/phone-hardening-unpushed` / stash are kept (not deleted).

### Shared code (`:core` / `:ui-common`)
- `ui-common/playback/VideoPlayerViewModel` is the one video player for both apps (sessions, progress,
  tracks, subtitles, recovery, Up Next, background suspend, `close()`), with `VideoPlayerOptions`
  (default quality, subtitle language, `limitToDisplay`, autoplay). `ResumeMode`, `PlayerPhase`,
  `PlaybackUnavailableException`, `StreamLimitException` moved there too. The TV `PlayerViewModel` is a
  subclass that adds remote scrubbing and number keys (`preview`/`targetBaseMs`/`canSeek` hooks); the phone uses
  the base class directly. Behaviour is the union of both copies: every pause saves, the recovery budget
  refills after 30 s of healthy playback, direct play follows the server's audio track, a 401 on the stream
  drops the media key and re-checks the token, Up Next pauses its countdown in the background (and has no
  countdown when autoplay is off).
- `core/playback/SubtitleLanguages` (was `TvSettings.sameLanguage`) picks the auto-enabled subtitle.
- Already done before this task and verified: one `TranscodeSeekingPlayer` (with the duplicate-track fix),
  `PendingWrites` on the phone (no `FinalProgressWrites`), `DeviceCaps` off the main thread, `LogoTile`.
- `core/music/PlaylistEdits`: PATCH bodies (rename, append, remove, move → `order` permutation) and the same
  edits applied locally. Tests `PlaylistEditsTest`, `SubtitleLanguagesTest`.

### Token encryption
- `ui-common/data/TokenCipher` + `KeystoreTokenCipher` (AES-256-GCM, non-exportable AndroidKeyStore key
  `antresol_session`, IV prepended, Base64). `DataStoreSessionStore(store, cipher)` stores the session state as
  `sealed1:<…>`. Plain JSON from older versions is sealed on the first load. If sealing fails the state is stored
  as plain JSON and a warning is logged (sign-in keeps working). A sealed state that cannot be opened (key lost)
  reads as signed out. `DataStoreSessionStoreTest` (Robolectric, fake cipher). CI now also runs
  `:ui-common:testDebugUnitTest` (the authoritative check command does not include it; run it too).
  Robolectric has no AndroidKeyStore, so the live UI tests exercise the plain fallback.

### Phone
- Songs: Play/Shuffle queue the whole list through `PagedViewModel.followToEnd`: failed pages are retried
  (2 s, 5 s, 15 s), then wait for Try Again; pages loaded later (retry or scrolling) still join the queue. The
  queue follows while the Songs page is open (leaving the page stops appending). `PagedViewModelTest`.
- Focus rings: every remaining plain `clickable` (settings, chapter, track, episode, account and sheet rows,
  genre chips, sort pills, section titles, Now Playing toggles, mini-player) uses `focusClickable`.
  **Note:** the task listed this for the TV, but the TV rows were already tv-material `Surface`s with the
  shared focus treatment (white fill); the plain-`clickable` gap was the phone's.
- Playlists: Playlists page "New Playlist"; "Add to Playlist" sheet (album button, or long press on a song in
  album/artist/playlist pages and Songs) with a new-playlist option; playlist page Edit (move up/down, remove),
  Rename, Delete (confirmation sheet). Edits are optimistic, one at a time, rolled back on failure.
  Decision: name entry and the delete confirmation are bottom sheets, not `AlertDialog`s — opening an
  `AlertDialog` from a click inside the signed-in shell never got idle under Robolectric (a standalone dialog
  did), so it could not be tested; sheets match the app's other sheets anyway.
- Live test `MobileLiveUiTest.phonePlaylists` (create, add album, move, remove, rename, delete; cleans up).

### TV
- Settings: Signed-in Devices section (last): this device, then others; OK asks to sign one out.
- Playlists: album pages "Add to Playlist" (panel with New Playlist → name panel with `TvTextField`), playlist
  pages "Delete Playlist" with confirmation (also for empty playlists). The nav hides while a panel is open
  (`MainShell.panelOpen`, shared with Settings). Live test `TvLiveUiTest.playlistsCreateAddDelete`.
- **Observed, not fixed:** under Robolectric, focus does not move into an `OptionsPanel` (the existing Settings
  panels behave the same: focus stays on the opener behind the scrim), and tv `Surface`s take no touch input,
  so the live tests press panel rows through their semantics click action. Verify panel focus on a device.

### Verified
`ANTRESOL_TEST_SERVER=1 ./gradlew --no-daemon :core:test :tv:testDebugUnitTest :mobile:testDebugUnitTest assembleDebug`
green, plus `:ui-common:testDebugUnitTest`.

### Known gaps
- Never run on a device: Keystore sealing, panel focus on TV, PiP, audio output remain unverified on hardware.
- Phone has no subtitle-language setting (the shared player supports it; only the TV sets it).
- TV playlists: no rename, remove or reorder (phone only).
- Test runs leave many "robolectric" device sessions on the test server (pre-existing; seen in the new
  devices list).

## Release engineering + screenshots

### Release builds
- Root `build.gradle.kts` configures both apps (`plugins.withId("com.android.application")`): `versionName` from
  `antresolVersionName` in `gradle.properties` (1.0.0), `versionCode` = `GITHUB_RUN_NUMBER` or 1, release = R8 +
  resource shrinking + `proguard-android-optimize.txt`, signing from `ANTRESOL_KEYSTORE_FILE` / `_PASSWORD` /
  `ANTRESOL_KEY_ALIAS` / `ANTRESOL_KEY_PASSWORD` (env or Gradle property), unsigned when absent. The modules no
  longer set versions or release build types themselves. Details and secret names: `docs/RELEASE.md`.
- Keep rules: nothing new was needed. `ui-common/consumer-rules.pro` keeps `$$serializer`/`serializer()` for
  `app.antresol.**`; kotlinx.serialization, Media3, OkHttp, Coil ship their own rules. No reflection or
  `getIdentifier` in the code base (checked), so resource shrinking is safe.
- **R8 proof**: `verifyReleaseSerialization` (in the root script) runs before every `assembleRelease`/`bundleRelease`.
  It reads `mapping.txt` + `usage.txt` (which together list every class R8 saw) and fails unless each serializable
  app class kept its `$$serializer` unrenamed with all members and a surviving class kept `serializer()` unrenamed.
  Current result: phone 71/71, TV 72/72 serializable classes, all kept. A doctored mapping (renamed serializer)
  makes it fail, so the check is live.
- Decision: no Robolectric run of the release variant. Unit tests (also `testReleaseUnitTest`) run the unminified
  classes, so they cannot show R8 damage; the mapping check is the honest proof available without a device.
  A real-device smoke test of the release APK remains to be done.
- Verified: `:tv:assembleRelease :mobile:assembleRelease :tv:bundleRelease :mobile:bundleRelease` (APKs ≈ 3.4–3.7 MB,
  AABs ≈ 7 MB), and signing with a throwaway keystore (`apksigner verify` OK, versionCode 42 from
  `GITHUB_RUN_NUMBER=42`).

### CI
- Push/PR: unit tests + debug builds (unchanged). Push to main and tags: release APKs + AABs, uploaded with the
  debug APKs and R8 mappings as workflow artifacts `debug-apks` and `release`. Tags `v*`: job `github-release`
  (`contents: write`) runs `gh release create` with `GITHUB_TOKEN`; notes = both apps' en-US changelog.
  Signing secrets: `ANTRESOL_KEYSTORE_BASE64`, `ANTRESOL_KEYSTORE_PASSWORD`, `ANTRESOL_KEY_ALIAS`,
  `ANTRESOL_KEY_PASSWORD`; without them files are named `*-unsigned.*`. Not run on GitHub from here (no way to
  trigger it); YAML parsed locally.

### Store metadata
- Decision: two apps need two listings, so the fastlane folders live per module:
  `{mobile,tv}/fastlane/metadata/android/{en-US,ru-RU}/` (title, short/full description, `changelogs/1.txt`), within
  Play's length limits. Copy follows the server README (own server required, no content, no data collected) and only
  claims what the Android apps do (no offline downloads, no EPUB reader on Android).
- `docs/PLAY_STORE.md`: data safety answers (no data collected or shared, traffic only to the user's server),
  permissions, TV review notes (leanback banner present, 1280×720 banner and TV screenshots still needed, reviewers
  need a demo server), graphics to make.

### Screenshot gallery
- `docs/screenshots/{tv,mobile/phone,mobile/tablet}` (59 PNGs) + `docs/screenshots/README.md`, produced by
  `scripts/update-screenshots.sh` (runs the live UI tests, then copies a fixed selection under stable names;
  `--copy-only` reuses the last run).
- Tests changed: phone qualifiers are now `w360dp-h800dp-xxhdpi` (1080×2400; the player `w800dp-h360dp`). New shots:
  clean server screens (TV + phone). The tablet test became `tabletOnboardingBrowseAndSearch` (profiles, home,
  movies, movie and series detail, music, albums, books, search) and `MobilePlaybackLiveUiTest` gained
  `tabletVideoPlayer` and `tabletNowPlayingAudiobookAndSettings` (album, two-column Now Playing, audiobook,
  settings); shared steps are helpers (`videoPlayer`, `playFirstTrackOfTestAlbum`, `openAudiobook`, `openSettings`).
- Fix: Media3 keeps session ids in a static map, so a second test creating `PlaybackService` failed with "Session ID
  must be unique"; the outer rule now destroys the service after each test.
- Fix (test isolation): with `org.gradle.parallel` the TV and phone live tests ran at the same time as the same
  profile on the same items, so the phone's progress snapshot captured the TV player's position and "restored" it
  (Big Buck Demo kept 881 ms of progress; the TV test then saw Resume instead of Play). `:mobile` test tasks now
  `mustRunAfter(":tv:testDebugUnitTest")`; the phone playback test re-restores until a late final write no longer
  changes the progress; the TV music test accepts the hero's Play or Resume. The stray progress on the server was
  reset (movie 19 unwatched, as the tests expect).
- Observed, not fixed: comic pages never decode under Robolectric (TV shows "This page couldn't be loaded", the
  phone a black page), so readers are left out of the gallery; verify on a device. The test library has almost no
  posters, so most cards show the gradient placeholder. On a landscape tablet the Settings column is capped at
  720 dp and left-aligned (by design of the existing screen, looks sparse).

### Verified
`ANTRESOL_TEST_SERVER=1 ./gradlew --no-daemon :core:test :tv:testDebugUnitTest :mobile:testDebugUnitTest assembleDebug`
green (11 live UI tests), `scripts/update-screenshots.sh` green, plus the release/bundle builds above.

### Known gaps
- The release APKs were never installed on a device or emulator; GitHub Actions release/tag jobs never ran.
- Play listing graphics (icon 512, feature graphic, TV banner 1280×720, real-device screenshots) are not made.

## Design fidelity pass (screenshots vs docs/design-ref)

Every gallery shot was compared with the web/iOS reference shots and DESIGN.md; fixes below, gallery re-rendered.

### Shared (`:ui-common`)
- `ArtworkImage`: the title-on-gradient placeholder now sits *under* the image and stays until it loads. Before,
  it was removed on success and Coil's crossfade faded in from the empty dark box, so posters flashed (and many
  gallery shots showed) plain dark tiles. Boxes narrower than 96 dp (queue/track thumbnails, mini player) show
  the title's initial instead of an ellipsised title ("S…", "…").
- `BadgeRow` wraps (FlowRow) instead of cutting badges in narrow cards ("Audio EN RU" became "Audio").

### Phone
- Grids: minimum column 100 dp, so posters are three across on a 360 dp phone as in the reference (104 dp gave
  two huge columns). Shelf cards 140 dp (poster/square) and 124 dp (circle), matching the reference proportion.
- Album page: Add to Playlist and the star moved to the top bar opposite Back (`BackBar`/`DetailHeader`
  `actions`), so Play and Shuffle keep full labels ("Pl…", "Sh…" before). `PlayShuffleRow` lost its now unused
  `trailing` slot.
- Book actions and playlist edit actions use FlowRow: they wrap rather than truncate ("Want t…", "Delete P…"),
  which also covers the longer Russian labels.
- `SegmentedPills` scrolls as a whole from the page gutter to the screen edge (new `gutter` parameter) instead
  of clipping inside its own capsule (Search's "Books" was cut). Pill padding 14 dp.
- Artwork placeholders of Now Playing, the mini player and the queue are seeded by the album title, so they match
  the album's card instead of each song getting another colour.
- Now Playing: Speed and Sleep toggles are plain like the panel toggles until active (they had a stray dark disc).
- A bottom fade behind the mini player and tab bar, so content no longer shows through the bar edges.

### TV
- Search: title, field and scope pills start on the page gutter, aligned with the result rows (the centred
  column did not line up with anything).
- Detail pages: no uniform 30 dp LazyColumn spacing; sections keep 30 dp above them and song/chapter rows sit
  close (tracks were ~65 dp apart).
- Settings no longer dims the tab it was opened from in the top bar (it belongs to the avatar).
- Now-playing chip: the equalizer bars rest at uneven heights and start desynchronised; flat bars read as "…".

### Tests
- `MobilePlaybackLiveUiTest` waits for the Music page's shelves before its shot (it captured the spinner).

### Known gaps (observed, not fixed)
- Robolectric does not move focus into the TV side panels (see the TV live test comment), so the Settings
  quality and Add to Playlist shots show no focused row; on a device the first/selected row takes focus.
- The faint rectangle behind the focused profile circle on TV is how Robolectric draws the tv-material glow; not
  checked on a device.
- Hero/now-playing artwork in some shots is still mid-crossfade (Robolectric clock), showing the placeholder.
