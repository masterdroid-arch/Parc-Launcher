package com.brightblu.parc.model

/** Stable swatch ids; colours belong to the skin token system, never the draw loop. */
enum class HubColour(val label: String, val argb: Long?) {
    Default("Skin default", null), Forest("Forest", 0xFF1F5A3A), Slate("Slate", 0xFF3B4A63),
    Plum("Plum", 0xFF4A3558), Rust("Rust", 0xFF7A3E2A), Teal("Teal", 0xFF1F5A5A),
    Graphite("Graphite", 0xFF3A3A3A), Sand("Sand", 0xFFBFB5A3), Ink("Ink", 0xFF12161C);
    companion object { fun decode(id: String?) = entries.firstOrNull { it.name == id } ?: Default }
}

/**
 * T171: one opacity per hub. T172: with glass on the range goes down to 20 percent and the default is 50; a flat card keeps
 * 40 to 100 and 85. Steps of 5 in both.
 */
object HubOpacity {
    const val Min = 40
    const val MinGlass = 20
    const val Max = 100
    const val Step = 5
    const val Default = 85
    const val DefaultGlass = 50
    val steps: List<Int> = (Min..Max step Step).toList()
    val glassSteps: List<Int> = (MinGlass..Max step Step).toList()

    fun stepsFor(glass: Boolean) = if (glass) glassSteps else steps
    fun defaultFor(glass: Boolean) = if (glass) DefaultGlass else Default

    /** Nearest legal value, clamped to the range and rounded to a step. */
    fun clamp(percent: Int, glass: Boolean = false): Int {
        val min = if (glass) MinGlass else Min
        return (((percent.coerceIn(min, Max) + Step / 2) / Step) * Step).coerceIn(min, Max)
    }

    /** The value a hub shows: its own saved value (clamped to this mode's range), else this mode's default. */
    fun resolve(own: Int?, glass: Boolean = false): Int = own?.let { clamp(it, glass) } ?: defaultFor(glass)

    /**
     * T177: the old single "Panel opacity" is gone. On every launch (idempotent) a hub that has no value of its own takes the
     * old panel value, but only when the user had moved it away from the old default; a user who never touched it keeps
     * following each mode's default (85 flat, 50 glass), so nothing looks different.
     */
    fun migratePanel(appearance: HubAppearance, legacyPanel: Int): HubAppearance {
        if (legacyPanel == Default) return appearance
        val value = clamp(legacyPanel, glass = true)
        return appearance.copy(arcOpacity = appearance.arcOpacity ?: value, statsOpacity = appearance.statsOpacity ?: value)
    }
}

data class HubAppearance(
    val arc: HubColour = HubColour.Default,
    val stats: HubColour = HubColour.Default,
    /** null follows the active skin; an explicit choice survives skin changes. */
    val glass: Boolean? = null,
    /** T171: null until the user moves the slider; then the hub keeps its own value. */
    val arcOpacity: Int? = null,
    val statsOpacity: Int? = null,
    /** T173: the lens edge on the hub cards while glass is on; default on. */
    val refraction: Boolean = true,
) {
    fun arcOpacityPercent(glass: Boolean = false) = HubOpacity.resolve(arcOpacity, glass)
    fun statsOpacityPercent(glass: Boolean = false) = HubOpacity.resolve(statsOpacity, glass)

    fun glassEnabled(skin: Skin) = glass ?: (skin.shape.moduleStyle == ModuleStyle.frosted)

    /**
     * [opacityPercent] null keeps the T168 tint (70 percent and up). A value makes the card that opaque: in glass it is the
     * tint alpha, and with [keepReadable] the ink is chosen against [backdrop] (what is behind the card; the haze stage when
     * null) and the tint rises by at most [GlassReadability.TintCap]; flat, it is the card alpha, raised to the
     * wallpaper-readability floor when [keepReadable] is on.
     */
    fun arcSkin(skin: Skin, opacityPercent: Int? = null, keepReadable: Boolean = false, backdrop: Backdrop? = null): Skin {
        val surface = opaque(arc.argb ?: skin.colors.moduleSurface)
        val card = card(surface, skin, opacityPercent, keepReadable, backdrop)
        return skin.copy(colors = skin.colors.copy(moduleSurface = card.surface,
            moduleInk = card.ink, textPrimary = card.ink, textSecondary = card.ink),
            shape = skin.shape.copy(moduleStyle = style(skin)))
    }

    fun statsSkin(skin: Skin, opacityPercent: Int? = null, keepReadable: Boolean = false, backdrop: Backdrop? = null): Skin {
        val surface = opaque(stats.argb ?: if (skin.shape.moduleStyle == ModuleStyle.frosted) skin.colors.moduleSurface else skin.colors.surface)
        val card = card(surface, skin, opacityPercent, keepReadable, backdrop)
        return skin.copy(colors = skin.colors.copy(surface = nestedSurface(skin, surface, card.ink), moduleSurface = card.surface,
            moduleInk = card.ink, textPrimary = card.ink, textSecondary = card.ink,
            accent = if (stats == HubColour.Default) skin.colors.accent else card.ink),
            shape = skin.shape.copy(moduleStyle = style(skin)))
    }

    /**
     * Tiles and pills inside the stats card are drawn in `surface`. When the ink had to flip to the other polarity (T172:
     * chosen from the picture behind the card), they flip with it, so the text on them stays readable.
     */
    private fun nestedSurface(skin: Skin, surface: Long, ink: Long): Long {
        if (contrast(ink, surface) >= SkinSafety.MinModuleInkContrast) return surface
        val candidates = listOf(skin.colors.bg, skin.colors.moduleInk, skin.colors.textPrimary).map { opaque(it) }
        val best = candidates.maxBy { contrast(ink, it) }
        return if (contrast(ink, best) >= SkinSafety.MinModuleInkContrast) best
            else if (contrast(ink, 0xFFFFFFFF) >= contrast(ink, 0xFF000000)) 0xFFFFFFFF else 0xFF000000
    }

    private fun style(skin: Skin) = if (glassEnabled(skin)) ModuleStyle.frosted else ModuleStyle.solid

    private class Card(val surface: Long, val ink: Long)

    private fun card(surface: Long, skin: Skin, opacityPercent: Int?, keepReadable: Boolean, backdrop: Backdrop?): Card {
        val ink = ink(skin, surface)
        if (opacityPercent == null) return Card(tint(surface, ink, skin), ink)
        val requested = (HubOpacity.clamp(opacityPercent, glassEnabled(skin)) * 255 + 50) / 100
        if (glassEnabled(skin)) {
            if (!keepReadable) return Card((surface and 0xFFFFFF) or (requested.toLong() shl 24), ink)
            val behind = backdrop ?: Backdrop.haze(skin.colors.bg.toInt(), skin.colors.lineActive.toInt())
            val inks = listOf(skin.colors.moduleInk, skin.colors.textPrimary, skin.colors.bg).map { it.toInt() }
            val resolved = GlassReadability.resolve(surface.toInt(), inks, requested, behind)
            return Card((surface and 0xFFFFFF) or (resolved.alpha.toLong() shl 24), opaque(resolved.ink.toLong() and 0xFFFFFFFF))
        }
        val alpha = if (keepReadable) (WallpaperReadability.safeAlpha(surface.toInt(), listOf(ink.toInt()), requested / 255f) * 255f + 0.5f).toInt() else requested
        return Card((surface and 0xFFFFFF) or (alpha.toLong() shl 24), ink)
    }

    private fun tint(surface: Long, ink: Long, skin: Skin): Long {
        if (!glassEnabled(skin)) return surface
        // Increase opacity only where 70% would fail the required 7:1, especially Sand.
        // T153 haze consists solely of bg and lineActive; these bound its cached pixels.
        for (alpha in Metrics.hubGlassTintAlpha..255) {
            val low = WallpaperReadability.composite(surface.toInt(), skin.colors.bg.toInt(), alpha / 255f)
            val high = WallpaperReadability.composite(surface.toInt(), skin.colors.lineActive.toInt(), alpha / 255f)
            if (WallpaperReadability.contrast(ink.toInt(), low) >= 7 &&
                WallpaperReadability.contrast(ink.toInt(), high) >= 7 &&
                (WallpaperReadability.luminance(ink.toInt()) <= minOf(WallpaperReadability.luminance(low), WallpaperReadability.luminance(high)) ||
                    WallpaperReadability.luminance(ink.toInt()) >= maxOf(WallpaperReadability.luminance(low), WallpaperReadability.luminance(high))))
                return (surface and 0xFFFFFF) or (alpha.toLong() shl 24)
        }
        return surface
    }

    companion object {
        private fun opaque(colour: Long) = colour or 0xFF000000
        /** Prefer the skin's light/dark inks. Extreme custom palettes get neutral safety inks. */
        fun ink(skin: Skin, surface: Long): Long {
            val candidates = listOf(skin.colors.moduleInk, skin.colors.textPrimary, skin.colors.bg)
            val preferred = candidates.maxBy { contrast(it, surface) }
            return if (contrast(preferred, surface) >= SkinSafety.MinModuleInkContrast) opaque(preferred)
                else if (contrast(0xFFFFFFFF, surface) >= contrast(0xFF000000, surface)) 0xFFFFFFFF else 0xFF000000
        }
        private fun contrast(a: Long, b: Long) = WallpaperReadability.contrast(opaque(a).toInt(), opaque(b).toInt())
    }
}
