package com.brightblu.parc

import com.brightblu.parc.core.data.skins.SkinNaming
import com.brightblu.parc.model.Skin

internal data class SkinCardModel(val skin: Skin?, val selected: Boolean, val editable: Boolean) {
    val name get() = skin?.name ?: "Make your own"
}
internal fun skinCards(skins: List<Skin>, selectedId: String): List<SkinCardModel> =
    skins.map { SkinCardModel(it, it.id == selectedId, SkinNaming.isCustomId(it.id)) } +
        SkinCardModel(null, false, false)

/** What the skin page's Apply button says and whether it can be pressed. [note] explains a disabled button. */
internal data class SkinApplyState(val label: String, val enabled: Boolean, val note: String? = null)

/** T374: previewing a skin and applying it are separate steps; these rules name the pending skin on its button. */
internal object SkinPickerRules {
    fun apply(pending: Skin, applied: Skin, readable: Boolean): SkinApplyState = when {
        pending.id == applied.id -> SkinApplyState("${pending.name} is in use", enabled = false)
        !readable -> SkinApplyState("Apply ${pending.name}", enabled = false, note = "${pending.name}: text would be too hard to read")
        else -> SkinApplyState("Apply ${pending.name}", enabled = true)
    }

    /** Cancel appears only while a skin other than the saved one is being previewed. */
    fun showCancel(pending: Skin, applied: Skin): Boolean = pending.id != applied.id
}
