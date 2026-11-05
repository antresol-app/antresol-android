# Google Play listings

Two apps, two listings, one developer account:

| App | Package | Listing text |
|---|---|---|
| Phone & tablet | `app.antresol.mobile` | `mobile/fastlane/metadata/android/{en-US,ru-RU}/` |
| Android TV | `app.antresol.tv` | `tv/fastlane/metadata/android/{en-US,ru-RU}/` |

Each locale folder has `title.txt` (≤ 30 characters), `short_description.txt` (≤ 80), `full_description.txt`
(≤ 4000) and `changelogs/<versionCode>.txt` (≤ 500). The folders follow fastlane `supply`'s layout, so they can be
uploaded with `fastlane supply --metadata_path mobile/fastlane/metadata/android --package_name app.antresol.mobile`
(and the same for `tv`). `changelogs/1.txt` is the first release's text; CI builds get the run number as their
versionCode, so copy the text to `changelogs/<versionCode>.txt` (or `default.txt`) for the build you upload.
The copy follows the server README: the app needs the user's own Antresol server and provides no content.

## Graphics still to make

- **Both**: 512×512 icon (from `docs/design-ref/logo-mark.svg` on the brand gradient, like the launcher icon),
  1024×500 feature graphic.
- **Phone**: at least 2 phone screenshots; 7" and 10" tablet screenshots. Candidates from the Robolectric gallery:
  `docs/screenshots/mobile/phone-*.png` (1080×2400) and `docs/screenshots/mobile/tablet-*.png` (2048×1536).
- **TV**: Play requires TV screenshots (1920×1080) and a TV banner (1280×720). The app already has its leanback
  banner (`tv/src/main/res/drawable-xhdpi/tv_banner.png`, 320×180, set as `android:banner`); export a 1280×720
  version of it for the listing. Candidates: `docs/screenshots/tv/*.png` (1920×1080). Replace the gallery shots with
  shots from a real device before publishing if possible: the Robolectric shots use the test server's library,
  which has no posters.

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
