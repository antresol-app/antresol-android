# Hand-off notes

Running log between milestones. Newest entries at the bottom.

## Milestone 1 — Gradle scaffold + `:core` API client

### Layout
- `settings.gradle.kts`: `:core`, `:ui-common`, `:tv`, `:mobile`. Versions in `gradle/libs.versions.toml`
  (AGP 8.7.3, Kotlin 2.1.0 + compose plugin, Compose BOM 2024.12.01, tv-material 1.0.0, media3 1.5.1,
  OkHttp 4.12, kotlinx-serialization 1.7.3, coroutines 1.9.0, Coil 2.7.0, navigation 2.8.5, lifecycle 2.8.7,
  DataStore 1.1.1). Wrapper: Gradle 8.14.3. Java/Kotlin target 17 (no `jvmToolchain`, the container has JDK 21 only).
- minSdk 26 for all Android modules (adaptive icons only, no legacy PNG launcher icons), compile/target 35
  (36 since "Google Play preparation").
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

### `:tv` (namespace `app.antresol.tv`; since 2026-11-14 the Play package is `app.antresol.mobile`, see Store metadata)
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
- 2026-11-14, supersedes the next item: the owner wanted one Play app, as on iOS/tvOS. The TV build now has
  `applicationId = app.antresol.mobile`, version code + 1 000 000, `leanback` required (so Play never offers the
  TV bundle to phones) and goes to the Android TV form-factor track of the phone app. One listing:
  `mobile/fastlane` holds the TV banner and TV screenshots too; `tv/fastlane` is gone. The old Play app
  `app.antresol.tv` (closed test since 2026-11-10) is no longer fed.
- Decision (superseded): two apps need two listings, so the fastlane folders live per module:
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
- Pill buttons use 14 dp side padding (was 18), so "Перемешать" fits a half-width pill on a 360 dp phone.

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

## Play listing graphics

- `scripts/render-store-graphics.js` (Playwright/Chromium, Inter font) renders into
  `{mobile,tv}/fastlane/metadata/android/{en-US,ru-RU}/images/`: `icon.png` 512×512 (full-bleed `app-icon.svg`,
  re-encoded as RGBA because Chromium writes opaque PNGs as 24-bit and Play wants 32-bit), `featureGraphic.png`
  1024×500 and, for TV, `tvBanner.png` 1280×720 (gradient + mark + wordmark + localized tagline).
- Screenshots: phone shots are framed 1080×1920 with localized captions (raw 1080×2400 exceeds Play's 2:1 aspect
  limit); tablet (`tenInchScreenshots/`) and TV (`tvScreenshots/`) gallery shots are copied unchanged. Selections
  and captions are lists at the top of the script. Details and caveats in `docs/PLAY_STORE.md` → Graphics.

## Google Play preparation (API 36, real screenshots, version 2)

- **API 36**: `compileSdk`/`targetSdk` 36 in `:tv`, `:mobile` (`:ui-common` compiles against 36). AGP stays 8.7.3:
  it links, dexes and runs R8 against android-36 without problems, so `android.suppressUnsupportedCompileSdk=36`
  silences its "untested API level" warning instead of an AGP/Kotlin bump. Robolectric tests keep their pinned
  `@Config(sdk = 34/35)`.
- API 36 behaviour checked on emulators (phone, tablet, TV): edge to edge was already on (`enableEdgeToEdge`,
  insets per screen) and is correct; the one visible problem was scrolled titles colliding with the transparent
  status bar, fixed with a short status-bar gradient in `MainShell` (not on readers/player). Back handling is all
  `BackHandler` (predictive back works); the player's `sensorLandscape` is ignored on large screens from API 36,
  which is fine (the tablet is landscape anyway, and the player handles any orientation).
- **Version**: local builds take `versionCode` from `antresolVersionCode` (2) in `gradle.properties`; CI still uses
  the run number. `changelogs/2.txt` (copy of 1.txt) in all four locale folders.
- **Store screenshots**: emulator captures (API 36 Google APIs / Android TV images, Pixel 8, Pixel Tablet, TV 1080p)
  of the debug builds signed in to a demo server with open-licensed content, demo-mode status bar; 8 per form factor
  in `docs/store-screenshots/`. `scripts/render-store-graphics.js` turns them into posters with CSS device frames
  (`--screenshots` for posters only). Details in `docs/PLAY_STORE.md` → Graphics. The Robolectric gallery in
  `docs/screenshots` is unchanged and no longer feeds the store.
- Observed, not fixed: the profile picker shows the server as `host:443` for an https address (explicit default port).
- Release AABs for both apps signed with the upload key and verified (`jarsigner -verify`, bundletool: versionCode 2,
  targetSdk 36). The release builds were still not installed on a device.

## Live TV channels («ТВ»)

### `:core`
- `model/Channels.kt`: `Channel`, `ChannelProgram`, `ChannelList`, `ChannelGuide` (editor fields such as
  `rules` are ignored). `AntresolClient.channels()`, `channelGuide(id, hours)`, `openLiveChannel(id)` (fetches a
  media key with ≥ 6 h left, GETs the live playlist so the server starts the encoder and reports 503/403/404 as
  `ApiException`, returns the signed URL). `MediaUrls.liveChannel(id, mk)`.
- `channels/ChannelLogic.kt`: `Channels.sections` (web's `byCategory`), `neighbour` (zap by number, wrapping),
  `progress`, `onAir` (guide → now/next), `showing` (a card moves on to `next` when `now` ended between
  refreshes), `programLine`; `LiveStartPolicy` (503 with `Retry-After` ≤ 5 s = "starting", retried up to 15
  times; everything else shows the server's message).
- `channels/LiveTvSession.kt`: the player's logic without ExoPlayer (`LiveApi`, state flow of channels, current
  channel, guide and `LiveStream` Starting/Ready/Failed; zap, retry, refresh every 30 s, reopen after a player
  error with the same retry budget). Tests: `ChannelLogicTest`, `LiveTvSessionTest`, sample decoding
  (`channels.json`, `channel-guide.json`), client request shapes.

### `:ui-common`
- `playback/LivePlayerViewModel`: ExoPlayer (OkHttp data source, HLS by MIME type) driven by `LiveTvSession`;
  behind-live-window → back to the live edge; a 401 drops the media key; background stops the stream and
  reopens it at the live edge on return. No progress, no HLS keepalive (the live stream has no session).
- `channels/ChannelsViewModel` (shared by both apps; sections, picked section, `onAir`, 30 s refresh while a page
  is started, pull to refresh, keeps data on a failed refresh), `ChannelLogo`/`ChannelLogoBadge` (gradient tile),
  `rememberNowMs`, `programClock`. EN/RU strings `nav_tv`, `tv_*`. `AntresolIcons.ChevronUp/Down` (moved from
  `MobileIcons`). Test `ChannelsViewModelTest`.

### Apps
- TV: `NavTab.Tv` («ТВ») between TV Shows and Music; `ChannelsRoute` → `ui/channels/ChannelsScreen` (title +
  section pills, rows or a 3-column section grid of `ChannelCard`s built on `CardSurface`), `LiveRoute` →
  `ui/player/LivePlayerScreen` (full screen, nav hidden). Up/Down/Channel±/Page± zap; other keys show the
  overlay; the error panel's Try Again takes focus. Audio pauses before live TV.
- Phone: decision as on the web — no sixth tab; `OnAirShelf` on Home after the first shelf, its title opens
  `ChannelsRoute` (pushed page). `player/LivePlayerActivity` (landscape, closes an open video player, pauses
  music, closes on profile switch) + `LivePlayerScreen` (tap = overlay, buttons and vertical swipe zap).

### Verified
- `./gradlew :core:test :ui-common:testDebugUnitTest :tv:testDebugUnitTest :mobile:testDebugUnitTest assembleDebug`.
- On emulators (API 36 Google APIs image; the TV app on a 1920×1080 / 320 dpi D-pad AVD, as no Android TV
  image was installed) against a test server: tab, sections, live playback (frames advance), zapping with
  D-pad and Channel+, wrap from 1 to the last channel, the server's "too many channels" refusal with Try
  Again, Back restoring focus; phone Home shelf, TV page, section, live playback, button and swipe zapping.

### Known gaps
- Not run on a real Android TV image/device (leanback launcher, real remote CH± keys); no Robolectric live UI
  test for the channels screens yet.
- A server keeps a channel's encoder running for a while after the viewer leaves, and allows 3 at once, so
  quick zapping through many channels shows "too many channels" (server behaviour, same on the web).
- The live player has no MediaSession (headset buttons / system now-playing) and no picture-in-picture
  (done in the next round, below).

## Live TV, second round (continue, zap without black, mini-guide, PiP)

### `:core`
- `channels/LastChannel.kt`: `LastChannels` (per server + profile, a `StateFlow` per key) over a
  `LastChannelStorage`; `InMemoryLastChannelStorage` for tests. `Channels.continueWith` (the last
  channel while it is still listed), `Channels.miniGuide` (the current channel's section by number,
  `startIndex` = the channel on; unknown channel → every channel from 0). `LiveTvSession.tune(id)` (the
  guide), `LiveTvState.onAir(nowMs)` (guide first, else the list's now/next), `liveMiniGuide` (the
  channel on screen shows its guide's now/next, the list is up to 30 s older). Tests in
  `LastChannelsTest`, `ChannelLogicTest`, `LiveTvSessionTest`.

### `:ui-common`
- `PrefsLastChannelStorage` (SharedPreferences `last_channels`), `lastChannels(context)`; both apps keep
  one `LastChannels` in their Application. `ChannelsViewModel(lastChannel = …)` exposes `continueChannel`.
- `LivePlayerViewModel(…, lastChannel)`: remembers the channel on its first rendered frame; `tune`,
  `miniGuide`, `awaitingPicture` / `hasPicture`. A switch or reopen now only `stop()`s the player (no
  `clearMediaItems`), and both apps' `VideoSurface(keepLastFrame = true)` set
  `keepContentOnPlayerReset`, so the old frame stays (dimmed 45 % with a spinner) until the new
  channel's first frame. The media item carries metadata (title = programme, artist = channel,
  `MEDIA_TYPE_TV_CHANNEL`), updated in place with `replaceMediaItem` as programmes change.
- `LiveMediaSession.build`: a Media3 session over a `ForwardingPlayer` without seek/skip/speed commands
  and without a duration, so the platform session has no scrubber (verified: actions 17031, no
  ACTION_SEEK_TO). `LiveBadge` («● LIVE»). Strings `tv_continue`, `tv_continue_watching`,
  `tv_next_at_long` («Далее в HH:MM — …»), `tv_guide_hint`, `tv_watching`.

### TV
- `ContinueChannel` (392 dp card + eyebrow, programme title, «● N · name», time, next) first on the page
  and the initial focus. Cards gained the «Next at» line, `width`, `watching` (red dot), `showNext`.
- `LivePlayerScreen`: one bottom banner (logo, «N · name ● LIVE», programme, start–progress–end,
  next, an «OK Channels» hint) sliding in on every switch and on OK, hiding after 5 s; OK with the
  banner up — or Down after OK — opens the mini-guide (section title + 172 dp cards, focus on the
  channel on, OK tunes, Up/Back closes, auto-hides after 15 s idle); Back: guide → banner → leave.
  Channel±/Page±/Media Next/Previous zap everywhere; Up/Down zap when the guide is closed.
  The media session lives while the screen is composed.

### Phone
- `ContinueChannel` on top of the TV page (stacked; side by side from 600 dp). Player: banner at the
  top (back, logo, channel + live, programme, progress, next, up/down buttons), a tap opens it with
  the bottom strip of the section (164 dp cards, a tap tunes), another tap hides; swipes zap as
  before. Picture-in-picture (auto-enter on Android 12+, `onUserLeaveHint` before), overlays hidden in
  PiP, closing the PiP window ends the channel; one live activity at a time, and a video closes it
  (and vice versa). `LocalMediaUrls` is provided in the activity so the strip shows programme art.

### Verified
- `./gradlew :core:test :ui-common:testDebugUnitTest :tv:testDebugUnitTest :mobile:testDebugUnitTest assembleDebug`.
- Emulators (API 36 Google APIs; TV app on a 1920×1080/320 dpi D-pad AVD, phone on a Pixel 8 AVD)
  against a test server: Continue after watching, focus, banner on open and on zap, the held frame
  right after a zap (dimmed, never black), mini-guide open/move/tune, Back order, phone strip, swipe
  zap, picture-in-picture, media session state and metadata (`dumpsys media_session`).

### Known gaps
- Not run on a real Android TV image or device; the emulator image is a phone image (status bar and
  gesture handle visible in captures).
- Other channels' cards in the strip use the 30 s channel list, so with very short programmes a card
  can show a programme that just ended for up to 30 s (the channel on screen uses its guide).
- No PiP actions (channel up/down buttons in the PiP window); headset next/previous don't zap.
- No Robolectric UI test for the new player overlays.

## Live TV: external IPTV channels

- The server merges channels of external IPTV sources into `GET /api/channels` and the guide by
  number (from 100): `external: true`, `stream {url, userAgent, referrer, fallbacks, unsupported}`;
  their programmes have `kind: "external"`, `itemId: 0` (no item page).
- `LiveTvSession` plays them straight from the provider (never the own live route): the stream,
  then the fallbacks in order, skipping ones marked unsupported on `android`/`androidtv`. A source
  that fails before playing hands over at once; one that broke while playing is reopened up to twice.
  When none plays: `LiveStream.Failed(external = true)` («Канал сейчас не открывается»), no automatic
  retry; Try Again starts over, zapping works.
- The server's hidden-address form `/api/channels/live/ext/{id}/{n}` (a 302) gets `?mk=`; provider
  URLs never do. The player loads external streams through a plain `DefaultHttpDataSource`
  (`setUserAgent`, `setDefaultRequestProperties(Referer)`, cross-protocol redirects), never the
  authorised OkHttp client. A URL without an extension is tried as HLS, then as a plain stream.
- Audio/subtitles of an external channel: the stream's own track groups with their labels
  (`ExternalTracks`, duplicates get «(CC)»/«5.1»/a number); the choice is kept per channel in the
  device's preferences (`live_external_tracks`, language + label); default = the profile's language.
- «ТВ» calls `POST /api/channels/opened` when it opens (at most every 10 minutes) and reloads the list.
- Verified on emulators (phone 360 dp and Pixel 9 Pro XL on API 36, Android TV API 34 image): the three
  demo HLS streams, alternate audio and subtitles, the per-channel memory, a failing channel and
  zapping out of it, the hidden-address form. Fallback order and TV-is-TV are unit-tested
  (`ExternalChannelSessionTest`, `LiveTvNoHistoryTest`).
- Not verified: real providers (User-Agent/Referer requirements, MPEG-TS without extension),
  fallbacks with real duplicate channels; on the TV emulator the Apple «advanced» stream rendered
  zoomed (goldfish decoder), the other two were fine.

## Episode guide on series pages

- `:core`: `AntresolClient.episodeGuide(kind, tmdbId)` (`GET /api/tracking/{kind}/{tmdbId}/episodes`,
  server 0.5.0+, docs/API.md §4.6), models in `model/EpisodeGuide.kt`, and `library/EpisodeGuide`:
  `fetch` (null for anything but a series with a `tmdbId`, for every failure — 404 from the age
  filter, 404/405 from older servers, network, decoding — and after 6 s) and `build`, which merges the
  series detail with the guide into `GuideSeason`s of `GuideEpisode`s (`Downloaded` / `NotDownloaded`
  / `Upcoming`). A library source's `meta.itemId` picks the library card (so title, still, progress,
  watched and the watched toggles stay the library's); a library episode at the same season and number
  also counts as downloaded; library episodes the guide misses are kept; a library source the detail
  does not list becomes a minimal card that still plays. Upcoming = air date after today or none.
  No guide = the library's seasons, unchanged, and no «Скачана» marks.
- `:ui-common`: `EpisodeLabels` (marks, localized air date «21 ноября» / "November 21", the year only
  when it is another year's, «Спецвыпуски» for season 0, the dim levels) and the strings.
- Phone: not-downloaded rows are dimmed without still/Play/watched toggle and tapping one shows «Серии
  нет в медиатеке»; upcoming rows are greyed and not clickable. The season watched toggle shows only
  for seasons the library has, and marks the library season item as before.
- TV: every guide episode is a card in the season row; library ones play on OK and open on hold as
  before. The others stay focusable so the row scrolls to them, but OK does nothing; the hint under
  the row says «Серии нет в медиатеке» / «Ещё не вышла» while one is focused.
- Tests: `EpisodeGuideTest` (states, numbering mismatches, fallback, fetch failures and timeout),
  `SampleDecodingTest.episodeGuide` (`tracking-episodes.json`), `VideoInfoTest` (TV), `EpisodeLabelsTest`
  (phone module, where Robolectric sees the merged resources).
- Not verified on a device or against a live 0.5.0 server.

## TV UI fixes from the owner's TV (keyboard, player focus, icon buttons, Back on panels)

- Text fields over the keyboard: Gboard for TV floats over the middle and bottom of the screen and
  covered the centred server address. `components/TvTextField.kt`: `rememberTvKeyboardOpen()` (the
  root window insets' IME visibility, polled every 150 ms; Compose's `WindowInsets.isImeVisible` is
  useless here, without an edge-to-edge window it says "visible" from the start), with a fallback for
  keyboards that report no IME at all: the field's own OK → `keyboard.show()` counts as open until a
  submit, focus loss or a D-pad key reaching the field. `KeyboardAwarePage` slides the page to the
  top (animated, 40 dp gap) and `WhileNotTyping` folds the logo/avatar/subtitle. Used by the server
  address and the profile password; search and the playlist-name panel already have the field in the
  top third. Addresses and passwords ask for no suggestions: `TYPE_TEXT_FLAG_NO_SUGGESTIONS` is added
  through `InterceptPlatformTextInput` (Compose never sets it; autocorrect off is not enough for Gboard).
- Player controls (`ui/player`): Play/Pause, Audio, Subtitles, Quality (and Next Episode) are round
  glass icon buttons (`PlayerIconButton`, 48 dp; focus = white fill, black glyph, 1.07 scale, glow, the
  name as a caption under the focused one only), like the Apple TV transport bar and the live banner.
  Play is no longer a white "primary" pill, so with focus on the seek bar nothing else looks focused.
  Down from the seek bar goes to Play. `PlayingOverlayContent` is the overlay without the view model.
- Back on side panels: Compose turns an unused Back key into a focus "exit" and counts a cancelled one
  (OptionsPanel's `exit = Cancel`) or a move to a focusable parent (the player's root box) as used, so
  the key never reached the activity's `BackHandler`s: player menus closed only by choosing. Now
  `OptionsPanel` takes Back itself (preview key handler, closes on key-up without choosing, plus its
  own `BackHandler`); every screen already puts focus back on the opener. The player's Back with the
  controls up is taken at the overlay root and hides them in one press (as a key on a focused button it would otherwise go to that focus exit first).
  KeyPanel-style panels (channels, guide, live player) keep focus outside and were not affected.
- Tests: `OptionsPanelBackTest`, `PlayerControlsFocusTest` (fill colours of focused/unfocused buttons,
  captions, D-pad order, Back on a track menu: closed, nothing chosen, focus on the opener, player not
  left), `ServerScreenKeyboardTest` (field in the top third while typing and back, NO_SUGGESTIONS +
  URI variation in the EditorInfo). All with real KEYCODE_BACK events through the window: the old
  tests' `onBackPressed()` path never went through Compose's focus exit.
- Not verified on a real TV: whether Gboard for TV reports IME insets (either way the fallback moves
  the field), and the look of the floating keyboard over the moved page. The TV emulator did not run
  (not enough disk space for its data partition); screenshots are Robolectric renders.

## LAN server discovery and TV sign-in by QR (phone and TV)

Implements the server's client contract for LAN discovery and QR sign-in (server 1.4.0+, the server
side of the same feature: UDP probe on port 8420, mDNS `_antresol._tcp`, `POST /api/auth/pair/*`).

### `:core`
- `discovery/`: `DiscoveryProtocol` (probe bytes, UDP answer parsing with the answer's **source address
  first**, since inside Docker the listed `urls` are container addresses; mDNS TXT parsing, IPv4 only),
  `Sightings.merge` (by server id; a UDP source address stays first), `UdpProbeSource` (3 probes 300 ms
  apart to 255.255.255.255, every interface's subnet broadcast and the last server's address when it is
  a private IPv4 literal, 2 s of answers, repeated every 10 s while collected), `ServerDiscovery` /
  `ServerFinder` (merges sources, confirms each server with `/api/health` on its candidates in order and
  lists only confirmed ones; `searching` for the first 4 s).
- `pairing/`: `PairingFlow` (TV state machine: Starting → Pending(code, url, deadline) → Approved /
  Denied; expired codes and the end of the countdown start a new code by themselves, one last poll
  runs at the deadline so a late approval is not lost; network errors keep polling; 429 waits
  `Retry-After`; a failing `start` is retried every 5 s). The countdown is on the device clock: the
  server's `expiresAt` is trusted only within 6 min of now (TV clocks are often wrong), else 5 min.
  `PairCode` / `PairLink` (QR URL `…/pair?code=&sid=`, or a bare typed code; the host is ignored),
  `QrCodes` (ZXing core encode/decode). `AntresolClient.pairStart/pairPoll/pairLookup/pairApprove/
  pairDeny` (`pairPoll` maps 410 to denied/expired), `SessionManager.deviceInfo()` and
  `signInWithPairing(token, user, serverId)` (stores the token as a normal sign-in).
- Tests: `DiscoveryTest` (parsing, merge, broadcast math, a real UDP round trip on loopback, health
  confirmation and impostors), `PairingTest` (flow with virtual time, request shapes via MockWebServer,
  codes/links, QR round trip, sign-in storage). `PairingIntegrationTest` runs against a real server
  with `ANTRESOL_PAIRING_E2E=<base URL>` (+ `ANTRESOL_PAIRING_E2E_UDP`, `ANTRESOL_PAIRING_E2E_PASSWORD`):
  unicast UDP probe → health → TV pairing flow → phone lookup/approve → TV token works for `/api/me`
  and shows in the devices list. Passed 2026-11-14 against the server branch built locally.

### `:ui-common`
- `discovery/LanDiscovery`: the Android `ServerFinder`: UDP probe + `NsdManager` (resolves one service
  at a time; any NSD failure only ends that source) and a Wi-Fi `MulticastLock` while collected
  (`CHANGE_WIFI_MULTICAST_STATE`, declared here). No location permission.
- `components/QrCodeImage` (black modules on a white rounded tile with a quiet zone, pixel-snapped),
  icons `Phone` and `QrCode`, shared strings for the server list.

### TV
- `ServerScreen(session, finder)`: found servers above the field (glass rows, white when focused; at
  most two rows and a peek, the rest scroll), "Looking…" then a Docker hint when none. The logo folds
  away while servers are listed. Focus moves to the first server found **only if no key was pressed
  yet**. Choosing one fills the field and runs the same `connect()`.
- Profiles: «Sign In with Your Phone» next to «Change Server» opens `PhoneSignInScreen`: a 300 dp QR of
  the contract's `url` verbatim, the code as `ABC 234`, "open host:port/pair and type the code", a
  countdown, New Code after a decline, «Sign In with the Remote» / Back return to the picker. The flow
  is collected with the lifecycle (it stops in the background; on return a new code replaces it).
- The TV's device name is now the system device name (`Settings.Global.DEVICE_NAME`, e.g. «Гостиная»)
  when set, else "Android TV (model)" as before; the approving phone shows it.
- Tests: `LanPairingUiTest` (list states, focus on the first server, D-pad to the field, connect through
  MockWebServer, focus not stolen after a key; QR screen countdown, **the rendered QR decodes back to
  the URL**, renewal after 5 min, approval → signed in; decline → New Code focused → new code; Back).
  `ServerScreenKeyboardTest` waits for the field's focus (it was racing the DataStore read).

### Phone
- `ServerScreen(session, finder)`: a grouped block of found servers above the field.
- Settings → «Sign In a TV» (`PairTvRoute`): CameraX preview + ZXing decoding of the Y plane
  (`QrScanner`), CAMERA asked with a rationale on the screen (Open Settings when permanently denied;
  `camera.any` not required), a "type the code" field, then the device card (name, model, version,
  orange warning when `remote`), the profile (admins: every profile from `/api/profiles`, others: their
  own), Approve / Decline, Done. A QR of another server (`sid` ≠ own) is refused with a message; the
  phone always calls its own server. The approve call sends `userId: 0` for the own profile.
- No app link for `/pair`: the contract defines no scheme and https links per user server cannot be
  verified; the phone camera opens the server's web page instead, which does the same.
- Tests: `LanPairingPhoneUiTest` (server list → connect; admin approves another profile; other-server
  QR refused; typed code; non-admin sends 0; permission rationale; invalid code; decline).

### Libraries
- `com.google.zxing:core` 3.5.3 (Apache-2.0; pure Java, no network) in `:core`.
- `androidx.camera:camera-camera2/-lifecycle/-view` 1.4.1 (Apache-2.0) in `:mobile` only.

### Not verified (needs a real TV/phone)
- Broadcast/multicast reception on real Wi-Fi (some routers and TV boxes drop them), NsdManager on TV
  boxes, the multicast lock's effect, real camera scanning distance/angle of the TV QR, Android 16+
  local-network permission behaviour (not enforced for target SDK 36 yet).

## TV setup by phone (one QR for the server and the sign-in)

2026-11-14: the TV is set up from the phone: one QR carries the server address and the sign-in, the
remote is the fallback. Builds on the server's pairing contract; no server change. The Apple apps
implement the same wire format.

### `:core` (`tvsetup/`)
- `TvSetupLink` (`http://<private IPv4>:<port>/setup?n=<nonce>&v=1`; plain http, private/link-local IPv4
  literal only; `v` absent or `1`), `ScannedTvCode` (setup link or the server's `/pair` link / bare code).
- `SetupNonces`: 128-bit base64url (22 chars), 10 min, one at a time; a POST claims it, a failed POST
  gives it back (the person can fix the address on the same page), a successful one consumes it.
- `TvSetupListener`: hand-written HTTP/1.1 on a `ServerSocket` (one request per connection, Connection:
  close, 10 s socket timeout, ≤ 4 concurrent). Clients not on private/link-local/loopback get the socket
  closed without an answer. ≤ 10 requests to `/setup` per minute in total (429 `rate_limited`), body
  ≤ 4 KB, no chunked bodies. `GET /setup?n=` → HTML form (ru/en by `Accept-Language`, inline CSS, no
  scripts, CSP, `Referrer-Policy: no-referrer`), wrong nonce → 404 «Код устарел…». `POST /setup`: JSON
  (`n, server, serverId?`) → `200 {code, sid, server, device}` or `{"error": "<code>"}` with the spec's
  statuses; form → `303` to `<server>/pair?code=&sid=` or the form again with the message. After a
  successful answer it runs the handler's commit and closes itself.
- `TvSetupCoordinator` (the TV's handler): `SessionManager.check` (health + setup, same as a typed
  address), `serverId` comparison (409), `pairStart`, answer; commit = `PairingTicket.offer` then
  `SessionManager.use` (state → ChooseProfile). A server that still needs setup answers `422
  not_antresol` (not in the spec's list); the TV's HTML page says "not set up yet".
- `PairingFlow(initial = StartedPairing)`: shows and polls the handed-over code first, no second `start`
  (that would cancel the code the phone is approving). Used once per flow instance.
- `TvSetupSender` (phone POST, 40 s read timeout since the TV checks the server first), `setUpTv` /
  `approveTvCode` (sid check, lookup, approve).
- Tests: `TvSetupTest` (links, nonces, validation, JSON contract, listener on loopback incl. rate limit,
  form redirect, error pages; coordinator vs MockWebServer; sender; flow with a handed-over code).
  `TvSetupIntegrationTest` with `ANTRESOL_TVSETUP_E2E=<base URL>` (+ `ANTRESOL_PAIRING_E2E_PASSWORD`):
  TV listener on loopback ← phone POST → real server pair start → phone lookup/approve → TV flow gets the
  token → `/api/me`, devices list, nonce single use. Passed 2026-11-14 against `feat/lan-pairing` built
  locally (fresh data dir, admin created with `POST /api/setup`).

### TV
- `AuthState.NoServer` → `TvSetupScreen` (QR right; title, hint, «Серверы в этой сети», «Ввести адрес
  пультом» left; focus on the button, or on the first server found before any key). «Ввести адрес
  пультом» → the old `ServerScreen`, Back returns. No LAN IPv4 → orange note, no QR.
- Listener lifecycle: `repeatOnLifecycle(STARTED)`; `LocalAddresses.lanIpv4()` (private before
  link-local, eth/wlan first); renewed at nonce expiry; stopped in `finally`.
- `ChooseProfile` → `PhoneSignInScreen` by default (Apple TV layout via `QrScreenLayout`), «Sign In with
  the Remote» → profile picker (Back → QR), «Change Server» on the QR screen. A declined code shows New
  Code under the status on the right. Session-expired notice shows on the QR screen.
- `AntresolTvApp.pairingTicket` carries the started code from the listener to the QR screen.
- Tests: `TvSetupUiTest` (screenshots to `$ANTRESOL_SHOTS_DIR`, default `tv/build/tv-setup-shots`),
  `LanPairingUiTest` / `TvLiveUiTest` adjusted to the new defaults.

### Phone
- The scanner recognises the setup QR → `PairStep.SetupConfirm` («Настроить телевизор»: server =
  the address the phone uses now, profile; admins pick) → POST → sid check → lookup + approve →
  «Готово». If the TV took the server but approve failed, «Повторить» only approves.
- Cleartext to a LAN IP: `android:usesCleartextTraffic="true"` and no network security config in
  `:mobile` (and `:tv`), so plain http to the TV's listener is allowed. Android 16+ local-network
  permission is not enforced for target SDK 36 yet (same open item as discovery).
- Tests: `TvSetupPhoneUiTest`.

### Not verified (needs a real TV/phone)
- The TV's LAN address choice on real boxes (Ethernet + Wi-Fi, VPN interfaces), whether some TV
  firewalls/ROMs block inbound connections to an app port, phone camera scanning of the setup QR, the
  phone browser path (form → 303 → server `/pair` page with its sign-in gate), and the phone on a public
  https address (the TV then uses that address too).

## Owner list 2026-11-15 (TV + phone)

Codes against the server contract of the same date (A `Card.watched`, B `Card.thumb` +
`/api/images/frame/{id}`, C `GET /api/items/{id}/trailers`), built in parallel; verified with
fixtures (`TvShellUiTest` + `tv/src/test/.../fixtures/FakeServer.kt`), not against a live server.

### `:core` / `:ui-common`
- `CardFields.thumb` / `watched` (interface defaults; `Card`, `EpisodeCard` carry both, `ItemDetail`
  `watched`), `CardFields.wideImage` = thumb → backdrop → poster for every 16:9 tile.
  `CastMember.photo`. `TrailerList` / `Trailer` (`appUrl` = `vnd.youtube:<key>`, `webUrl`),
  `AntresolClient.trailers(id)`. Episodes the guide synthesizes get `thumb = /api/images/frame/<id>`.
- `Kind.isBook`, `HomeResponse.withoutBooks()` (the TV apps have no books, as on Apple TV).
- `components/Watched.kt` (`WatchedStyle` alphas, `WatchedMark` eye in a glass circle),
  `components/CastPhoto.kt` (photo over the initials avatar), `AntresolIcons.Eye` / `Trailer`,
  `trailers/Trailers.kt` (fetch, empty on any failure; open the YouTube app, else the https page).
  Strings `action_trailer`, `trailer_choose`, `trailer_teaser`, `trailer_no_app` (en + ru).

### TV
- Trailer pill last in the detail actions (no shift when trailers arrive after the page); one
  trailer opens directly, several open an `OptionsPanel` chooser; no app → a caption.
- Cast row (`detail/CastRow.kt`): focusable round photos with name and character; the cast text
  line in the header is gone.
- Episode tiles and Wide cards use `wideImage`; watched cards and episodes: dimmed artwork
  (`WatchedStyle`, less while focused) and the eye; progress only when not watched.
- Grids (`CardGridScreen`): the scrolling area starts under the nav (`TopNavHeight`, clipped), the
  nav stays up; `EvenColumns` shares the space a fixed column count leaves over between the gaps,
  so both gutters are 48 dp.
- No Books: tab, page, book grids, search scope/tiles/rows and book cards on Home are gone. The
  book detail, comic reader and audiobook player code stays (unreachable from the TV UI now).
- Settings: the server section (address, version, library, change server), devices and
  `SettingsViewModel` are gone; profile, playback, language and app version remain.
- Tabs (`MainShell`): every tab sits on an empty `ShellRootRoute`, so each tab (Home too) is saved
  and restored the same way. Leaving a tab starts a 5 s timer (`TAB_RESET_MS`); coming back first
  cancels it (state kept), else `clearBackStack(tab)` drops its saved stack and it opens fresh.
  Selecting the current tab pops to its root. Back on a tab root: Home → `moveTaskToBack`, other
  tabs → Home.
- Long press (`HeldKeyGuard`): after a long press opened a page, the rest of the held OK (repeats
  and the release) is swallowed at the shell root once focus has left the long-pressed card, so
  the new page's Play is not clicked by the release.
- Hang (item 6), root cause: two focus faults that together leave the TV with nothing focused and
  the D-pad unable to bring focus back (it looks frozen).
  1. Pages took focus from `LaunchedEffect`s gated only by "the nav has no focus". During Back the
     leaving page stays composed for its fade-out; a detail page whose load finished then (or whose
     focus request was still retrying) pulled focus onto itself and dropped it when it was disposed.
  2. Each page was a `focusRestorer()` group. The restorer keeps the page's last focused child and
     redirects later focus requests inside the page to it, also when that child is gone, so Home
     coming back from a detail page (or a page opening a side panel, e.g. the trailer chooser)
     ended up with no element focused.
  `TvShellUiTest.enteringAndLeavingTheSameSeriesNeverLosesFocus` (OK, then Back at 16…700 ms, item
  answers after 0/120/400 ms; 21 rounds) froze in 5 rounds and showed no focused element in 6 more
  on e9a3d7a; now every round returns to Details.
  Fix: each page gets a gate (`LocalPageCanTakeFocus`) that is true only for the current back stack
  entry, asked on every frame of a focus attempt (`tryRequestFocus(allowed)`,
  `FocusMemory.restore(key, allowed)`); the page is a plain focus group and the nav enters it
  through the page's `FocusMemory` (`LocalFocusMemories`, `PageFocus.enter`); the shell re-enters
  the current page if it has no focus after a transition or loses it later (`ensureFocus`),
  registering each page composition separately (an entry can be composed anew while its old
  composition is disposed). `OptionsPanel` repeats its focus request until focus has arrived.

### Phone
- Same data: `MediaCard` Wide tiles (Continue Watching) use `wideImage`; watched cards dimmed with
  the eye; episode rows show the episode's own still, dimmed when watched; cast photos
  (`CastPhoto`) with initials as the fallback; a trailer icon button beside Play (a pill in the
  actions row when there is nothing to play): one trailer opens YouTube, several open
  `TrailerSheet`; no app → snackbar.
- Already there, unchanged: long press on a Continue Watching card opens its page. Phone Settings
  keep the server and devices sections (the owner's item 8 is about the TVs; the phone is where a TV
  is signed in) and Books stay on the phone.
- Tests: `PhoneOwnerListUiTest` (fixture copy in `mobile/src/test/.../fixtures/FakeServer.kt`),
  screenshots `mobile/build/screenshots/owner-phone-*.png`. The trailer sheet is a separate window
  that the decor-view screenshot does not capture; the test selects its row and checks the intent.

### Follow-up: cast photos on TV, busy frames
- Cast photos on TV: the TV image loader sends the photo's absolute URL with the Bearer header
  (`TvShellUiTest` asserts the header on `/api/images/cast/…`) and the photo replaces the initials
  (`CastPhoto` now hides the initials once loaded). Earlier TV screenshots showed initials for two
  test-only reasons: Coil's process-wide loader came from an earlier test's application (no token),
  and Robolectric draws no animation frames, so a first load stayed at the crossfade's empty start.
  Both UI tests now install the app's own loader without crossfade.
- `/api/images/frame/{id}` may answer 503 + Retry-After while busy: `ArtworkImage(fallbackUrl)`
  shows the backdrop (else poster, `CardFields.wideFallback`) on any error, and a later visit asks
  for the frame again (Coil does not cache failures). `TvShellUiTest.busyFramesFallBackToTheBackdropAndAreRetried`.

## Trailers inside the app (owner, 2026-11-15)

Owner: a trailer plays inside Antresol, not in another app. Server contract (vault
`fix/trailers-inapp`): each trailer may carry `play` (`/api/trailers/<key>/play`); `POST` it and
the server answers `{"url","durationMs"}` (a cached MP4 signed with `mk`), `503` + Retry-After
while busy, `502` with a translated message when the fetch failed, `404` for an unknown key.

- core: `Trailer.play` / `playsInApp`, `TrailerPlay`, `AntresolClient.trailerPlay(play)` (90 s read
  timeout: the first fetch can take a minute), `playback/TrailerPlayback.open`: 503 asked again
  after Retry-After (default 2 s, 0.5–10 s) up to 30 s of waiting, then `TrailerBusyException`;
  404 → `TrailerUnavailableException`; 502 thrown as is (the server's message is shown).
- ui-common `trailers/`: `Trailers.howToPlay` → `Player` (has `play`), `Embed` (no `play`: public
  servers, trailers off), `External` (no WebView on the device: the old YouTube app / browser
  intent, last resort). `TrailerPlayerViewModel` (the films' ExoPlayer via `buildVideoPlayer`, no
  progress, no Continue Watching, no next episode). `TrailerEmbed` + `TrailerEmbedView`: the
  official IFrame player in a WebView, page loaded with `loadDataWithBaseURL("https://www.youtube.com/")`
  so the embed gets a referrer (error 153 otherwise), player host `youtube-nocookie.com`,
  `origin` set, autoplay without a gesture, a JS bridge for playing/ended/errors, `antresolToggle()`
  for play/pause; the WebView is destroyed with the screen.
- TV: `TrailerRoute(play, key, name)` full screen (nav hidden) → `TrailerPlayerScreen` (the name
  and a spinner, then the film player's transport without menus; Back leaves at once) or
  `TrailerEmbedScreen` (OK toggles through the IFrame API). Errors: `ErrorView` with «Повторить».
- Phone: `TrailerPlayerActivity` (landscape, bars hidden, no picture-in-picture) with the same two
  screens in `player/TrailerPlayerScreen.kt`.
- Tests: both FakeServers answer `POST /api/trailers/{key}/play` (ready, busy once, 502) and serve
  `src/test/resources/trailer-sample.mp4` (30 s, 160x90, 9 KB); `TvShellUiTest` and
  `PhoneOwnerListUiTest` cover own player (loading after a 503, playing), error + retry, and the
  embedded page (HTML, base URL, settings, bridge, toggle, destroy). Not verified: the embedded
  player on a real device (Robolectric draws no WebView content), and real video frames.


## Review pass 2: bugs, duplicates between the apps, dead code (2026-11-16)

From the duplication review (`AS-n`). Nothing here was run on a device.

- Bugs: the TV live player's audio panel is as long as the rows it draws; the guide is asked for
  once on opening (`showView`), the phone asks for a failed guide page again while scrolling near
  the end, TV guide focus requesters are keyed by channel + programme; the TV comic reader's last
  save is a tracked pending write and `DetailViewModel` re-reads on `PendingWrites.completions`;
  `TrailerEmbedView` pauses the player and the WebView on stop (`LifecycleStartEffect`); phone
  audio reports a 401 from the stream (`PlaybackException.isUnauthorized`, checked in the service:
  the HTTP status does not cross the MediaSession); `attempt {}` (`core/api/Attempt.kt`) replaces
  `runCatching` around suspend calls; overlapping optimistic toggles on the phone go through
  `LoadViewModel.optimistic` (back to the value before the first, then a re-read); the TV's server
  screens no longer read an HTTP error as «Can't reach the server».
- Shared now: `LoadState` / `load` / `userMessage` / `LoadViewModel` (`ui-common`, package
  `app.antresol.ui`; one 429 rule: a bare «HTTP 429» is «Too many attempts», a server sentence gets
  «Try again in N s» when the server sent Retry-After); `ServerConnectState` (`ui/onboarding/`) for
  the phone's and the TV's two connect screens; `buildAudioPlayer`; `playerMenu()` + `MenuOption` +
  `PlayerMenu` + `qualityLabel` (`ui/playback/PlayerMenus.kt`); `PageProgressSaver`
  (`core/reading/`) for the three readers; `HomeContent` (`core/model/`, the TV passes
  `home.withoutBooks()`); `kindLabel`; `Kind.VIDEO`; `MediaUrls.hostPort`; the guide's
  `LocalMinuteWidth` / `minutesToDp` / `stickyLabel` (`ui/channels/GuideScale.kt`); 13 strings both
  apps spelled the same.
- Tests: one `FakeServer` and `trailer-sample.mp4` in `test-fixtures/`, added to both apps' unit
  tests by the root `build.gradle.kts` as a **java** source dir (the Kotlin plugin overwrites an
  Android source set's `kotlin` dirs with its own, so `kotlin.srcDir` is silently dropped).
- `HeldKeyGuard`: a first key-down (repeat 0) is a new press: it ends the guard and is never
  swallowed. The release of the held OK is lost when it arrives while the opened page has nothing
  focused (no key handler sees it); the guard then stayed armed and ate the next OK. That is also
  why `TvShellUiTest`'s trailer-chooser tests failed on a busy machine (also on 7484bda, before
  this pass). `HeldKeyGuardTest`.
- Verified: `./gradlew :core:test :ui-common:testDebugUnitTest :tv:testDebugUnitTest
  :mobile:testDebugUnitTest assembleDebug` green: core 224 (9 skipped), ui-common 28, tv 41,
  mobile 45.
- Left alone on purpose: the other TV focus timers (AS-26), paged sources and grid sources (the two
  apps' sort labels, playlist cards and paging differ), the detail models, profile sign-in,
  rulers/timelines/blocks of the guide (each app's own `Text`, sizes and focus or touch).


## Review pass 3: first run on an emulator, leftovers (2026-11-17)

There are no instrumented tests in this repo (no `androidTest` source set): every UI test is
Robolectric, in the unit-test tasks. «On a device» here means the release APKs driven by hand.

- How it was run: `:mobile:assembleRelease :tv:assembleRelease` (R8, unsigned without the keystore;
  `verifyReleaseSerialization` OK), signed with the debug key (`apksigner`), installed in headless
  emulators (phone: API 36 google_apis arm64, Pixel 6; TV: API 34 android-tv arm64, 1080p; both
  `-gpu swiftshader_indirect -memory 2048`), against a throwaway Python port of `FakeServer` on
  `10.0.2.2` with channels, an EPG and the 30 s test clip as every file (`/api/play` → direct).
  Driven with `adb shell input`, read with `uiautomator dump` and `screencap`. The server screen
  finds the real servers of the LAN through the emulator's NAT: type the fake address.
- An emulator quirk, not the app: after the landscape player the launcher/Home stay landscape,
  because the system rewrites `user_rotation` when rotation is locked.
- What ran (release builds): phone — server screen, profiles, password, Home, grids, film page,
  channels, guide, film player to the end with the Up Next panel and its Cancel, system Back out of
  the players, trailer chooser, landscape, font scale 1.3; TV — setup and typed address, sign-in
  with the remote, Home, grid, film page, player (Previous and Stop keys), channels, guide. No
  crash in either app. The embedded YouTube trailer loaded its frame and then reported a player
  error on the emulator (software GPU), so the embed itself is still not seen playing anywhere.
- Found there, fixed: a `PrimaryPillButton` that became enabled lost its white fill («Sign In» after
  the first letter of the password; debug and release alike). `Modifier.alpha(1f)` is no modifier,
  so the chain changed; the pill now has a `graphicsLayer` that is always present. Robolectric
  never showed it: keep such a state change in mind for anything that swaps `alpha()` in and out.
- PDF reader (AP-14): `pdfRenderSize` caps a page at 4 MP (the shape kept), `PdfDocument` keeps the
  last three rendered pages (not recycled on eviction: a page on screen may draw one). Page sizes
  are still read on opening.
- `compose_stability.conf` (AP-21): `app.antresol.core.model.*` is stable for the three Compose
  modules. A promise: the package holds immutable values only. Checked once with the compiler
  report (`reportsDestination`): `stable card: Card`.
- TV `FocusRequesters` (AP-23): requesters counted by the elements using them
  (`Modifier.focusRequester(requesters, key)`), dropped with the last one; `FocusMemory` and the
  guide use it. The channel list's `rowFocus` is one per channel and stays a map.
- Tile artwork (`FITS_BOX`) asks for the larger side of a cropped tile
  (`MediaUrls.neededTileWidth`): exact for a square cover in a poster tile, one step more than
  needed for a poster.
- Phone: `GlassTextField` is named by its placeholder for TalkBack, `FormError` is a polite live
  region (AU-12; autofill needs Compose 1.8's `ContentType`, the BOM here is 1.7); the seek slider
  says «12:34 of 1:02:00» (AU-10); Back handlers that repeated the system's Back are gone, the
  live player's is on only with something open (AU-15); detail pages pull to refresh (AU-13).
- TV film player: Stop leaves, Previous starts again (`mediaKeyAction`, AU-23). The embedded
  trailer's WebView refuses main-frame navigations (AS-6).
- Looked at and left: AP-22 (nothing left: `ShelvesViewModel` is a `LoadViewModel`, whose `reload`
  cancels the running job; `VideoSession.open`'s catch only matters for a cancelled caller and its
  callers already stop the detached session); Coil's memory cache on `onTrimMemory` (Coil 2 does it
  itself: its `SystemCallbacks` trims the cache); AU-10's height and AU-9 (the Slider's own touch
  node is 16 dp whatever height is set outside, and Compose stretches a small clickable to 48 dp
  when hit-testing: the 44 dp player buttons report 48 dp bounds on the emulator; making the boxes
  48 dp would move every header); AP-25 (the keyboard poll: the replacement cannot be tried on the
  TV keyboards that report no insets); AU-22 (moves every page's top padding); the remote's Search
  key (needs the shell's focus logic); AU-27 (no count strings to pluralise yet).
- Verified on the final tree: `./gradlew :core:test :ui-common:testDebugUnitTest
  :tv:testDebugUnitTest :mobile:testDebugUnitTest assembleDebug :mobile:assembleRelease
  :tv:assembleRelease` green: core 225 (9 skipped), ui-common 28, tv 48, mobile 52; R8 keeps the
  serializers (109 and 107 classes); unsigned release APKs 4.72 MB (phone) and 3.71 MB (TV).
- Not verified: a real phone or TV; TalkBack itself (the semantics were read with `uiautomator`
  and in tests); the PDF reader and the comic readers (the fake library has no books); music;
  a live channel playing; predictive-back previews; the TV keyboard.

## Review pass 4: the guide's page seams, landscape, the trailer embed, the branch review (2026-11-17)

- `scripts/fake-server.py` (README, «Building») replaces the throwaway port of pass 3: the tests'
  `FakeServer` answers plus eight channels and a guide. One schedule per channel for the day, cut
  into pages by a copy of the server's `epgGrid` (`Store.Range` + `programs()`: the entries that
  overlap the window, the files of one title joined, the id of the first entry IN THE WINDOW).
  Channel 1 shows only films in two files; channels 2 and 5 have a transparent logo; the series has
  a transparent `logo`; an image asked for with `?w=` comes on black, as the server's JPEG copies do.
- The guide's overlap (pass 3, t12): not the placement. With that fixture, base 65d144d and the
  branch draw every ordinary row right over three pages, down and back (one origin, one minute
  width; the composed window only filters). Pass 3's fixture made the picture. But the server does
  send overlapping programmes at a page seam for a title in several files: page 1 `1-40`
  9:20–11:00, page 2 `1-41` 10:10–11:00 (drawn over it), and where the seam is in the first file,
  the same id twice with two ends (the short one was kept: a hole). Both on base and branch, TV and
  phone. `Epg.joined`: one programme per start (the longer), a title's overlapping parts joined, a
  repeat (starts where the first showing ends) left alone; `Epg.key` for merge, blocks and focus.
  A seam exactly between two files still gives two blocks of one title (nothing tells it from a
  repeat). The server could send whole programmes instead; not touched here.
- Phone landscape (AU-2): the side insets moved from the NavHost to the pages (`padded<Route>`);
  Home and the detail pages pad their list (`pageListPadding`) and their header spans it again
  (`fullBleed(insets)`) with its own content padded. The short hero ends above the navigation area,
  so Play/Details end clear of the tab bar instead of touching it. On a detail page the bar
  lies over the buttons at the top of the scroll, as it lies over any content; the list has the
  bar's height as bottom padding. The embedded trailer's WebView keeps its safe-drawing padding.
- Embedded trailer: never played because the page was loaded under `https://www.youtube.com`:
  the embed answers «Error code: 152 - 4», `PLAYABILITY_ERROR_CODE_EMBEDDER_IDENTITY_DENIED` (seen
  with the same Referer from the host; the app showed «This video can't be played right now»).
  Under `https://<application id>` (YouTube API Services, Required Minimum Functionality: API
  Client Identity; the Apple apps do the same) it plays in the phone emulator (`-gpu host`). The
  console still logs the IFrame API's `postMessage` origin warnings while the frame loads; harmless.
- Images with transparency: the apps draw neither title logos nor picture avatars (`Card.logo` is
  parsed and unused); the channel logo was never sized, but only by the shape of its URL. It now
  says so (`AntresolImageLoader.TRANSPARENT`). Any logo added later must do the same.
- From the branch review (`RV-n`): optimistic writes keep the accepted change without the re-read
  (RV-3); the small video buffer only at ≤ 2 GB or `isLowRamDevice`, a third of the heap, 32–64 MB
  (RV-5); a failed guide page waits 3 s (RV-8); 429 with the wait time (RV-10; the test is in `:tv`,
  `:ui-common`'s unit tests have no resources); PDF pages capped by a sixteenth of the heap, at
  least 4 MP, zoom still does not re-render (RV-7); `FocusRequesters.tryRequestFocus(key)` looks
  the requester up on every frame and drops one nobody used (RV-4); a trailer left while loading
  is dropped and loaded again on return, since `autoplay` starts it whatever the page's scripts do
  (RV-2); the trailer keeps the screen on while playing or buffering only (RV-9); an episode's
  poster may be its 16:9 still, so in a tall tile it is asked for at its cut width
  (`MediaUrls.artworkFitsTile`, RV-6).
- Not verified: RV-2, RV-4, RV-5, RV-7, RV-9 on a device (tests only); the trailer on the TV
  emulator and on a real device; a real server's guide with a film in several files; RTL.
- Verified on the final tree: `./gradlew :core:test :ui-common:testDebugUnitTest
  :tv:testDebugUnitTest :mobile:testDebugUnitTest assembleDebug :mobile:assembleRelease
  :tv:assembleRelease` green: core 228 (9 skipped), ui-common 34, tv 51, mobile 53; R8 keeps the
  serializers (109 and 107 classes). The guide, landscape, logos and the trailer were driven in the
  emulators with debug builds (TV API 34, phone API 36).

## Review pass 5: the delta review's findings (2026-11-17)

- Video buffer (D-1): a third of `memoryClass` on every device (the buffer is `byte[]` in the Java
  heap whatever RAM the device has), at least 16 MB, ExoPlayer's default from a 414 MB heap on, at
  most 64 MB on a low-RAM device. 48 → 16 MB, 64 → 21, 128 → 42, 192 → 64, 256 → 85, 512 → default
  138; at 80 Mbit/s that is 1.7 / 2.2 / 4.4 / 6.7 / 8.9 / 14 s.
- Guide merge (D-2): the server numbers a rebuilt schedule on from the last entry kept, so ids
  come again (`builder.go`, `extras.go`: `DELETE … WHERE seq >= ?`). The newer page wins: for one
  key or one start its copy stays, what the rows had inside its window is dropped, a library title
  across the seam ends where the newer page's begins. One block per key by construction. What the
  older page showed before the seam stays as it was (stale after a rebuild; where an id was used
  again, a hole): only reloading the guide would correct it.
- A film in several files shown back to back (D-9): the page that starts inside showing 2 gets its
  last files joined with the first of showing 3. A continuation exactly as long as the programme
  it continues is taken for that (the same files, rotated): showing 2 keeps its end, showing 3 is
  two blocks. It cannot be told from a single-file film moved by a rebuild to an overlapping time,
  or from a film in three files whose first and last are equally long: the server would have to
  send whole programmes (or the showing's first entry, seq or start, with each programme).
- A guide page that failed (D-3) is asked for again by its own job (3 s, 6 s, … 30 s) while a page
  shows the channels, and by `startRefreshing` after that.
- Optimistic changes must be absolute (D-4): `LoadViewModel.optimistic` replays the accepted ones
  on the value before all of them.
- PDF (D-7): a page may take an eighth of the heap (A4 at 2400 px, 32.6 MB, from 256 MB), the
  rendered pages kept are what fits a quarter of it (1–3).
- `scripts/fake-server.py`: port 8421 (8420 is the live tests'), the schedule's day fixed for the
  run, `--rebuild-after N` rebuilds the schedule after the Nth guide page.
- Embedded trailer on the phone: nothing of ours over the player (YouTube's embed rules). The back
  button has a 56 dp strip inside the safe area: beside the player when the window is wider than
  tall, above it otherwise (a tablet; the activity is `sensorLandscape` on a phone). The player is
  16:9 in what is left: in a wider WebView the embed lays its controls out over the whole frame and
  cuts them at the picture's edge (the link and full-screen buttons came out halved); the WebView
  itself never cut anything. The TV's screen is 16:9 and draws nothing over the player.
- Found on the way: `NoticeSnackbar` / `PlaylistNoticeSnackbar` never showed anything. They showed
  the snackbar inside a `LaunchedEffect` keyed by the notice and cleared the notice first, which
  restarted the effect. A refused toggle was undone silently, since the first phone commits; no
  test looked for the text. Now shown from the page's scope.
- Driven in the emulators (fake server): TV debug build, the guide with `--rebuild-after 1`: Right
  and Left pass through a row across the rebuilt seam, no overlaps, a hole where an id came again;
  the embedded trailer plays on the TV emulator (`-gpu host`). Phone RELEASE build (R8): server
  screen, profile, Home, a film's page, the server-played trailer in the app's player, the embedded
  trailer, the guide with a second page. Phone debug build: the embedded trailer in both landscape
  orientations and upright in a tablet-sized window, the detail snackbar on a 600 dp wide landscape
  window. The fake server answers 404 to `/api/play`, so the film player itself was not driven.
- Not verified: D-1 and D-7 on a device with a small heap (tests only); D-3 on a phone with the
  network cut; a real server's rebuild; a real TV or phone.

## Review pass 6: the second delta review's findings (2026-11-17)

- The guide merge (E-1, E-4, E-5) was patched three times and each review found another wrong
  case: `Epg.joined` rebuilt the schedule from the programmes' lengths. It is gone. `Epg.mergedRow`
  goes by how the app pages (forward only, each window starts where the one before ended; a reload
  starts from nothing) and how the server answers (`Store.Range` is `end > from AND start < to`;
  `programs()` joins a title's files only inside the window and ends a programme where its first
  file comes again):
  1. The page is the truth from `cut` on (the earlier of its `from` and its first programme's
     start): every known programme that starts at or after `cut` goes, and one whose key the page
     gives. No page programme is dropped or joined with another of the page.
  2. A library row's programme still on when the page's first one starts: the same title, the
     page's starting before `from` → one programme cut by the seam, extended to the page copy's end
     under its own key; any other → it ends at `cut`. The same title starting AT `from` is not a
     continuation (the entry the rows had across the seam is not in the page: a rebuild).
  3. External rows: replaced by start from `cut` on, never joined or cut.
  4. An empty row says nothing. 5. One programme per key (`check` in the function).
  Known limits, all shown as two blocks of one title side by side rather than an invented one:
  a seam exactly between two files; a film repeated back to back with the seam in a later file
  (the page then starts with «end of this showing + beginning of the next»: the known showing keeps
  its end, the page's programme starts there); a page that ends inside the film (never with 6 h
  pages). A title moved by a rebuild to a start before the seam and still overlapping its old
  place gives the same answers as a film cut in its second file: one block (stale start). What an
  older page showed before `cut` stays (stale after a rebuild; a hole where its id was used again).
  The server could end all of this by sending whole programmes or each programme's first entry.
- The guide's retry (E-2, E-3): five requests for a page (0, 3, 9, 21, 45 s), then it waits for
  the next call (a scroll position, a key) or `startRefreshing`. `loadMoreGuide(explicit = true)`
  (Right at the guide's end on the TV) ends the pause: asked for 1 s after the failure at the
  earliest (a held key), pauses and count start over. Scroll positions are still ignored in a pause.
- `ItemDetailViewModelTest` (E-6): three taps (on, off, on), both «on» refused: the page ends on
  «off», not what it showed after the last tap; the waits fail when they give up; the page's
  coroutines run on one thread and no write is answered before all three arrived (no race on the
  counter, no sleeps for the order).
- Embedded trailer (E-7): the page's reports carry the number of their load and the page says
  that it runs (`onPage`) before anything else; a failed load before that, or a report of an
  earlier load, is the dropped page's. Chromium's timing itself was not seen (unit test only).
- Snackbars (E-8): `NoticeSnackbars` shows a sentence that is showing or waiting anew in its place.
- Video buffer (E-9), from the Media3 1.5.1 sources: the stall is real (`DefaultLoadControl`
  `shouldContinueLoading`: target allocated and `prioritizeTimeOverSizeThresholds` off → no
  loading whatever is buffered; `ExoPlayerImplInternal`: not loading, under 0.5 s buffered,
  renderers not ready for 4 s → «Playback stuck buffering and not loading»). It needs a track
  without a sample in the first `target` bytes. MP4 cannot: `Mp4Extractor` reads no track more than
  10 MB ahead (`MAXIMUM_READ_AHEAD_BYTES_STREAM`), under the 16 MB floor. Matroska has no such
  bound, so no floor is «enough»: 16 MB is 1.6 s of skew at 80 Mbit/s (32 MB was 3.2 s, the default
  138 MB is 13.8 s). The floor stays; the comment and a test say it must stay above 10 MB.
- The guides' ruler: a time that begins left of the visible edge was cut by the channel column
  («AM» of «4:30 AM»; the phone's ruler did the same). It is not drawn until it fits.
- Checked: the required Gradle line (core 241 tests, 9 skipped; ui-common 42; tv 54; mobile 61).
  TV emulator (API 34) with `scripts/fake-server.py`: Right across three seams and Left back, the
  blocks read from the accessibility tree: per channel ordered, no overlap, no hole, and every
  programme of one 24 h window of the server present as one block (the seam inside a film's first
  and second file included). With `--rebuild-after 1` the same from the second page's first
  programme on, the stale stretch before it ending where the page's programme begins. The ruler's
  first time is not drawn while cut. Not verified: E-7 with Chromium's real timing, E-8 on a phone,
  the phone guide, a real server's rebuild, a real TV.

## TV home screen: Watch Next and the «Antresol» channel (tester item 28, 2026-11-19)

Nothing of Antresol showed on the Google TV / Android TV home screen (tvOS has Top Shelf). Built in
`tv/src/main/kotlin/app/antresol/tv/homechannels/`, androidx.tvprovider 1.1.0 + WorkManager 2.10.5.

- What: Watch Next gets the `continue` shelf of `GET /api/home` (started videos → `WATCH_NEXT_TYPE_CONTINUE` with
  position and length from `GET /api/progress/{id}`; next-up episodes, which have no progress → `..._NEXT`; at most
  10). One preview channel «Antresol» (`PreviewChannelHelper`) gets recent movies and new series interleaved, minus
  watched titles, titles with no poster and what is already continuing (at most 20). The server's lists are used as
  they come (kid limits, «Отдельная история» are resolved by it).
- Why the pictures go through our own provider: the launcher has no login and the server's images need the Bearer
  header (`?mk=` is refused for images). The app fetches each picture, shrinks it (≤ 960 px, JPEG), keeps it in
  `filesDir/tv-home` for 7 days (not `cacheDir`: the system may empty that while the launcher still holds the addresses; `retain` keeps only what a program shows) and `HomeArtworkProvider` (`{package}.homeart`, read only) serves exactly those
  files; a file's name is SHA-256(install secret + server path), so only someone who read it from the TV provider
  can ask for it. A Watch Next tile whose picture cannot be had shows `tv_banner`; a channel tile is skipped.
- Diff: each program's internal provider id is `{wn|ch}:{serverId}:{userId}:{kind}:{id}` and its first internal flag a
  64-bit FNV digest of everything shown ([ProgramSpec.fingerprint]); a run adds, updates (by digest) and removes. A
  changed Watch Next row is rewritten whole, because that row is ordered by last-engagement time (`now - rank` min). A
  tile the user dismissed stays in the provider (hidden) and is only updated, so it does not come back.
- When: app start (≤ every 5 min), after a player's final progress write (`PendingWrites.completions`), profile
  change / sign-out, and a 30-minute periodic WorkManager job (network required). Nobody signed in: the one run that
  notices removes the programs and cancels the periodic job; no request is made. The channel is created by the first
  run; the activity then asks once (`TvContractCompat.requestChannelBrowsable`, from the foreground) and never again.
- Deep links (`MainActivity`, exported VIEW filter, not BROWSABLE): `antresol://play/{id}?u={userId}` →
  `PlayerRoute(id, Resume)` (the player continues from the server's saved position), `antresol://title/{kind}/{id}?u=`
  → the detail page. `u` is the profile that published the tile; a link of another profile is ignored, and so is a
  link older than 60 s. An open player/trailer/channel/reader is closed first. Every program's address also carries
  `&n={nonce}`, a random 128-bit string of the install (`HomeChannelPrefs.linkNonce`, in the app's prefs, part of the
  program fingerprint); `HomeLinks.parse` ignores a link without it (constant-time compare), because the activity is
  exported and any app could start `antresol://play/{id}?u=1` (`u` is a guessable small number). The 60 s check stays,
  but only for staleness (a link that arrived while nobody was signed in must not fire at the next sign-in): its time
  is taken when the activity receives the link, so it is not a defence against forged links; the nonce is.
- No TV provider (some boxes, e.g. certain Fire OS builds): `HomeSync` asks `TvProviderStore.available()` (the
  `android.media` TV authority, resolved once) first and returns `Unsupported`: no request to the server, no pictures,
  no periodic job (an existing one is cancelled). A picture that cannot be written (full disk) is skipped like one that
  cannot be fetched (Watch Next shows `tv_banner`, the channel leaves it out); it does not fail the run.
- Permissions: nothing declared by us. The library's manifest merges in `WRITE_EPG_DATA` (normal, install time, no Play
  Console declaration; the provider enforces it on writes, so it is needed despite the brief) and WorkManager's
  `ACCESS_NETWORK_STATE`; `READ_EPG_DATA` (also the library's) is kept: the app queries its own rows, and removing it
  gained nothing and could break the syncs. No new foreground-service permission.
- Not verified on a device (no TV or emulator was available to this run): the provider writes (`PreviewChannelHelper`
  against the real TvProvider), the launcher drawing the content:// pictures, the one-time browsable dialog, a tile
  opening the app. Unit tests cover the mapping, the diff and the whole run against an in-memory provider, the
  deep links against the manifest, the picture cache and provider. Checked by hand: see the README's adb line.

## Addon ways (`x/app`)

The apps show what the server's addons offer, by the protocol in docs/API.md section 13; no addon id is in the code.

- `:core`: models (`model/Ways.kt`), the calls on `AntresolClient`, `ways/AddonWays` (discovery: `GET /api/addons/web`,
  then `x/app` of each, sorted by id; `WaysCatalog` keeps it per (server, token) and `invalidate()` resets it, as Home's
  pull to refresh does), `ways/WaysController` (the offer for one title, the state poll every 5 s while a keep way is
  active, a quick run, the choice as a `WayPicker` of steps with back, the outcome `Play`/`Done`/`Failed`),
  `ways/WayPlayback` (the input player's `VideoApi`/`PlaybackApi`, the 202 wait, the addon's own progress API, and
  `WayTicket`, a short token that hands a playback to the player screen).
- `:ui-common`: `VideoPlayerViewModel(way = ...)` plays a way. An input is a `VideoSession` over
  `/api/play/input/{id}` (caps, tracks, quality, recovery as for a file); a direct stream is a plain URL with `?mk=`
  that reports to `x/app/progress` and closes its session; a 410 runs the way again with `refresh`.
- Phone and TV: the ways on the detail page (`VideoDetailScreen`, `DetailScreen`), the episode menus, the step sheet /
  panel, the title page (`TitleRoute`, the detail screens with item id 0), the addons' sections of Search.
- Decisions: on a watch way a tap is `quick` (a `pick` answer opens the choice); a keep
  way always opens the choice (`WaysController.start`), as the web's «Скачать» does; an episode in the library counts as `inside` and one not in it as `outside` for the `scope`; the
  state text shows also once a keep way is done; TV has no pull to refresh, so its ways are asked again on a new sign-in or app start.
- TV page (`DetailScreen`): the main button of a title nothing of the library plays is «Play» (not the first way's
  label) and runs the way remembered for the title (`WayDefaults`, `DeviceWayDefaults` on the device, key
  `kind/tmdbId`; the first way while none) with a quick run; a chevron button beside it (`ways-more`; hold OK on
  Play too) opens the menu: a row per watch way with its short line (`AddonWay.hint`, else a neutral one by the
  icon), the default ticked; a row makes that way the title's default and runs it; under a line «Audio & quality…»
  opens the step choice of the ticked way. A library title has the chevron beside its own Play. An episode's tile
  is its «Play»: OK on one not in the library runs the title's way for it (as `forEpisode` allows), hold OK opens the
  same menu (with «Open Episode Page» first). An episode not in the library that has ways is not dimmed (`EpisodeLabels.alpha(state, playable)`,
  phone rows and TV tiles alike); one still to air stays greyed. The old «More ways to watch» pill and the hold-to-choose hint are gone.
- Busy is keyed by way and subject (`WayRun`: way key, title, season, episode): only the button of the run shows
  «Opening…» (a tile for an episode); the other buttons wait (one run at a time) but do not spin.
- Player: a stream the addon serves (`play`) lists qualities in the quality menu when its answer has two or more and
  a `ref`; a pick runs the way again with the `ref`, the `quality` and the position (`VideoPlayerViewModel.reopenDirect`,
  mirrors the iPhone app). A stream whose answer lists no audio or subtitles shows the stream's own tracks
  (`OwnTracks`, ExoPlayer's groups). A stream made by a way carries its run (`WayPlayback.call`): the audio menu then
  ends with «Audio & source…», which opens the way's step choice over the player (the picker of the player's own
  `WaysController`) and replaces the stream where the video is (`switchWay`). Phone: the same rows through the shared
  `playerMenu`, the picker in a `WayStepSheet`.
- Tests: `PhoneWaysUiTest`, `TvWaysUiTest` (the fake server speaks the protocol with `FakeServer.addons`; `secondWay`,
  `searchSeries`, `runDelayMs` and `wayQualities` shape it), `core/.../ways/*`, `OwnTracksTest`, `DeviceWayDefaultsTest`. `scripts/fake-server.py --addons` is the same for a device.
- Trailers: the web lists at most three per item and plays one without `play` in the embedded player; the apps did the
  latter already and now cap the list at three too (`Trailers.MAX_TRAILERS`).

## TV density and the channels page

- **Density**: the TV app draws for a 960×540 dp canvas (type in `Type.kt`, cards in `Cards.kt`), but a TV reports a
  density of its own: a 1080p one is often tvdpi (213 dpi), 1442×811 dp, where everything came out small in one
  corner (the Channels page, with its fixed 430 dp list and 960 dp preview, had a narrow list at the top left, the
  preview at the far right and the lower half empty). `AntresolTvTheme` now wraps its content in `DesignCanvas`
  (`ui/components/DesignCanvas.kt`): `LocalDensity` is `min(width / 960, height / 540)` of the window, so the canvas is
  never smaller than the design (a taller screen has more height, a wider one more width), the system's font scale
  is kept, and `canvasHeight()` gives the window's height in canvas dp (Home's hero and the detail header used the
  configuration's `screenHeightDp`, which counts in the density the TV reports). At 960 dp xhdpi nothing changes.
- **Channels page** (`ChannelsScreen.kt`): the header (title, `Channels | Guide`, the category button next to it, the
  guide has the same header) never scrolls; under it the list is on a panel (46% of the width less the gap) beside the
  card of the focused programme (the rest), both the full height. Row height = the panel's room / channels, between
  62 and 104 dp; from 84 dp up a row has the programme's still. Down/Up walk the list (scrolling the panel), Left from
  a row opens the categories, Down from the header enters the list on the channel the card shows.
- Tests: `DesignCanvasTest` (the maths, and the window is 960 canvas dp wide at xhdpi and tvdpi), `ChannelsLayoutTest`
  (the page's geometry in canvas dp at both densities, focus and the card, the guide's header; screenshots to
  `$CHANNEL_SHOTS_DIR`), `TvDensityUiTest` (Home, Movies and a series page laid out the same at both densities;
  screenshots to `$DENSITY_SHOTS_DIR`).
- Not verified on a real TV: the density a given box reports, and the page on a real remote (the tests drive D-pad
  key events in Robolectric).

## Owner list 2026-11-23: badges, action rows, icons, poster zoom

The owner found these on the iPhone app; the Android phone and TV apps were checked for the same defects.

- **Badges** (`ui-common/.../Badge.kt`): every badge has the height of one line of its text plus the padding, with or without an
  icon (an icon-only chip was a little shorter). A subtitles or audio badge with no languages ("Subtitles", «Субтитры») shows the
  icon and the word; with languages the icon stands for the word as before (`BadgeLabel.shown`). Applies to the phone and the TV
  (cards, detail pages) and to the badges of the choice step by step (phone); the TV lists a way's badges as one line of text.
- **Action rows** (phone, `ActionFlow`): the buttons of a title page's play row and of its watchlist/watched row keep their whole
  label on one line. `ActionFlow` measures each child's natural width and moves a child that does not fit to the next line; the
  children marked `grow()` share what is left of their line, and in the play row (`firstAlone`) the main button («Продолжить
  S1 E2») takes a line to itself when the buttons beside it do not fit at 360 dp. Before, the label had about 84 dp between
  the 50 dp icon buttons and was cut to an ellipsis. TV (`VideoActions`): the same row is a `FlowRow`: in Russian «Трейлер» ran off the right edge
  of the screen, now it goes to a second line.
- **Optical centring** (`IconOptics`, `opticalOffset`): a glyph centred in its 24-unit grid can still look off when its ink is
  lopsided. The shifts (grid units) are measured by `IconOpticsTest`, which draws the icons of the round buttons and compares
  them with 0.6 of the distance from the centre of their ink to the middle; an axis moves only where the icon's frame is centred
  (the play triangle and the chevrons are drawn toward their tip by hand). Moved: picture-in-picture (up and left), the audio
  bubble, subtitles, skip ahead, next, the channel list, the favourite star. Used by every round button of the phone
  (`GlassIconButton`, `RoundIconButton`, `KeepWayButton`) and the TV (`RoundIconButton`, `PlayerIconButton`). There is no
  cast/AirPlay button on Android.
- **Poster zoom** (phone, `PosterTransition.kt`): a tapped poster grows from its tile into the detail page's artwork and shrinks
  back on Back (Apple TV's open and close). Compose shared bounds (`SharedTransitionLayout` around the navigation host,
  `sharedBounds` on the tile's artwork and on the page's: the video page's backdrop, the cover of an album, book or playlist).
  The key is `poster/<shelf title>/<kind>:<id>:<tmdb id>`, so the same card in two shelves of a page has two keys; the tapped tile
  tells the page which one (`PosterTransitions.opened` / `take`, valid for a second). While a video page loads it draws the
  tile's picture in the artwork's place, so the zoom has something to land on. A detail page's fade is as long as the zoom
  (`POSTER_ZOOM_MS`). The artwork is drawn through a hardware layer: a test that draws the view on a software canvas turns the
  zoom off with `LocalPosterZoom provides false`. TV: a detail page zooms in from 0.9 with a fade and out again (no shared
  element: the TV's focus scaling and focus memory do not mix with it).
- **Not changed, checked**: Settings is already one screen on both apps with About last (phone: profile, server, playback,
  language, devices, About; TV: the same, About at the foot of the profile column); the choice of a way's quality is a wrap-height
  sheet with its note under the title (phone) and a side panel (TV); no page title stays on screen over scrolling content (the
  phone's tab headers, titles and back bars scroll away with their page and a scrim keeps the status bar clear; the TV's grid
  scrolls below the navigation and never under it).
- Tests: `BadgeLabelTest`, `IconOpticsTest`, `PhoneOwnerNov23UiTest` (labels on one line at 360 dp in English and Russian, badges of
  one size, the zoom's shared key and the artwork growing and shrinking; screenshots, the zoom's frames drawn through a hardware
  renderer (`RenderNode` + `HardwareRenderer`, which Robolectric's native graphics supports), to `mobile/build/screenshots/nov23-*.png`), `TvOwnerNov23UiTest` (the Russian row,
  the zoom frames; `tv/build/screenshots/nov23-tv-*.png`).
