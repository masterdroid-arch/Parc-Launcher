package com.brightblu.parc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T258: each hub page can keep its own size; the global size stays the default. */
class T258Test {
    @Test
    fun aPageWithoutItsOwnSizeFollowsTheDefault() {
        val config = HubConfig(size = HubSize.Tall)
        assertEquals(HubSize.Tall, config.sizeFor("today"))
        assertEquals(HubSize.Tall, config.sizeFor(null))
        val own = config.withPageSize("today", HubSize.Compact)
        assertEquals(HubSize.Compact, own.sizeFor("today"))
        assertEquals(HubSize.Tall, own.sizeFor("device"))
        assertFalse("today" in own.withPageSize("today", null).pageSizes)
        // An unknown page id never gets a size.
        assertEquals(config, config.withPageSize("nonsense", HubSize.Compact))
    }

    @Test
    fun ownSizesSurviveSaveAndOldDocumentsAndReadersStillWork() {
        val config = HubConfig().withPageSize("today", HubSize.Tall).withPageSize("system", HubSize.Compact)
        val back = HubConfig.decode(config.encode())
        assertEquals(config.pageSizes, back.pageSizes)
        assertEquals(config.pages, back.pages)
        // A document written before this card has no size row.
        assertTrue(HubConfig.decode(HubConfig().encode()).pageSizes.isEmpty())
        // The extra row is skipped by a four-part row filter, so page rows are unchanged.
        assertEquals(HubConfig().encode(), HubConfig.decode(HubConfig().encode()).encode())
        // Unknown pages and sizes in a stored row are dropped, the good pair is kept.
        val raw = HubConfig().encode() + ";~sizes:today=tall,ghost=tall,device=huge,device"
        assertEquals(mapOf("today" to HubSize.Tall), HubConfig.decode(raw).pageSizes)
    }

    @Test
    fun theSizeStaysWhenAPageIsHiddenAndShownAgain() {
        val config = HubConfig().withPageSize("today", HubSize.Tall)
        val round = config.showPage("today", false).showPage("today", true)
        assertEquals(HubSize.Tall, round.sizeFor("today"))
    }
}
