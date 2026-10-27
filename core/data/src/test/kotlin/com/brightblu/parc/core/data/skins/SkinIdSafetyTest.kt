package com.brightblu.parc.core.data.skins

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** OPUS6 (audit F15): a restored custom skin id can never act as a path. */
class SkinIdSafetyTest {
    @Test fun onlySlugCharactersAreAccepted() {
        assertTrue(SkinNaming.isCustomId("custom_dawn_2"))
        assertTrue(SkinNaming.isCustomId(SkinNaming.uniqueId("Late Night!", emptySet())))
        assertFalse(SkinNaming.isCustomId("custom_x/../../evil"))
        assertFalse(SkinNaming.isCustomId("custom_Upper"))
        assertFalse(SkinNaming.isCustomId("custom_"))
        assertFalse(SkinNaming.isCustomId("midnight"))
    }
}
