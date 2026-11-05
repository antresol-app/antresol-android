#!/usr/bin/env bash
# Regenerates the screenshot gallery in docs/screenshots from the Robolectric live UI tests.
#
#   scripts/update-screenshots.sh              # run the live UI tests against ANTRESOL_TEST_SERVER (default 1 =
#                                              # http://127.0.0.1:8420), then copy the shots
#   scripts/update-screenshots.sh --copy-only  # copy shots from an earlier test run
#
# The tests write every shot to {tv,mobile}/build/screenshots; the tables below pick the gallery ones and give them
# stable names (docs/screenshots/README.md shows them).
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ "${1:-}" != "--copy-only" ]]; then
  rm -rf tv/build/screenshots mobile/build/screenshots
  ANTRESOL_TEST_SERVER="${ANTRESOL_TEST_SERVER:-1}" ./gradlew --no-daemon \
    :tv:testDebugUnitTest --rerun --tests '*TvLiveUiTest' :mobile:testDebugUnitTest --rerun --tests '*LiveUiTest'
fi

copy() { # <module> <gallery dir> <pairs "gallery-name:test-shot" ...>
  local module=$1 dir=$2; shift 2
  rm -rf "docs/screenshots/$dir"; mkdir -p "docs/screenshots/$dir"
  for pair in "$@"; do
    cp "$module/build/screenshots/${pair#*:}.png" "docs/screenshots/$dir/${pair%%:*}.png"
  done
}

copy tv tv \
  01-server:00-server 02-profiles:01-profiles 03-password:01b-password 04-home:02-home \
  05-home-shelves:03-home-shelf 06-movies:05-movies 07-movie-detail:11-movie-detail \
  08-series-detail:13-series-detail 09-player:12-player 10-episode-player:14-episode-player \
  11-music:20-music 12-album:21-album 13-now-playing:22-now-playing 14-books:24-books \
  15-audiobook:25-audiobook 16-audiobook-playing:26-audiobook-playing 17-search:10-search \
  18-settings:29-settings 19-settings-quality:30-settings-quality 20-add-to-playlist:40-add-to-playlist

copy mobile mobile/phone \
  01-server:phone-00-server 02-profiles:phone-01-profiles 03-password:phone-02-password 04-home:phone-03-home \
  05-home-shelves:phone-04-home-shelves 06-movies:phone-05-movies 07-movie-detail:phone-06-movie-detail \
  08-series-detail:phone-07-series-detail 09-player:play-01-video-controls 10-player-quality:play-02-video-quality \
  11-music:play-10-music 12-albums:phone-09-albums 13-album:play-11-album-mini-player 14-now-playing:play-12-now-playing \
  15-queue:play-13-queue 16-books:phone-10-books 17-audiobook:play-20-audiobook-detail \
  18-audiobook-playing:play-21-audiobook-now-playing 19-chapters:play-22-chapters 20-search:phone-11-search-browse \
  21-search-results:phone-12-search-results 22-account:phone-13-account 23-settings:play-40-settings \
  24-playlist-editing:phone-22-playlist-editing

copy mobile mobile/tablet \
  01-profiles:tablet-00-profiles 02-home:tablet-01-home 03-movies:tablet-02-movies 04-movie-detail:tablet-03-detail \
  05-series-detail:tablet-04-series-detail 06-player:tablet-20-video-controls 07-player-quality:tablet-21-video-quality \
  08-music:tablet-05-music 09-albums:tablet-06-albums 10-album:tablet-10-album 11-now-playing:tablet-11-now-playing \
  12-books:tablet-07-books 13-audiobook:tablet-12-audiobook 14-search:tablet-08-search 15-settings:tablet-13-settings

echo "Gallery updated: $(find docs/screenshots -name '*.png' | wc -l) screenshots."
