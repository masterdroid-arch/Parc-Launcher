package com.brightblu.parc.core.data.icons

/**
 * Thin icon-pack boundary. The real implementation reads packages, compiled XML and drawables;
 * everything above it (parsing, caching, fallback) is plain Kotlin and runs under JVM unit tests.
 *
 * [B] is the bitmap type so tests can use a fake; production uses [android.graphics.Bitmap].
 */
internal interface IconPackSource<B : Any> {
    /** Installed packs declaring one of the three theme actions, de-duplicated and sorted. */
    fun packs(): List<IconPack>

    /**
     * The pack's parsed appfilter map, keyed by [AppFilterParser.normalizeComponent] output.
     * An unreadable pack returns an empty map; it never throws into the icon path.
     */
    fun appFilter(packageName: String): Map<String, String>

    /** Rasterises one of the pack's drawables at a square size, or null when it is missing. */
    fun drawable(packageName: String, drawableName: String, sizePx: Int): B?
}
