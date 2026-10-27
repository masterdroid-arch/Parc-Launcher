import com.android.build.api.artifact.SingleArtifact
import javax.xml.parsers.DocumentBuilderFactory
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}
val parcVersionName = providers.gradleProperty("PARC_VERSION_NAME").get()
val parcVersionCode = providers.gradleProperty("PARC_VERSION_CODE").get().toInt()
val versionParts = parcVersionName.split('.').map(String::toInt)
require(versionParts.size == 3 && versionParts[1] in 0..99 && versionParts[2] in 0..99 &&
    parcVersionCode == versionParts[0] * 10000 + versionParts[1] * 100 + versionParts[2])
// Change either line to false to remove that feature and its permissions from Play only.
val FILE_SEARCH_ENABLED = true
val CONTACT_SEARCH_ENABLED = true
val playManifest = layout.buildDirectory.file("generated/playManifest/AndroidManifest.xml")
val generatePlayManifest by tasks.registering {
    inputs.property("files", FILE_SEARCH_ENABLED)
    inputs.property("contacts", CONTACT_SEARCH_ENABLED)
    outputs.file(playManifest)
    doLast {
        val removed = mutableListOf("USE_EXACT_ALARM", "DUMP", "REQUEST_DELETE_PACKAGES")
        if (!FILE_SEARCH_ENABLED) removed += listOf("READ_MEDIA_IMAGES", "READ_MEDIA_VIDEO", "READ_MEDIA_AUDIO")
        if (!CONTACT_SEARCH_ENABLED) removed += "READ_CONTACTS"
        playManifest.get().asFile.apply {
            parentFile.mkdirs()
            writeText("""<manifest xmlns:android="http://schemas.android.com/apk/res/android" xmlns:tools="http://schemas.android.com/tools">
""" + removed.joinToString("\n") { """    <uses-permission android:name="android.permission.$it" tools:node="remove" />""" } + "\n</manifest>\n")
        }
    }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(generatePlayManifest) }
android {
    namespace = "com.brightblu.parc"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.brightblu.parc"
        minSdk = 33
        targetSdk = 36
        versionCode = parcVersionCode
        versionName = parcVersionName
        buildConfigField("String", "BUILD_DATE", "\"${LocalDate.now(ZoneOffset.UTC)}\"")
    }
    flavorDimensions += "distribution"
    productFlavors {
        create("personal") {
            dimension = "distribution"
            manifestPlaceholders["fileSearchEnabled"] = "true"
            manifestPlaceholders["contactSearchEnabled"] = "true"
            manifestPlaceholders["personalBuild"] = "true"
            buildConfigField("boolean", "FILE_SEARCH_ENABLED", "true")
            buildConfigField("boolean", "CONTACT_SEARCH_ENABLED", "true")
        }
        // T137: the personal build plus the Weather page and the INTERNET permission. Same package
        // and signing key as personal. Its own source set holds every line of network code.
        create("personalWeather") {
            dimension = "distribution"
            manifestPlaceholders["fileSearchEnabled"] = "true"
            manifestPlaceholders["contactSearchEnabled"] = "true"
            manifestPlaceholders["personalBuild"] = "true"
            buildConfigField("boolean", "FILE_SEARCH_ENABLED", "true")
            buildConfigField("boolean", "CONTACT_SEARCH_ENABLED", "true")
        }
        create("play") {
            dimension = "distribution"
            buildConfigField("boolean", "FILE_SEARCH_ENABLED", FILE_SEARCH_ENABLED.toString())
            buildConfigField("boolean", "CONTACT_SEARCH_ENABLED", CONTACT_SEARCH_ENABLED.toString())
            manifestPlaceholders["fileSearchEnabled"] = FILE_SEARCH_ENABLED.toString()
            manifestPlaceholders["contactSearchEnabled"] = CONTACT_SEARCH_ENABLED.toString()
            manifestPlaceholders["personalBuild"] = "false"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    buildTypes.getByName("release") {
        isMinifyEnabled = true
        isShrinkResources = true
        // Private signing is performed manually after Gradle has finished.
        signingConfig = null
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
    }
    sourceSets.getByName("play").manifest.srcFile(playManifest)
    // T137: the normal builds compile only the Weather stub; the weather variant uses src/personalWeather.
    listOf("personal", "play").forEach { sourceSets.getByName(it).java.srcDir("src/noWeather/kotlin") }
    listOf("testPersonal", "testPlay").forEach { sourceSets.getByName(it).java.srcDir("src/testNoWeather/kotlin") }
    sourceSets.getByName("test").resources.srcDir("src/main/assets")
    // T65: API 33 support proof. NewApi is an error, not a warning.
    lint {
        abortOnError = true
        error += "NewApi"
    }
}
dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.animation.core)
    implementation(libs.activity.compose)
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
    implementation(libs.datastore.preferences)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)

    implementation(project(":core:model"))
    testImplementation(testFixtures(project(":core:model")))
    implementation(project(":core:data"))
    implementation(project(":feature:ring"))
    implementation(project(":feature:search"))
    implementation(project(":feature:settings"))
}

// Keep the pre-flavour verification command covering app tests as well as library tests.
tasks.register("testDebugUnitTest") {
    dependsOn("testPersonalDebugUnitTest", "testPlayDebugUnitTest", "testPersonalWeatherDebugUnitTest")
}

val copyDebugApk by tasks.registering {
    dependsOn("packagePersonalDebug", "verifyPersonalDebugManifest")
    doLast {
        layout.buildDirectory.file("outputs/apk/personal/debug/app-personal-debug.apk").get().asFile
            .copyTo(rootProject.file("Parc-debug.apk"), overwrite = true)
    }
}
tasks.matching { it.name == "assembleDebug" || it.name == "assemblePersonalDebug" }.configureEach { finalizedBy(copyDebugApk) }

listOf("personal" to "personal", "play" to "play", "personalWeather" to "personal-weather").forEach { (flavour, fileName) ->
    tasks.register(when (flavour) { "personal" -> "copyReleaseApk"; "play" -> "copyPlayReleaseApk"; else -> "copyWeatherReleaseApk" }) {
        dependsOn("assemble${flavour.replaceFirstChar(Char::uppercase)}Release")
        doLast {
            val destination = rootProject.file("Parc-$parcVersionName-$fileName-unsigned.apk")
            check(!destination.exists()) { "${destination.name} already exists; move it before copying another release" }
            layout.buildDirectory.file("outputs/apk/$flavour/release/app-$flavour-release-unsigned.apk").get().asFile.copyTo(destination)
        }
    }
}

// Check the actual merged artifact for every build type, including dependency permissions.
val personalPermissions = setOf(
    "REQUEST_DELETE_PACKAGES", "VIBRATE", "ACCESS_NOTIFICATION_POLICY", "USE_BIOMETRIC",
    "READ_CALENDAR", "USE_EXACT_ALARM", "POST_NOTIFICATIONS", "READ_CONTACTS",
    "READ_MEDIA_IMAGES", "READ_MEDIA_VIDEO", "READ_MEDIA_AUDIO", "DUMP",
    "PACKAGE_USAGE_STATS", "ACCESS_WIFI_STATE",
).map { "android.permission.$it" }.toSet() + "com.brightblu.parc.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
androidComponents.onVariants { variant ->
    val capitalized = variant.name.replaceFirstChar(Char::uppercase)
    val manifest = variant.artifacts.get(SingleArtifact.MERGED_MANIFEST)
    val verify = tasks.register("verify${capitalized}Manifest") {
        inputs.file(manifest)
        doLast {
            val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                .newDocumentBuilder().parse(manifest.get().asFile)
            val permissions = listOf("uses-permission", "uses-permission-sdk-23").flatMap { tag ->
                val nodes = doc.getElementsByTagName(tag)
                (0 until nodes.length).map {
                    nodes.item(it).attributes.getNamedItemNS("http://schemas.android.com/apk/res/android", "name").nodeValue
                }
            }.toSet()
            if (variant.flavorName == "personal" || variant.flavorName == "personalWeather") {
                val base = if (variant.buildType == "debug") personalPermissions - "com.brightblu.parc.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" else personalPermissions
                // T137: the weather variant adds exactly INTERNET and nothing else.
                val expected = if (variant.flavorName == "personalWeather") base + "android.permission.INTERNET" else base
                check(permissions == expected) { "${variant.flavorName} permissions changed: $permissions" }
            } else {
                val forbidden = setOf("INTERNET", "USE_EXACT_ALARM", "SCHEDULE_EXACT_ALARM", "DUMP", "REQUEST_DELETE_PACKAGES", "QUERY_ALL_PACKAGES")
                    .map { "android.permission.$it" }.toSet()
                check(permissions.intersect(forbidden).isEmpty()) { "Forbidden Play permissions: ${permissions.intersect(forbidden)}" }
                listOf("fileSearchEnabled" to listOf("READ_MEDIA_IMAGES", "READ_MEDIA_VIDEO", "READ_MEDIA_AUDIO"),
                    "contactSearchEnabled" to listOf("READ_CONTACTS")).forEach { (flag, names) ->
                    val metadata = doc.getElementsByTagName("meta-data")
                    val enabled = (0 until metadata.length).map { metadata.item(it).attributes }.first {
                        it.getNamedItemNS("http://schemas.android.com/apk/res/android", "name").nodeValue == "com.brightblu.parc.$flag"
                    }.getNamedItemNS("http://schemas.android.com/apk/res/android", "value").nodeValue == "true"
                    names.forEach { check(("android.permission.$it" in permissions) == enabled) { "$flag disagrees with $it" } }
                }
            }
            if (variant.flavorName != "personalWeather") check("android.permission.INTERNET" !in permissions)
        }
    }
    // T137: the normal builds must contain no network code at all. Scan every Parc class (app and
    // Parc's own modules) compiled into this variant for network classes and the weather host.
    val scan = tasks.register<NoNetworkClassesTask>("verify${capitalized}NoNetworkClasses") {
        weatherVariant.set(variant.flavorName == "personalWeather")
        report.set(layout.buildDirectory.file("reports/noNetwork/${variant.name}.txt"))
    }
    variant.artifacts.forScope(com.android.build.api.variant.ScopedArtifacts.Scope.ALL).use(scan)
        .toGet(com.android.build.api.artifact.ScopedArtifact.CLASSES, NoNetworkClassesTask::allJars, NoNetworkClassesTask::allDirectories)
    tasks.matching { it.name == "assemble$capitalized" || it.name == "test${capitalized}UnitTest" || it.name == "check" }
        .configureEach { dependsOn(verify, scan) }
}

/**
 * T137: fails the build when a normal variant (personal, play) contains a network class or the
 * weather host, and when the weather variant is missing them (so the check itself is proven to
 * see the code). Only Parc's own classes (`com/brightblu/parc/`) are scanned; libraries are not ours.
 */
abstract class NoNetworkClassesTask : DefaultTask() {
    @get:InputFiles abstract val allJars: ListProperty<RegularFile>
    @get:InputFiles abstract val allDirectories: ListProperty<Directory>
    @get:Input abstract val weatherVariant: Property<Boolean>
    @get:OutputFile abstract val report: RegularFileProperty

    @TaskAction fun scan() {
        val forbidden = listOf(
            Regex("java/net/(Http)?URLConnection"), Regex("javax/net/ssl/"), Regex("java/net/Socket"),
            Regex("java/net/URL(?![A-Za-z])"), Regex("java/net/http/"), Regex("okhttp"), Regex("open-meteo"),
        )
        val hits = sortedSetOf<String>()
        var scanned = 0
        fun inspect(name: String, bytes: ByteArray) {
            if (!name.startsWith("com/brightblu/parc/") || !name.endsWith(".class")) return
            scanned++
            val text = String(bytes, Charsets.ISO_8859_1)
            forbidden.forEach { if (it.containsMatchIn(text)) hits += "$name: ${it.pattern}" }
        }
        allDirectories.get().forEach { dir ->
            val root = dir.asFile
            root.walkTopDown().filter { it.isFile }.forEach { inspect(it.relativeTo(root).invariantSeparatorsPath, it.readBytes()) }
        }
        allJars.get().forEach { jar ->
            ZipFile(jar.asFile).use { zip ->
                zip.entries().asSequence().filter { !it.isDirectory }.forEach { entry -> inspect(entry.name, zip.getInputStream(entry).readBytes()) }
            }
        }
        report.get().asFile.apply { parentFile.mkdirs(); writeText("scanned $scanned Parc classes\n" + hits.joinToString("\n")) }
        check(scanned > 0) { "No Parc classes were scanned" }
        if (weatherVariant.get()) check(hits.isNotEmpty()) { "The weather variant should contain its network code" }
        else check(hits.isEmpty()) { "Network code in a normal build: $hits" }
    }
}
