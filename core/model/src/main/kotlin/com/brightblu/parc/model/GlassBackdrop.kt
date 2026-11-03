package com.brightblu.parc.model

/** What the home canvas lays over the wallpaper while the glass effect is on (T169). */
enum class GlassStage {
    /** Glass off: the old look, scrims included. */
    Off,
    /** No wallpaper to show (Black, Solid, or an Image that cannot be read): T153's haze stage. */
    Haze,
    /** The wallpaper stays visible; only a faint flat tint of at most 8 percent lies over it. */
    WallpaperTint,
}

/** Pure rules for glass over the real wallpaper. No Android types, so they are testable on the JVM. */
object GlassBackdrop {
    fun stage(glassOn: Boolean, wallpaper: WallpaperStyle, imageReadable: Boolean): GlassStage = when {
        !glassOn -> GlassStage.Off
        wallpaper == WallpaperStyle.System -> GlassStage.WallpaperTint // the window shows it; no pixel read is needed to keep it visible
        wallpaper == WallpaperStyle.Image && imageReadable -> GlassStage.WallpaperTint
        else -> GlassStage.Haze
    }

    /**
     * T176: the look the picture is loaded with. Dim is ALWAYS kept, so a chosen picture darkens on Home and in the stage the
     * glass blurs (frosted cards darken with it). With glass on only the picture's readability scrim is left out: the text
     * sits on the glass cards, whose ink and tint keep it readable (T172), and the scrim would hide the picture again.
     */
    fun pictureLook(look: LookSettings, glassOn: Boolean): LookSettings =
        if (!glassOn || !look.imageReadabilityScrim) look else look.copy(imageReadabilityScrim = false)

    /** The one faint tint, bg colour at [Metrics.glassWallpaperTint]. */
    fun wallpaperTint(bg: Int): Int =
        ((Metrics.glassWallpaperTint.coerceAtMost(Metrics.glassWallpaperTintMax) * 255f + 0.5f).toInt() shl 24) or (bg and 0xFFFFFF)

    /** Blurred wallpaper pixels behind a card: where a hub surface may take its picture from. */
    fun frostFromWallpaper(stage: GlassStage, pixelsReadable: Boolean) = stage == GlassStage.WallpaperTint && pixelsReadable

    /**
     * Card tint alpha (0..255) over a blurred wallpaper whose darkest and lightest pixels are [low] and [high].
     * Starts at [start] (default 70 percent) and rises only as far as the ink needs [minContrast] over both extremes.
     */
    fun tintAlpha(surface: Int, ink: Int, low: Int, high: Int, start: Int = Metrics.hubGlassTintAlpha,
        minContrast: Double = SkinSafety.MinModuleInkContrast): Int {
        for (alpha in start.coerceIn(0, 255)..255) {
            val a = alpha / 255f
            val darkest = WallpaperReadability.composite(surface, low, a)
            val lightest = WallpaperReadability.composite(surface, high, a)
            val l = WallpaperReadability.luminance(ink)
            if (WallpaperReadability.contrast(ink, darkest) >= minContrast &&
                WallpaperReadability.contrast(ink, lightest) >= minContrast &&
                (l <= WallpaperReadability.luminance(darkest) || l >= WallpaperReadability.luminance(lightest))) return alpha
        }
        return 255
    }

    /** Deterministic noise tile: [size] squared ARGB pixels, white or black dots at [Metrics.glassGrainAlpha]. */
    fun grainTile(size: Int = Metrics.glassGrainTilePx, seed: Int = Metrics.glassGrainSeed): IntArray {
        val alpha = (Metrics.glassGrainAlpha * 255f).toInt() // rounds down, so it stays at or below 4 percent
        var state = seed
        return IntArray(size * size) {
            state = state * 1664525 + 1013904223
            val grey = (state ushr 24)
            (alpha shl 24) or (grey shl 16) or (grey shl 8) or grey
        }
    }

    /** Wallpaper identity from a sparse grid of its pixels, so the frost cache key changes when the picture does. */
    fun identity(width: Int, height: Int, pixelAt: (Int, Int) -> Int): Int {
        var hash = width * 31 + height
        val n = Metrics.glassStageSamples
        for (j in 0 until n) for (i in 0 until n) {
            hash = hash * 31 + pixelAt(((i + 0.5f) * width / n).toInt().coerceIn(0, width - 1), ((j + 0.5f) * height / n).toInt().coerceIn(0, height - 1))
        }
        return if (hash == 0) 1 else hash // 0 is reserved for "no wallpaper stage"
    }
}
