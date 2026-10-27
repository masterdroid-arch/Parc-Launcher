package com.brightblu.parc

import com.brightblu.parc.model.LookJson
import com.brightblu.parc.model.LookSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** OPUS6 (audit F4): an unreadable look document is kept before a change starts from defaults. */
class LookDocumentTest {
    private val defaults = LookSettings()

    @Test fun aReadableDocumentIsChangedInPlaceAndNothingIsKeptAside() {
        val stored = LookJson.encode(defaults.copy(panelOpacityPercent = 60))
        val (next, keep) = LookDocument.apply(stored, null, defaults) { it.copy(showDate = true) }
        assertEquals(60, LookJson.decode(next).panelOpacityPercent)
        assertNull(keep)
    }

    @Test fun anUnreadableDocumentIsKeptAsideNotLost() {
        val (next, keep) = LookDocument.apply("{damaged", null, defaults) { it.copy(showDate = true) }
        assertEquals("{damaged", keep)
        assertEquals(true, LookJson.decode(next).showDate)
        // An earlier kept copy is never replaced.
        assertNull(LookDocument.apply("{other", "{damaged", defaults) { it }.second)
    }
}
