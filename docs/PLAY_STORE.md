# Google Play listings

One Play app, one listing, as on the App Store (iPhone + Apple TV): package `app.antresol.mobile`, listing in
`mobile/fastlane/metadata/android/{en-US,ru-RU}/`.

| Build | Gradle module | Version code | Play tracks |
|---|---|---|---|
| Phone & tablet | `:mobile` | n | main tracks (internal, closed «Alpha», production) |
| Android TV | `:tv` | 1 000 000 + n | the same main tracks, beside the phone bundle (the app's form factors are one group «Phones, Tablets, Android TV, …»; there are no `tv:` tracks) |

The TV manifest requires `android.software.leanback`, the phone manifest requires a touchscreen (implicitly), so
each device can only get its own bundle. The older separate app `app.antresol.tv` (closed test only, never in
production) is no longer updated.

Each locale folder has `title.txt` (≤ 30 characters), `short_description.txt` (≤ 80), `full_description.txt`
(≤ 4000) and `changelogs/<versionCode>.txt` (≤ 500). The folders follow fastlane `supply`'s layout, so they can be
uploaded with `fastlane supply --metadata_path mobile/fastlane/metadata/android --package_name app.antresol.mobile`. `changelogs/1.txt` is the first release's text and `changelogs/2.txt` the same text for the first Play upload
(versionCode 2, see `antresolVersionCode`); CI builds get the run number as their
versionCode, so copy the text to `changelogs/<versionCode>.txt` (or `default.txt`) for the build you upload.
The copy follows the server README: the app needs the user's own Antresol server and provides no content.

## Graphics

All listing graphics are in the fastlane trees next to the text (`<app>/fastlane/metadata/android/<locale>/images/`),
so `fastlane supply` uploads them with the listing. They are rendered by `scripts/render-store-graphics.js`
(Playwright + Chromium; `NODE_PATH=<dir containing node_modules/playwright> node scripts/render-store-graphics.js`).
`--screenshots` renders only the screenshot posters and leaves the icon, feature graphic and TV banner alone;
`--offline` skips loading Inter from Google Fonts. Both locales (en-US, ru-RU) have:

- `icon.png` 512×512, 32-bit RGBA PNG, full-bleed square from `docs/design-ref/app-icon.svg` (Play applies the mask).
- `featureGraphic.png` 1024×500: brand gradient, logo mark, "Antresol" and the localized tagline, centred
  (en "Your films, music and books. One library.", ru "Твои фильмы. Твоя музыка. Твои книги. В одной библиотеке.").
- `phoneScreenshots/` — 8 posters 1080×1920; `tenInchScreenshots/` — 8 posters 2560×1600.
- `tvBanner.png` 1280×720 in the feature-graphic style; `tvScreenshots/` — 8 posters 1920×1080.

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

- The TV manifest requires `android.software.leanback`, declares `android.hardware.touchscreen` as not required, has a
  `LEANBACK_LAUNCHER` activity and the banner. Everything works with a D-pad; there is no touch-only UI.
- The Android TV form factor is opted in for `app.antresol.mobile` (Test and release → Advanced settings → Form
  factors). Play reviews TV releases separately from phone releases, so a TV rejection does not hold the phone app.
- Reviewers need a server to sign in to. Provide a reachable demo server, its address and a profile without a
  password in "App access", or the review fails at the server step.

## Content rating and category

- Category: Video Players & Editors. Content rating questionnaire: the app shows no content of its own; all
  media comes from the user's server (answer "no" to user-generated content shared with other users: profiles
  only see their own household's server).
- Target audience: 13+ is the simplest choice; kid profiles are a feature of the user's own server, the app is not
  designed for children.

## Data safety form

The answers cover the phone and TV builds.

- **Does your app collect or share any of the required user data types?** No.
  - The developer runs no server, analytics, crash reporting or advertising SDK. The apps contain no third-party
    SDK that sends data (dependencies: AndroidX, Jetpack Compose, Media3/ExoPlayer, CameraX, OkHttp, Coil,
    kotlinx.serialization/coroutines, ZXing core for QR codes; no Google Play services or ML Kit).
  - All network traffic goes only to the server address the user enters (their own Antresol server on their own
    hardware). Play's definition of "collection" covers data sent off the device to the developer or third
    parties; data sent to a server the user controls is not collected by the developer.
  - Finding servers on the home network: while the "Connect to your server" screen is open, the apps send a
    UDP probe (`antresol-discover`) to the local broadcast address and to the last server's private address,
    and browse mDNS (`_antresol._tcp`). This traffic stays on the local network (broadcast/multicast and
    private addresses only; the server answers only private sources) and carries no user data.
  - «Sign in a TV» (phone): the camera reads the TV's QR code on the device; no image is stored or sent. The
    code is then sent to the user's own server only.
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
  playback controls), `WAKE_LOCK` (playback), `CHANGE_WIFI_MULTICAST_STATE` (a Wi-Fi multicast lock while
  looking for servers on the home network; no location permission), `CAMERA` (phone only, asked on the
  «Sign in a TV» screen to read the TV's QR code; the camera is not required, the code can be typed).
