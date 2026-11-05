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

`app.antresol.tv`: Compose for TV, D-pad first, English and Russian. It shows in the Leanback
launcher (banner) and also runs on Google TV and other Android TV devices (minSdk 26).

- **Onboarding**: server address (a bare host gets port 8420), profile picker with gradient avatars
  and a password step for protected profiles. The session is restored on launch.
- **Shell**: a centred glass pill (Search, Home, Movies, TV Shows, Music, Books), the now-playing chip
  while music or an audiobook is loaded, and the avatar, which opens Settings. Down from the pills
  enters the page and returns to the element that was focused there.
- **Home**: an artwork-tinted hero (eyebrow line, huge title, white Play and glass Details pills),
  Continue Watching and every server shelf.
- **Browse**: Movies and TV Shows grids with sort pills; Music (All Albums, Artists, Songs,
  Playlists, Shuffle All, genre chips, shelves); Books (All Books, Audiobooks, Manga & Comics,
  shelves); album, artist and book grids; debounced Search.
- **Video**: detail pages with Play/Resume, watchlist and watched toggles, seasons and episodes. The
  ExoPlayer player sends the device's codec caps (direct play, or HLS remux/transcode), has audio,
  subtitle and quality menus, keeps the HLS session alive, saves progress every 10 s and counts
  down to the next episode.
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

- **Onboarding**: server address, then the profile picker. Profiles with a password ask for it.
- **Browse**: Home hero and shelves, Movies, TV Shows, Music (albums, songs, artists, playlists,
  genres, Shuffle All) and Books (books, audiobooks, manga & comics). There are detail pages for
  every kind of item, plus Search.
- **Video**: a landscape player with picture-in-picture, double-tap to skip, and audio, subtitle
  and quality menus. It uses direct play when the phone can decode the file, and HLS remux or
  transcode when it can't. Progress is saved every 10 s, and the next episode starts after a
  countdown.
- **Music & audiobooks**: a Media3 `MediaSessionService` handles background playback, the
  notification and lock-screen controls. A mini-player sits above the tab bar. Now Playing has
  synced lyrics, the queue, shuffle/repeat, star and a sleep timer. Audiobooks also get chapters,
  15/30 s skips and speeds from 0.75× to 3×, and their whole-book progress is synced.
- **Readers**: comics and manga (swipe in the reading direction, pinch or double tap to zoom) and
  PDF books. Both save the page to the server. EPUB/FB2 reading is not built yet.
- **Settings**: switch profile, sign out, server address and version, default video quality,
  autoplay, app language (System / English / Russian) and signed-in devices.
