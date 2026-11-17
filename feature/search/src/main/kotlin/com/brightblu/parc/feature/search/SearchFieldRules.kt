package com.brightblu.parc.feature.search

import androidx.compose.ui.text.input.ImeAction

/**
 * T195 follow-up: the search field's Enter rules. The soft keyboard's action key always submits
 * (Go), also in the multi-line Note mode, so a quick note is saved with one tap. A new line comes
 * only from Shift+Enter on a hardware keyboard or from pasting several lines.
 */
internal object SearchFieldRules {
    val imeAction: ImeAction = ImeAction.Go

    /** A hardware Enter submits in a multi-line field unless Shift is held. (Single-line fields submit through the IME action.) */
    fun hardwareEnterSubmits(multiline: Boolean, isEnterKey: Boolean, shift: Boolean): Boolean =
        multiline && isEnterKey && !shift
}
