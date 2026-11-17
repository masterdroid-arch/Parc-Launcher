package com.brightblu.parc.reader

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.brightblu.parc.TranslateSearchMode
import com.brightblu.parc.feature.search.SearchModeHost
import com.brightblu.parc.model.Skin

/**
 * T256: Translate in the reader. It is the same [TranslateSearchMode] the search sheet uses, so the
 * DeepL rules, key, history and result surface are shared. The sheet is only composed after the
 * reader's Translate action is tapped, so selected text is never sent before that tap.
 */
internal object ReaderTranslateVariant {
    const val Available = true

    @Composable
    fun Sheet(text: String, skin: Skin, onClose: () -> Unit) {
        val context = LocalContext.current
        val mode = remember(text) { TranslateSearchMode(sendAtOnce = true) }
        val host = remember(context) {
            object : SearchModeHost {
                override val context: Context = context
                override fun setText(text: String) = Unit
            }
        }
        ReaderSheetFrame("Translate", skin, onClose) {
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
                BasicText("Selected text", style = readerLabelStyle(skin))
                BasicText(text, Modifier.fillMaxWidth(), style = readerValueStyle(skin))
                BasicText("Selected text is sent to DeepL and kept in your translation history on this phone.",
                    Modifier.fillMaxWidth(), style = readerLabelStyle(skin))
                mode.Body(text, skin, host)
            }
        }
    }
}
