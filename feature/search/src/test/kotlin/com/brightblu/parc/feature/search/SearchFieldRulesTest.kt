package com.brightblu.parc.feature.search

import androidx.compose.ui.text.input.ImeAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T195 follow-up: in Note mode the soft keyboard's action key saves; a newline is Shift+Enter or a paste. */
class SearchFieldRulesTest {
    @Test fun softKeyboardActionKeyIsGoForEveryMode() = assertEquals(ImeAction.Go, SearchFieldRules.imeAction)

    @Test fun hardwareEnterSavesInMultilineModesUnlessShifted() {
        assertTrue(SearchFieldRules.hardwareEnterSubmits(multiline = true, isEnterKey = true, shift = false))
        assertFalse(SearchFieldRules.hardwareEnterSubmits(multiline = true, isEnterKey = true, shift = true))
        assertFalse(SearchFieldRules.hardwareEnterSubmits(multiline = true, isEnterKey = false, shift = false))
        assertFalse(SearchFieldRules.hardwareEnterSubmits(multiline = false, isEnterKey = true, shift = false))
    }
}
