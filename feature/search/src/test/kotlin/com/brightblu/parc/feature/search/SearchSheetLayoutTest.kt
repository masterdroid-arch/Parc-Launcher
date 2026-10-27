package com.brightblu.parc.feature.search

import com.brightblu.parc.model.Metrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** OPUS1: the search sheet's top edge stays below the status bar and the pull-down search bar. */
class SearchSheetLayoutTest {
    private val pullBand = Metrics.keepOutTopDp + Metrics.searchPullBarHeightDp

    @Test fun clearsThePullBarBandOnANormalStatusBar() {
        assertEquals(pullBand, SearchSheetLayout.topClearanceDp(24f), 0.001f)
    }

    @Test fun clearsATallStatusBarOrCutout() {
        assertEquals(140f, SearchSheetLayout.topClearanceDp(140f), 0.001f)
    }

    @Test fun neverNegativeAndNeverAboveTheBand() {
        assertEquals(pullBand, SearchSheetLayout.topClearanceDp(-5f), 0.001f)
        for (status in listOf(0f, 24f, 47f, 56f, 104f, 200f)) {
            assertTrue(SearchSheetLayout.topClearanceDp(status) >= status)
            assertTrue(SearchSheetLayout.topClearanceDp(status) >= pullBand)
        }
    }
}
