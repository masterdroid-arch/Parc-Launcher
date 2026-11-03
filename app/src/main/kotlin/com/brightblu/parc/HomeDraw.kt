package com.brightblu.parc

import com.brightblu.parc.model.Backdrop
import com.brightblu.parc.model.GlassBackdrop
import com.brightblu.parc.model.HubAppearance
import com.brightblu.parc.model.LookSettings
import com.brightblu.parc.model.ModuleStyle
import com.brightblu.parc.model.Skin
import com.brightblu.parc.model.WallpaperStyle

/**
 * T176: the two hub card skins, derived in ONE place for Home, the Settings previews and the skin gallery. Glass is one rule
 * for both hubs in every skin: [glass] on means both cards are frosted (their skins say `ModuleStyle.frosted`), off means both
 * are flat cards at their own opacity. The skin only gives the default of [glass] (the Glass skin: on).
 * [arcBackdrop] / [statsBackdrop] are what sits behind each card (measured from the picture by Home); null uses the haze.
 */
internal class HubCards(val arc: Skin, val stats: Skin, val glass: Boolean, val refraction: Boolean,
    val arcOpacity: Int, val statsOpacity: Int) {
    companion object {
        fun of(skin: Skin, look: LookSettings, appearance: HubAppearance, arcBackdrop: Backdrop? = null,
            statsBackdrop: Backdrop? = null): HubCards {
            val glass = appearance.glassEnabled(skin)
            val arcOpacity = appearance.arcOpacityPercent(glass)
            val statsOpacity = appearance.statsOpacityPercent(glass)
            val readable = keepReadable(look)
            return HubCards(appearance.arcSkin(skin, arcOpacity, readable, arcBackdrop),
                appearance.statsSkin(skin, statsOpacity, readable, statsBackdrop), glass, appearance.refraction && glass,
                arcOpacity, statsOpacity)
        }

        /**
         * "Keep text readable" acts only where there is a wallpaper or picture behind Parc (the Wallpaper page shows the switch
         * exactly then). Over the skin's own colour it has nothing to guard against, so it never changes the cards there.
         */
        fun keepReadable(look: LookSettings) = look.wallpaperReadability &&
            (look.wallpaper == WallpaperStyle.System || look.wallpaper == WallpaperStyle.Image)
    }
}

/** One hub card as Home draws it. */
internal data class CardDraw(val surface: Long, val ink: Long, val frosted: Boolean, val refraction: Boolean)

/**
 * T175: what Home would draw for a settings snapshot, built from the same functions Home uses ([HubCards],
 * [GlassBackdrop.pictureLook]). The wiring test changes each visual setting and checks this changes; the emulator audit
 * checks the same on real screenshots.
 */
internal data class HomeDraw(
    val skinId: String,
    val wallpaper: WallpaperStyle,
    /** Dim laid on the phone wallpaper or baked into the picture (0 where there is none). */
    val wallpaperDim: Int,
    /** The picture's readability scrim (only without glass; glass keeps the text readable on the cards). */
    val pictureScrim: Boolean,
    /** The readability gradient over the phone wallpaper (flat cards only; glass keeps text readable on the cards). */
    val systemScrim: Boolean,
    /** Glass with "Keep text readable": Home measures the picture behind each card and picks its ink (T172). */
    val glassReadable: Boolean,
    val arcCard: CardDraw,
    val statsCard: CardDraw?,
    val iconShape: String,
    val iconSizeDp: Int,
    val iconPack: String?,
    val clock: String?,
    val arcs: Int,
    val iconsPerArc: Int,
    val aligned: Boolean,
    val hubSize: String?,
    val badges: Boolean,
    val markWorkApps: Boolean,
    val wallpaperSkin: String?,
) {
    companion object {
        fun of(s: SettingsSnapshot): HomeDraw {
            val look = s.look
            val cards = HubCards.of(s.skin, look, s.hub)
            fun card(skin: Skin) = CardDraw(skin.colors.moduleSurface, skin.colors.moduleInk,
                skin.shape.moduleStyle == ModuleStyle.frosted, cards.refraction)
            val picture = GlassBackdrop.pictureLook(look, cards.glass)
            val hasWallpaper = s.hasWallpaper
            return HomeDraw(
                skinId = s.skinId,
                wallpaper = look.wallpaper,
                wallpaperDim = if (hasWallpaper) picture.wallpaperDimPercent else 0,
                pictureScrim = look.wallpaper == WallpaperStyle.Image && picture.imageReadabilityScrim,
                systemScrim = look.wallpaper == WallpaperStyle.System && look.wallpaperReadability && !cards.glass,
                glassReadable = cards.glass && HubCards.keepReadable(look),
                arcCard = card(cards.arc),
                statsCard = if (s.statsVisible) card(cards.stats) else null,
                iconShape = look.iconShape.name,
                iconSizeDp = look.iconSizeDp,
                iconPack = s.iconPack,
                // The date and alarm lines are drawn only by the standalone clock (stats hub off); the Clock page always has both.
                clock = if (!s.clockVisible) null else if (s.statsVisible) "${look.clockStyle}"
                    else "${look.clockStyle}/${look.showDate}/${look.showAlarm}",
                arcs = s.counts.arcs,
                iconsPerArc = s.counts.icons,
                aligned = s.aligned,
                hubSize = if (s.statsVisible) s.hubSize.name else null,
                badges = s.badges,
                markWorkApps = s.markWorkApps,
                wallpaperSkin = if (s.skinId == "wallpaper") "${s.wallpaperMode}/${s.wallpaperLook}" else null,
            )
        }
    }
}
