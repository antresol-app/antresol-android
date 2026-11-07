# Releasing the Android apps

## Release builds

    ./gradlew :tv:assembleRelease :mobile:assembleRelease   # APKs:  {tv,mobile}/build/outputs/apk/release/
    ./gradlew :tv:bundleRelease :mobile:bundleRelease       # AABs:  {tv,mobile}/build/outputs/bundle/release/

Both apps share one release setup in the root `build.gradle.kts`:

- **Version**: `versionName` is `antresolVersionName` in `gradle.properties` (1.0.0). `versionCode` is the
  CI run number (`GITHUB_RUN_NUMBER`), or `antresolVersionCode` in `gradle.properties` for local builds
  (2: the first Play upload). Play needs a higher code for every upload, which the
  run number gives.
- **R8**: minification and resource shrinking. Keep rules: `ui-common/consumer-rules.pro` (API models and
  navigation routes) plus the rules that kotlinx.serialization, Media3, OkHttp, Coil and AndroidX ship in their
  artifacts. The R8 mapping is in `build/outputs/mapping/release/mapping.txt`; keep it for every published build
  to de-obfuscate crash stack traces.
- **Serialization check**: `verifyReleaseSerialization` runs with every `assembleRelease`/`bundleRelease`. It reads
  R8's `mapping.txt` and `usage.txt` and fails the build unless every `@Serializable` class in `app.antresol.**`
  kept its generated `$$serializer` and its `serializer()` unrenamed. It prints e.g.
  `Serialization keep rules OK: 72 serializable classes, 72 in the release build.`
- **Signing**: set these as environment variables or Gradle properties (for example in `~/.gradle/gradle.properties`):

  | Name | Meaning |
  |---|---|
  | `ANTRESOL_KEYSTORE_FILE` | path to the keystore (`.jks`), relative to the repo root or absolute |
  | `ANTRESOL_KEYSTORE_PASSWORD` | keystore password |
  | `ANTRESOL_KEY_ALIAS` | key alias |
  | `ANTRESOL_KEY_PASSWORD` | key password (defaults to the keystore password) |

  Without `ANTRESOL_KEYSTORE_FILE` the outputs are unsigned (`*-release-unsigned.apk`, an unsigned `.aab`). For Google
  Play, use Play App Signing and sign the bundle with the upload key.

  Create a key once (keep the file and passwords safe and out of the repo):

      keytool -genkeypair -v -keystore antresol-upload.jks -alias antresol -keyalg RSA -keysize 4096 -validity 10000

## CI (`.github/workflows/ci.yml`)

- Every push and pull request: unit tests and debug builds.
- Push to `main` and tags: also release APKs and AABs. Workflow artifacts: `debug-apks` (both debug APKs) and
  `release` (release APKs, AABs and R8 mappings).
- Tags `v*` (for example `v1.0.0`): a GitHub Release with the release and debug APKs and the AABs, its notes taken
  from both apps' `fastlane/metadata/android/en-US/changelogs/1.txt` (`gh release create` with `GITHUB_TOKEN`, the job
  has `contents: write`).
- Signing uses these repository secrets (Settings → Secrets and variables → Actions); without them the release
  files are unsigned and named `*-unsigned.*`:

  | Secret | Value |
  |---|---|
  | `ANTRESOL_KEYSTORE_BASE64` | the keystore file, `base64 -w0 antresol-upload.jks` |
  | `ANTRESOL_KEYSTORE_PASSWORD` | keystore password |
  | `ANTRESOL_KEY_ALIAS` | key alias |
  | `ANTRESOL_KEY_PASSWORD` | key password |

To release: bump `antresolVersionName`, add the changelogs (see `docs/PLAY_STORE.md`), push, then
`git tag v1.0.0 && git push origin v1.0.0`.
