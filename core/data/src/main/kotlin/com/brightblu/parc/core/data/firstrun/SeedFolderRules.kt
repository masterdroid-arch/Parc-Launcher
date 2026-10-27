package com.brightblu.parc.core.data.firstrun

import java.util.Locale

/**
 * One auto-built folder (T62): a stable id, a name the ring can draw on one caption line, the app
 * kinds that belong to it, and package patterns for apps that do not set a category.
 *
 * Patterns are public package fragments only. A pattern without a dot ("bank") matches anywhere in
 * the package name; a dotted pattern is a package prefix. Rules are tried in declaration order, so
 * the specific media, chat, social, web, maps and money rules run before the Google catch-all.
 */
data class SeedFolderRule(
    val id: String,
    val name: String,
    val kinds: Set<AppKind> = emptySet(),
    val patterns: List<String> = emptyList(),
) {
    init {
        require(name.length <= MaxFolderNameChars) { "Folder name too long: $name" }
        require(id.isNotBlank()) { "Folder id is blank" }
    }

    /** True when [app] belongs to this folder by category or by a public package pattern. */
    fun matches(app: InstalledApp): Boolean =
        app.kind in kinds || patterns.any { it.matches(app.packageName) }

    private fun String.matches(packageName: String): Boolean {
        val normalized = packageName.lowercase(Locale.ROOT)
        val pattern = lowercase(Locale.ROOT)
        return if (pattern.contains('.')) normalized.startsWith(pattern) else normalized.contains(pattern)
    }

    companion object {
        /** Longest folder name, mirroring [com.brightblu.parc.core.data.LayoutRepositoryImpl]. */
        const val MaxFolderNameChars = 8
    }
}

/**
 * The public folder table (T62). It is deliberately small: the category rules catch most apps and
 * the package patterns only cover well-known app families. Nothing here names a personal app.
 *
 * Order is the placement order on the folder arcs and the match priority. Apps matching no rule are
 * left unfiled and stay reachable through search and the All-apps view.
 */
object SeedFolderRules {

    val rules: List<SeedFolderRule> = listOf(
        SeedFolderRule(
            id = "chat",
            name = "Chat",
            kinds = setOf(),
            patterns = listOf(
                "com.whatsapp",
                "org.telegram",
                "com.facebook.orca",
                "com.discord",
                "org.thoughtcrime.securesms",
                "com.viber",
                "com.slack",
                "com.microsoft.teams",
                "com.google.android.apps.messaging",
                "com.android.mms",
            ),
        ),
        SeedFolderRule(
            id = "social",
            name = "Social",
            kinds = setOf(AppKind.Social),
            patterns = listOf(
                "com.instagram",
                "com.facebook.katana",
                "com.twitter",
                "com.zhiliaoapp.musically",
                "com.linkedin",
                "com.reddit",
                "com.bsky",
                "org.joinmastodon",
            ),
        ),
        SeedFolderRule(
            id = "media",
            name = "Media",
            kinds = setOf(AppKind.Audio, AppKind.Video, AppKind.Image, AppKind.News),
            patterns = listOf(
                "com.spotify",
                "com.google.android.youtube",
                "com.netflix",
                "com.amazon.avod",
                "org.videolan.vlc",
                "com.google.android.apps.photos",
                "com.pocketcasts",
                "de.danoeh.antennapod",
                "com.apple.android.music",
            ),
        ),
        SeedFolderRule(
            id = "web",
            name = "Web",
            kinds = setOf(),
            patterns = listOf(
                "org.mozilla.firefox",
                "com.brave.browser",
                "com.opera",
                "com.microsoft.emmx",
                "com.duckduckgo",
                "org.chromium.chrome",
                "com.sec.android.app.sbrowser",
            ),
        ),
        SeedFolderRule(
            id = "maps",
            name = "Maps",
            kinds = setOf(AppKind.Maps),
            patterns = listOf(
                "com.google.android.apps.maps",
                "com.waze",
                "com.here.app.maps",
                "org.osmand.plus",
            ),
        ),
        SeedFolderRule(
            id = "money",
            name = "Money",
            kinds = setOf(),
            patterns = listOf(
                "com.paypal",
                "com.coinbase",
                "com.binance",
                "com.bybit",
                "com.kraken",
                "com.wise",
                "com.phonepe",
                "com.google.android.apps.walletnfcrel",
                "bank",
                "banco",
                "banking",
                "wallet",
                "finance",
            ),
        ),
        SeedFolderRule(
            id = "google",
            name = "Google",
            kinds = setOf(),
            patterns = listOf(
                "com.google.",
                "com.android.chrome",
                "com.android.vending",
            ),
        ),
        SeedFolderRule(
            id = "games",
            name = "Games",
            kinds = setOf(AppKind.Game),
        ),
        SeedFolderRule(
            id = "tools",
            name = "Tools",
            kinds = setOf(AppKind.Productivity, AppKind.Accessibility),
            patterns = listOf(
                "calculator",
                "camera",
                "clock",
                "calendar",
                "contacts",
                "documentsui",
                "fileexplorer",
                "files",
                "recorder",
            ),
        ),
    )

    /** The folder [app] belongs to, or null when it is uncategorised and matches no pattern. */
    fun folderFor(app: InstalledApp): SeedFolderRule? = rules.firstOrNull { it.matches(app) }

    /**
     * The public keyword table as plain text, one rule per line. The card asks for a grep proof
     * over this table; printing it through a test keeps the proof honest and diffable.
     */
    fun keywordTableDump(): String = buildString {
        rules.forEach { rule ->
            append(rule.name)
            append(" (")
            append(rule.id)
            append("): kinds=")
            append(rule.kinds.joinToString(",") { it.name }.ifEmpty { "none" })
            append("; patterns=")
            append(rule.patterns.joinToString(",").ifEmpty { "none" })
            append('\n')
        }
    }
}
