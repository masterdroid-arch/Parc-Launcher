package com.brightblu.parc

import org.junit.Assert.*
import org.junit.Test

class AppsSoundFormatTest {
    @Test fun durationsDistinguishUnavailableZeroAndSubMinute() {
        assertEquals("—", AppsSoundFormat.time(null))
        assertEquals("—", AppsSoundFormat.time(-1))
        assertEquals("0 min", AppsSoundFormat.time(0))
        assertEquals("<1 min", AppsSoundFormat.time(59_999))
        assertEquals("59 min", AppsSoundFormat.time(3_599_999))
        assertEquals("1 h 0 min", AppsSoundFormat.time(3_600_000))
        assertEquals("27 h 1 min", AppsSoundFormat.time(97_260_000))
    }
    @Test fun sliderClampsRoundsAndHandlesFixedStream() {
        assertEquals(0, AppsSoundFormat.sliderIndex(Float.NaN, 15))
        assertEquals(0, AppsSoundFormat.sliderIndex(-1f, 15))
        assertEquals(15, AppsSoundFormat.sliderIndex(2f, 15))
        assertEquals(8, AppsSoundFormat.sliderIndex(.5f, 15))
        assertEquals(0, AppsSoundFormat.sliderIndex(.8f, 0))
        for (i in 0..15) assertEquals(i, AppsSoundFormat.sliderIndex(i / 15f, 15))
    }
    @Test fun barsHaveSafeBounds() {
        assertEquals(0f, AppsSoundFormat.bar(4, 0))
        assertEquals(0f, AppsSoundFormat.bar(-1, 4))
        assertEquals(.5f, AppsSoundFormat.bar(2, 4))
        assertEquals(1f, AppsSoundFormat.bar(Long.MAX_VALUE, Long.MAX_VALUE))
    }
    @Test fun newTilesParticipateInPersistentEditing() {
        for (page in listOf("apps", "sound")) {
            val tile = HubCatalog.tiles(page).first()
            val config = HubConfig().showTile(page, tile, false).move(page, tile, 1)
            val restored = HubConfig.decode(config.encode())
            assertEquals(tile, restored.tiles(page)[1])
            assertTrue(tile in restored.hiddenTiles[page].orEmpty())
        }
    }
}
