# Antresol — Android & Android TV

Native Android and Android TV apps for the self-hosted
[Antresol](https://github.com/sidun-av/antresol) media server. See [PLAN.md](PLAN.md).

## Building

    ./gradlew :core:test assembleDebug                      # unit tests + both apps
    ANTRESOL_TEST_SERVER=1 ./gradlew :core:test :mobile:testDebugUnitTest   # plus live tests against http://127.0.0.1:8420

Create `local.properties` with `sdk.dir=<Android SDK>` in a fresh clone. JDK 17+.

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
