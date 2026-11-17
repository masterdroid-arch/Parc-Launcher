package com.brightblu.parc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T239: the family build has the internet features but none of the personal extras. */
class T239FamilyBuildTest {
    @Test fun registersNoExtraPages() {
        assertTrue(PersonalExtras.pages.isEmpty())
    }

    @Test fun hasWeatherAndTranslateButNoExtraPageIds() {
        assertTrue(WeatherVariant.Available)
        assertEquals(emptyList<String>(), PersonalExtras.pages.map { it.id })
    }

    @Test fun carriesTheReleaseVersion() {
        assertEquals("1.5.1", BuildConfig.VERSION_NAME)
        assertEquals(10501, BuildConfig.VERSION_CODE)
    }
}
