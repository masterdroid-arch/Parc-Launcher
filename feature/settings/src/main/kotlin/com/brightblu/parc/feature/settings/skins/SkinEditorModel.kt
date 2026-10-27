package com.brightblu.parc.feature.settings.skins

import com.brightblu.parc.core.data.skins.ColorMath
import com.brightblu.parc.core.data.skins.SkinNaming
import com.brightblu.parc.model.Skin
import com.brightblu.parc.model.SkinColors

/** The editable colour tokens, in the order the editor lists them. */
enum class ColorToken(val label: String, val help: String, val textOn: ColorToken? = null) {
    Bg("Background", "Behind everything"),
    Surface("Panels", "Tiles and menus"),
    LineBase("Lines", "Arcs at rest"),
    LineActive("Active lines", "Arcs while aiming"),
    Accent("Accent", "Highlights and the hub"),
    Accent2("Second accent", "Secondary highlights"),
    Glow("Glow", "Touch glow, translucent"),
    Pulse("Pulse", "Attention pulse"),
    TextPrimary("Text", "Main text", Bg),
    TextSecondary("Dim text", "Captions and hints", Bg),
    Danger("Danger", "Errors and delete", Bg),
    Warn("Warning", "Heat and alerts", Bg),
    Scrim("Scrim", "Dimming behind menus, translucent");

    fun get(c: SkinColors): Long = when (this) {
        Bg -> c.bg; Surface -> c.surface; LineBase -> c.lineBase; LineActive -> c.lineActive
        Accent -> c.accent; Accent2 -> c.accent2; Glow -> c.glow; Pulse -> c.pulse
        TextPrimary -> c.textPrimary; TextSecondary -> c.textSecondary; Danger -> c.danger; Warn -> c.warn; Scrim -> c.scrim
    }

    fun set(c: SkinColors, argb: Long): SkinColors = when (this) {
        Bg -> c.copy(bg = argb); Surface -> c.copy(surface = argb); LineBase -> c.copy(lineBase = argb)
        LineActive -> c.copy(lineActive = argb); Accent -> c.copy(accent = argb); Accent2 -> c.copy(accent2 = argb)
        Glow -> c.copy(glow = argb); Pulse -> c.copy(pulse = argb); TextPrimary -> c.copy(textPrimary = argb)
        TextSecondary -> c.copy(textSecondary = argb); Danger -> c.copy(danger = argb); Warn -> c.copy(warn = argb)
        Scrim -> c.copy(scrim = argb)
    }

    /** Tokens that carry an alpha channel by design; the editor shows an alpha slider for them. */
    val translucent: Boolean get() = this == Glow || this == Scrim
}

/** One text-on-background pair and whether it passes WCAG AA (4.5:1). */
data class ContrastCheck(val token: ColorToken, val ratio: Double) { val passes: Boolean get() = ratio >= ColorMath.TextContrast }

/**
 * Pure state of the skin editor (T85): a base skin, the colours being edited, and the name. Immutable;
 * every change returns a new model. [existingId] is the custom skin being edited, null for a new one.
 */
data class SkinEditorModel(
    val base: Skin,
    val colors: SkinColors = base.colors,
    val name: String = "",
    val existingId: String? = null,
) {
    fun withColor(token: ColorToken, argb: Long): SkinEditorModel = copy(colors = token.set(colors, argb and 0xFFFFFFFFL))
    fun withName(value: String): SkinEditorModel = copy(name = value.take(SkinNaming.MaxNameLength + 8))
    fun reset(): SkinEditorModel = copy(colors = base.colors)

    val isDirty: Boolean get() = colors != base.colors
    val nameValid: Boolean get() = SkinNaming.isValidName(name)

    /** Contrast of each text-like token against the background and panel colours (the worse of the two). */
    fun contrast(): List<ContrastCheck> = ColorToken.entries.filter { it.textOn != null }.map { t ->
        val fg = t.get(colors)
        ContrastCheck(t, minOf(ColorMath.contrast(fg, colors.bg), ColorMath.contrast(fg, colors.surface)))
    }

    fun failing(): List<ContrastCheck> = contrast().filter { !it.passes }

    /** OPUS6 (audit F7): the colours are readable enough to switch this skin on. */
    fun canUse(): Boolean = com.brightblu.parc.model.SkinSafety.readable(base.copy(colors = colors))

    fun canSave(takenNames: Collection<String>): Boolean = nameValid &&
        takenNames.none { it.equals(SkinNaming.clean(name), ignoreCase = true) }

    /** The skin to save: a stable id when editing, a fresh unique one otherwise. */
    fun toSkin(takenIds: Set<String>): Skin {
        val clean = SkinNaming.clean(name)
        val id = existingId ?: SkinNaming.uniqueId(clean, takenIds)
        return base.copy(id = id, name = clean, colors = colors)
    }

    companion object {
        /** Start from a skin: a custom one edits in place, a built-in becomes a new copy named after it. */
        fun from(skin: Skin): SkinEditorModel =
            if (SkinNaming.isCustomId(skin.id)) SkinEditorModel(skin, skin.colors, skin.name, skin.id)
            else SkinEditorModel(skin, skin.colors, "${skin.name} copy".take(SkinNaming.MaxNameLength), null)
    }
}
