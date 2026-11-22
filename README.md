# Antresol — Android & Android TV

Native Android and Android TV apps for the self-hosted
[Antresol](https://github.com/antresol-app/antresol) media server. See [PLAN.md](PLAN.md).

## Install

One app for phones, tablets and Android TV, in closed testing on Google Play:

1. Join the [antresol-test](https://groups.google.com/g/antresol-test) group.
2. [Become a tester](https://play.google.com/apps/testing/app.antresol.mobile).
3. Install Antresol from Google Play (on a TV: from its Play Store, signed in with the same account).

## Building

    ./gradlew :core:test :ui-common:testDebugUnitTest :tv:testDebugUnitTest :mobile:testDebugUnitTest assembleDebug   # unit tests + both apps (what CI runs)
    ANTRESOL_TEST_SERVER=1 ./gradlew :core:test :tv:testDebugUnitTest :mobile:testDebugUnitTest   # plus live tests against http://127.0.0.1:8420

    ./gradlew :tv:assembleRelease :mobile:assembleRelease :tv:bundleRelease :mobile:bundleRelease   # release APKs/AABs
    scripts/update-screenshots.sh   # regenerate docs/screenshots from the live UI tests

There are no instrumented tests (no `androidTest` source set): the UI tests are Robolectric and run inside
the unit-test tasks above. To drive an app by hand without a server, start `scripts/fake-server.py`
(Python 3, nothing to install; `--help` for the options) and type its address on the app's server screen:
`http://10.0.2.2:8421` in an emulator, `--host 0.0.0.0` and the computer's address for a device (its port
is 8421, not the 8420 the live tests above look for a real server on). It plays
the small library of the tests' `FakeServer` (`--addons` adds an addon with the ways protocol) plus live channels with a programme guide cut into pages as
the server cuts them (`--rebuild-after 1` rebuilds the schedule between the first two pages, as the server
may); debug and release builds both accept it (plain HTTP is allowed).

Create `local.properties` with `sdk.dir=<Android SDK>` in a fresh clone. JDK 17+. Release signing, versioning and CI:
[docs/RELEASE.md](docs/RELEASE.md). Store listings: [docs/PLAY_STORE.md](docs/PLAY_STORE.md). Screenshots:
[docs/screenshots](docs/screenshots/README.md).

## Android TV app (`:tv`)

Play package `app.antresol.mobile` (the same listing as the phone app, delivered to TVs from Play's
Android TV track; the code namespace is `app.antresol.tv`): Compose for TV, D-pad first, English and Russian. It shows in the Leanback
launcher (banner) and also runs on Google TV and other Android TV devices (minSdk 26).

- **Onboarding by phone**: the first screen is «Set Up with Your Phone», a QR code that the Antresol
  phone app (or the phone camera, which opens a small form served by the TV) scans to tell the TV which
  server to use and sign it in; the TV then lands on Home without typing anything. The remote fallbacks
  sit next to it: Antresol servers found on the home network (UDP probe + mDNS, each confirmed with
  `/api/health`) and the typed address (a bare host gets port 8420). On a known server the default is
  «Sign In with Your Phone» (a QR and a 6-character code a signed-in phone approves; the TV gets a
  normal device token; the code renews itself every 5 minutes), with «Sign In with the Remote» (profile
  picker, password step) as the fallback. The session is restored on launch.
- **Screen size**: every size is drawn for a 960×540 dp canvas. A TV reports its own density (a 1080p one is often
  tvdpi, so 1440×810 dp), so the app normalises it once at its root (`DesignCanvas`, `ui/components`): 960 dp is the
  window's width (or its height, on a screen taller than 16:9) at any density, and the page is laid out the same.
  A screen wider than 16:9 has more canvas width, so new layouts use weights and fractions of the width (as the TV
  page's panel and card do), not widths worked out from 960. Use `canvasHeight()`, not the configuration's
  `screenHeightDp`, for a height taken from the screen.
- **Shell**: a centred glass pill (Search, Home, Movies, Shows, TV, Music), the now-playing chip
  while music or an audiobook is loaded, and the avatar, which opens Settings. Down from the pills
  enters the page and returns to the element that was focused there. A tab left for another one is
  back at its root (top, first element) when the user returns after more than 5 s; within 5 s it is
  as it was. There are no books on the TV (as on Apple TV): no Books tab, and Home and Search leave
  book results out.
- **Home**: an artwork-tinted hero (eyebrow line, huge title, white Play and glass Details pills),
  Continue Watching and every server shelf.
- **Browse**: Movies and Shows grids with sort pills (they scroll below the nav, never under it);
  Music (All Albums, Artists, Songs, Playlists, Shuffle All, genre chips, shelves); album and artist
  grids; debounced Search. Watched movies, episodes and series (and finished books, audiobooks and comic volumes) are dimmed with an eye and show no progress bar.
- **Video**: detail pages with Play/Resume, watchlist and watched toggles, «Trailer» (played inside the app: in its own
  player when the server fetches it, else in the embedded YouTube player; a chooser when there
  are several), the cast with photos, seasons and episodes, each
  with its own still (with the server's episode guide every episode shows: ones not in the library dimmed, ones not
  aired yet greyed with their air date; only library episodes play). The
  ExoPlayer player sends the device's codec caps (direct play, or HLS remux/transcode), has audio,
  subtitle and quality menus, keeps the HLS session alive, saves progress every 10 s and counts
  down to the next episode.
- **Addon ways**: what the server's addons offer for a movie or series (`x/app`, [docs/API.md](docs/API.md#13-addon-ways)):
  a title nothing of the library plays has «Play» as its main button, which runs the way chosen for that title (the
  first one until a choice is made); the chevron beside it (or hold OK on Play) opens the menu of the ways with a short
  line each and the default ticked, where a pick runs that way and makes it the title's default, and «Audio & quality…»
  chooses by hand, step by step, with the ticked way. A way that keeps a copy on the server is a button with a progress
  ring and «Cancel», and the ways that run for an episode are on the episode (OK on one that is not in the library
  plays it by the title's way, hold OK opens the same menu with its page). Only the button that was pressed shows
  «Opening…». The player of an addon's stream lists the qualities, subtitles and audio its answer gives (else the
  stream's own tracks) and ends the audio menu with «Audio & source…», the way's own choice from where the video is.
  A title off the library, found by an addon's search row in Search, has a page of its own. Nothing shows without
  addons, nor for a kid profile.
- **TV (live channels)**: one numbered list of the server's channels (own and external, in channel
  number order) on a panel (the left ~46% of the page, its full height) beside a card of the focused
  channel's programme (its still across the card, title, episode, time and progress, overview, what is next,
  the keys; a few channels make tall rows with the programme's still, many make compact rows), or the
  programme guide («Channels | Guide»); focus starts on the channel this profile watched last. The category
  button next to the view switch («All Channels · 842 ⌄»; a playlist can have dozens of groups) opens a list on the left (All, Favourites, then the
  server's categories with their channel counts: its AI topics first, the sources, the playlists'
  groups, under headings when there is more than one kind; an older server's `category` texts
  without it), as does Left from the first column of the list or the guide, and narrows both to the
  category. The phone's filter pills show the same categories in the same order. OK opens the full-screen live player (live edge, no seeking): Up/Down, Channel +/− and Page
  Up/Down switch to the next/previous channel by number (wrapping), holding the last frame until the
  new channel draws; the channel banner slides in on every switch and on OK. OK with the banner up
  opens the mini-guide, a row of the category's channels starting at the one on; Back hides the
  guide, then the banner, then leaves. The media session reports the live programme. A starting
  channel is retried automatically; other server refusals are shown with Try Again.
- **Autostart** (Settings, off by default): «Open Antresol at power on» brings the app to the front when the TV or
  box starts and (optionally) when the screen comes back on after standby, to where it was left, the TV tab or the
  last channel playing. Android 10+ lets an app start its screen from the background only with «Display over other
  apps», so the setting guides to it (the system page, or the adb command on a TV without one); a small foreground
  service listens for the screen. Notes for Play: [docs/PLAY_STORE.md](docs/PLAY_STORE.md).
- **Android TV home screen**: Watch Next (the system's «Play Next» row) shows this profile's started movies and
  episodes with a progress bar, and the next episode of a series; one preview channel «Antresol» («Новое в
  медиатеке») shows the recent movies and new series. Both come from the server's `GET /api/home` (the lists the app's
  own Home shows, so a kid profile gets what the server gives it and «Отдельная история» is already resolved), the
  length for the progress bar from `GET /api/progress/{id}`. A tile opens `antresol://play/{id}?u={profile}` (plays
  from where the profile stopped) or `antresol://title/{kind}/{id}?u={profile}` (the title's page); the same
  addresses work from `adb shell am start -a android.intent.action.VIEW -d "antresol://play/19?u=1&n=<nonce>"`, where
  `<nonce>` is this install's random link string (the activity ignores links without it; on a debuggable build:
  `adb shell run-as app.antresol.mobile cat shared_prefs/tv_home_channels.xml`, the `link_nonce` entry). They are
  refreshed when the app opens, after a player closes, when the profile changes or signs out, and every 30 minutes
  by WorkManager; nothing is asked or scheduled while nobody is signed in, and the tiles of the profile that left
  are removed. The launcher cannot send the server's login, so the pictures are fetched by the app, shrunk, and
  served to it by a read-only content provider (`{package}.homeart`). The first time the channel exists, the system
  asks once to add it to the home screen. Code: `tv/.../homechannels/`.
- **Music**: album, artist and playlist pages with Play, Shuffle, Favorite and the track list. A
  Media3 `MediaSessionService` keeps music playing while you browse or leave the app and publishes
  it to the system's now-playing card and remote. Now Playing has an artwork-tinted background,
  shuffle/repeat, synced lyrics that follow the song, Up Next, favorite and a sleep timer.
  Transcoded streams seek by reloading at the new time; plays are scrobbled after 30 s.
- **Audiobooks**: Listen/Resume (whole-book position across parts), chapters, back 15 s / forward
  30 s, previous/next chapter, speed 0.75×–3×, sleep timer (minutes or end of chapter), and
  progress synced to the server every 10 s, on pause and on stop.
- **Comics**: a full-screen reader that turns pages with Left/Right (swapped for right-to-left
  manga), loads the next pages in advance with the Bearer header, saves the page and offers the
  next volume at the end. E-books show that they can be read in the web app or on a phone.
- **Settings**: switch profile, sign out, default video quality, subtitle language turned on
  automatically, app language (System / English / Русский), app version. Server facts and devices
  are not shown on the TV.

Live UI tests (`TvLiveUiTest`, Robolectric with D-pad key events) write screenshots to
`tv/build/screenshots/`.

## Phone & tablet app (`:mobile`)

`app.antresol.mobile`: Compose Material 3, dark only, edge to edge, English and Russian.

- **Onboarding**: servers found on the home network (UDP probe + mDNS) above the address field, then
  the profile picker. Profiles with a password ask for it.
- **Browse**: Home hero and shelves, Movies, Shows, Music (albums, songs, artists, playlists,
  genres, Shuffle All) and Books (books, audiobooks, manga & comics). There are detail pages for
  every kind of item, plus Search.
- **Video**: a landscape player with picture-in-picture, double-tap to skip, and audio, subtitle
  and quality menus. It uses direct play when the phone can decode the file, and HLS remux or
  transcode when it can't. Progress is saved every 10 s, and the next episode starts after a
  countdown. A series page lists every episode of the server's guide, marked downloaded or not;
  ones not aired yet are greyed with their air date.
- **Addon ways**: what the server's addons offer for a movie or series (`x/app`, [docs/API.md](docs/API.md#13-addon-ways)):
  a chevron beside Play opens the ways to watch (the first one is the main button when nothing of the library plays),
  a way that keeps a copy on the server is a round button with a progress ring and «Cancel», and a long press on an
  episode lists the ways that run for it. A tap on a way to watch runs it at once; a long press chooses by hand, step
  by step, in a bottom sheet; a tap on a way that keeps a copy always opens that choice. Search adds a section per addon after the library's results; a title off the library opens a page made
  from `/api/title`. Nothing shows without addons, nor for a kid profile.
- **TV (live channels)**: «On Air Now» on Home (after the first shelf; the tab bar keeps five tabs, as
  on the web) opens the TV page: «Continue» on top, section pills and channel cards. The live player
  is a landscape activity; channel up/down buttons and vertical swipes switch channels without a
  black frame, a tap shows the banner with the strip of the section's channels. Leaving the app
  while a channel plays shrinks it to picture-in-picture; the media session reports it as live.
- **Music & audiobooks**: a Media3 `MediaSessionService` handles background playback, the
  notification and lock-screen controls. A mini-player sits above the tab bar. Now Playing has
  synced lyrics, the queue, shuffle/repeat, star and a sleep timer. Audiobooks also get chapters,
  15/30 s skips and speeds from 0.75× to 3×, and their whole-book progress is synced.
- **Readers**: comics and manga (swipe in the reading direction, pinch or double tap to zoom) and
  PDF books. Both save the page to the server. EPUB/FB2 reading is not built yet.
- **Settings**: switch profile, sign out, server address and version, «Sign In a TV» (scan the TV's QR
  code with the camera or type its code, see the device, pick the profile as an admin, Approve or
  Decline; a TV that has no server yet is set up from the same scanner: «Set Up a TV» sends it this
  phone's server and approves its sign-in), default video quality, autoplay, app language (System / English / Russian) and signed-in
  devices.
