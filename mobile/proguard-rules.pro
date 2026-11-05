# App-specific R8 rules (release builds minify and shrink resources; see the root build.gradle.kts).
# - API models and navigation routes (kotlinx.serialization): kept by :ui-common's consumer-rules.pro and the rules
#   bundled in kotlinx-serialization; `verifyReleaseSerialization` checks the result on every release build.
# - Media3, OkHttp, Coil, Compose and AndroidX ship their own consumer rules; nothing else is loaded by reflection.
