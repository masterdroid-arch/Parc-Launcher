package com.brightblu.parc.feature.settings.skins

import com.brightblu.parc.model.textFamily
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.brightblu.parc.model.TypeToken

/** ARGB skin token to a Compose colour. Shared by the settings screens; no literal colours here. */
internal fun color(argb: Long): Color = Color(argb.toInt())

/** A [TypeToken] applied with one skin colour. Shared so every settings surface uses the same roles. */
internal fun tokenStyle(tint: Long, token: TypeToken): TextStyle = TextStyle(
    color = color(tint),
    fontSize = token.sizeSp.sp,
    fontWeight = FontWeight(token.weight),
    letterSpacing = token.trackingSp.sp,
)

internal fun skinTokenStyle(skin: com.brightblu.parc.model.Skin, tint: Long, token: TypeToken): TextStyle =
    tokenStyle(tint, token).copy(fontFamily = skin.textFamily)
