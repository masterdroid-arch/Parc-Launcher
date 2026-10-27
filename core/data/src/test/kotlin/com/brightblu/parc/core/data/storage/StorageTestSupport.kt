package com.brightblu.parc.core.data.storage

import com.brightblu.parc.model.AppEntry
import kotlinx.coroutines.Dispatchers

internal class FakeStorageProbe : StorageStatsProbe {

    var granted = true
    var throwOnGrant = false

    val samples = LinkedHashMap<String, PackageStorageSample>()
    val requested = ArrayList<String>()

    override fun usageAccessGranted(): Boolean {
        if (throwOnGrant) error("appops unavailable")
        return granted
    }

    override fun statsFor(packageName: String): PackageStorageSample? {
        requested += packageName
        return samples[packageName]
    }
}

internal fun storageRepository(
    probe: StorageStatsProbe,
    installed: () -> List<AppEntry>,
): AndroidAppStorageRepository = AndroidAppStorageRepository(
    probe = probe,
    installed = installed,
    ioDispatcher = Dispatchers.Unconfined,
)

internal fun storageSample(
    packageName: String,
    appBytes: Long,
    dataBytes: Long = 0L,
    cacheBytes: Long = 0L,
): PackageStorageSample = PackageStorageSample(
    packageName = packageName,
    appBytes = appBytes,
    dataBytes = dataBytes,
    cacheBytes = cacheBytes,
)
