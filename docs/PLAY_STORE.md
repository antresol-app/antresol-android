# Google Play listings

One Play app, one listing, as on the App Store (iPhone + Apple TV): package `app.antresol.mobile`, listing in
`mobile/fastlane/metadata/android/{en-US,ru-RU}/`.

| Build | Gradle module | Version code | Play tracks |
|---|---|---|---|
| Phone & tablet | `:mobile` | n | main tracks (internal, closed «Alpha», production) |
| Android TV | `:tv` | 1 000 000 + n | the same main tracks, beside the phone bundle (the app's form factors are one group «Phones, Tablets, Android TV, …»; there are no `tv:` tracks) |

The TV manifest requires `android.software.leanback`, the phone manifest requires a touchscreen (implicitly), so
each device can only get its own bundle.

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

## TV home screen (Watch Next, the «Antresol» channel): permissions

The TV build puts this profile's «Continue Watching» into the system's Watch Next row and keeps one preview channel
«Antresol» («Новое в медиатеке», recent movies and new series) on the home screen of Google TV and Android TV. The
merged TV manifest gains two permissions, both normal (granted at install, no runtime prompt, **no Play Console
declaration**), and no foreground service of any type:

- `com.android.providers.tv.permission.WRITE_EPG_DATA`, declared by androidx.tvprovider itself: the TV provider
  enforces it on writes of channels and programs. (The library also declares the deprecated `READ_EPG_DATA`; the TV
  manifest removes it.)
- `android.permission.ACCESS_NETWORK_STATE`, from WorkManager, for the 30-minute refresh that runs only with a network.

WorkManager's `SystemForegroundService` is in the merged manifest without a `foregroundServiceType` and is never
started (the sync is a short periodic job). The app also exposes a read-only content provider (`{package}.homeart`)
that gives the launcher the cached posters, and `antresol://play/{id}` / `antresol://title/{kind}/{id}` deep links
(not browsable). Data safety: nothing new is collected or sent; the posters are the user's own server's pictures,
copied to the app's cache on the device.

## TV autostart: permissions and Play Console declarations

The TV build has «Open Antresol at power on» in Settings (off by default; Russian «Открывать Antresol при включении»).
It opens the app after the TV or box starts and, if asked, when the screen comes back on after standby. It needs three
more permissions, all of them only in the TV manifest (the phone app is unchanged):

- `RECEIVE_BOOT_COMPLETED`: normal permission, hears that the device has started.
- `SYSTEM_ALERT_WINDOW` («Display over other apps»): the one exemption that lets an app in the background start an
  activity on Android 10+ (a boot receiver or a service has none). The app draws no overlay. It is not on Play's
  restricted-permission list (no declaration form) but the user must grant it in the system settings; Settings shows
  its state, guides to the system page and, for a TV without the page, prints the adb command. Without it, nothing is
  launched and no service runs. The setting says why in the UI text; say the same if asked.
- `FOREGROUND_SERVICE_SPECIAL_USE` for the service that hears the screen come on (`ACTION_SCREEN_ON` cannot be declared
  in a manifest, it needs a running process, and a cached TV app process is killed at will). The manifest carries the
  `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` text. In the Console, App content → Foreground service permissions →
  «Special use»:
  - Description: «An optional setting the user switches on. While it is on, the app keeps a minimal foreground
    service with a low-importance notification that listens for the screen turning on, and opens the app when the TV
    or box wakes from standby. It does nothing else: no network, no media, no data collection.»
  - If the task were interrupted: the app would not open by itself after standby; the user opens it by hand.
  - Demo video: Settings → switch «Open Antresol at power on» on → allow «Display over other apps» → send the TV to
    standby and wake it → Antresol opens (an emulator or a TV, ~30 s).
  The service runs only while the setting is on and the permission is granted; with «Also after standby» off it
  runs just for the launch after a boot and stops.

Android 15+ restrictions that were checked against the docs: a `BOOT_COMPLETED` receiver may not start foreground
services of the types data sync, camera, media playback, phone call, media projection or microphone (special use is
fine), and an app that holds `SYSTEM_ALERT_WINDOW` needs a visible overlay to start a foreground service from the
background but not to start an activity. The app starts the service from the background only in the boot and
app-update broadcasts (both exempt) and otherwise from its own screens.
