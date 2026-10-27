package com.brightblu.parc.core.data.apps

import com.brightblu.parc.model.AppEntry
import com.brightblu.parc.model.AppId
import java.time.LocalDateTime
import java.time.ZoneId

/** One launchable test entry; the id is component-shaped like T04's real ids. */
internal fun testEntry(
    name: String,
    packageName: String = "com.test.$name",
    label: String = name.replaceFirstChar { it.uppercase() },
    isSystem: Boolean = false,
    installTime: Long = 0L,
    suspended: Boolean = false,
    activity: String = "Main",
): AppEntry = AppEntry(
    id = AppId("$packageName/$packageName.$activity@0"),
    label = label,
    packageName = packageName,
    isSystem = isSystem,
    installTime = installTime,
    suspended = suspended,
)

/** Epoch millis of a local wall-clock time, so tests never hardcode a timezone's offset. */
internal fun epoch(
    year: Int,
    month: Int,
    day: Int,
    hour: Int,
    minute: Int,
    zone: ZoneId,
): Long = LocalDateTime.of(year, month, day, hour, minute)
    .atZone(zone)
    .toInstant()
    .toEpochMilli()

internal fun bucket(
    packageName: String,
    firstMillis: Long,
    lastMillis: Long,
    foregroundMillis: Long,
): ForegroundBucket = ForegroundBucket(packageName, firstMillis, lastMillis, foregroundMillis)

/** The reference device's local zone has no DST; the DST cases use their own zone explicitly. */
internal val nairobi: ZoneId = ZoneId.of("Africa/Nairobi")

internal val newYork: ZoneId = ZoneId.of("America/New_York")

internal const val HourMillis = 60L * 60L * 1000L
internal const val DayMillis = 24L * HourMillis
