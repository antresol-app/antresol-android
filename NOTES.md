# Hand-off notes

Running log between milestones. Newest entries at the bottom.

## Milestone 1 — Gradle scaffold + `:core` API client

### Layout
- `settings.gradle.kts`: `:core`, `:ui-common`, `:tv`, `:mobile`. Versions in `gradle/libs.versions.toml`
  (AGP 8.7.3, Kotlin 2.1.0 + compose plugin, Compose BOM 2024.12.01, tv-material 1.0.0, media3 1.5.1,
  OkHttp 4.12, kotlinx-serialization 1.7.3, coroutines 1.9.0, Coil 2.7.0, navigation 2.8.5, lifecycle 2.8.7,
  DataStore 1.1.1). Wrapper: Gradle 8.14.3. Java/Kotlin target 17 (no `jvmToolchain`, the container has JDK 21 only).
- minSdk 26 for all Android modules (adaptive icons only, no legacy PNG launcher icons), compile/target 35.
- CI: `.github/workflows/ci.yml` runs `./gradlew :core:test assembleDebug` on JDK 17.
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
