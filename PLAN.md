# Antresol for Android & Android TV — plan

Native Kotlin clients for the self-hosted Antresol server (v0.3.0+), one Gradle project:

- `:core` — shared API client (OkHttp + kotlinx.serialization), models, auth (token via
  `/api/auth/token`), session store, playback/progress logic. Pure JVM, unit-tested.
- `:ui-common` — shared Compose theme (Antresol black + purple gradient), image loading (Coil), formatters.
- `:tv` — Android TV app: Compose for TV (`androidx.tv.material3`), Leanback launcher banner,
  Media3 ExoPlayer (HLS), D-pad focus first. Priority #2.
- `:mobile` — phone/tablet app: Compose Material 3, bottom navigation, Media3 MediaSessionService
  for background music & audiobooks, offline downloads (later). Priority #3.

Design follows the existing Antresol iOS/web apps: pure black, white type, pill navigation,
artwork-tinted hero, white "Play" pill, rounded poster cards with badges, brand gradient #3a1c8c → #9b2fc9.

## Build
    ./gradlew :tv:assembleDebug :mobile:assembleDebug
