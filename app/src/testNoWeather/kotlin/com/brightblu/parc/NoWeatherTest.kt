package com.brightblu.parc

import org.junit.Assert.assertFalse
import org.junit.Test

/** T137: the personal and play builds have no Weather page, no weather options and no demo weather. */
class NoWeatherTest {
    @Test fun normalBuildsHaveNoWeatherPage() {
        assertFalse(WeatherVariant.Available)
        assertFalse(WeatherVariant.PageId in HubCatalog.pages)
        assertFalse(WeatherVariant.PageId in hubTemplates)
        assertFalse(WeatherVariant.PageId in hubPageOptions)
        assertFalse(WeatherVariant.PageId in DemoHubPages)
        assertFalse(WeatherVariant.PageId in HubConfig().pages)
    }

    @Test fun aWeatherRowFromABackupOfTheWeatherVariantIsDropped() {
        val fromWeatherBuild = "clock:true::;weather:true::;system:true:battery:"
        val config = HubConfig.decode(fromWeatherBuild)
        assertFalse("weather" in config.orderedPages)
        assertFalse("weather" in config.encode())
    }

    @Test fun noOpenMeteoOrNetworkClassIsOnTheTestClasspath() {
        listOf("com.brightblu.parc.WeatherHttp", "com.brightblu.parc.OpenMeteo", "com.brightblu.parc.WeatherStore").forEach {
            assertFalse(it, runCatching { Class.forName(it) }.isSuccess)
        }
    }
}
