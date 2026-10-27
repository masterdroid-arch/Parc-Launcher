package com.brightblu.parc.model

/** T64 terminal palette; all effects are static prepared artwork. */
object Phosphor {
    val Skin = com.brightblu.parc.model.Skin("phosphor", "Phosphor", SkinColors(
        bg = 0xFF080E0B, surface = 0xFF101C14, textPrimary = 0xFFB4F6AF,
        textSecondary = 0xFF93B498, lineBase = 0xFF54765A, lineActive = 0xFFB4F6AF,
        accent = 0xFFB4F6AF, glow = 0x00B4F6AF, pulse = 0xFFFFBF69,
        danger = 0xFFFF9487, scrim = 0xB8080E0B, warn = 0xFFFFBF69,
    ), typography = TypographyTokens(family = "monospace", fallbackFamily = "monospace"),
        style = SkinStyle(arc = ArcStyle.dotted, ticks = true,
            iconFrame = IconFrame.brackets, iconScale = 0.8f, iconOutlineDp = 1f,
            hub = HubStyle.crosshair, aim = AimStyle.coordinates, label = LabelStyle.brackets,
            statsFrame = StatsFrame.terminal, terminalStats = true, header = HeaderStyle.ruled,
            clockFamily = "monospace", clockWeight = 400, annotationFamily = "monospace"),
        breatheAmount = 0f, parallaxDp = 0f,
        depthAlpha = listOf(1f, 1f, 1f, 1f),
        shape = ShapeTokens(surfaceAlpha = 1f, surfaceCornerDp = 0f),
        icons = IconTokens(saturation = 0f))
}
