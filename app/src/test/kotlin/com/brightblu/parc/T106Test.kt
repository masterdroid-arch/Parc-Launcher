package com.brightblu.parc

import com.brightblu.parc.model.*
import com.brightblu.parc.core.data.backup.*
import org.junit.Assert.*
import org.junit.Test

class T106Test {
    @Test fun cropStaysInsideSourceAtEveryPanZoomAndAspect() {
        for ((w,h) in listOf(4000 to 3000, 3000 to 4000, 1 to 1))
            for (aspect in listOf(384f/853f, 360f/800f, 411f/914f))
                for (zoom in listOf(1f, 2f, 4f)) for (pan in listOf(-1f, 0f, 1f)) {
                    val crop = WallpaperImageRules.crop(w,h,aspect,zoom,pan,-pan)
                    assertTrue(crop.left >= 0 && crop.top >= 0)
                    assertTrue(crop.left + crop.width <= w + .001 && crop.top + crop.height <= h + .001)
                    assertEquals(aspect, crop.width/crop.height, .0001f)
                }
        assertEquals(0f, WallpaperImageRules.crop(4000,3000,.5f,1f,-1f,-1f).left)
    }
    @Test fun downscaleNeverUpscalesAndSamplingIsBounded() {
        assertEquals(648 to 1440, WallpaperImageRules.output(1080f,2400f))
        assertEquals(100 to 200, WallpaperImageRules.output(100f,200f))
        assertEquals(1, WallpaperImageRules.sampleSize(1440,648))
        assertTrue(WallpaperImageRules.sampleSize(16000,12000) >= 4)
    }
    @Test fun backupDefaultsOffAndHasExactBinaryCap() {
        assertFalse(LookSettings().includeWallpaperInBackups)
        assertFalse(WallpaperImageRules.includeInBackup(false,1))
        assertFalse(WallpaperImageRules.includeInBackup(true,0))
        assertTrue(WallpaperImageRules.includeInBackup(true,Metrics.wallpaperMaxBytes.toLong()))
        assertFalse(WallpaperImageRules.includeInBackup(true,Metrics.wallpaperMaxBytes.toLong()+1))
        assertNotNull(BackupSpecs.byId("wallpaper_image"))
        assertTrue(BackupSpecs.wallpaperImage.keys.single().maxLength >= (Metrics.wallpaperMaxBytes + 2) / 3 * 4)
        assertTrue(BackupCodec.MaxDocumentChars > BackupSpecs.wallpaperImage.keys.single().maxLength)
    }
    @Test fun backupImageRoundTripsOnlyWhenIncludedAndRejectsBadData() {
        val bytes = byteArrayOf(1,2,3,4)
        assertNull(WallpaperImageBackupRules.encode(false,bytes))
        assertArrayEquals(bytes, WallpaperImageBackupRules.decode(WallpaperImageBackupRules.encode(true,bytes)!!))
        assertNull(WallpaperImageBackupRules.decode("%%%"))
        assertNull(WallpaperImageBackupRules.decode(""))
        assertNull(WallpaperImageBackupRules.encode(true,ByteArray(Metrics.wallpaperMaxBytes+1)))
    }
    @Test fun settingsRoundTripAndOldDocumentsUseSafeDefaults() {
        val image = LookSettings(wallpaper = WallpaperStyle.Image, includeWallpaperInBackups = true, imageReadabilityScrim = false, wallpaperImageRevision = 123)
        assertEquals(LookJson.migrate(image), LookJson.decode(LookJson.encode(image)))
        assertFalse(LookJson.decode("{}").includeWallpaperInBackups)
        assertTrue(LookJson.decode("{}").imageReadabilityScrim)
        assertEquals("Image", LookRules.wallpaperLabel(WallpaperStyle.Image))
    }
    @Test fun missingCorruptOrRemovedImageFallsBack() {
        assertEquals(WallpaperStyle.Black, WallpaperImageRules.effectiveStyle(WallpaperStyle.Image,false))
        assertEquals(WallpaperStyle.Image, WallpaperImageRules.effectiveStyle(WallpaperStyle.Image,true))
        assertEquals(WallpaperStyle.System, WallpaperImageRules.effectiveStyle(WallpaperStyle.System,false))
    }
    @Test fun imageScrimProtectsAllSkinsAgainstBrightAndDarkExtremes() {
        for (skin in listOf(Skin.Midnight, FieldAtlas.Light, FieldAtlas.Dark, Phosphor.Skin)) {
            val surface = skin.colors.bg.toInt()
            val text = skin.colors.textPrimary.toInt()
            val alpha = WallpaperReadability.safeAlpha(surface,listOf(text),Metrics.wallpaperArcScrim)
            for (background in listOf(0xff000000.toInt(),0xffffffff.toInt()))
                assertTrue(WallpaperReadability.contrast(text,WallpaperReadability.composite(surface,background,alpha)) >= 4.5)
        }
    }
}
