package com.brightblu.parc

import com.brightblu.parc.model.*
import org.junit.Assert.*
import org.junit.Test

class T114Test {
    @Test fun oldReadabilityKeysMigrateForEveryCombinationAndBackground() {
        for (style in WallpaperStyle.entries) for (a in listOf(false, true)) for (b in listOf(false, true)) {
            val old = """{"wallpaper":"$style","wallpaperReadability":$a,"imageReadabilityScrim":$b}"""
            val next = LookJson.decode(old)
            val expected = when (style) { WallpaperStyle.Image -> b; WallpaperStyle.System -> a; else -> a || b }
            assertEquals(expected, next.keepTextReadable)
            assertEquals(expected, next.wallpaperReadability)
            assertEquals(expected, next.imageReadabilityScrim)
            assertEquals(next, LookJson.decode(LookJson.encode(next)))
        }
        assertEquals(true, LookJson.decode("{}").keepTextReadable)
    }
    @Test fun explicitNewValueWinsOnRestoringAMixedBackup() {
        val next = LookJson.decode("""{"keepTextReadable":false,"wallpaperReadability":true,"imageReadabilityScrim":true}""")
        assertFalse(next.wallpaperReadability)
        assertFalse(next.imageReadabilityScrim)
    }
}
