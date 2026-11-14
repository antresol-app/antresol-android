# Antresol — Android & Android TV

Native Android and Android TV apps for the self-hosted
[Antresol](https://github.com/sidun-av/antresol) media server. See [PLAN.md](PLAN.md).

## Building

    ./gradlew :core:test :tv:testDebugUnitTest :mobile:testDebugUnitTest assembleDebug   # unit tests + both apps
    ANTRESOL_TEST_SERVER=1 ./gradlew :core:test :tv:testDebugUnitTest :mobile:testDebugUnitTest   # plus live tests against http://127.0.0.1:8420

    ./gradlew :tv:assembleRelease :mobile:assembleRelease :tv:bundleRelease :mobile:bundleRelease   # release APKs/AABs
    scripts/update-screenshots.sh   # regenerate docs/screenshots from the live UI tests

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
- **Shell**: a centred glass pill (Search, Home, Movies, TV Shows, TV, Music, Books), the now-playing chip
  while music or an audiobook is loaded, and the avatar, which opens Settings. Down from the pills
  enters the page and returns to the element that was focused there.
- **Home**: an artwork-tinted hero (eyebrow line, huge title, white Play and glass Details pills),
  Continue Watching and every server shelf.
- **Browse**: Movies and TV Shows grids with sort pills; Music (All Albums, Artists, Songs,
  Playlists, Shuffle All, genre chips, shelves); Books (All Books, Audiobooks, Manga & Comics,
  shelves); album, artist and book grids; debounced Search.
- **Video**: detail pages with Play/Resume, watchlist and watched toggles, seasons and episodes
  (with the server's episode guide every episode shows: ones not in the library dimmed, ones not
  aired yet greyed with their air date; only library episodes play). The
  ExoPlayer player sends the device's codec caps (direct play, or HLS remux/transcode), has audio,
  subtitle and quality menus, keeps the HLS session alive, saves progress every 10 s and counts
  down to the next episode.
- **TV (live channels)**: «Continue» (the channel this profile last watched on this TV) leads the
  page, then the server's channels by section («All» shows every section as a row, a section pill
  shows only its channels); each card shows the programme on air with its live progress and what is
  next. OK opens the full-screen live player (live edge, no seeking): Up/Down, Channel +/− and Page
  Up/Down switch to the next/previous channel by number (wrapping), holding the last frame until the
  new channel draws; the channel banner slides in on every switch and on OK. OK with the banner up
  opens the mini-guide, a row of the section's channels starting at the one on; Back hides the
  guide, then the banner, then leaves. The media session reports the live programme. A starting
  channel is retried automatically; other server refusals are shown with Try Again.
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
  automatically, app language (System / English / Русский), server address, version and library
  counts, change server, app version.

Live UI tests (`TvLiveUiTest`, Robolectric with D-pad key events) write screenshots to
`tv/build/screenshots/`.

## Phone & tablet app (`:mobile`)

`app.antresol.mobile`: Compose Material 3, dark only, edge to edge, English and Russian.

- **Onboarding**: servers found on the home network (UDP probe + mDNS) above the address field, then
  the profile picker. Profiles with a password ask for it.
- **Browse**: Home hero and shelves, Movies, TV Shows, Music (albums, songs, artists, playlists,
  genres, Shuffle All) and Books (books, audiobooks, manga & comics). There are detail pages for
  every kind of item, plus Search.
- **Video**: a landscape player with picture-in-picture, double-tap to skip, and audio, subtitle
  and quality menus. It uses direct play when the phone can decode the file, and HLS remux or
  transcode when it can't. Progress is saved every 10 s, and the next episode starts after a
  countdown. A series page lists every episode of the server's guide, marked downloaded or not;
  ones not aired yet are greyed with their air date.
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
