package com.brightblu.parc

import androidx.compose.ui.text.font.FontWeight
import com.brightblu.parc.model.TypeToken
import com.brightblu.parc.model.Skin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** T221: release headings keep the skin token's weight; About and What's new no longer force Bold. */
class ReleaseNotesTypeTest {
    @Test fun versionBodyAndDateUseTheirOwnTokens() {
        val skin = Skin.Midnight
        assertEquals(skin.typography.menu.sizeSp + 2f, releaseTextToken(skin, heading = true, caption = false).sizeSp)
        assertEquals(skin.typography.menu, releaseTextToken(skin, heading = false, caption = false))
        assertEquals(skin.typography.caption, releaseTextToken(skin, heading = false, caption = true))
    }
    @Test fun headingUsesTheTokenWeight() {
        val light = TypeToken(28f, 300, 0f)
        assertEquals(FontWeight(300), releaseTextWeight(light))
        assertNotEquals(FontWeight.Bold, releaseTextWeight(light))
        assertEquals(FontWeight(450), releaseTextWeight(TypeToken(15f, 450, 0f)))
    }
}
