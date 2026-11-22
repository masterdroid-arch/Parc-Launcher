package com.brightblu.parc.model

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.brightblu.parc.core.model.R

/**
 * Bundled fonts (OFL, see NOTICE). Variable fonts, loaded from res/font with no font provider and
 * no network. One entry per 100 weight step; Compose picks the nearest to a requested weight.
 * Figtree's axis starts at 300, so lighter requests resolve to 300.
 */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
object ParcFonts {
    val Figtree: FontFamily = FontFamily(
        (3..9).map { variable(R.font.figtree_variable, it * 100) },
    )

    val Doto: FontFamily = FontFamily(
        (1..9).map { variable(R.font.doto_variable, it * 100) },
    )

    private fun variable(resId: Int, weight: Int): Font = Font(
        resId,
        FontWeight(weight),
        variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
    )
}

/** Launcher text uses Figtree for every skin, including imported legacy skins. */
val Skin.textFamily: FontFamily get() = ParcFonts.Figtree
