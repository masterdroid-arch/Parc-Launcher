package com.brightblu.parc

import androidx.compose.ui.text.TextStyle
import com.brightblu.parc.model.Skin
import com.brightblu.parc.model.TypeToken

/*
 * T323: Messages on the approved six-role type scale. Every line of the page names a role instead
 * of carrying a size, weight or family, so the skin's tokens and the bundled Figtree reach it.
 *   clock    44 sp / 200  the quiet clock (no place on Messages)
 *   title    28 sp / 300  the page heading
 *   stat     28 sp / 600  a numeral readout (no place on Messages)
 *   section  17 sp / 500  a mark or a control that must stand clear of body text
 *   body     15 sp / 400  message text, senders, fields and actions
 *   caption  11 sp / 500  app names, times, counts and hints
 */

internal object CommsType {
    fun clock(skin: Skin): TypeToken = skin.typography.clock
    fun title(skin: Skin): TypeToken = skin.typography.title
    fun stat(skin: Skin): TypeToken = skin.typography.stat
    fun section(skin: Skin): TypeToken = skin.typography.section
    fun body(skin: Skin): TypeToken = skin.typography.body
    fun caption(skin: Skin): TypeToken = skin.typography.caption

    /** The six roles in scale order, for the guard test and for anybody reading the mapping. */
    fun all(skin: Skin): List<TypeToken> =
        listOf(clock(skin), title(skin), stat(skin), section(skin), body(skin), caption(skin))
}

/**
 * One role applied through the shared HUD style: size, weight, tracking and tabular figures come
 * from the token, and the family is always the skin's reading family (Figtree).
 */
internal fun commsStyle(
    skin: Skin,
    role: TypeToken = CommsType.body(skin),
    color: Long? = null,
): TextStyle = hudValueStyle(skin, color, role)
