# Google Play listings

Two apps, two listings, one developer account:

| App | Package | Listing text |
|---|---|---|
| Phone & tablet | `app.antresol.mobile` | `mobile/fastlane/metadata/android/{en-US,ru-RU}/` |
| Android TV | `app.antresol.tv` | `tv/fastlane/metadata/android/{en-US,ru-RU}/` |

Each locale folder has `title.txt` (≤ 30 characters), `short_description.txt` (≤ 80), `full_description.txt`
(≤ 4000) and `changelogs/<versionCode>.txt` (≤ 500). The folders follow fastlane `supply`'s layout, so they can be
uploaded with `fastlane supply --metadata_path mobile/fastlane/metadata/android --package_name app.antresol.mobile`
(and the same for `tv`). `changelogs/1.txt` is the first release's text and `changelogs/2.txt` the same text for the first Play upload
(versionCode 2, see `antresolVersionCode`); CI builds get the run number as their
versionCode, so copy the text to `changelogs/<versionCode>.txt` (or `default.txt`) for the build you upload.
The copy follows the server README: the app needs the user's own Antresol server and provides no content.

## Graphics

All listing graphics are in the fastlane trees next to the text (`<app>/fastlane/metadata/android/<locale>/images/`),
so `fastlane supply` uploads them with the listing. They are rendered by `scripts/render-store-graphics.js`
(Playwright + Chromium; `NODE_PATH=<dir containing node_modules/playwright> node scripts/render-store-graphics.js`).
`--screenshots` renders only the screenshot posters and leaves the icon, feature graphic and TV banner alone;
`--offline` skips loading Inter from Google Fonts. Both locales (en-US, ru-RU) of both apps have:

- `icon.png` 512×512, 32-bit RGBA PNG, full-bleed square from `docs/design-ref/app-icon.svg` (Play applies the mask).
- `featureGraphic.png` 1024×500: brand gradient, logo mark, "Antresol" and the localized tagline, centred
  (en "Your films, music and books. One library.", ru "Твои фильмы. Твоя музыка. Твои книги. В одной библиотеке.").
- **Phone app**: `phoneScreenshots/` — 8 posters 1080×1920; `tenInchScreenshots/` — 8 posters 2560×1600.
- **TV app**: `tvBanner.png` 1280×720 in the feature-graphic style; `tvScreenshots/` — 8 posters 1920×1080.

### Screenshot posters

Each poster is the brand gradient, a short English caption and a real capture inside a device frame drawn in CSS
(no third-party device art): a phone with thin bezel, concentric rounded corners, punch-hole camera and side buttons
(turned to landscape for the video player), a tablet with a wider bezel and a camera in the long edge, and a TV with
a slim bezel on a small stand. The same English posters go to en-US and ru-RU (the app UI in them is English).
Sizes are inside Play's limits (each side 320–3840 px, long side ≤ 2× short side, < 8 MB).

The captures are in `docs/store-screenshots/{phone,tablet,tv}/` (JPEG): home, film, series, player (paused, with
controls), album, now playing, books and search. They come from emulators (API 36: Pixel 8 1080×2400, Pixel Tablet
2560×1600, Android TV 1080p) running the debug builds signed in to a demo server with open-licensed content (Blender
open movies, Creative Commons music, public-domain books), with the demo status bar (9:41, full battery, Wi-Fi).
None shows the server address, the sign-in screen or Settings. To refresh them: sign in on the emulators, drive the
app with `adb shell input`, `adb exec-out screencap -p`, save the picks under the same names, then re-run the script
with `--screenshots`. Captions and order are the `SHOTS` table at the top of the script. No 7" tablet set is made;
Play accepts the 10" set for tablets.

## Android TV review notes

- The manifest declares `android.software.leanback` and `android.hardware.touchscreen` as not required, has a
  `LEANBACK_LAUNCHER` activity and the banner. Everything works with a D-pad; there is no touch-only UI.
- The TV app is a separate package (`app.antresol.tv`), so TV review and phone review are independent. Opt in to
  the Android TV form factor in Play Console (Advanced settings → Form factors) for the TV app only.
- Reviewers need a server to sign in to. Provide a reachable demo server, its address and a profile without a
  password in "App access", or the review fails at the server step.

## Content rating and category

- Category: Video Players & Editors. Content rating questionnaire: the app shows no content of its own; all
  media comes from the user's server (answer "no" to user-generated content shared with other users: profiles
  only see their own household's server).
- Target audience: 13+ is the simplest choice; kid profiles are a feature of the user's own server, the app is not
  designed for children.

## Data safety form

The answers are the same for both apps.

- **Does your app collect or share any of the required user data types?** No.
  - The developer runs no server, analytics, crash reporting or advertising SDK. The apps contain no third-party
    SDK that sends data (dependencies: AndroidX, Jetpack Compose, Media3/ExoPlayer, OkHttp, Coil,
    kotlinx.serialization/coroutines).
  - All network traffic goes only to the server address the user enters (their own Antresol server on their own
    hardware). Play's definition of "collection" covers data sent off the device to the developer or third
    parties; data sent to a server the user controls is not collected by the developer.
  - Stored on the device only: the server address, the device id, sign-in tokens (encrypted with an Android
    Keystore key), app settings, an image cache and downloaded PDF/subtitle cache files.
- **Is all of the user data collected by your app encrypted in transit?** Not applicable (nothing is
  collected). Note: the connection to the user's server is plain `http` unless the user sets up `https`
  (the apps allow cleartext because home servers on a LAN usually have no certificate).
- **Do you provide a way for users to request that their data be deleted?** Not applicable. Signing out deletes the
  token from the device and the server; uninstalling removes everything else. Account data lives on the user's
  server and is managed there.
- **Privacy policy URL**: https://sidun-av.github.io/antresol/privacy.html
- Permissions to explain if asked: `INTERNET` (the user's server), `FOREGROUND_SERVICE` /
  `FOREGROUND_SERVICE_MEDIA_PLAYBACK` (music and audiobooks in the background), `POST_NOTIFICATIONS` (phone,
  playback controls), `WAKE_LOCK` (playback).
