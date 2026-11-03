package com.brightblu.parc

/**
 * T179: which build is running. All four values come from Gradle at build time (no network, no permission), so two APKs
 * can always be told apart in Settings > Backup and about.
 */
internal object BuildStamp {
    /** One line per value, in the order Settings shows them. */
    fun lines(versionName: String = BuildConfig.VERSION_NAME, versionCode: Int = BuildConfig.VERSION_CODE,
        builtAt: String = BuildConfig.BUILD_TIME, commit: String = BuildConfig.GIT_COMMIT): List<Pair<String, String>> = listOf(
        "Version" to versionName,
        "Version code" to versionCode.toString(),
        "Built" to builtAt,
        "Code commit" to commit,
    )

    /** The short form for the Settings home row, for example `1.1.1 (10101) · 2026-11-01 20:15`. */
    fun short(versionName: String = BuildConfig.VERSION_NAME, versionCode: Int = BuildConfig.VERSION_CODE,
        builtAt: String = BuildConfig.BUILD_TIME): String = "$versionName ($versionCode) · $builtAt"

    val TimeFormat = Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}")
    val StampFormat = Regex("\\d{10}")
    val CommitFormat = Regex("([0-9a-f]{7,12}|unknown)(\\+changes)?")
}
