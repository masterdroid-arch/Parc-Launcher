package com.brightblu.parc

import android.content.Context
import androidx.compose.runtime.Composable
import com.brightblu.parc.model.Skin

/**
 * T293: the no-op stub for family and play (and the public snapshot). These builds have no personal
 * auto update, no data-pack downloader and no token; the real one lives in `personalExtras`.
 */
internal object UpdateVariant {
    const val Available = false

    @Composable
    fun AboutContent(skin: Skin) = Unit

    fun maybeAutoCheck(context: Context) = Unit
}
