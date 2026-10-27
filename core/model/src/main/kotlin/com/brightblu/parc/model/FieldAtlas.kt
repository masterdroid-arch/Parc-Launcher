package com.brightblu.parc.model

/** Written T64 palette is authoritative; night variant keeps warm readable annotations. */
object FieldAtlas {
    val Style = SkinStyle(
        arc = ArcStyle.contour, ticks = true, bearings = true,
        iconFrame = IconFrame.registration, iconScale = 0.88f, iconOutlineDp = 1f,
        hub = HubStyle.compass, aim = AimStyle.distance, label = LabelStyle.mapLabel,
        background = BackgroundPattern.contour, statsFrame = StatsFrame.legend,
        header = HeaderStyle.ruled, clockFamily = "serif", clockWeight = 400,
        annotationFamily = "monospace",
    )
    val Light = Skin("field-atlas-light", "Field Atlas Light", SkinColors(
        bg = 0xFFF3EBDD, surface = 0xFFFBF5E8, textPrimary = 0xFF17252B,
        textSecondary = 0xFF4C5552, lineBase = 0xFF737B70, lineActive = 0xFFBF3826,
        accent = 0xFFBF3826, glow = 0x00BF3826, pulse = 0xFFBF3826, danger = 0xFFA02020,
        scrim = 0x9917252B, warn = 0xFF854600,
    ), style = Style, breatheAmount = 0f, parallaxDp = 0f,
        shape = ShapeTokens(surfaceAlpha = 1f), icons = IconTokens(saturation = 0.65f))
    val Dark = Skin("field-atlas-dark", "Field Atlas Dark", SkinColors(
        bg = 0xFF101F23, surface = 0xFF192B2D, textPrimary = 0xFFF3EBDD,
        textSecondary = 0xFFC2CBBD, lineBase = 0xFF728F87, lineActive = 0xFFF49A7F,
        accent = 0xFFF49A7F, glow = 0x00F49A7F, pulse = 0xFFF49A7F, danger = 0xFFFFAAA0,
        scrim = 0xB8101F23, warn = 0xFFF4C379,
    ), style = Style, breatheAmount = 0f, parallaxDp = 0f,
        shape = ShapeTokens(surfaceAlpha = 1f), icons = IconTokens(saturation = 0.65f))
}
