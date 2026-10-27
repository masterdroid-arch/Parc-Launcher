package com.brightblu.parc.core.data.comms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderedDitherTest {

    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    @Test
    fun `small images are not upscaled and keep their aspect ratio`() {
        assertEquals(PixelSize(16, 8), OrderedDither.targetSize(16, 8))
        assertEquals(PixelSize(96, 48), OrderedDither.targetSize(192, 96))
        assertEquals(PixelSize(48, 96), OrderedDither.targetSize(96, 192))
        assertEquals(PixelSize(96, 96), OrderedDither.targetSize(1000, 1000))
        assertEquals(PixelSize(96, 1), OrderedDither.targetSize(4000, 1))
    }

    @Test
    fun `all white pixels become ink and all black pixels do not`() {
        val lit = OrderedDither.fromPixels(IntArray(64) { white }, 8, 8)
        val dark = OrderedDither.fromPixels(IntArray(64) { black }, 8, 8)

        for (y in 0 until 8) for (x in 0 until 8) {
            assertTrue(lit.bitAt(x, y))
            assertFalse(dark.bitAt(x, y))
        }
    }

    @Test
    fun `fully transparent pixels are never ink`() {
        val transparent = OrderedDither.fromPixels(IntArray(16) { 0x00FFFFFF }, 4, 4)
        for (y in 0 until 4) for (x in 0 until 4) assertFalse(transparent.bitAt(x, y))
    }

    @Test
    fun `the Bayer threshold gives a known pattern at mid grey`() {
        val grey = 0xFF808080.toInt()
        val image = OrderedDither.fromPixels(IntArray(2) { grey }, 2, 1)

        assertEquals(2, OrderedDither.threshold(0, 0))
        assertEquals(130, OrderedDither.threshold(1, 0))
        assertTrue(image.bitAt(0, 0))
        assertFalse(image.bitAt(1, 0))
    }

    @Test
    fun `alpha darkens a pixel before dithering`() {
        assertEquals(255, OrderedDither.luminance(white))
        assertEquals(0, OrderedDither.luminance(black))
        assertEquals(0, OrderedDither.luminance(0x00FFFFFF))
        assertTrue(OrderedDither.luminance(0x80FFFFFF.toInt()) < 200)
    }

    @Test
    fun `the packed mask has one word per 64 pixels and zero padding bits`() {
        val image = OrderedDither.fromPixels(IntArray(9) { white }, 3, 3)

        assertEquals(1, image.wordCount)
        assertEquals(1, image.words.size)
        assertEquals(0x1FFL, image.wordAt(0))
        assertEquals(511L, image.wordAt(0))
    }

    @Test
    fun `fromWords clears padding bits so equal masks compare equal`() {
        val padded = CommsImage.fromWords(3, 3, listOf(-1L))
        val exact = CommsImage.fromWords(3, 3, listOf(0x1FFL))

        assertEquals(exact, padded)
        assertEquals(exact.hashCode(), padded.hashCode())
    }

    @Test
    fun `a 96 by 96 mask is 144 words`() {
        val image = OrderedDither.fromPixels(IntArray(96 * 96) { black }, 96, 96)
        assertEquals(144, image.wordCount)
        assertEquals(0L, image.wordAt(143))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an image larger than the cap is rejected`() {
        OrderedDither.fromPixels(IntArray(97), 97, 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a mask with the wrong pixel count is rejected`() {
        OrderedDither.fromPixels(IntArray(5), 3, 2)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `reading outside the mask is rejected`() {
        OrderedDither.fromPixels(IntArray(4) { white }, 2, 2).bitAt(2, 0)
    }
}
