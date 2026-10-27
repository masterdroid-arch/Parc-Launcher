package com.brightblu.parc

import com.brightblu.parc.model.*
import com.brightblu.parc.ring.RingLayout
import org.junit.Assert.*
import org.junit.Test

class T29Test {
    private fun app(id: String, label: String) = AppEntry(AppId(id), label, id.substringBefore('/'), false, 0)

    @Test fun rotationCodecRoundTripsAndSkipsMalformed() {
        val map = mapOf("all/A4" to 5f, "social/A4" to 2.5f, "A2" to -1f)
        assertEquals(map, RotationCodec.decode(RotationCodec.encode(map)))
        assertTrue(RotationCodec.decode("").isEmpty())
        assertTrue(RotationCodec.decode("bad").isEmpty())
        assertEquals(mapOf("a" to 1f), RotationCodec.decode("bad;a=1;x=zz;=2"))
    }

    @Test fun allAppsOrderIsAlphabeticalByLabelAndSkipsHiddenRegardlessOfPins() {
        val a = app("pkg/alpha@0", "alpha")
        val b = app("pkg/Beta@0", "Beta")
        val c = app("pkg/charlie@0", "charlie")
        val hidden = app("pkg/Hidden@0", "Hidden")
        // "charlie" is pinned, yet alphabetical order (not pins) decides; hidden drops out.
        val layout = ParcLayout(pins = setOf(c.id), hidden = setOf(hidden.id))
        val order = allAppsOrder(listOf(hidden, c, b, a), layout)
        assertEquals(listOf(a.id, b.id, c.id), order.map { it.id })
    }

    @Test fun allAppsOrderComparesLabelsCaseInsensitively() {
        val upper = app("pkg/Upper@0", "Zebra")
        val lower = app("pkg/Lower@0", "apple")
        // A case-sensitive sort would put "Zebra" (Z) before "apple" (a).
        assertEquals(listOf(lower.id, upper.id), allAppsOrder(listOf(upper, lower), ParcLayout()).map { it.id })
    }
}
