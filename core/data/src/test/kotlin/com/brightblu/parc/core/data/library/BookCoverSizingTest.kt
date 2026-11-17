package com.brightblu.parc.core.data.library

import org.junit.Assert.*
import org.junit.Test

class BookCoverSizingTest {
    @Test fun portraitLandscapeAndWideShelfAlwaysFitTheWholeCover() {
        assertEquals(BookCoverSizing.Size(1080, 1512), BookCoverSizing.fit(1000, 1400, 1080, 2400))
        assertEquals(BookCoverSizing.Size(1714, 2400), BookCoverSizing.fit(1000, 1400, 3200, 2400))
        assertEquals(BookCoverSizing.Size(700, 350), BookCoverSizing.fit(2000, 1000, 700, 980))
        assertEquals(BookCoverSizing.Size(350, 490), BookCoverSizing.fit(1000, 1400, 700, 490))
    }

    @Test fun thumbnailsAndSmallOriginalsAreRejectedAtDisplaySize() {
        assertFalse(BookCoverSizing.sharpEnough(320, 448, 1080, 2400))
        assertFalse(BookCoverSizing.sharpEnough(320, 448, 700, 980))
        assertTrue(BookCoverSizing.sharpEnough(2000, 2800, 1080, 2400))
        assertTrue(BookCoverSizing.sharpEnough(320, 448, 160, 224))
        assertFalse(BookCoverSizing.sharpEnough(0, 0, 160, 224))
    }
}
