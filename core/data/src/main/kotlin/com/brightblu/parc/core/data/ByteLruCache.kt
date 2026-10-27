package com.brightblu.parc.core.data

/**
 * Least-recently-used cache bounded by a byte budget. Hand-rolled rather than
 * android.util.LruCache because the framework class is a no-op stub under JVM unit tests,
 * which would make the byte-budget eviction untestable. Thread-safe.
 */
internal class ByteLruCache<K : Any, V : Any>(
    private var maxBytes: Int,
    private val sizeOf: (V) -> Int,
) {
    private val entries = LinkedHashMap<K, V>(0, 0.75f, true)
    private var bytes = 0

    @Synchronized
    fun get(key: K): V? {
        val value = entries[key] ?: return null
        if (sizeOf(value) > maxBytes) {
            entries.remove(key)
            bytes -= sizeOf(value)
            return null
        }
        return value
    }

    @Synchronized
    fun put(key: K, value: V) {
        val valueBytes = sizeOf(value)
        if (valueBytes > maxBytes) {
            entries.remove(key)?.let { bytes -= sizeOf(it) }
            return
        }
        entries.put(key, value)?.let { bytes -= sizeOf(it) }
        bytes += valueBytes
        trim()
    }

    @Synchronized
    fun size(): Int = bytes

    @Synchronized
    fun maxBytes(): Int = maxBytes

    /** OPUS2: changes the byte budget; shrinking evicts least-recently-used entries at once. */
    @Synchronized
    fun resize(newMaxBytes: Int) {
        maxBytes = newMaxBytes.coerceAtLeast(0)
        trim()
    }

    @Synchronized
    fun keys(): Set<K> = entries.keys.toSet()

    /** Drops every entry and frees the whole byte budget. */
    @Synchronized
    fun clear() {
        entries.clear()
        bytes = 0
    }

    private fun trim() {
        val iterator = entries.entries.iterator()
        while (bytes > maxBytes && iterator.hasNext()) {
            bytes -= sizeOf(iterator.next().value)
            iterator.remove()
        }
    }
}
