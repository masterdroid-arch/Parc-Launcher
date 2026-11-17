package com.brightblu.parc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T198: the play build carries no extra page, in the catalog, the demo list, the page options or the backup. */
class ExtraPagesPlayTest {
    @Test fun playHasNoExtraPages() {
        assertTrue(PersonalExtras.pages.isEmpty())
        assertEquals(HubCatalog.pages.toSet(), hubPageOptions.keys)
        assertTrue(DemoHubPages.all { it in HubCatalog.pages })
        assertEquals(DemoHubPages.size, DemoHubPages.toSet().size)
    }
}
