package com.brightblu.parc.core.data.library

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Pure disk cache. Only bounded thumbnails live here; no book bytes or reading positions. */
class BookCoverCache(
    private val directory: File,
    private val maxBytes: Long = 24L * 1024 * 1024,
    private val maxEntryBytes: Int = 1024 * 1024,
) {
    init { require(maxBytes > 0 && maxEntryBytes > 0 && maxEntryBytes <= maxBytes) }

    @Synchronized
    fun get(bookId: String): ByteArray? {
        val file = file(bookId)
        if (!file.isFile) return null
        if (file.length() > maxEntryBytes) { file.delete(); return null }
        val bytes = try { file.inputStream().use { it.boundedBytes(maxEntryBytes) } }
        catch (_: IllegalArgumentException) { file.delete(); return null }
        file.setLastModified(System.currentTimeMillis())
        trim()
        return bytes
    }

    @Synchronized
    fun put(bookId: String, bytes: ByteArray): Boolean {
        if (bytes.isEmpty() || bytes.size > maxEntryBytes) return false
        directory.mkdirs()
        val temp = File.createTempFile("cover-", ".tmp", directory)
        try {
            temp.writeBytes(bytes)
            Files.move(temp.toPath(), file(bookId).toPath(), StandardCopyOption.REPLACE_EXISTING)
            trim()
            return true
        } finally { temp.delete() }
    }

    @Synchronized
    fun remove(bookId: String) { file(bookId).delete() }

    private fun file(id: String) = File(directory, stableBookId(id) + ".png")

    private fun trim() {
        val files = directory.listFiles()?.filter { it.extension == "png" }?.sortedBy { it.lastModified() }.orEmpty()
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total <= maxBytes) break
            val size = file.length()
            if (file.delete()) total -= size
        }
    }
}

fun stableBookId(location: String): String = MessageDigest.getInstance("SHA-256")
    .digest(location.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
