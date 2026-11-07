import com.android.build.api.dsl.ApplicationExtension

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

/**
 * Release settings shared by the TV and phone apps (see docs/RELEASE.md):
 * - version name from `antresolVersionName` (gradle.properties), version code from `GITHUB_RUN_NUMBER` (CI) or
 *   `antresolVersionCode` (gradle.properties, local builds);
 * - R8 minification and resource shrinking;
 * - signing from `ANTRESOL_KEYSTORE_FILE` / `_PASSWORD` / `_KEY_ALIAS` / `_KEY_PASSWORD` (environment variables or
 *   Gradle properties). Without a keystore the release outputs are unsigned.
 */
fun Project.setting(name: String): String? =
    (providers.environmentVariable(name).orNull ?: providers.gradleProperty(name).orNull)?.takeIf { it.isNotBlank() }

subprojects {
    plugins.withId("com.android.application") {
        extensions.configure<ApplicationExtension> {
            defaultConfig {
                versionName = providers.gradleProperty("antresolVersionName").get()
                versionCode = setting("GITHUB_RUN_NUMBER")?.toIntOrNull()
                    ?: providers.gradleProperty("antresolVersionCode").get().toInt()
            }
            val keystore = setting("ANTRESOL_KEYSTORE_FILE")?.let { rootProject.file(it) }
            val release = keystore?.let { file ->
                signingConfigs.create("release") {
                    storeFile = file
                    storePassword = setting("ANTRESOL_KEYSTORE_PASSWORD")
                    keyAlias = setting("ANTRESOL_KEY_ALIAS")
                    keyPassword = setting("ANTRESOL_KEY_PASSWORD") ?: setting("ANTRESOL_KEYSTORE_PASSWORD")
                }
            }
            buildTypes.getByName("release") {
                isMinifyEnabled = true
                isShrinkResources = true
                proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
                signingConfig = release
            }
        }

        // Proves the R8 output still has what kotlinx.serialization needs (see verifySerializationKeepRules below).
        val verify = tasks.register("verifyReleaseSerialization") {
            group = "verification"
            description = "Checks that R8 kept the generated serializers of every @Serializable class."
            val mappingDir = layout.buildDirectory.dir("outputs/mapping/release")
            inputs.dir(mappingDir)
            dependsOn("minifyReleaseWithR8")
            doLast {
                val dir = mappingDir.get().asFile
                println(verifySerializationKeepRules(dir.resolve("mapping.txt"), dir.resolve("usage.txt")))
            }
        }
        tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }.configureEach { dependsOn(verify) }
    }
}

/**
 * Reads R8's `mapping.txt` (what survived, old -> new names) and `usage.txt` (what was removed). Together they list
 * every class R8 saw. For every app class that is serializable — it has a generated `$$serializer`, or it or its
 * companion declares `serializer()` — this fails unless:
 * - every `$$serializer` survived under its original name with its members;
 * - a surviving serializable class kept `serializer()` (on itself or on a surviving companion) unrenamed.
 */
fun verifySerializationKeepRules(mapping: File, usage: File): String {
    class Members(val kept: Boolean, val renamedTo: String?, val members: MutableMap<String, String?> = mutableMapOf())
    val classes = mutableMapOf<String, Members>()
    val removedClasses = mutableSetOf<String>()

    var current: Members? = null
    mapping.forEachLine { line ->
        when {
            line.isBlank() || line.trimStart().startsWith("#") -> Unit
            !line.startsWith(" ") -> {
                val (from, to) = line.removeSuffix(":").split(" -> ")
                current = Members(kept = true, renamedTo = to).also { classes[from] = it }
            }
            else -> {
                val (signature, newName) = line.trim().split(" -> ")
                val name = signature.substringBefore("(").substringAfterLast(" ").substringAfterLast(":")
                if (signature.contains("(")) current?.members?.put(name, newName)
            }
        }
    }
    var removedOf: String? = null
    val removedMembers = mutableMapOf<String, MutableSet<String>>()
    usage.forEachLine { line ->
        when {
            line.isBlank() -> Unit
            !line.startsWith(" ") && line.endsWith(":") -> removedOf = line.removeSuffix(":")
            !line.startsWith(" ") -> { removedClasses += line.trim(); removedOf = null }
            else -> removedOf?.let { owner ->
                val name = line.trim().substringBefore("(").substringAfterLast(" ")
                removedMembers.getOrPut(owner) { mutableSetOf() } += name
            }
        }
    }

    val all = (classes.keys + removedClasses + removedMembers.keys).filter { it.startsWith("app.antresol.") }.toSet()
    fun declaresSerializer(name: String) =
        classes[name]?.members?.containsKey("serializer") == true || removedMembers[name]?.contains("serializer") == true
    val serializable = all.filter { it.endsWith("\$\$serializer") }.map { it.removeSuffix("\$\$serializer") }.toSet() +
        all.filter { !it.endsWith("\$Companion") && (declaresSerializer(it) || declaresSerializer("$it\$Companion")) }

    val problems = mutableListOf<String>()
    var surviving = 0
    for (name in serializable.sorted()) {
        val generated = "$name\$\$serializer"
        if (generated in all) {
            val kept = classes[generated]
            if (kept == null) problems += "$generated was removed"
            else if (kept.renamedTo != generated) problems += "$generated was renamed to ${kept.renamedTo}"
            else if (removedMembers[generated].orEmpty().isNotEmpty()) problems += "$generated lost ${removedMembers[generated]}"
        }
        if (name !in classes) continue
        surviving++
        val companion = "$name\$Companion"
        val owner = when {
            classes[companion]?.members?.containsKey("serializer") == true -> companion
            classes[name]?.members?.containsKey("serializer") == true -> name
            companion in classes || companion in all || declaresSerializer(name) -> null
            else -> continue // Only reached with a custom serializer (`@Serializable(with = …)`); nothing to look up.
        }
        when {
            owner == null -> problems += "$name survived but its serializer() was removed"
            classes.getValue(owner).members["serializer"] != "serializer" ->
                problems += "$owner.serializer() was renamed to ${classes.getValue(owner).members["serializer"]}"
        }
    }
    check(problems.isEmpty()) { "R8 broke kotlinx.serialization:\n" + problems.joinToString("\n") }
    return "Serialization keep rules OK: ${serializable.size} serializable classes, $surviving in the release build."
}
