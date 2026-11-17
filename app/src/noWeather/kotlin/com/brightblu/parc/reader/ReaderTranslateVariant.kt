package com.brightblu.parc.reader

import androidx.compose.runtime.Composable
import com.brightblu.parc.model.Skin

/**
 * T256: these builds have no internet, so the reader has no Translate action. The real sheet lives
 * in `src/personalWeather`, beside the DeepL code the search Translate mode already uses.
 */
internal object ReaderTranslateVariant {
    const val Available = false

    @Composable
    fun Sheet(text: String, skin: Skin, onClose: () -> Unit) = Unit
}
