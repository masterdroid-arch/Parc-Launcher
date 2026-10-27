package com.brightblu.parc

import com.brightblu.parc.core.data.skins.SkinNaming
import com.brightblu.parc.model.Skin

internal data class SkinCardModel(val skin: Skin?, val selected: Boolean, val editable: Boolean) {
    val name get() = skin?.name ?: "Make your own"
}
internal fun skinCards(skins: List<Skin>, selectedId: String): List<SkinCardModel> =
    skins.map { SkinCardModel(it, it.id == selectedId, SkinNaming.isCustomId(it.id)) } +
        SkinCardModel(null, false, false)
