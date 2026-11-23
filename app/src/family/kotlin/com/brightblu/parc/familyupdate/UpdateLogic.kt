package com.brightblu.parc.familyupdate

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.InputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * T396: the pure rules of the family build's update checker. No Android types and no network, so every rule here is
 * unit-tested on the JVM. The network and the screens live in the other files of this package.
 */

/** `major.minor.patch`, compared number by number, so 1.5.9 < 1.5.18 < 1.10.0. */
internal data class UpdateVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<UpdateVersion> {
    override fun compareTo(other: UpdateVersion): Int =
        compareValuesBy(this, other, UpdateVersion::major, UpdateVersion::minor, UpdateVersion::patch)

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        private val shape = Regex("^v?(\\d{1,6})\\.(\\d{1,6})\\.(\\d{1,6})$")

        /** A release tag (`v1.5.19`) or a version name (`1.5.19`). Anything with a suffix or another shape is null. */
        fun parse(text: String?): UpdateVersion? {
            val match = shape.matchEntire(text?.trim().orEmpty()) ?: return null
            val (major, minor, patch) = match.destructured
            return UpdateVersion(major.toInt(), minor.toInt(), patch.toInt())
        }

        /** True only when [tag] is a well-formed version strictly newer than the installed [installedName]. */
        fun isNewer(tag: String?, installedName: String): Boolean {
            val candidate = parse(tag) ?: return false
            val installed = parse(installedName) ?: return false
            return candidate > installed
        }
    }
}

/** One downloadable file of a release. [url] is the public `browser_download_url`. */
internal data class UpdateAsset(val name: String, val url: String, val size: Long)

/** A parsed GitHub release: its tag, and the files it carries. */
internal data class UpdateRelease(val tag: String, val version: UpdateVersion, val assets: List<UpdateAsset>)

/** The two files an update needs: the APK and the checksum file beside it. */
internal data class UpdateFiles(val version: UpdateVersion, val apk: UpdateAsset, val checksum: UpdateAsset)

internal object UpdateReleaseJson {
    private val json = Json { ignoreUnknownKeys = true }

    /** Parses `releases/latest`. Null for anything that is not a published release with a version tag. Never throws. */
    fun parse(text: String): UpdateRelease? {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
        if ((root["draft"] as? JsonPrimitive)?.booleanOrNull == true) return null
        if ((root["prerelease"] as? JsonPrimitive)?.booleanOrNull == true) return null
        val tag = root["tag_name"]?.jsonPrimitive?.contentOrNull ?: return null
        val version = UpdateVersion.parse(tag) ?: return null
        val assets = (root["assets"] as? JsonArray).orEmpty().mapNotNull { element ->
            val obj = (element as? JsonObject) ?: return@mapNotNull null
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val url = obj["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val size = obj["size"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
            UpdateAsset(name, url, size)
        }
        return UpdateRelease(tag, version, assets)
    }
}

internal object UpdateAssets {
    fun apkName(version: UpdateVersion) = "Parc-$version.apk"
    fun checksumName(version: UpdateVersion) = apkName(version) + ".sha256"

    /** The APK and its checksum file, matched by exact name. Null when either is missing: nothing is offered then. */
    fun choose(release: UpdateRelease): UpdateFiles? {
        val apk = release.assets.firstOrNull { it.name == apkName(release.version) } ?: return null
        val checksum = release.assets.firstOrNull { it.name == checksumName(release.version) } ?: return null
        return UpdateFiles(release.version, apk, checksum)
    }
}

/** How often Parc may ask GitHub. A successful check holds for a day; a failed one may be tried again after an hour. */
internal object UpdateThrottle {
    const val DayMs = 24L * 60 * 60 * 1000
    const val RetryMs = 60L * 60 * 1000

    fun due(now: Long, lastSuccess: Long, lastAttempt: Long): Boolean {
        val dayPassed = lastSuccess <= 0L || lastSuccess > now || now - lastSuccess >= DayMs
        val retryPassed = lastAttempt <= 0L || lastAttempt > now || now - lastAttempt >= RetryMs
        return dayPassed && retryPassed
    }
}

/** "Later" hides the notice for one version for the rest of that day. A newer version, or the next day, shows it again. */
internal object UpdateNotice {
    fun visible(available: String?, laterVersion: String?, laterDay: Long, today: Long): Boolean =
        available != null && !(laterVersion == available && laterDay == today)
}

internal object UpdateChecksum {
    private const val Buffer = 8 * 1024

    fun hex(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(Buffer)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun hex(bytes: ByteArray): String = hex(bytes.inputStream())

    private val line = Regex("^([0-9a-fA-F]{64})(?:\\s+\\*?(.+))?$")

    /**
     * The expected hash from a `.sha256` file: either the bare hash or `hash  file-name` lines. A line naming [fileName]
     * wins; otherwise the file must hold exactly one hash. Anything else is null, so an unclear file is never trusted.
     */
    fun expected(content: String, fileName: String): String? {
        val entries = content.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
            .mapNotNull { line.matchEntire(it)?.destructured }
            .map { (hash, name) -> hash.lowercase() to name.trim() }.toList()
        entries.firstOrNull { it.second == fileName }?.let { return it.first }
        return entries.singleOrNull()?.takeIf { it.second.isEmpty() || it.second == fileName }?.first
    }

    fun matches(actualHex: String, expectedHex: String): Boolean =
        actualHex.trim().equals(expectedHex.trim(), ignoreCase = true) && actualHex.trim().length == 64
}

/** The hosts an update may talk to: GitHub and its release file hosts, over https. */
internal object UpdateHosts {
    fun allowed(scheme: String, host: String): Boolean =
        scheme == "https" && (host == "github.com" || host == "api.github.com" ||
            host.endsWith(".github.com") || host.endsWith(".githubusercontent.com"))
}

/** Plain-words failures. The texts are what the person reads. */
internal enum class UpdateProblem(val message: String) {
    Offline("No internet connection. Check you are online and try again."),
    Timeout("The request took too long. Try again."),
    Server("GitHub answered with an error. Try again later."),
    BadAnswer("GitHub answered in an unexpected way. Try again later."),
    NoFiles("This release has no APK and checksum attached yet. Try again later."),
    BadChecksum("The download did not match its checksum. It was not installed."),
    NeedInstallPermission("Allow Parc to install updates in Android settings, then tap Update again."),
    CannotOpenInstaller("Android could not open its installer. Try again."),
    NoSpace("The file could not be saved. Free some space and try again."),
}

/** Text for the Settings rows. Pure so the wording is tested. */
internal object UpdateText {
    private val stamp = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.ENGLISH)

    fun lastChecked(lastCheckedMillis: Long, zone: ZoneId): String =
        if (lastCheckedMillis <= 0L) "Last checked: never"
        else "Last checked: " + Instant.ofEpochMilli(lastCheckedMillis).atZone(zone).format(stamp)

    fun available(version: String) = "Update available: $version"
}

/** How the hub row lays out: beside its buttons normally, above them when the text is enlarged. */
internal object UpdateLayout {
    const val StackAboveFontScale = 1.15f
    fun stacked(fontScale: Float): Boolean = fontScale > StackAboveFontScale
}

internal object UpdateWords {
    fun percent(fraction: Float?): String = if (fraction == null) "Downloading…" else "Downloading ${(fraction * 100).toInt().coerceIn(0, 100)}%"

    /** The one line under "Update available" in Settings, for each state. */
    fun summary(state: UpdateState): String = when (state) {
        UpdateState.Idle -> ""
        is UpdateState.Available -> if (state.installerOpened) "Installer opened. Confirm there to finish." else "Parc ${state.version} is ready to download."
        is UpdateState.Downloading -> percent(state.fraction)
        is UpdateState.Failed -> state.problem.message
    }
}
