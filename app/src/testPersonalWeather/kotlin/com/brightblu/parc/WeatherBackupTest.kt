package com.brightblu.parc

import com.brightblu.parc.core.data.backup.BackupSpecs
import com.brightblu.parc.core.data.backup.BackupStoreSpec
import com.brightblu.parc.core.data.backup.StoreAccess
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** M13 review: the saved Weather places travel in the backup; nothing else of Weather does. */
class WeatherBackupTest {
    private class Fake(var stored: Map<String, Any> = emptyMap()) : StoreAccess {
        override suspend fun read(spec: BackupStoreSpec) = stored
        override suspend fun write(spec: BackupStoreSpec, values: Map<String, Any>) { stored = stored + values }
    }
    private val lisbon = WeatherPlace("Lisbon", "Portugal", 38.72, -9.14)
    private val porto = WeatherPlace("Porto", "Portugal", 41.15, -8.61)

    @Test fun specHasOnlyThePlacesKey() {
        assertEquals(listOf("places"), BackupSpecs.weather.keys.map { it.name })
        assertTrue(BackupSpecs.weather in BackupSpecs.all)
    }

    @Test fun placesRoundTripThroughTheBackup() = runBlocking {
        val source = Fake(mapOf("places" to WeatherPlaces.encode(listOf(lisbon, porto))))
        val doc = WeatherBackupAccess(source).read(BackupSpecs.weather)
        val target = Fake()
        WeatherBackupAccess(target).write(BackupSpecs.weather, doc)
        assertEquals(listOf(lisbon, porto), WeatherPlaces.decode(target.stored["places"] as String))
    }

    @Test fun badItemsAreDroppedAndGarbageKeepsWhatIsThere() = runBlocking {
        val target = Fake(mapOf("places" to WeatherPlaces.encode(listOf(lisbon))))
        val access = WeatherBackupAccess(target)
        access.write(BackupSpecs.weather, mapOf("places" to """[{"name":"X","lat":999,"lon":0},{"name":"Porto","detail":"Portugal","lat":41.15,"lon":-8.61}]"""))
        assertEquals(listOf(porto), WeatherPlaces.decode(target.stored["places"] as String))
        access.write(BackupSpecs.weather, mapOf("places" to "not json"))
        access.write(BackupSpecs.weather, mapOf("places" to "[]"))
        access.write(BackupSpecs.weather, emptyMap())
        assertEquals(listOf(porto), WeatherPlaces.decode(target.stored["places"] as String))
    }

    @Test fun oldBackupWithoutWeatherLeavesPlacesAlone() = runBlocking {
        val target = Fake(mapOf("places" to WeatherPlaces.encode(listOf(lisbon))))
        WeatherBackupAccess(target).write(BackupSpecs.weather, emptyMap())
        assertEquals(listOf(lisbon), WeatherPlaces.decode(target.stored["places"] as String))
    }
}
