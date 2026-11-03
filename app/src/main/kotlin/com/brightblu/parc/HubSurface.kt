package com.brightblu.parc

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import com.brightblu.parc.model.*
import com.brightblu.parc.ring.module.LocalGlassRefraction
import com.brightblu.parc.ring.module.hubCardBackground

internal val LocalHubLook = compositionLocalOf { LookSettings() }
/** T173: a preview raster already holds the hub card (drawn by the same HubCardPaint); the live card must not draw it again. */
internal val LocalHubFrostDrawn = compositionLocalOf { false }

/**
 * T158 (DESIGN 3.4) / T176: the stats hub card. [cardSkin] is the card skin from [HubCards] (`moduleSurface` alpha final,
 * `moduleStyle` frosted when glass is on); the drawing is [com.brightblu.parc.ring.module.HubCardPaint], the same path as
 * the arc hub card, frosted or flat, in every skin.
 */
internal fun Modifier.hubSurface(cardSkin: Skin): Modifier = composed {
    if (LocalHubFrostDrawn.current) Modifier else Modifier.hubCardBackground(cardSkin, LocalGlassRefraction.current)
}
