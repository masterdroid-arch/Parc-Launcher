package com.brightblu.parc.feature.settings.skins

import com.brightblu.parc.core.data.HapticsLevel
import com.brightblu.parc.model.Skin

/**
 * Immutable screen state (DESIGN §5 S14). Non-null; [skins] is an immutable snapshot with the default
 * first. [selectedSkinId] is always a valid catalog id, so [selectedSkin] cannot fail.
 */
data class SettingsUiState(
    val skins: List<Skin>,
    val selectedSkinId: String,
    val haptics: HapticsLevel = HapticsLevel.Light,
    val clockVisible: Boolean = true,
) {
    init {
        require(skins.isNotEmpty()) { "The picker needs at least the default skin" }
    }

    val selectedSkin: Skin get() = skins.firstOrNull { it.id == selectedSkinId } ?: skins.first()
}
