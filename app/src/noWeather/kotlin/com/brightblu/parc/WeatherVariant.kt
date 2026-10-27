package com.brightblu.parc

import androidx.compose.runtime.Composable
import com.brightblu.parc.model.Skin

/**
 * T137: the normal builds (personal and play) have no Weather page. This stub is all they compile;
 * the page, its network code and its store live only in `src/personalWeather`. The build checks
 * that these builds have no INTERNET permission and no network classes.
 */
internal object WeatherVariant {
    const val Available = false
    const val PageId = "weather"

    @Composable fun Page(skin: Skin, active: Boolean, openOptions: () -> Unit) = Unit
    @Composable fun DemoPage(skin: Skin) = Unit
    @Composable fun Options(skin: Skin) = Unit
}
