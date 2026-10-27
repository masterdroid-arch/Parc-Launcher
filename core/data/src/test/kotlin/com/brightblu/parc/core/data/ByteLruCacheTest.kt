package com.brightblu.parc.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ByteLruCacheTest {

    @Test
    fun `evicts the least recently used entry past the byte budget`() {
        val cache = ByteLruCache<String, ByteArray>(250) { it.size }
        cache.put("a", ByteArray(100))
        cache.put("b", ByteArray(100))

        assertNotNull(cache.get("a"))
        cache.put("c", ByteArray(100))

        assertNull(cache.get("b"))
        assertNotNull(cache.get("a"))
        assertNotNull(cache.get("c"))
        assertEquals(200, cache.size())
    }

    @Test
    fun `rejects a value larger than the whole budget`() {
        val cache = ByteLruCache<String, ByteArray>(250) { it.size }
        cache.put("big", ByteArray(300))
        assertNull(cache.get("big"))
        assertEquals(0, cache.size())
    }
}
