package com.brightblu.parc

import androidx.compose.ui.graphics.Color
import com.brightblu.parc.model.GlassBackdrop
import com.brightblu.parc.model.Skin

/**
 * T391: the fill of a panel that floats over other content and carries text or taps (tips, menus, chips, snackbars,
 * small sheets). It is the skin's surface colour at its own alpha, but never below [com.brightblu.parc.model.Metrics.floatingPanelMinAlpha],
 * so clock text, app icons or arcs behind it can no longer show through. Panels over a frosted skin use the cached frost
 * (`frostedBackground`) instead, which has the same floor.
 */
internal fun Skin.floatingSurface(): Color =
    Color(colors.surface.toInt()).copy(alpha = GlassBackdrop.floatingAlpha(shape.surfaceAlpha))
