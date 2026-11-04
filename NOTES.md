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
