package com.brightblu.parc.core.data.skins

import android.content.Context
import android.content.res.Configuration
import com.brightblu.parc.core.data.ParcSettings
import com.brightblu.parc.model.Skin
import com.brightblu.parc.model.SkinColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Which variant the Wallpaper skin uses (T96). Stored by name. */
enum class WallpaperMode(val label: String) { System("Follow system"), Dark("Dark"), Light("Light") }

/** Which authored look the Wallpaper skin copies its shapes, frames and type from (T96). Stored by name. */
enum class WallpaperLook(val label: String, val baseId: String) {
    Midnight("Midnight", "midnight"),
    FieldAtlas("Field Atlas", "field-atlas-dark"),
    Phosphor("Phosphor", "phosphor"),
}

/**
 * Android's dynamic colour tonal palettes (API 31+), each 13 shades in the platform order
 * 0, 10, 50, 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000. Opaque ARGB Longs. Immutable.
 */
class DynamicPalette private constructor(
    val accent1: List<Long>, val accent2: List<Long>, val neutral1: List<Long>, val neutral2: List<Long>,
) {
    companion object {
        const val Shades = 13

        /** Null unless every tone has all 13 shades. */
        fun orNull(accent1: List<Long>, accent2: List<Long>, neutral1: List<Long>, neutral2: List<Long>): DynamicPalette? =
            if (listOf(accent1, accent2, neutral1, neutral2).all { it.size == Shades }) {
                DynamicPalette(
                    accent1.map { it or 0xFF000000L }, accent2.map { it or 0xFF000000L },
                    neutral1.map { it or 0xFF000000L }, neutral2.map { it or 0xFF000000L },
                )
            } else null
    }
}

/** The system state the Wallpaper skin follows: the palette (null when unavailable) and night mode. */
data class DynamicEnvironment(val palette: DynamicPalette?, val systemNight: Boolean)

/** Pure construction of the generated Wallpaper skin (T96). */
object WallpaperSkin {
    const val Id = "wallpaper"
    const val Name = "Wallpaper"

    private const val S10 = 1; private const val S50 = 2; private const val S100 = 3; private const val S200 = 4
    private const val S300 = 5; private const val S500 = 7; private const val S600 = 8; private const val S700 = 9
    private const val S800 = 10; private const val S900 = 11; private const val S1000 = 12

    fun isDark(mode: WallpaperMode, systemNight: Boolean): Boolean = when (mode) {
        WallpaperMode.Dark -> true
        WallpaperMode.Light -> false
        WallpaperMode.System -> systemNight
    }

    private fun alpha(argb: Long, a: Int): Long = (a.toLong() shl 24) or (argb and 0xFFFFFFL)

    /** Every colour token from [palette]; text, accents, danger and warning are repaired to 4.5:1 on bg and panels. */
    fun colors(palette: DynamicPalette, dark: Boolean): SkinColors {
        val n1 = palette.neutral1; val n2 = palette.neutral2; val a1 = palette.accent1; val a2 = palette.accent2
        val toward = if (dark) 0xFFFFFFFFL else 0xFF000000L
        val bg = if (dark) n1[S1000] else n1[S10]
        val surface = if (dark) ColorMath.mix(n1[S1000], n1[S900], 0.5) else n1[S50]
        val backs = listOf(bg, surface)
        fun fix(c: Long) = ColorMath.ensureContrast(c, backs, ColorMath.TextContrast, toward)
        val accent = fix(if (dark) a1[S200] else a1[S700])
        return SkinColors(
            bg = bg,
            surface = surface,
            lineBase = if (dark) n2[S800] else n2[S300],
            lineActive = if (dark) n2[S600] else n2[S500],
            accent = accent,
            accent2 = fix(if (dark) a2[S200] else a2[S700]),
            glow = alpha(accent, if (dark) 0x47 else 0x40),
            textPrimary = fix(if (dark) n1[S100] else n1[S900]),
            textSecondary = fix(if (dark) n2[S300] else n2[S700]),
            pulse = fix(if (dark) a1[S100] else a1[S600]),
            danger = fix(if (dark) 0xFFE5736BL else 0xFFB3261EL),
            warn = fix(if (dark) 0xFFFFB000L else 0xFF8A5A00L),
            scrim = alpha(if (dark) n1[S1000] else n1[S900], if (dark) 0xB8 else 0x66),
        )
    }

    /** The Wallpaper skin: [base] supplies the whole look; only the colours (and id, name) are replaced. */
    fun build(palette: DynamicPalette, dark: Boolean, base: Skin): Skin =
        base.copy(id = Id, name = Name, colors = colors(palette, dark))

    /** The look's source skin from [catalog], else the built-in Midnight. */
    fun baseFor(look: WallpaperLook, catalog: List<Skin>): Skin =
        catalog.firstOrNull { it.id == look.baseId } ?: catalog.firstOrNull { it.id == Skin.Midnight.id } ?: Skin.Midnight

    /** Null when the device exposes no palette. */
    fun create(env: DynamicEnvironment, mode: WallpaperMode, look: WallpaperLook, catalog: List<Skin>): Skin? {
        val palette = env.palette ?: return null
        return build(palette, isDark(mode, env.systemNight), baseFor(look, catalog))
    }
}

/** Reads the system palette and night mode from resources; the Android-side shell of [WallpaperSkin]. */
object AndroidDynamicPalette {
    private val Shades = listOf("0", "10", "50", "100", "200", "300", "400", "500", "600", "700", "800", "900", "1000")

    fun read(context: Context): DynamicEnvironment {
        val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        return DynamicEnvironment(palette(context), night)
    }

    private fun palette(context: Context): DynamicPalette? = runCatching {
        fun tone(name: String): List<Long> = Shades.map { shade ->
            val id = context.resources.getIdentifier("system_${name}_$shade", "color", "android")
            check(id != 0)
            context.resources.getColor(id, context.theme).toLong() and 0xFFFFFFFFL
        }
        DynamicPalette.orNull(tone("accent1"), tone("accent2"), tone("neutral1"), tone("neutral2"))
    }.getOrNull()
}

/**
 * Holds the Wallpaper skin and rebuilds it when the system palette or night mode is re-read
 * ([refresh], on configuration change and resume) or when the mode or look setting changes.
 * Thread-safe: state is a [StateFlow]. [skin] is null on devices without dynamic colour.
 */
class WallpaperSkinSource(
    initial: DynamicEnvironment,
    settings: Flow<ParcSettings>,
    private val catalog: List<Skin>,
    scope: CoroutineScope,
    private val reader: (Context) -> DynamicEnvironment = AndroidDynamicPalette::read,
) {
    private val env = MutableStateFlow(initial)

    /** True when the device exposes a palette. */
    val supported: Boolean get() = env.value.palette != null

    val skin: StateFlow<Skin?> = combine(
        env, settings.map { it.wallpaperMode to it.wallpaperLook }.distinctUntilChanged(),
    ) { e, (mode, look) -> WallpaperSkin.create(e, mode, look, catalog) }
        .stateIn(scope, SharingStarted.Eagerly, WallpaperSkin.create(initial, WallpaperMode.System, WallpaperLook.Midnight, catalog))

    /** Re-reads the palette and night mode from [context]; cheap, call from the main thread. */
    fun refresh(context: Context) { env.value = reader(context) }

    /** Applies an already-read environment. */
    fun refresh(next: DynamicEnvironment) { env.value = next }
}
